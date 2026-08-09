package com.anezium.rokidbus.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class PluginRegistrationRetryTest {
    private class FakeScheduler : RetryScheduler {
        private data class ScheduledTask(
            val delayMs: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = ArrayDeque<ScheduledTask>()
        val scheduledDelays = mutableListOf<Long>()

        override fun schedule(delayMs: Long, action: () -> Unit): RetryCancellation {
            val task = ScheduledTask(delayMs, action)
            tasks.addLast(task)
            scheduledDelays += delayMs
            return RetryCancellation { task.cancelled = true }
        }

        fun runNext(): Boolean {
            while (tasks.isNotEmpty()) {
                val task = tasks.removeFirst()
                if (!task.cancelled) {
                    task.action()
                    return true
                }
            }
            return false
        }

        fun hasPendingTask(): Boolean = tasks.any { !it.cancelled }
    }

    private class FakeRegistrationTransport(vararg results: Int) {
        private val results = ArrayDeque(results.toList())
        var registrationAttempts = 0
            private set

        fun register(): Int {
            registrationAttempts += 1
            return results.removeFirst()
        }
    }

    private class Fixture(vararg results: Int) {
        val scheduler = FakeScheduler()
        val transport = FakeRegistrationTransport(*results)
        val callbacks = mutableListOf<Int>()
        val retry: PluginRegistrationRetry

        init {
            retry = PluginRegistrationRetry(
                scheduler = scheduler,
                retry = ::attemptRegistration,
            )
        }

        fun attemptRegistration() {
            val result = transport.register()
            callbacks += result
            retry.onRegistrationResult(result)
        }
    }

    @Test
    fun `pending registration retries and delivers approval exactly once`() {
        val fixture = Fixture(
            PluginRegistrationResult.PENDING_USER_APPROVAL,
            PluginRegistrationResult.APPROVED,
        )

        fixture.attemptRegistration()
        assertEquals(listOf(PluginRegistrationResult.PENDING_USER_APPROVAL), fixture.callbacks)
        assertEquals(listOf(1_000L), fixture.scheduler.scheduledDelays)

        assertTrue(fixture.scheduler.runNext())
        assertEquals(
            listOf(
                PluginRegistrationResult.PENDING_USER_APPROVAL,
                PluginRegistrationResult.APPROVED,
            ),
            fixture.callbacks,
        )
        assertEquals(1, fixture.callbacks.count { it == PluginRegistrationResult.APPROVED })
        assertEquals(2, fixture.transport.registrationAttempts)
        assertFalse(fixture.scheduler.hasPendingTask())
    }

    @Test
    fun `close during backoff cancels the pending registration attempt`() {
        val fixture = Fixture(PluginRegistrationResult.PENDING_USER_APPROVAL)

        fixture.attemptRegistration()
        fixture.retry.close()

        assertFalse(fixture.scheduler.runNext())
        assertEquals(1, fixture.transport.registrationAttempts)
    }

    @Test
    fun `immediate approval remains a single registration attempt and callback`() {
        val fixture = Fixture(PluginRegistrationResult.APPROVED)

        fixture.attemptRegistration()

        assertEquals(listOf(PluginRegistrationResult.APPROVED), fixture.callbacks)
        assertEquals(1, fixture.transport.registrationAttempts)
        assertFalse(fixture.scheduler.hasPendingTask())
    }

    @Test
    fun `hard denial does not schedule another registration attempt`() {
        val hardResults = listOf(
            PluginRegistrationResult.DENIED,
            PluginRegistrationResult.INVALID_DESCRIPTOR,
            PluginRegistrationResult.IDENTITY_MISMATCH,
            PluginRegistrationResult.UNSUPPORTED_API,
        )

        hardResults.forEach { result ->
            val fixture = Fixture(result)
            fixture.attemptRegistration()

            assertEquals(listOf(result), fixture.callbacks)
            assertEquals(1, fixture.transport.registrationAttempts)
            assertFalse(fixture.scheduler.hasPendingTask())
        }
    }

    @Test
    fun `transient failures back off to the ceiling and continue there`() {
        val fixture = Fixture(
            *IntArray(8) { PluginRegistrationResult.REGISTRATION_FAILED },
        )

        fixture.attemptRegistration()
        repeat(7) { assertTrue(fixture.scheduler.runNext()) }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 32_000L, 32_000L),
            fixture.scheduler.scheduledDelays,
        )
        assertTrue(fixture.scheduler.hasPendingTask())
    }
}
