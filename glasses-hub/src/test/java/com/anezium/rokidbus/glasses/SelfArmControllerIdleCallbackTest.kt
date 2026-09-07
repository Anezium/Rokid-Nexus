package com.anezium.rokidbus.glasses

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmControllerIdleCallbackTest {
    /**
     * Regression: a background arm started under setup session A; A was replaced by B while it
     * ran; an owner repair queued through runWhenIdle under B. The arm's completion used to drop
     * every queued callback because *its own* session was no longer current, so the repair's
     * single-flight latch was never released and every later repair answered BUSY.
     */
    @Test
    fun idleCallbacksAreDeliveredEvenWhenTheOperationsSessionWasReplaced() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.beginSession(context)

        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val accepted = SelfArmController.runAsync(context, "test_arm") { _, _ ->
            operationStarted.countDown()
            releaseOperation.await(5, TimeUnit.SECONDS)
        }
        assertTrue(accepted)
        assertTrue(operationStarted.await(5, TimeUnit.SECONDS))

        // The operation's session is superseded while it is still running.
        SelfArmOnboardingStore.beginSession(context)

        val callbackRan = CountDownLatch(1)
        SelfArmController.runWhenIdle { callbackRan.countDown() }
        assertTrue(SelfArmController.isOperationRunning())

        releaseOperation.countDown()
        assertTrue(
            "idle callback queued under the new session must run when the old operation ends",
            callbackRan.await(5, TimeUnit.SECONDS),
        )
    }
}
