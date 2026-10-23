package com.getisolated.canvasos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * A minimal mDNS responder (RFC 6762) so the phone page also answers at `http://canvasos.local:8790/…`
 * ([PhoneServer.hostUrl]): A queries for [HOST] get the TV's LAN IPv4, and Bonjour browsers see an
 * `_http._tcp` service ([MdnsPacket.Self]). Everything else on the wire is ignored. No probing: a
 * second TV claiming the same name is only logged.
 *
 * Runs while the phone page runs ([PhoneServer.start] / [PhoneServer.stop]) and is reopened on every
 * network change ([KeeperService]). Wi-Fi drivers drop multicast unless an app holds a
 * [WifiManager.MulticastLock]; it is held only while running on Wi-Fi. Any socket failure is logged
 * ([TAG]) and the page keeps working on its IP address.
 */
object Mdns {
    const val HOST = "canvasos.local"
    private const val TAG = "canvasOSMdns"
    private const val PORT = 5353
    private val GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
    private const val TTL = 120L

    private class Run(val socket: MulticastSocket, val self: MdnsPacket.Self, val lock: WifiManager.MulticastLock?) {
        @Volatile var lastMulticast = 0L
    }

    @Volatile private var run: Run? = null
    /** Set by [start], cleared by [stop]: whether [restart] should bring it back. */
    @Volatile private var wanted = false

    /** Whether it is answering now, so the screens show [HOST] only when it resolves. */
    val isRunning: Boolean get() = run?.socket?.isClosed == false

    /** Starts answering for the current LAN address; nothing to do when it already does. Blocking (binds a socket). */
    @Synchronized
    fun start(ctx: Context) {
        wanted = true
        val app = ctx.applicationContext
        val ip = Net.lanAddress(app)
        if (run?.let { !it.socket.isClosed && it.self.ip.contentEquals(addr(ip)) } == true) return
        close(goodbye = true)
        if (ip == null) return
        open(app, ip)
    }

    /** Stops answering, and tells the network the name is gone. */
    @Synchronized
    fun stop() {
        wanted = false
        close(goodbye = true)
    }

    /** After a network change: a fresh socket on the new interface, and a new announcement. */
    @Synchronized
    fun restart(ctx: Context) {
        if (!wanted) return
        close(goodbye = false)
        start(ctx)
    }

    private fun addr(ip: String?): ByteArray? = ip?.let { runCatching { InetAddress.getByName(it).address }.getOrNull() }

    private fun open(app: Context, ip: String) {
        val local = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return
        var lock: WifiManager.MulticastLock? = null
        try {
            val nif = NetworkInterface.getByInetAddress(local)
            if (!Net.isWired(app)) {
                lock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("canvasOS mDNS")?.apply { setReferenceCounted(false); acquire() }
            }
            val socket = MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                timeToLive = 255 // RFC 6762 §11
                if (nif != null) networkInterface = nif
                joinGroup(InetSocketAddress(GROUP, PORT), nif)
            }
            val self = MdnsPacket.Self(HOST, local.address, "canvasOS", PhoneServer.PORT, listOf(TXT))
            val r = Run(socket, self, lock)
            run = r
            Thread({ read(r) }, "canvasOS-mdns").apply { isDaemon = true }.start()
            Thread({ announce(r) }, "canvasOS-mdns-announce").apply { isDaemon = true }.start()
            Log.i(TAG, "answering for $HOST at $ip on ${nif?.name}")
        } catch (e: Exception) {
            runCatching { lock?.release() }
            Log.w(TAG, "could not open the mDNS socket", e)
        }
    }

    private fun close(goodbye: Boolean) {
        val r = run ?: return
        run = null
        if (goodbye) runCatching { send(r, MdnsPacket.announcement(r.self, ttl = 0), GROUP, PORT) }
        runCatching { r.socket.close() }
        runCatching { r.lock?.release() }
    }

    /** Two unsolicited responses one second apart (RFC 6762 §8.3). */
    private fun announce(r: Run) {
        repeat(2) { i ->
            if (i > 0) Thread.sleep(1000)
            if (r.socket.isClosed) return
            runCatching { send(r, MdnsPacket.announcement(r.self, TTL), GROUP, PORT) }
                .onFailure { Log.w(TAG, "announcement failed", it) }
        }
    }

    private fun read(r: Run) {
        val buf = ByteArray(9000)
        while (!r.socket.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                r.socket.receive(p)
            } catch (e: Exception) {
                if (!r.socket.isClosed) Log.w(TAG, "receive failed", e)
                break
            }
            runCatching { handle(r, p) }.onFailure { Log.w(TAG, "bad packet from ${p.address}", it) }
        }
        // The socket died under us (interface gone): the next network change or start opens a new one.
        if (run === r) synchronized(this) { if (run === r) close(goodbye = false) }
    }

    private fun handle(r: Run, p: DatagramPacket) {
        val bytes = p.data.copyOfRange(p.offset, p.offset + p.length)
        MdnsPacket.claimedAddress(bytes, r.self.host)?.let { other ->
            if (!other.contentEquals(r.self.ip)) Log.w(TAG, "another device also answers for ${r.self.host}: ${InetAddress.getByAddress(other).hostAddress}")
            return
        }
        val q = MdnsPacket.parseQuery(bytes) ?: return
        // A plain DNS client (dig @224.0.0.251 -p 5353) asks from another port: answer it directly (§6.7).
        val legacy = p.port != PORT
        val reply = MdnsPacket.reply(q, r.self, if (legacy) 10 else TTL, legacy) ?: return
        val now = System.currentTimeMillis()
        if (legacy) {
            send(r, reply, p.address, p.port)
        } else if (q.questions.all { it.unicast } && now - r.lastMulticast < TTL * 1000 / 4) {
            // QU and recently multicast (§5.4): the asker alone.
            send(r, reply, p.address, PORT)
        } else {
            send(r, reply, GROUP, PORT)
            r.lastMulticast = now
        }
    }

    private fun send(r: Run, bytes: ByteArray, to: InetAddress, port: Int) {
        r.socket.send(DatagramPacket(bytes, bytes.size, to, port))
    }

    /**
     * The service's TXT record. Deliberately without the token: mDNS answers go to the whole network,
     * and the token is the page's only protection ([PhoneServer]).
     */
    private const val TXT = "path=/"
}

