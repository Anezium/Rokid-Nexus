package com.anezium.rokidbus.glasses

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Issue #53 round 9: setup began while the owner's repair waited for Wi-Fi readiness, the
     * repair's arm captured that session, and the session was replaced before the arm ended. The
     * arm's completion was dropped and the repair stayed "running" for good. An unbound arm takes
     * no session at all.
     */
    @Test
    fun anUnboundOperationTakesNoSetupSessionAndStillCompletesWhenOneIsReplaced() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.beginSession(context)

        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var boundSession: String? = "unset"
        val accepted = SelfArmController.runAsync(
            context,
            "test_owner_arm",
            onComplete = { completed.countDown() },
            bindSetupSession = false,
        ) { _, sessionId ->
            boundSession = sessionId
            operationStarted.countDown()
            releaseOperation.await(5, TimeUnit.SECONDS)
        }
        assertTrue(accepted)
        assertTrue(operationStarted.await(5, TimeUnit.SECONDS))
        assertNull(boundSession)

        SelfArmOnboardingStore.beginSession(context)
        releaseOperation.countDown()
        assertTrue(
            "an unbound operation must report back whatever happened to setup sessions",
            completed.await(5, TimeUnit.SECONDS),
        )
    }

    /** The same race through the branch where a background arm already held the single-flight. */
    @Test
    fun anUnboundIdleCallbackSurvivesItsSetupSessionBeingReplaced() {
        val context = RuntimeEnvironment.getApplication()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        assertTrue(
            SelfArmController.runAsync(context, "test_background_arm") { _, _ ->
                operationStarted.countDown()
                releaseOperation.await(5, TimeUnit.SECONDS)
            },
        )
        assertTrue(operationStarted.await(5, TimeUnit.SECONDS))

        // Setup starts after the repair began; the repair queues behind the background arm.
        SelfArmOnboardingStore.beginSession(context)
        val boundRan = CountDownLatch(1)
        val unboundRan = CountDownLatch(1)
        SelfArmController.runWhenIdle { boundRan.countDown() }
        SelfArmController.runWhenIdle(bindSetupSession = false) { unboundRan.countDown() }

        SelfArmOnboardingStore.beginSession(context)
        releaseOperation.countDown()
        assertTrue(unboundRan.await(5, TimeUnit.SECONDS))
        // A session-bound caller still loses its callback with its session: that is setup's rule.
        assertFalse(boundRan.await(200, TimeUnit.MILLISECONDS))
    }
}
