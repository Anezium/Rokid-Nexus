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
    fun `an accessibility bridge overlay does not replace the home or native app underneath`() {
        val bridge = ForegroundWindow("com.anezium.r08accessbridge", false, true, true, 10)
        val home = ForegroundWindow("com.rokid.os.launcher", true)
        assertTrue(resolver.onWindowsChanged(listOf(home, bridge)))
        assertEquals(Foreground.Home, resolver.current)

        val settings = ForegroundWindow("com.android.settings", true, true, true, 1)
        assertTrue(resolver.onWindowsChanged(listOf(home, bridge, settings)))
        assertEquals(Foreground.App("com.android.settings"), resolver.current)
        assertTrue(resolver.onWindowsChanged(listOf(home, bridge)))
        assertEquals(Foreground.Home, resolver.current)
    }

    @Test
    fun `identified Nexus system and keyboard windows are ignored and empty snapshots retain foreground`() {
        val reader = ForegroundWindow("com.example.reader", true, layer = 2)
        val own = ForegroundWindow("com.anezium.rokidbus.glasses", true, true, true, 9)
        val keyboard = ForegroundWindow("com.example.keyboard", true, true, true, 10)
        val system = ForegroundWindow("com.android.systemui", true, true, true, 11)
        resolver.onWindowsChanged(listOf(reader, own, keyboard, system))
        assertEquals(Foreground.App("com.example.reader"), resolver.current)
        assertFalse(resolver.onWindowsChanged(emptyList()))
        assertFalse(resolver.onWindowsChanged(listOf(own, keyboard, system)))
        assertEquals(Foreground.App("com.example.reader"), resolver.current)
    }

    @Test
    fun `a focused application wins over another visible application and layers break ties`() {
        val rear = ForegroundWindow("com.example.reader", true, layer = 3)
        val focused = ForegroundWindow("com.android.settings", true, true, true, 1)
        resolver.onWindowsChanged(listOf(rear, focused))
        assertEquals(Foreground.App("com.android.settings"), resolver.current)
        resolver.onWindowsChanged(listOf(rear, focused.copy(isFocused = false, isActive = false)))
        assertEquals(Foreground.App("com.example.reader"), resolver.current)
    }

    @Test
    fun `an unidentified winning application retains the native foreground until readable`() {
        val rearHome = ForegroundWindow("com.rokid.os.launcher", true, layer = 3)
        val priorities = listOf(
            ForegroundWindow(null, true, isFocused = true, layer = 1),
            ForegroundWindow(null, true, isActive = true, layer = 1),
            ForegroundWindow(null, true, layer = 4),
        )
        for (priority in priorities) {
            for (unreadablePackage in listOf(null, "", "  ")) {
                resolver.onWindowState("com.example.reader")
                val front = priority.copy(packageName = unreadablePackage)
                assertFalse(resolver.onWindowsChanged(listOf(rearHome, front)))
                assertEquals(Foreground.App("com.example.reader"), resolver.current)

                assertTrue(resolver.onWindowsChanged(listOf(rearHome, front.copy(packageName = "com.android.settings"))))
                assertEquals(Foreground.App("com.android.settings"), resolver.current)
            }
        }
    }

    @Test
    fun `unreadable rear applications and nonapplication windows do not block a readable winner`() {
        val focusedHome = ForegroundWindow("com.rokid.os.launcher", true, isFocused = true)
        val unreadableRear = ForegroundWindow(null, true, isActive = true, layer = 9)
        val unreadableOverlay = ForegroundWindow(null, false, true, true, 10)
        resolver.onWindowState("com.example.reader")
        assertTrue(resolver.onWindowsChanged(listOf(unreadableRear, unreadableOverlay, focusedHome)))
        assertEquals(Foreground.Home, resolver.current)
    }

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
