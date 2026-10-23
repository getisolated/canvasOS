package com.getisolated.canvasos

import com.getisolated.canvasos.MdnsPacket.Question
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsPacketTest {
    private val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 42)
    private val self = MdnsPacket.Self("canvasos.local", ip, "canvasOS", 8790, listOf("path=/"))

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Reads every record of a response: name, type, class, ttl, data. */
    private data class Rr(val name: String, val type: Int, val cls: Int, val ttl: Long, val data: ByteArray)

    private fun records(b: ByteArray): List<Rr> {
        var pos = 12
        repeat(u16(b, 4)) { pos = MdnsPacket.readName(b, pos).second + 4 }
        return List(u16(b, 6) + u16(b, 8) + u16(b, 10)) {
            val (name, next) = MdnsPacket.readName(b, pos)
            val len = u16(b, next + 8)
            val ttl = (u16(b, next + 4).toLong() shl 16) or u16(b, next + 6).toLong()
            Rr(name, u16(b, next), u16(b, next + 2), ttl, b.copyOfRange(next + 10, next + 10 + len)).also { pos = next + 10 + len }
        }.also { assertEquals("no trailing bytes", b.size, pos) }
    }

    @Test fun namesAreLengthPrefixedLabelsWithoutCompression() {
        val n = MdnsPacket.encodeName("canvasos.local")
        assertArrayEquals(byteArrayOf(8) + "canvasos".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0), n)
        assertArrayEquals(n, MdnsPacket.encodeName("canvasos.local."))
    }

    @Test fun aQueryRoundTrips() {
        val q = MdnsPacket.parseQuery(MdnsPacket.encodeQuery(7, listOf(Question("canvasos.local", MdnsPacket.TYPE_A, unicast = true))))!!
        assertEquals(7, q.id)
        assertEquals(listOf(Question("canvasos.local", MdnsPacket.TYPE_A, true)), q.questions)
    }

    @Test fun compressedQuestionNamesAreFollowed() {
        // Two questions; the second's name points into the first ("local").
        val head = byteArrayOf(0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0)
        val first = MdnsPacket.encodeName("canvasos.local") + byteArrayOf(0, 1, 0, 1)
        val second = byteArrayOf(4) + "_tcp".toByteArray() + byteArrayOf(0xC0.toByte(), 21) + byteArrayOf(0, 12, 0, 1)
        val q = MdnsPacket.parseQuery(head + first + second)!!
        assertEquals(listOf("canvasos.local", "_tcp.local"), q.questions.map { it.name })
        assertEquals(listOf(MdnsPacket.TYPE_A, MdnsPacket.TYPE_PTR), q.questions.map { it.type })
    }

    @Test fun responsesAndGarbageAreNotQueries() {
        assertNull(MdnsPacket.parseQuery(MdnsPacket.announcement(self, 120)))
        assertNull(MdnsPacket.parseQuery(byteArrayOf(1, 2, 3)))
        // A pointer loop does not hang.
        assertNull(MdnsPacket.parseQuery(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 1, 0, 1)))
    }

    @Test fun anAQueryForCanvasosLocalGetsTheAddressWithCacheFlush() {
        val q = MdnsPacket.parseQuery(MdnsPacket.encodeQuery(0, listOf(Question("CanvasOS.local", MdnsPacket.TYPE_A))))!!
        val r = MdnsPacket.reply(q, self, 120)!!
        assertEquals(0, u16(r, 0))
        assertEquals(0x8400, u16(r, 2))
        assertEquals(0, u16(r, 4))
        val rr = records(r).single()
        assertEquals("canvasos.local", rr.name)
        assertEquals(MdnsPacket.TYPE_A, rr.type)
        assertEquals(0x8001, rr.cls)
        assertEquals(120L, rr.ttl)
        assertArrayEquals(ip, rr.data)
        // Exact bytes: header, name, A, IN|flush, ttl 120, length 4, address.
        val expected = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0) + MdnsPacket.encodeName("canvasos.local") +
            byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4) + ip
        assertArrayEquals(expected, r)
    }

    @Test fun otherNamesAreIgnored() {
        val q = MdnsPacket.parseQuery(MdnsPacket.encodeQuery(0, listOf(Question("printer.local", MdnsPacket.TYPE_A))))!!
        assertNull(MdnsPacket.reply(q, self, 120))
    }

    @Test fun aaaaGetsAnNsecSayingOnlyAExists() {
        val q = MdnsPacket.parseQuery(MdnsPacket.encodeQuery(0, listOf(Question("canvasos.local", MdnsPacket.TYPE_AAAA))))!!
        val rr = records(MdnsPacket.reply(q, self, 120)!!).single()
        assertEquals(MdnsPacket.TYPE_NSEC, rr.type)
        assertArrayEquals(MdnsPacket.encodeName("canvasos.local") + byteArrayOf(0, 1, 0x40), rr.data)
    }

    @Test fun aLegacyQueryGetsItsIdTheQuestionAndNoCacheFlush() {
        val q = MdnsPacket.parseQuery(MdnsPacket.encodeQuery(0x1234, listOf(Question("canvasos.local", MdnsPacket.TYPE_A))))!!
        val r = MdnsPacket.reply(q, self, 10, legacy = true)!!
        assertEquals(0x1234, u16(r, 0))
        assertEquals(1, u16(r, 4))
        val rr = records(r).single()
        assertEquals(1, rr.cls)
        assertEquals(10L, rr.ttl)
    }

    @Test fun browsingHttpFindsTheServiceWithSrvTxtAndAddress() {
        val q = MdnsPacket.parseQuery(MdnsPacket.encodeQuery(0, listOf(Question("_http._tcp.local", MdnsPacket.TYPE_PTR))))!!
        val r = MdnsPacket.reply(q, self, 120)!!
        assertEquals(1, u16(r, 6))
        assertEquals(3, u16(r, 10))
        val rrs = records(r)
        assertEquals(MdnsPacket.TYPE_PTR, rrs[0].type)
        assertEquals(1, rrs[0].cls) // shared record: no cache flush
        assertEquals("canvasOS._http._tcp.local", MdnsPacket.readName(rrs[0].data, 0).first)
        val srv = rrs.single { it.type == MdnsPacket.TYPE_SRV }
        assertEquals(8790, u16(srv.data, 4))
        assertEquals("canvasos.local", MdnsPacket.readName(srv.data, 6).first)
        val txt = rrs.single { it.type == MdnsPacket.TYPE_TXT }
        assertArrayEquals(byteArrayOf(6) + "path=/".toByteArray(), txt.data)
        assertTrue(rrs.any { it.type == MdnsPacket.TYPE_A })
    }

    @Test fun theAnnouncementCarriesEverythingAndGoodbyeHasTtlZero() {
        val rrs = records(MdnsPacket.announcement(self, 120))
        assertEquals(listOf(MdnsPacket.TYPE_A, MdnsPacket.TYPE_PTR, MdnsPacket.TYPE_SRV, MdnsPacket.TYPE_TXT), rrs.map { it.type })
        assertTrue(records(MdnsPacket.announcement(self, 0)).all { it.ttl == 0L })
    }

    @Test fun anotherDevicesClaimIsSeen() {
        val other = byteArrayOf(10, 0, 0, 9)
        val theirs = MdnsPacket.announcement(MdnsPacket.Self("canvasos.local", other, "canvasOS", 8790, emptyList()), 120)
        assertArrayEquals(other, MdnsPacket.claimedAddress(theirs, "canvasos.local"))
        assertNull(MdnsPacket.claimedAddress(theirs, "other.local"))
        assertNull(MdnsPacket.claimedAddress(MdnsPacket.encodeQuery(0, listOf(Question("canvasos.local", 1))), "canvasos.local"))
        assertNotNull(MdnsPacket.claimedAddress(MdnsPacket.announcement(self, 120), "canvasos.local"))
    }
}
