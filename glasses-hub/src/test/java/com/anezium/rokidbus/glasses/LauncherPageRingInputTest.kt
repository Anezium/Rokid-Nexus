package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPageRingInputTest {
    private val actions = mutableListOf<String>()
    private val input = LauncherPageRingInput(
        onMove = { actions += "move$it" },
        onOpen = { actions += "open" },
        onBack = { actions += "back" },
    )
    private val window = RingTapPolicy.DEFAULT_WINDOW_MS + 1L

    @Test
    fun `swipes move at once and leave no tap to resolve`() {
        assertFalse(input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_FORWARD, 1_000))
        assertFalse(input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD, 1_100))

        assertEquals(listOf("move1", "move-1"), actions)
    }

    @Test
    fun `one tap opens only once its window has passed`() {
        assertTrue(input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 1_000))

        input.resolveTaps(1_000 + window - 1)
        assertEquals(emptyList<String>(), actions)
        input.resolveTaps(1_000 + window)
        assertEquals(listOf("open"), actions)
    }

    @Test
    fun `two taps go back and three do nothing`() {
        input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 1_000)
        input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 1_200)
        input.resolveTaps(1_200 + window)
        assertEquals(listOf("back"), actions)

        actions.clear()
        input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 5_000)
        input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 5_200)
        input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 5_400)
        input.resolveTaps(5_400 + window)
        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun `a page that left the front forgets its pending tap`() {
        input.onPress(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 1_000)
        input.reset()
        input.resolveTaps(1_000 + window)

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun `only the three ring gestures belong to the page`() {
        assertTrue(LauncherPageRingInput.isRingGesture(RingSurfaceInputPolicy.RING_KEYCODE_TAP))
        assertTrue(LauncherPageRingInput.isRingGesture(RingSurfaceInputPolicy.RING_KEYCODE_FORWARD))
        assertTrue(LauncherPageRingInput.isRingGesture(RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD))
        assertFalse(LauncherPageRingInput.isRingGesture(RingSurfaceInputPolicy.KEYCODE_ENTER))
    }
}