/**
 * The DNS wire format for [Mdns], pure so it can be unit-tested: queries parsed (name compression
 * followed), answers written without compression.
 */
object MdnsPacket {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val TYPE_NSEC = 47
    const val TYPE_ANY = 255
    const val CLASS_IN = 1
    /** In a question's class: a unicast reply is welcome (QU). In a record's class: replace what is cached (§10.2). */
    const val TOP_BIT = 0x8000
    const val SERVICE = "_http._tcp.local"

    data class Question(val name: String, val type: Int, val unicast: Boolean = false)

    class Query(val id: Int, val questions: List<Question>)

    /** One resource record, its data already encoded. */
    class Record(val name: String, val type: Int, val ttl: Long, val flush: Boolean, val data: ByteArray)

    /** What this TV answers for: [host] at [ip], and the service [instance]`._http._tcp.local` on [port]. */
    class Self(val host: String, val ip: ByteArray, val instance: String, val port: Int, val txt: List<String>) {
        val service get() = "$instance.$SERVICE"
    }

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            val b = label.toByteArray(Charsets.UTF_8)
            require(b.size <= 63) { "label too long" }
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        return out.toByteArray()
    }

    fun a(name: String, ip: ByteArray, ttl: Long, flush: Boolean = true) = Record(name, TYPE_A, ttl, flush, ip.copyOf())

    fun ptr(name: String, target: String, ttl: Long) = Record(name, TYPE_PTR, ttl, false, encodeName(target))

    fun srv(name: String, target: String, port: Int, ttl: Long, flush: Boolean = true): Record {
        val t = encodeName(target)
        val d = ByteArray(6 + t.size)
        d[4] = (port shr 8).toByte(); d[5] = port.toByte()
        t.copyInto(d, 6)
        return Record(name, TYPE_SRV, ttl, flush, d)
    }

    fun txt(name: String, entries: List<String>, ttl: Long, flush: Boolean = true): Record {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val b = e.toByteArray(Charsets.UTF_8).take(255).toByteArray()
            out.write(b.size); out.write(b)
        }
        if (entries.isEmpty()) out.write(0)
        return Record(name, TYPE_TXT, ttl, flush, out.toByteArray())
    }

    /** "Only these types exist for [name]" (§6.1), types below 256. */
    fun nsec(name: String, types: List<Int>, ttl: Long, flush: Boolean = true): Record {
        val bitmap = ByteArray(types.maxOf { it } / 8 + 1)
        for (t in types) bitmap[t / 8] = (bitmap[t / 8].toInt() or (0x80 shr (t % 8))).toByte()
        return Record(name, TYPE_NSEC, ttl, flush, encodeName(name) + byteArrayOf(0, bitmap.size.toByte()) + bitmap)
    }

    /** A response: flags QR + AA; [questions] are echoed only for a legacy unicast reply. */
    fun encodeAnswer(id: Int, answers: List<Record>, additionals: List<Record> = emptyList(), questions: List<Question> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(id); out.u16(0x8400)
        out.u16(questions.size); out.u16(answers.size); out.u16(0); out.u16(additionals.size)
        for (q in questions) {
            out.write(encodeName(q.name)); out.u16(q.type); out.u16(CLASS_IN)
        }
        for (r in answers + additionals) {
            out.write(encodeName(r.name))
            out.u16(r.type)
            out.u16(CLASS_IN or if (r.flush) TOP_BIT else 0)
            out.u32(r.ttl)
            out.u16(r.data.size)
            out.write(r.data)
        }
        return out.toByteArray()
    }

    /** A query, for tests and tools. */
    fun encodeQuery(id: Int, questions: List<Question>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(id); out.u16(0); out.u16(questions.size); out.u16(0); out.u16(0); out.u16(0)
        for (q in questions) {
            out.write(encodeName(q.name)); out.u16(q.type); out.u16(CLASS_IN or if (q.unicast) TOP_BIT else 0)
        }
        return out.toByteArray()
    }

    /** The questions of a standard query, or null for a response, another opcode, or a malformed packet. */
    fun parseQuery(b: ByteArray): Query? = runCatching {
        if (b.size < 12) return null
        val flags = b.u16(2)
        if (flags and 0x8000 != 0 || flags and 0x7800 != 0) return null
        var pos = 12
        val qs = ArrayList<Question>()
        repeat(b.u16(4)) {
            val (name, next) = readName(b, pos)
            val type = b.u16(next)
            val cls = b.u16(next + 2)
            pos = next + 4
            if (cls and 0x7FFF == CLASS_IN || cls and 0x7FFF == 255) qs += Question(name, type, cls and TOP_BIT != 0)
        }
        Query(b.u16(0), qs)
    }.getOrNull()

    /** For a response that carries an A record for [host], that address; null for anything else. */
    fun claimedAddress(b: ByteArray, host: String): ByteArray? = runCatching {
        if (b.size < 12 || b.u16(2) and 0x8000 == 0) return null
        var pos = 12
        repeat(b.u16(4)) { pos = readName(b, pos).second + 4 }
        repeat(b.u16(6) + b.u16(8) + b.u16(10)) {
            val (name, next) = readName(b, pos)
            val type = b.u16(next)
            val len = b.u16(next + 8)
            val data = next + 10
            if (type == TYPE_A && len == 4 && same(name, host)) return b.copyOfRange(data, data + 4)
            pos = data + len
        }
        null
    }.getOrNull()

    /** The records that answer [q], or null when it asks nothing of ours. */
    fun reply(q: Query, self: Self, ttl: Long, legacy: Boolean = false): ByteArray? {
        val flush = !legacy // §6.7: no cache-flush bit to a plain DNS client
        val answers = LinkedHashMap<String, Record>()
        val extra = LinkedHashMap<String, Record>()
        fun add(m: MutableMap<String, Record>, r: Record) { m.putIfAbsent("${r.name.lowercase()}/${r.type}", r) }
        val hostA = a(self.host, self.ip, ttl, flush)
        val srv = srv(self.service, self.host, self.port, ttl, flush)
        val txt = txt(self.service, self.txt, ttl, flush)
        for (question in q.questions) {
            val t = question.type
            when {
                same(question.name, self.host) -> when (t) {
                    TYPE_A, TYPE_ANY -> add(answers, hostA)
                    TYPE_AAAA -> add(answers, nsec(self.host, listOf(TYPE_A), ttl, flush))
                }
                same(question.name, SERVICE) && (t == TYPE_PTR || t == TYPE_ANY) -> {
                    add(answers, ptr(SERVICE, self.service, ttl))
                    add(extra, srv); add(extra, txt); add(extra, hostA)
                }
                same(question.name, self.service) -> {
                    if (t == TYPE_SRV || t == TYPE_ANY) { add(answers, srv); add(extra, hostA) }
                    if (t == TYPE_TXT || t == TYPE_ANY) add(answers, txt)
                }
            }
        }
        if (answers.isEmpty()) return null
        for (k in answers.keys) extra.remove(k)
        return encodeAnswer(if (legacy) q.id else 0, answers.values.toList(), extra.values.toList(), if (legacy) q.questions else emptyList())
    }

    /** Everything at once, unsolicited (§8.3); with ttl 0, the goodbye (§10.1). */
    fun announcement(self: Self, ttl: Long): ByteArray = encodeAnswer(0, listOf(
        a(self.host, self.ip, ttl),
        ptr(SERVICE, self.service, ttl),
        srv(self.service, self.host, self.port, ttl),
        txt(self.service, self.txt, ttl),
    ))

    private fun same(a: String, b: String) = a.trimEnd('.').equals(b.trimEnd('.'), ignoreCase = true)

    /** A name at [start], following compression pointers; returns it and the offset after it. */
    fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = b[pos].toInt() and 0xFF
            when {
                len == 0 -> { if (end < 0) end = pos + 1; break }
                len and 0xC0 == 0xC0 -> {
                    if (++jumps > 32) throw IllegalArgumentException("pointer loop")
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3F) shl 8) or (b[pos + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> throw IllegalArgumentException("bad label")
                else -> {
                    labels += String(b, pos + 1, len, Charsets.UTF_8)
                    pos += 1 + len
                }
            }
        }
        return labels.joinToString(".") to end
    }

    private fun ByteArray.u16(i: Int) = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) { u16((v shr 16).toInt() and 0xFFFF); u16(v.toInt() and 0xFFFF) }
}
