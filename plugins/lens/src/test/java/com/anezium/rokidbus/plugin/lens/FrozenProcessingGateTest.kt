package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenProcessingGateTest {
    @Test
    fun `first frozen job pauses and final completion resumes live work`() {
        val events = mutableListOf<String>()
        val gate = FrozenProcessingGate(
            pauseLive = { events += "pause" },
            cancelLiveTranslations = { events += "cancel-translations" },
            resumeLive = { events += "resume" },
        )

        val first = checkNotNull(gate.begin())
        val second = checkNotNull(gate.begin())

        assertTrue(gate.isActive)
        assertFalse(gate.runIfLive { events += "live" })
        assertEquals(listOf("pause", "cancel-translations"), events)

        first.close()
        first.close()
        assertTrue(gate.isActive)
        assertEquals(listOf("pause", "cancel-translations"), events)

        second.close()
        assertFalse(gate.isActive)
        assertTrue(gate.runIfLive { events += "live" })
        assertEquals(listOf("pause", "cancel-translations", "resume", "live"), events)
    }

    @Test
    fun `closing the gate prevents late completion from resuming live work`() {
        var resumes = 0
        val gate = FrozenProcessingGate({}, {}, { resumes += 1 })
        val lease = checkNotNull(gate.begin())

        gate.close()
        lease.close()

        assertEquals(0, resumes)
        assertNull(gate.begin())
        assertFalse(gate.runIfLive { resumes += 1 })
    }
}
