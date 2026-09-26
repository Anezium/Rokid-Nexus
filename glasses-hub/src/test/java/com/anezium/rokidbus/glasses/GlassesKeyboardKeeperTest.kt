package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesKeyboardKeeperTest {
    private val nexus = "com.anezium.rokidbus.glasses/.NexusRemoteInputMethodService"
    private val rokid = "com.rokid.os.sprite.assistserver/com.rokid.os.sprite.assist.ime.RemoteInputMethodService"

    @Test
    fun `takes the keyboard back from Rokid's or from none`() {
        assertTrue(GlassesKeyboardKeepPolicy.shouldReclaim(keep = true, selected = rokid, nexusComponent = nexus))
        assertTrue(GlassesKeyboardKeepPolicy.shouldReclaim(keep = true, selected = null, nexusComponent = nexus))
        assertTrue(GlassesKeyboardKeepPolicy.shouldReclaim(keep = true, selected = "", nexusComponent = nexus))
    }

    @Test
    fun `never replaces a keyboard the owner installed`() {
        assertFalse(
            GlassesKeyboardKeepPolicy.shouldReclaim(
                keep = true,
                selected = "com.example.remotekeyboard/.RemoteIme",
                nexusComponent = nexus,
            ),
        )
    }

    @Test
    fun `does nothing with the switch off or Nexus already selected`() {
        assertFalse(GlassesKeyboardKeepPolicy.shouldReclaim(keep = false, selected = rokid, nexusComponent = nexus))
        assertFalse(GlassesKeyboardKeepPolicy.shouldReclaim(keep = true, selected = nexus, nexusComponent = nexus))
    }

    @Test
    fun `stops taking it back once the window's limit is spent and resumes after`() {
        val budget = GlassesKeyboardReclaimBudget(limit = 3, windowMs = 1_000L)

        listOf(0L, 100L, 200L).forEach { now ->
            assertTrue(budget.hasRoom(now))
            budget.record(now)
        }
        assertFalse(budget.hasRoom(300L))
        assertTrue(budget.hasRoom(1_000L))
    }

    @Test
    fun `a takeback that never happened leaves the budget untouched`() {
        val budget = GlassesKeyboardReclaimBudget(limit = 1, windowMs = 1_000L)

        repeat(5) { assertTrue(budget.hasRoom(it * 10L)) }
        budget.record(50L)
        assertFalse(budget.hasRoom(60L))
    }
}
