package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDisplayHoldLifecycleTest {
    @Test
    fun `first step acquires a short lease that wakes the panel`() {
        val harness = Harness()

        val acquired = harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS, wake = true), acquired)
        assertEquals(listOf(LEASE_MS), harness.leases.single().acquisitions)
        assertEquals(listOf(true), harness.leases.single().wakes)
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
    fun `renewals and handovers never wake the panel again`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 1L, nowMs = 4_000L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 2L, nowMs = 4_500L, deadlineAtMs = 110_000L)

        assertEquals(listOf(true, false, false, false), harness.leases.single().wakes)
    }

    @Test
    fun `a lease never reaches past the run deadline`() {
        val harness = Harness()

        harness.renew(runToken = 1L, nowMs = 104_000L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 1L, nowMs = 108_500L, deadlineAtMs = 110_000L)

        assertEquals(listOf(6_000L, 1_500L), harness.leases.single().acquisitions)
        assertEquals(listOf(6_000L, 1_500L), harness.backstop.tasks.map { it.delayMs })
    }

    @Test
    fun `renewing at the deadline releases instead of extending`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)

        val expired = harness.renew(runToken = 1L, nowMs = 110_000L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldEnd.RUN_EXPIRED, (expired as SetupDisplayHoldTransition.Release).reason)
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.heldRunToken())
        assertTrue(harness.backstop.tasks.single().cancelled)
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
            assertTrue(end.logValue, harness.backstop.tasks.all { it.cancelled })
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
    fun `the backstop releases a lease nobody renews, off the main thread`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 110_000L)

        // The main thread is stuck: no tick renews, no posted release runs. Only the
        // backstop's own thread is left to let the panel go.
        harness.backstop.fireOnOwnThread(harness.backstop.tasks.last())

        assertEquals(
            listOf(
                SetupDisplayHoldTransition.Release(
                    1L,
                    SetupDisplayHoldEnd.LEASE_EXPIRED,
                    ageMs = 12_000L,
                    renewals = 1,
                ),
            ),
            harness.backstopReleases,
        )
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.heldRunToken())
    }

    @Test
    fun `each renewal moves the backstop and a superseded one releases nothing`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 110_000L)
        val (first, second) = harness.backstop.tasks

        harness.backstop.fireOnOwnThread(first)

        assertTrue(first.cancelled)
        assertFalse(second.cancelled)
        assertTrue(harness.backstopReleases.isEmpty())
        assertEquals(0, harness.leases.single().releases)
        assertEquals(1L, harness.lifecycle.heldRunToken())
    }

    @Test
    fun `an ended run's backstop never releases the next run's lease`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        harness.lifecycle.release(SetupDisplayHoldEnd.SUCCEEDED, nowMs = 1_000L, runToken = 1L)
        harness.renew(runToken = 2L, nowMs = 1_500L, deadlineAtMs = 110_000L)
        val (endedRun, nextRun) = harness.backstop.tasks

        harness.backstop.fireOnOwnThread(endedRun)

        assertTrue(endedRun.cancelled)
        assertFalse(nextRun.cancelled)
        assertTrue(harness.backstopReleases.isEmpty())
        assertEquals(2L, harness.lifecycle.heldRunToken())
        assertEquals(0, harness.leases.last().releases)
    }

    @Test
    fun `a handed-over lease is only released by the new run's backstop`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 45_000L)
        harness.renew(runToken = 2L, nowMs = 500L, deadlineAtMs = 110_500L)
        val (previousRun, currentRun) = harness.backstop.tasks

        harness.backstop.fireOnOwnThread(previousRun)
        assertEquals(2L, harness.lifecycle.heldRunToken())

        harness.backstop.fireOnOwnThread(currentRun)
        assertEquals(SetupDisplayHoldEnd.LEASE_EXPIRED, harness.backstopReleases.single().reason)
        assertEquals(2L, harness.backstopReleases.single().runToken)
        assertNull(harness.lifecycle.heldRunToken())
    }

    @Test
    fun `a lease the backstop could not cover is refused`() {
        val harness = Harness(backstopFails = true)

        val refused = harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldTransition.Refused(1L, DisplayHoldFailure.ACQUIRE_FAILED), refused)
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.heldRunToken())
    }

    @Test
    fun `a lapsed lease wakes the panel again, since the lapse was not the wearer's choice`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        harness.backstop.fireOnOwnThread(harness.backstop.tasks.single())

        val reacquired = harness.renew(runToken = 1L, nowMs = 11_000L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS, wake = true), reacquired)
    }

    @Test
    fun `a panel the wearer slept mid-hold is let go and left asleep`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 300_000L, scope = "session-a")

        harness.interactive = false
        val slept = harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 300_000L, scope = "session-a")
        val stillDark = harness.renew(runToken = 1L, nowMs = 4_000L, deadlineAtMs = 300_000L, scope = "session-a")
        val chainedRun = harness.renew(runToken = 2L, nowMs = 6_000L, deadlineAtMs = 300_000L, scope = "session-a")

        assertEquals(
            SetupDisplayHoldTransition.Release(1L, SetupDisplayHoldEnd.WEARER_SLEPT, ageMs = 2_000L, renewals = 0),
            slept,
        )
        assertNull(stillDark)
        assertNull(chainedRun)
        assertEquals(1, harness.leases.size)
        assertEquals(listOf(true), harness.leases.single().wakes)
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.heldRunToken())
    }

    @Test
    fun `a panel the wearer lights again is held without being woken`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 300_000L, scope = "session-a")
        harness.interactive = false
        harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 300_000L, scope = "session-a")

        harness.interactive = true
        val tooSoon = harness.renew(runToken = 1L, nowMs = 2_500L, deadlineAtMs = 300_000L, scope = "session-a")
        val resumed = harness.renew(runToken = 1L, nowMs = 4_000L, deadlineAtMs = 300_000L, scope = "session-a")

        assertNull(tooSoon)
        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS, wake = false), resumed)
        assertEquals(listOf(false), harness.leases.last().wakes)
    }

    @Test
    fun `a new request after the wearer slept the panel may wake it`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 300_000L, scope = "session-a")
        harness.interactive = false
        harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 300_000L, scope = "session-a")

        val newRequest = harness.renew(runToken = 2L, nowMs = 3_000L, deadlineAtMs = 300_000L, scope = "session-b")

        assertEquals(SetupDisplayHoldTransition.Acquire(2L, LEASE_MS, wake = true), newRequest)
    }

    @Test
    fun `a backstop that finds the panel dark remembers the wearer slept it`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 300_000L, scope = "session-a")

        // The main thread stalled, the wearer pressed power, and only the backstop is left.
        harness.interactive = false
        harness.backstop.fireOnOwnThread(harness.backstop.tasks.single())
        val nextTick = harness.renew(runToken = 1L, nowMs = 12_000L, deadlineAtMs = 300_000L, scope = "session-a")
        harness.interactive = true
        val lit = harness.renew(runToken = 1L, nowMs = 14_000L, deadlineAtMs = 300_000L, scope = "session-a")

        assertEquals(SetupDisplayHoldEnd.WEARER_SLEPT, harness.backstopReleases.single().reason)
        assertNull(nextTick)
        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS, wake = false), lit)
        assertEquals(listOf(true, false), harness.leases.flatMap { it.wakes })
    }

    @Test
    fun `a recreated lifecycle keeps a session's panel asleep`() {
        val shared = SetupDisplaySleptScope()
        val before = Harness(sleptScope = shared)
        before.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 300_000L, scope = "session-a")
        before.interactive = false
        before.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 300_000L, scope = "session-a")

        // The service is recreated mid-session: a fresh automator, a fresh lifecycle.
        val after = Harness(sleptScope = shared).apply { interactive = false }
        val resumedDark = after.renew(runToken = 1L, nowMs = 5_000L, deadlineAtMs = 300_000L, scope = "session-a")
        after.interactive = true
        val lit = after.renew(runToken = 1L, nowMs = 7_000L, deadlineAtMs = 300_000L, scope = "session-a")

        assertNull(resumedDark)
        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS, wake = false), lit)
    }

    @Test
    fun `a session's sleep memory ends with the session`() {
        val shared = SetupDisplaySleptScope()
        val harness = Harness(sleptScope = shared)
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 300_000L, scope = "session-a")
        harness.interactive = false
        harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 300_000L, scope = "session-a")

        shared.forget("session-b")
        assertEquals(2_000L, shared.checkedAtMs("session-a"))
        shared.forget("session-a")

        assertNull(shared.checkedAtMs("session-a"))
        val next = Harness(sleptScope = shared)
            .renew(runToken = 1L, nowMs = 5_000L, deadlineAtMs = 300_000L, scope = "session-a")
        assertEquals(SetupDisplayHoldTransition.Acquire(1L, LEASE_MS, wake = true), next)
    }

    @Test
    fun `a failing interactivity probe never ends a hold`() {
        val harness = Harness()
        harness.renew(runToken = 1L, nowMs = 0L, deadlineAtMs = 110_000L)
        harness.probeFails = true

        val renewed = harness.renew(runToken = 1L, nowMs = 2_000L, deadlineAtMs = 110_000L)

        assertEquals(SetupDisplayHoldTransition.Renew(1L, LEASE_MS), renewed)
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

    private class FakeLease(private val failAcquireAfter: Int?) : SetupDisplayLease {
        val acquisitions = mutableListOf<Long>()
        val wakes = mutableListOf<Boolean>()
        var releases = 0

        override fun acquire(timeoutMs: Long, wake: Boolean) {
            if (failAcquireAfter != null && acquisitions.size >= failAcquireAfter) {
                error("acquire failed")
            }
            acquisitions += timeoutMs
            wakes += wake
        }

        override fun release() {
            releases += 1
        }
    }

    /** Scheduled tasks are fired by the test, from a thread that is not the caller's. */
    private class FakeBackstop(private val fails: Boolean) : SetupDisplayHoldBackstop {
        class Task(val delayMs: Long, val action: () -> Unit) {
            @Volatile var cancelled = false
        }

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            if (fails) error("executor rejected")
            val task = Task(delayMs, action)
            tasks += task
            return { task.cancelled = true }
        }

        /** Fires even a cancelled task: a cancel can lose the race with a task already running. */
        fun fireOnOwnThread(task: Task) {
            var failure: Throwable? = null
            Thread { runCatching(task.action).onFailure { failure = it } }
                .apply { start() }
                .join()
            failure?.let { throw it }
        }
    }

    private class Harness(
        private val powerAvailable: Boolean = true,
        private val failAcquireAfter: Int? = null,
        backstopFails: Boolean = false,
        sleptScope: SetupDisplaySleptScope = SetupDisplaySleptScope(),
    ) {
        @Volatile var interactive = true
        @Volatile var probeFails = false
        val backstop = FakeBackstop(backstopFails)
        val backstopReleases = mutableListOf<SetupDisplayHoldTransition.Release>()
        val lifecycle = SetupDisplayHoldLifecycle(
            isInteractive = {
                if (probeFails) error("power service died")
                interactive
            },
            backstop = backstop,
            onBackstopRelease = { synchronized(backstopReleases) { backstopReleases += it } },
            sleptScope = sleptScope,
        )
        val leases = mutableListOf<FakeLease>()

        fun renew(runToken: Long, nowMs: Long, deadlineAtMs: Long, scope: String = "session") =
            lifecycle.renew(runToken, scope, nowMs, deadlineAtMs) {
                if (powerAvailable) FakeLease(failAcquireAfter).also(leases::add) else null
            }
    }

    private companion object {
        const val LEASE_MS = SetupDisplayHoldLifecycle.LEASE_MS
    }
}
