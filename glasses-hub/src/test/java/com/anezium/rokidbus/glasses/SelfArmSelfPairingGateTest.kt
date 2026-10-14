package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSelfPairingGateTest {
    private val liveSessions = mutableSetOf<String>()
    private val isLive: (String) -> Boolean = { it in liveSessions }

    private fun SelfArmSelfPairingGate.start(owner: Boolean, sessionId: String): SelfArmSelfPairingGate.Admission {
        val admission = admit(owner, backgroundPermitted = true, isLive)
        if (admission.refusal == null) {
            liveSessions += sessionId
            onStarted(sessionId)
        }
        return admission
    }

    @Test
    fun `a live run holds the latch for owner and background alike`() {
        val gate = SelfArmSelfPairingGate(backgroundBudget = 6)
        assertNull(gate.start(owner = false, sessionId = "a").refusal)

        assertEquals(SelfPairingOutcome.ALREADY_IN_FLIGHT, gate.admit(owner = false, backgroundPermitted = true, isLive).refusal)
        assertEquals(SelfPairingOutcome.ALREADY_IN_FLIGHT, gate.admit(owner = true, backgroundPermitted = true, isLive).refusal)
    }

    @Test
    fun `a failed run releases the latch and the owner's retry starts again`() {
        val gate = SelfArmSelfPairingGate(backgroundBudget = 6)
        gate.start(owner = true, sessionId = "first")

        // The automation ended the run as failed: the session finished, and its end released it.
        liveSessions -= "first"
        assertTrue(gate.release("first"))
        assertNull(gate.inFlightSessionId())

        val retry = gate.start(owner = true, sessionId = "second")
        assertNull(retry.refusal)
        assertEquals("second", gate.inFlightSessionId())
    }

    @Test
    fun `a run that died without finishing is noticed and dropped at the next request`() {
        val gate = SelfArmSelfPairingGate(backgroundBudget = 6)
        gate.start(owner = false, sessionId = "expired")
        // Lease expired or superseded: no finish callback ever came.
        liveSessions -= "expired"

        val admission = gate.admit(owner = true, backgroundPermitted = true, isLive)

        assertNull(admission.refusal)
        assertEquals("expired", admission.releasedSessionId)
    }

    @Test
    fun `releasing a session that never held the latch changes nothing`() {
        val gate = SelfArmSelfPairingGate(backgroundBudget = 6)
        gate.start(owner = false, sessionId = "held")

        assertFalse(gate.release("other"))
        assertFalse(gate.release(""))
        assertEquals("held", gate.inFlightSessionId())
    }

    @Test
    fun `background requests are budgeted and the owner never is`() {
        val gate = SelfArmSelfPairingGate(backgroundBudget = 2)
        // Requests that found nothing to drive Settings still spend the budget.
        assertEquals(1, gate.admit(owner = false, backgroundPermitted = true, isLive).backgroundAttempt)
        assertEquals(2, gate.admit(owner = false, backgroundPermitted = true, isLive).backgroundAttempt)

        val exhausted = gate.admit(owner = false, backgroundPermitted = true, isLive)
        assertEquals(SelfPairingOutcome.BUDGET_EXHAUSTED, exhausted.refusal)
        assertEquals(2, exhausted.backgroundAttempt)

        repeat(5) {
            val owner = gate.admit(owner = true, backgroundPermitted = true, isLive)
            assertNull(owner.refusal)
            assertEquals(0, owner.backgroundAttempt)
        }
    }

    @Test
    fun `without standing consent only the owner may start a pairing`() {
        val gate = SelfArmSelfPairingGate(backgroundBudget = 6)

        val refused = gate.admit(owner = false, backgroundPermitted = false, isLive)
        assertEquals(SelfPairingOutcome.NOT_PERMITTED, refused.refusal)
        // A refused request is not an attempt: consent given later still has the whole budget.
        assertEquals(0, refused.backgroundAttempt)
        assertEquals(1, gate.admit(owner = false, backgroundPermitted = true, isLive).backgroundAttempt)

        assertNull(gate.admit(owner = true, backgroundPermitted = false, isLive).refusal)
        assertFalse(SelfPairingOutcome.NOT_PERMITTED.pending)
    }

    @Test
    fun `only a started or in-flight run counts as pending`() {
        assertTrue(SelfPairingOutcome.STARTED.pending)
        assertTrue(SelfPairingOutcome.ALREADY_IN_FLIGHT.pending)
        assertFalse(SelfPairingOutcome.SERVICE_UNAVAILABLE.pending)
        assertFalse(SelfPairingOutcome.BUDGET_EXHAUSTED.pending)
    }
}
