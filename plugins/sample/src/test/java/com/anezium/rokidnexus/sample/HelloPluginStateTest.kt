package com.anezium.rokidnexus.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloPluginStateTest {
    @Test
    fun `selection wraps once in either direction`() {
        val state = HelloPluginState()
        state.move(-1)
        assertEquals(2, state.selectedIndex)
        state.move(1)
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `tap marks only the selected row`() {
        val state = HelloPluginState()
        state.move(1)
        state.activate()
        assertEquals(1, state.lines().count { "✓" in it })
        assertTrue("✓" in state.lines()[1])
    }
}
