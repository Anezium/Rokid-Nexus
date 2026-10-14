package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDisplayHoldLifecycleTest {
    @Test
    fun `first step acquires a short lease that wakes the panel`() {
        val harness = Harness()

        val acquired = harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS), acquired)
        assertEquals(listOf(LEASE_MS), harness.leases.single().acquisitions)
        assertEquals(1L, harness.lifecycle.heldRunToken())
    }

    @Test
    fun `steps renew at most once per interval and keep one lease`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)

        val tooSoon = harness.renew(runToken = 1L, nowMs = 450L, deadlineAtMs = 110_000L)
        val due = harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 110_000L)

        assertNull(tooSoon)
        assertEquals(SetupDisplayHoldTransition.Renew(1L, LEASE_MS), due)
        assertEquals(1, harness.leases.size)
        assertEquals(listOf(LEASE_MS, LEASE_MS), harness.leases.single().acquisitions)
        assertEquals(0, harness.leases.single().releases)
    }

    @Test
    fun `a lease never reaches past the run deadline`() {
        val harness = Harness()

        harness.renew(runToken = 1L, nowMs = 104_000L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 1L, nowMs = 108_500L, deadlineAtMs = 110_000L)

        assertEquals(listOf(6_000L, 1_500L), harness.leases.single().acquisitions)
    }

    @Test
    fun `renewing at the deadline releases instead of extending`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)

        val expired = harness.renew(runToken = 1L, nowMs = 110_000L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldEnd.RUN_EXPIRED, (expired as SetupDisplayHoldTransition.Release).reason)
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.heldRunToken())
    }

    @Test
    fun `every end path releases the hold exactly once`() {
        val ends = listOf(
            SetupDisplayHoldEnd.SUCCEEDED,
            SetupDisplayHoldEnd.FAILED,
            SetupDisplayHoldEnd.TIMED_OUT,
            SetupDisplayHoldEnd.STOPPED,
            SetupDisplayHoldEnd.SESSION_ENDED,
            SetupDisplayHoldEnd.SERVICE_DESTROYED,
        )
        ends.forEach { end ->
            val harness = Harness()
            harness.renew(runToken = 7L, nowMs = 1_000L, deadlineAtMs = 110_000L)
            harness.renew(runToken = 7L, nowMs = 4_000L, deadlineAtMs = 110_000L)

            val release = harness.lifecycle.release(end, nowMs = 9_000L, runToken = 7L)
            val duplicate = harness.lifecycle.release(end, nowMs = 9_500L, runToken = 7L)
            val unscoped = harness.lifecycle.release(end, nowMs = 9_600L)

            assertEquals(
                end.logValue,
                SetupDisplayHoldTransition.Release(7L, end, ageMs = 8_000L, renewals = 1),
                release,
            )
            assertNull(end.logValue, duplicate)
            assertNull(end.logValue, unscoped)
            assertEquals(end.logValue, 1, harness.leases.single().releases)
            assertNull(end.logValue, harness.lifecycle.heldRunToken())
        }
    }

    @Test
    fun `a run started before the previous release takes the lease over`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 45_000L)

        val handover = harness.renew(runToken = 2L, nowMs = 500L, deadlineAtMs = 110_500L)
        val staleRelease = harness.lifecycle.release(
            SetupDisplayHoldEnd.SUCCEEDED,
            nowMs = 501L,
            runToken = 1L,
        )

        assertEquals(SetupDisplayHoldTransition.Handover(2L, 1L, LEASE_MS), handover)
        assertNull(staleRelease)
        assertEquals(2L, harness.lifecycle.heldRunToken())
        assertEquals(1, harness.leases.size)
        assertEquals(0, harness.leases.single().releases)
    }

    @Test
    fun `an ended run that nobody took over is released by its token`() {
        val harness = Harness()
        harness.renew(runToken = 3L, nowMs = 0L, deadlineAtMs = 110_000L)

        val release = harness.lifecycle.release(
            SetupDisplayHoldEnd.SESSION_ENDED,
            nowMs = 600L,
            runToken = 3L,
        )

        assertEquals(SetupDisplayHoldEnd.SESSION_ENDED, release?.reason)
        assertEquals(1, harness.leases.single().releases)
    }

    @Test
    fun `a stale run cannot take the hold after its deadline`() {
        val harness = Harness()

        val late = harness.renew(runToken = 4L, nowMs = 120_000L, deadlineAtMs = 110_000L)

        assertNull(late)
        assertTrue(harness.leases.isEmpty())
    }

    @Test
    fun `power failures refuse or end the hold without leaking a lease`() {
        val noPower = Harness(powerAvailable = false)
        assertEquals(
            SetupDisplayHoldTransition.Refused(1L, DisplayHoldFailure.POWER_SERVICE_UNAVAILABLE),
            noPower.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L),
        )

        val acquireFails = Harness(failAcquireAfter = 0)
        assertEquals(
            SetupDisplayHoldTransition.Refused(1L, DisplayHoldFailure.ACQUIRE_FAILED),
            acquireFails.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L),
        )
        assertEquals(1, acquireFails.leases.single().releases)
        assertNull(acquireFails.lifecycle.heldRunToken())

        val renewFails = Harness(failAcquireAfter = 1)
        renewFails.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        val failed = renewFails.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 110_000L)
        assertEquals(
            SetupDisplayHoldEnd.HOLD_FAILURE,
            (failed as SetupDisplayHoldTransition.Release).reason,
        )
        assertEquals(1, renewFails.leases.single().releases)
        assertNull(renewFails.lifecycle.heldRunToken())
    }

    @Test
    fun `finish states map to their end reason`() {
        assertEquals(
            SetupDisplayHoldEnd.SUCCEEDED,
            SetupDisplayHoldEnd.forFinish("wireless_bootstrap_complete", success = true),
        )
        assertEquals(
            SetupDisplayHoldEnd.SUCCEEDED,
            SetupDisplayHoldEnd.forFinish("manual_pairing_closed", success = true),
        )
        listOf("wireless_setup_timeout", "wifi_enable_timeout", "manual_pairing_timeout").forEach {
            assertEquals(it, SetupDisplayHoldEnd.TIMED_OUT, SetupDisplayHoldEnd.forFinish(it, success = false))
        }
        listOf("pairing_code_expired", "wireless_debugging_manual_step_needed").forEach {
            assertEquals(it, SetupDisplayHoldEnd.FAILED, SetupDisplayHoldEnd.forFinish(it, success = false))
        }
    }

    private class FakeLease(private val failAcquireAfter: Int?) : DisplayHoldLease {
        val acquisitions = mutableListOf<Long>()
        var releases = 0

        override fun acquire(timeoutMs: Long) {
            if (failAcquireAfter != null && acquisitions.size >= failAcquireAfter) {
                error("acquire failed")
            }
            acquisitions += timeoutMs
        }

        override fun release() {
            releases += 1
        }
    }

    private class Harness(
        private val powerAvailable: Boolean = true,
        private val failAcquireAfter: Int? = null,
    ) {
        val lifecycle = SetupDisplayHoldLifecycle()
        val leases = mutableListOf<FakeLease>()

        fun renew(runToken: Long, nowMs: Long, deadlineAtMs: Long) =
            lifecycle.renew(runToken, nowMs, deadlineAtMs) {
                if (powerAvailable) FakeLease(failAcquireAfter).also(leases::add) else null
            }
    }

    private companion object {
        const val LEASE_MS = SetupDisplayHoldLifecycle.LEASE_MS
    }
}
