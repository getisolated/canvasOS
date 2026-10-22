package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessesTest {
    /** Abridged `dumpsys activity services` from an Android TV (Android 11 / 14 lines mixed). */
    private val dump = """
        ACTIVITY MANAGER SERVICES (dumpsys activity services)
          User 0 active services:
          * ServiceRecord{3f1c2b8 u0 com.android.bluetooth/.btservice.AdapterService}
            intent={cmp=com.android.bluetooth/.btservice.AdapterService}
            packageName=com.android.bluetooth
            processName=com.android.bluetooth
            baseDir=/system/app/Bluetooth/Bluetooth.apk
            dataDir=/data/user_de/0/com.android.bluetooth
            app=ProcessRecord{a2b3c4d 1820:com.android.bluetooth/1002}
            createTime=-3d2h11m4s211ms startingBgTimeout=--
            startRequested=true delayedStop=false stopIfKilled=false callStart=true lastStartId=1
            Bindings:
            * IntentBindRecord{9e8d7c6 CREATE}:
              intent={act=android.bluetooth.IBluetooth cmp=com.android.bluetooth/.btservice.AdapterService}
              binder=android.os.BinderProxy@5f4e3d2
              requested=true received=true hasBound=true doRebind=false
              * Client AppBindRecord{1a2b3c4 ProcessRecord{7d8e9f0 1043:system/1000}}
                Per-process Connections:
                  ConnectionRecord{2b3c4d5 u0 CR FGS com.android.bluetooth/.btservice.AdapterService:@6c5d4e3}
          * ServiceRecord{8f3b2a1 u0 com.spotify.tv.android/com.spotify.mobile.android.service.SpotifyService}
            intent={act=com.spotify.mobile.android.service.action.client.FOREGROUND cmp=com.spotify.tv.android/com.spotify.mobile.android.service.SpotifyService}
            packageName=com.spotify.tv.android
            processName=com.spotify.tv.android
            app=ProcessRecord{4e5f6a7 4321:com.spotify.tv.android/u0a85}
            allowWhileInUsePermissionInFgs=true
            startForegroundCount=1
            recentCallingPackage=com.spotify.tv.android
            isForeground=true foregroundId=20 foregroundNoti=Notification(channel=playback shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff1db954 category=transport actions=3 vis=PUBLIC)
            createTime=-25m3s12ms startingBgTimeout=--
          * ServiceRecord{c4d5e6f u0 com.plexapp.mediaserver/.PlexMediaServerService:service}
            packageName=com.plexapp.mediaserver
            processName=com.plexapp.mediaserver:service
            isForeground=true foregroundId=1 types=0x00000001 foregroundNoti=Notification(channel=server pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0x00000000 vis=PRIVATE)
          * ServiceRecord{1234abc u0 com.netflix.ninja/.NetflixService}
            packageName=com.netflix.ninja
            isForeground=false foregroundId=0 foregroundNoti=null
          * ServiceRecord{77aa88b u10 com.tailscale.ipn/.IPNService}
            packageName=com.tailscale.ipn
            isForeground=true foregroundId=1 foregroundNoti=Notification(channel=status pri=-1 vis=PRIVATE)

          Connection bindings to services:
          * ConnectionRecord{2b3c4d5 u0 CR FGS com.android.bluetooth/.btservice.AdapterService:@6c5d4e3}
            binding=AppBindRecord{1a2b3c4 com.android.bluetooth/.btservice.AdapterService:system}
            conn=android.os.BinderProxy@6c5d4e3 flags=0x1
    """.trimIndent()

    @Test fun foregroundServicesAreFoundAndTheOthersIgnored() {
        assertEquals(setOf("com.spotify.tv.android", "com.plexapp.mediaserver", "com.tailscale.ipn"),
            Processes.parseForegroundServices(dump))
    }

    @Test fun theGrepFilteredOutputReadsTheSame() {
        val filtered = dump.lineSequence()
            .filter { "ServiceRecord" in it || "packageName=" in it || "isForeground=true" in it }
            .joinToString("\n")
        assertEquals(Processes.parseForegroundServices(dump), Processes.parseForegroundServices(filtered))
    }

    @Test fun otherRecordShapesStillWork() {
        // The user after the brace, and a header with no component at all (packageName= decides).
        val odd = """
            * ServiceRecord{9a8b7c6} u0 com.example.cast/.ReceiverService
              isForeground=true foregroundId=3 foregroundNoti=Notification(channel=cast)
            * ServiceRecord{5d6e7f8}
              packageName=org.jellyfin.server
              isForeground=true foregroundId=9
        """.trimIndent()
        assertEquals(setOf("com.example.cast", "org.jellyfin.server"), Processes.parseForegroundServices(odd))
    }

    @Test fun unknownOutputGivesNothing() {
        assertTrue(Processes.parseForegroundServices("").isEmpty())
        assertTrue(Processes.parseForegroundServices("Permission Denial: can't dump ActivityManager").isEmpty())
        // A foreground line with no record before it belongs to nobody.
        assertTrue(Processes.parseForegroundServices("isForeground=true foregroundId=1\n/system/bin/sh: grep: inaccessible").isEmpty())
    }

    @Test fun processNamesBecomePackages() {
        val ps = """
            NAME
            init
            [kthreadd]
            /system/bin/surfaceflinger
            zygote
            system_server
            android.hardware.audio@2.0-service
            com.android.systemui
            com.spotify.tv.android
            com.plexapp.mediaserver:service
            u0_a91        5120  1234 1532044 98304 0                   0 S com.netflix.ninja
        """.trimIndent()
        val names = Processes.parseProcessNames(ps)
        assertTrue(names.containsAll(listOf("com.android.systemui", "com.spotify.tv.android", "com.plexapp.mediaserver", "com.netflix.ninja")))
        for (n in listOf("NAME", "init", "[kthreadd]", "zygote", "system_server", "/system/bin/surfaceflinger", "android.hardware.audio@2.0-service")) {
            assertTrue(n, n !in names)
        }
        assertTrue(Processes.parseProcessNames("ps: Unknown option 'A'").isEmpty())
    }

    @Test fun summaryReadsLikeFrench() {
        val r = Cleaner.Result(100, 412, 30, true, listOf("a.b", "c.d", "e.f", "g.h"), listOf("i.j", "k.l"))
        assertEquals("312 Mo libérés · 4 apps fermées · 2 en cours d'utilisation épargnées", r.summary())
        assertEquals("312 Mo libérés, 4 apps fermées, 2 en cours d'utilisation épargnées", r.summary(", "))
        assertEquals("1 Mo libéré · 1 app fermée · 1 en cours d'utilisation épargnée",
            Cleaner.Result(100, 101, 1, true, listOf("a.b"), listOf("c.d")).summary())
        assertEquals("0 Mo libéré · aucune app fermée", Cleaner.Result(200, 150, 0, true).summary())
        assertEquals("312 Mo libérés (cache uniquement)", Cleaner.Result(100, 412, 0, false).summary())
    }
}
