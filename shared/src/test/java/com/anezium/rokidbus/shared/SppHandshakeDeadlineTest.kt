package com.anezium.rokidbus.shared

import org.junit.Assert.*
import org.junit.Test

class SppHandshakeDeadlineTest {
    @Test fun expiryClosesCandidateAndPreventsPublication() {
        var tick: (() -> Unit)? = null
        var closed = 0
        val deadline = SppHandshakeDeadline({ delay, task ->
            assertEquals(5_000L, delay)
            tick = task
            ({})
        }, { closed++ })
        tick!!()
        assertFalse(deadline.complete())
        tick!!()
        assertEquals(1, closed)
    }

    @Test fun lateTimerCannotCloseCompletedHandshake() {
        var tick: (() -> Unit)? = null
        var cancelled = false
        val deadline = SppHandshakeDeadline({ _, task ->
            tick = task
            ({ cancelled = true })
        }, { fail("Completed handshake closed") })
        assertTrue(deadline.complete())
        assertTrue(cancelled)
        tick!!()
    }
}
