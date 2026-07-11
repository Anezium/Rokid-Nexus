package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginWakePolicyTest {
    private fun candidate(
        owner: String = "hello",
        uid: Int = 10,
        prefix: String = "/plugin/hello",
        approved: Boolean = true,
    ) = PluginWakeCandidate(owner, uid, listOf(prefix), approved)

    @Test
    fun `approved exact owner wakes`() {
        assertTrue(
            PluginWakePolicy.select("/plugin/hello/event", listOf(candidate())) is
                PluginWakeSelection.Selected,
        )
    }

    @Test
    fun `denied revoked and excluded owners do not wake`() {
        assertEquals(PluginWakeSelection.None, PluginWakePolicy.select("/plugin/hello", listOf(candidate(approved = false))))
        assertEquals(PluginWakeSelection.None, PluginWakePolicy.select("/plugin/hello", listOf(candidate()), excludeUid = 10))
    }

    @Test
    fun `segment boundary blocks lexical near miss`() {
        assertEquals(PluginWakeSelection.None, PluginWakePolicy.select("/plugin/hello-world", listOf(candidate())))
    }

    @Test
    fun `duplicate owners are a conflict instead of first match`() {
        val result = PluginWakePolicy.select(
            "/plugin/hello/event",
            listOf(candidate(owner = "one"), candidate(owner = "two")),
        )
        assertEquals(PluginWakeSelection.Conflict, result)
    }
}
