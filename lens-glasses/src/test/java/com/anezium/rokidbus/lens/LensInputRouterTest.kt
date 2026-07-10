package com.anezium.rokidbus.lens

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensInputRouterTest {
    @Test
    fun pairedRightAndDownSwipeSwitchesModeOnlyOnce() {
        val router = LensInputRouter(directionalDebounceMs = 250L)

        val right = router.routeKey(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 0, eventTimeMs = 1_000L)
        val down = router.routeKey(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 0, eventTimeMs = 1_030L)

        assertEquals(LensInputAction.SWITCH_OCR_MODE, right.action)
        assertTrue(down.consumed)
        assertNull(down.action)
    }

    @Test
    fun pairedLeftAndUpSwipeSwitchesModeOnlyOnce() {
        val router = LensInputRouter(directionalDebounceMs = 250L)

        val left = router.routeKey(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 0, eventTimeMs = 2_000L)
        val up = router.routeKey(KeyEvent.KEYCODE_DPAD_UP, repeatCount = 0, eventTimeMs = 2_040L)

        assertEquals(LensInputAction.SWITCH_OCR_MODE, left.action)
        assertTrue(up.consumed)
        assertNull(up.action)
    }

    @Test
    fun defaultDebounceSuppressesAliasesThreeHundredMillisecondsApart() {
        val router = LensInputRouter()

        val right = router.routeKey(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 0, eventTimeMs = 1_000L)
        val down = router.routeKey(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 0, eventTimeMs = 1_300L)

        assertEquals(LensInputAction.SWITCH_OCR_MODE, right.action)
        assertTrue(down.consumed)
        assertNull(down.action)
    }

    @Test
    fun nextPhysicalSwipeAfterDebounceIsAccepted() {
        val router = LensInputRouter(directionalDebounceMs = 250L)

        router.routeKey(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 0, eventTimeMs = 1_000L)
        val next = router.routeKey(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 0, eventTimeMs = 1_250L)

        assertEquals(LensInputAction.SWITCH_OCR_MODE, next.action)
    }

    @Test
    fun enterAndCenterPairTogglesFreezeOnlyOnce() {
        val router = LensInputRouter(activationDebounceMs = 250L)

        val enter = router.routeKey(KeyEvent.KEYCODE_ENTER, repeatCount = 0, eventTimeMs = 3_000L)
        val center = router.routeKey(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 0, eventTimeMs = 3_020L)

        assertEquals(LensInputAction.TOGGLE_FREEZE, enter.action)
        assertTrue(center.consumed)
        assertNull(center.action)
    }

    @Test
    fun recognizedRepeatIsConsumedWithoutAction() {
        val router = LensInputRouter()

        val repeat = router.routeKey(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1, eventTimeMs = 4_000L)

        assertTrue(repeat.consumed)
        assertNull(repeat.action)
    }

    @Test
    fun backFallsThroughToNormalActivityNavigation() {
        val router = LensInputRouter()

        val back = router.routeKey(KeyEvent.KEYCODE_BACK, repeatCount = 0, eventTimeMs = 5_000L)

        assertFalse(back.consumed)
        assertNull(back.action)
    }
}
