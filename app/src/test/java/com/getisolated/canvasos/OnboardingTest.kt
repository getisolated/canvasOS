package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-launch assistant's dock order, and who gets the assistant at all ([Prefs.setupDone]). */
class OnboardingTest {
    @Test fun tickedAppsComeFirstInTickOrderThenTheRest() {
        assertEquals(listOf("tv", "music", "video", "photos", "games"),
            Onboarding.dockOrder(listOf("tv", "music"), listOf("video", "music", "photos", "tv", "games"), emptyList()))
    }

    @Test fun noDuplicatesAndSavedOnlyEntriesKeepTheirPlaceAtTheEnd() {
        val order = Onboarding.dockOrder(listOf("b"), listOf("a", "b", "c"), listOf("hidden", "a", "inFolder", "b"))
        assertEquals(listOf("b", "a", "c", "hidden", "inFolder"), order)
        assertEquals(order.distinct(), order)
    }

    @Test fun nothingTickedKeepsTheSavedOrderEvenEmpty() {
        assertEquals(emptyList<String>(), Onboarding.dockOrder(emptyList(), listOf("a", "b"), emptyList()))
        assertEquals(listOf("x", "y"), Onboarding.dockOrder(emptyList(), listOf("a"), listOf("x", "y")))
    }

    @Test fun atMostFiveTickedAndATickRepeatedCountsOnce() {
        val ticked = listOf("1", "2", "2", "3", "4", "5", "6")
        val order = Onboarding.dockOrder(ticked, listOf("0", "6"), emptyList())
        assertEquals(listOf("1", "2", "3", "4", "5"), order.take(Onboarding.DOCK))
        assertEquals(listOf("1", "2", "3", "4", "5", "0", "6"), order)
    }

    @Test fun freshInstallGetsTheAssistantAndTheAnswerIsKept() {
        val ctx = FakeContext()
        assertFalse(Prefs(ctx).setupDone)
        // The home screen showing afterwards does not turn the fresh install into an old one.
        Prefs(ctx).homeInFront = true
        Prefs(ctx).order = listOf("a")
        assertFalse(Prefs(ctx).setupDone)
        Prefs(ctx).setupDone = true
        assertTrue(Prefs(ctx).setupDone)
    }

    @Test fun anInstallUpdatedFromOneZeroZeroSkipsTheAssistant() {
        FakeContext().let { ctx -> Prefs(ctx).order = listOf("com.netflix.ninja"); assertTrue(Prefs(ctx).setupDone) }
        FakeContext().let { ctx -> Prefs(ctx).adbAuthorized = true; assertTrue(Prefs(ctx).setupDone) }
        FakeContext().let { ctx -> Prefs(ctx).homeInFront = false; assertTrue(Prefs(ctx).setupDone) }
    }
}
