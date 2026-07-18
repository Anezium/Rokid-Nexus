package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmWatchdogRetryPolicyTest {
    @Test
    fun unreachableFailureRequiresAReachabilitySignal() {
        val policy = SelfArmWatchdogRetryPolicy()

        assertFalse(policy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE))
        assertTrue(policy.onReachabilitySignal())
        assertTrue(policy.onRetryDelayElapsed())
        assertFalse(policy.onRetryDelayElapsed())
    }

    @Test
    fun signalObservedDuringInitialAttemptIsUsedOnce() {
        val policy = SelfArmWatchdogRetryPolicy()

        assertFalse(policy.onReachabilitySignal())
        assertTrue(policy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE))
        assertTrue(policy.onRetryDelayElapsed())

        assertFalse(policy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE))
        assertFalse(policy.onRetryDelayElapsed())
    }

    @Test
    fun multipleSignalsDebounceToOneRunningRetry() {
        val policy = SelfArmWatchdogRetryPolicy()
        policy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE)

        assertTrue(policy.onReachabilitySignal())
        assertTrue(policy.onReachabilitySignal())
        assertTrue(policy.onRetryDelayElapsed())
        assertFalse(policy.onReachabilitySignal())
        assertFalse(policy.onRetryDelayElapsed())

        assertTrue(policy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE))
        assertTrue(policy.onRetryDelayElapsed())
    }

    @Test
    fun successfulOrNonReachabilityFailureClearsPendingRetry() {
        val readyPolicy = SelfArmWatchdogRetryPolicy()
        readyPolicy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE)
        readyPolicy.onReachabilitySignal()
        assertFalse(readyPolicy.onEnsureFinished(SelfArmWatchdogEnsureResult.READY))
        assertFalse(readyPolicy.onRetryDelayElapsed())

        val failedPolicy = SelfArmWatchdogRetryPolicy()
        failedPolicy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE)
        failedPolicy.onReachabilitySignal()
        assertFalse(failedPolicy.onEnsureFinished(SelfArmWatchdogEnsureResult.FAILED))
        assertFalse(failedPolicy.onRetryDelayElapsed())
    }

    @Test
    fun rejectedSingleFlightStartCanBeRetriedAfterControllerBecomesIdle() {
        val policy = SelfArmWatchdogRetryPolicy()
        policy.onEnsureFinished(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE)
        policy.onReachabilitySignal()
        assertTrue(policy.onRetryDelayElapsed())

        policy.onRetryStartRejected()

        assertTrue(policy.onReachabilitySignal())
        assertTrue(policy.onRetryDelayElapsed())
    }
}
