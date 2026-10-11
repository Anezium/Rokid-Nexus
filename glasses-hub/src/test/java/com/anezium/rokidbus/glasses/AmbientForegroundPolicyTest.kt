package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The foreground resolver and the ambient presentation over home and apps (plan 028a §8.1, §8.2). */
class AmbientForegroundPolicyTest {
    private val resolver = ForegroundResolver(
        ownPackage = "com.anezium.rokidbus.glasses",
        isHome = { it == "com.rokid.os.launcher" },
        isInputMethod = { it == "com.example.keyboard" },
    )
    private val state = ActivityStateMachine()

    @Test
    fun `the resolver tells home from apps and ignores Nexus overlays, input methods and the system UI`() {
        assertEquals(Foreground.Unknown, resolver.current)
        assertTrue(resolver.onWindowState("com.example.reader"))
        assertEquals(Foreground.App("com.example.reader"), resolver.current)

        assertFalse(resolver.onWindowState("com.anezium.rokidbus.glasses"))
        assertFalse(resolver.onWindowState("com.example.keyboard"))
        assertFalse(resolver.onWindowState("com.android.systemui"))
        assertFalse(resolver.onWindowState(null))
        assertFalse(resolver.onWindowState("com.example.reader"))
        assertEquals(Foreground.App("com.example.reader"), resolver.current)

        assertTrue(resolver.onWindowState("com.rokid.os.launcher"))
        assertEquals(Foreground.Home, resolver.current)
    }

    @Test
    fun `over a native app an island never expands`() {
        assertEquals(ActivityPresentation.PANEL, select(ActivityPresentationContext.IDLE_HOME, ActivityCollapseState.RUNNING))
        assertEquals(ActivityPresentation.CHIP, select(ActivityPresentationContext.NATIVE_APP, ActivityCollapseState.RUNNING))
        assertEquals(ActivityPresentation.CHIP, select(ActivityPresentationContext.NATIVE_APP, ActivityCollapseState.ALWAYS_EXPANDED))
        // A significant update still signals over an app.
        assertEquals(
            ActivityPresentation.FLARE,
            selectActivityPresentation(ActivityPresentationContext.NATIVE_APP, true, true, ActivityCollapseState.RUNNING),
        )
    }

    @Test
    fun `per-app modes keep residents, only signals, or nothing`() {
        ActivityPresentation.entries.forEach {
            assertEquals(it, applyForegroundMode(it, ForegroundAmbientMode.RESIDENTS))
            assertEquals(ActivityPresentation.HIDDEN, applyForegroundMode(it, ForegroundAmbientMode.NOTHING))
        }
        assertEquals(ActivityPresentation.FLARE, applyForegroundMode(ActivityPresentation.FLARE, ForegroundAmbientMode.SIGNALS))
        assertEquals(ActivityPresentation.HIDDEN, applyForegroundMode(ActivityPresentation.PANEL, ForegroundAmbientMode.SIGNALS))
        assertEquals(ActivityPresentation.HIDDEN, applyForegroundMode(ActivityPresentation.CHIP, ForegroundAmbientMode.SIGNALS))
        assertEquals(ActivityPresentation.HIDDEN, applyForegroundMode(ActivityPresentation.PULSE, ForegroundAmbientMode.SIGNALS))
    }

    @Test
    fun `an explicit size replaces the ten-second collapse and is capped at text over an app`() {
        state.start("maps:activity", "maps", 1, content(), NOW)
        val later = NOW + 10_001

        val automatic = state.snapshot(later, ActivityPresentationContext.IDLE_HOME, null, alwaysExpanded = false).primary!!
        assertEquals(ActivityPresentation.CHIP, automatic.presentation)
        assertNull(automatic.size)

        val sized = state.snapshot(later, ActivityPresentationContext.IDLE_HOME, null, alwaysExpanded = false, sizeOf = { AmbientSize.PANEL }).primary!!
        assertEquals(ActivityPresentation.PANEL, sized.presentation)
        assertEquals(AmbientSize.PANEL, sized.size)

        val inApp = state.snapshot(later, ActivityPresentationContext.NATIVE_APP, null, alwaysExpanded = false, sizeOf = { AmbientSize.PANEL }).primary!!
        assertEquals(ActivityPresentation.CHIP, inApp.presentation)
        assertEquals(AmbientSize.TEXT, inApp.size)
        assertEquals(AmbientSize.MINIMAP, ambientIslandSize(AmbientSize.MINIMAP, ActivityPresentationContext.IDLE_HOME))
        assertNull(ambientIslandSize(null, ActivityPresentationContext.NATIVE_APP))
    }

    @Test
    fun `signals keep a significant update's flare and hide the resident island`() {
        state.start("maps:activity", "maps", 1, content(), NOW)
        val flare = state.presentEvent(
            "maps:activity", ActivityPresentationContext.NATIVE_APP, significant = true, urgent = false,
            nowMs = NOW, alwaysExpanded = false, mode = ForegroundAmbientMode.SIGNALS,
        )
        assertEquals(ActivityPresentation.FLARE, flare.presentation)
        val steady = state.snapshot(NOW, ActivityPresentationContext.NATIVE_APP, null, alwaysExpanded = false, mode = ForegroundAmbientMode.SIGNALS)
        assertEquals(ActivityPresentation.HIDDEN, steady.primary?.presentation)

        val nothing = state.presentEvent(
            "maps:activity", ActivityPresentationContext.NATIVE_APP, significant = true, urgent = true,
            nowMs = NOW + 60_000, alwaysExpanded = false, mode = ForegroundAmbientMode.NOTHING,
        )
        assertEquals(ActivityPresentation.HIDDEN, nothing.presentation)
    }

    private fun select(context: ActivityPresentationContext, collapse: ActivityCollapseState) =
        selectActivityPresentation(context, significant = false, flareBudgetAvailable = false, collapseState = collapse)

    private fun content() = ActivitySurfaceContent(
        glyph = "straight",
        primary = "300 m",
        secondary = "Rue de la Paix",
        progress = null,
        eta = null,
        detail = emptyList(),
        actions = emptyList(),
        maxDurationMs = null,
    )

    private companion object {
        const val NOW = 10_000L
    }
}
