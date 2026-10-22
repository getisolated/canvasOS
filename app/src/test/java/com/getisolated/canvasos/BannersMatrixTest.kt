package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards, over every combination of its inputs, that a notification becomes a banner only for an allowed app, for news, with something to say. */
class BannersMatrixTest {
    private val pkg = "com.ring.doorbell"
    private val allow = setOf(pkg, "io.homeassistant.companion.android")

    private val packages = listOf(pkg, "io.homeassistant.companion.android", "com.netflix.ninja", "", "com.ring", "com.ring.doorbell.beta", "COM.RING.DOORBELL")
    /** Each template with whether it is a media notification, written out rather than derived. */
    private val templates: List<Pair<String?, Boolean>> = listOf(
        null to false, "" to false,
        "android.app.Notification\$BigTextStyle" to false, "android.app.Notification\$BigPictureStyle" to false,
        "android.app.Notification\$InboxStyle" to false, "android.app.Notification\$MessagingStyle" to false,
        "android.app.Notification\$CallStyle" to false,
        "android.app.Notification\$MediaStyle" to true, "android.app.Notification\$DecoratedMediaCustomViewStyle" to true,
        "androidx.media.app.NotificationCompat\$MediaStyle" to true,
        "androidx.media3.session.MediaStyleNotificationHelper\$MediaStyle" to true, Banners.MEDIA_TEMPLATE to true,
    )

    /** Each text with whether it is blank (nothing to show), written out rather than derived. */
    private val texts: List<Pair<CharSequence?, Boolean>> = listOf(
        null to true, "" to true, " " to true, "\n\t" to true,
        "Mouvement détecté" to false, StringBuilder("Sonnette") to false, "0" to false,
    )

    @Test fun everyCombinationFollowsTheRule() {
        var accepted = 0
        var total = 0
        for (p in packages) for (ongoing in listOf(false, true)) for (summary in listOf(false, true)) for ((t, media) in templates)
            for ((title, titleBlank) in texts) for ((text, textBlank) in texts) {
                val expected = p in allow && !ongoing && !summary && !media && !(titleBlank && textBlank)
                val got = Banners.accept(p, ongoing, summary, t, title, text, allow)
                assertEquals("pkg=$p ongoing=$ongoing summary=$summary template=$t title=$title text=$text", expected, got)
                total++
                if (got) accepted++
            }
        // The rule itself is not trivially true or false.
        assertTrue("$accepted of $total", accepted in 1 until total)
    }

    @Test fun theAllowListIsMatchedExactly() {
        fun ok(p: String) = Banners.accept(p, false, false, null, "Titre", "Texte", allow)
        assertTrue(ok(pkg))
        assertFalse(ok("com.ring"))
        assertFalse(ok("com.ring.doorbell.beta"))
        assertFalse(ok("COM.RING.DOORBELL"))
        assertFalse(ok(" $pkg"))
        assertFalse(ok(""))
    }

    @Test fun nothingIsAcceptedWhenNoAppIsChosen() {
        for ((t, _) in templates) assertFalse(Banners.accept(pkg, false, false, t, "Titre", "Texte", emptySet()))
    }

    @Test fun aTextOfZeroIsStillSomethingToShow() {
        assertTrue(Banners.accept(pkg, false, false, null, null, "0", allow))
    }

    @Test fun anyOneBlockerIsEnough() {
        assertFalse(Banners.accept(pkg, true, true, Banners.MEDIA_TEMPLATE, null, null, emptySet()))
        assertFalse(Banners.accept(pkg, true, false, null, "T", "x", allow))
        assertFalse(Banners.accept(pkg, false, true, null, "T", "x", allow))
        assertFalse(Banners.accept(pkg, false, false, Banners.MEDIA_TEMPLATE, "T", "x", allow))
        assertFalse(Banners.accept(pkg, false, false, null, " ", null, allow))
        assertTrue(Banners.accept(pkg, false, false, null, "T", "x", allow))
    }

    @Test fun theMediaTemplateConstantIsOneTheMatcherRecognises() {
        assertTrue(Banners.MEDIA_TEMPLATE.endsWith("MediaStyle"))
        assertFalse(Banners.accept(pkg, false, false, Banners.MEDIA_TEMPLATE, "T", "x", allow))
    }
}
