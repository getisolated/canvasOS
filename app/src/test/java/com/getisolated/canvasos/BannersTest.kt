package com.getisolated.canvasos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BannersTest {
    private val excluded = setOf("com.android.vending", "com.chatty.ads")

    private fun accept(
        pkg: String = "com.ring.doorbell",
        ongoing: Boolean = false,
        summary: Boolean = false,
        template: String? = null,
        title: CharSequence? = "Sonnette",
        text: CharSequence? = "Quelqu'un est à la porte",
    ) = Banners.accept(pkg, ongoing, summary, template, title, text, excluded)

    @Test fun anyAppNotExcludedBecomesABanner() {
        assertTrue(accept())
        assertTrue(accept(template = "android.app.Notification\$BigTextStyle"))
    }

    @Test fun excludedAppsAreNeverShown() {
        assertTrue(accept(pkg = "com.netflix.ninja"))
        assertFalse(accept(pkg = "com.chatty.ads"))
        assertFalse(accept(pkg = "com.android.vending"))
        // Nothing excluded (a fresh install): every app's news shows.
        assertTrue(Banners.accept("com.chatty.ads", false, false, null, "Promo", "-50 %", emptySet()))
    }

    @Test fun runningServicesAndGroupSummariesAreSkipped() {
        assertFalse(accept(ongoing = true))
        assertFalse(accept(summary = true))
    }

    @Test fun mediaNotificationsAreSkipped() {
        assertFalse(accept(template = "android.app.Notification\$MediaStyle"))
        assertFalse(accept(template = "android.app.Notification\$DecoratedMediaCustomViewStyle"))
        assertFalse(accept(template = Banners.MEDIA_TEMPLATE))
    }

    @Test fun somethingMustBeLeftToShow() {
        assertFalse(accept(title = null, text = null))
        assertFalse(accept(title = "  ", text = ""))
        // A title alone, or a text alone (the app's name then stands in as the title), is enough.
        assertTrue(accept(title = "Mouvement détecté", text = null))
        assertTrue(accept(title = null, text = "Mouvement détecté dans l'entrée"))
    }
}
