package com.anezium.rokidbus.plugin.lens

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensLifecycleTest {
    private class FakeEngine : LensSessionEngine {
        var closes = 0
        val messages = mutableListOf<String>()
        override fun message(path: String, id: String, payload: JSONObject) {
            messages += path
        }
        override fun close() {
            closes++
        }
    }

    @Test
    fun `engines exist only while open and close is idempotent`() {
        val created = mutableListOf<FakeEngine>()
        val lifecycle = LensLifecycle { FakeEngine().also(created::add) }
        lifecycle.message("/camera/link/offer", "closed", JSONObject())
        assertFalse(lifecycle.isActive())

        lifecycle.open()
        assertTrue(lifecycle.isActive())
        lifecycle.message("/camera/session/state", "open", JSONObject())
        assertEquals(listOf("/camera/session/state"), created.single().messages)

        lifecycle.close()
        lifecycle.close()
        assertFalse(lifecycle.isActive())
        assertEquals(1, created.single().closes)
    }

    @Test
    fun `fresh open tears down any previous session before creating engines`() {
        val created = mutableListOf<FakeEngine>()
        val lifecycle = LensLifecycle { FakeEngine().also(created::add) }
        lifecycle.open()
        lifecycle.open()
        assertEquals(2, created.size)
        assertEquals(1, created.first().closes)
        assertEquals(0, created.last().closes)
        lifecycle.close()
        assertEquals(1, created.last().closes)
    }
}
