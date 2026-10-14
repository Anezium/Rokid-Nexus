package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbSession
import com.anezium.rokidbus.phone.selfarm.adb.ManualShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class GlassesManualPairingEngineTest {
    @Test
    fun submitFromIdleStartsPairingWithoutAnIntermediateWaitingState() {
        val fixture = fixture()
        val states = mutableListOf<GlassesManualPairingState>()
        fixture.engine.observe(states::add)

        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))

        assertEquals(GlassesManualPairingState.ARMING, fixture.engine.state)
        assertEquals(
            listOf(
                GlassesManualPairingState.IDLE,
                GlassesManualPairingState.PAIRING,
                GlassesManualPairingState.CONNECTING,
                GlassesManualPairingState.ARMING,
            ),
            states,
        )
    }

    @Test
    fun submitFromIdleWithoutOptInIsStillRefused() {
        // The phone-assisted path calls submit without the opt-in; after a cancel() has dropped
        // the engine back to IDLE, that call must keep failing instead of resurrecting the offer.
        val fixture = fixture()
        val states = mutableListOf<GlassesManualPairingState>()
        fixture.engine.observe(states::add)

        assertFalse(fixture.engine.submit(HOST, PAIR_PORT, CODE))

        assertEquals(GlassesManualPairingState.IDLE, fixture.engine.state)
        assertEquals(listOf(GlassesManualPairingState.IDLE), states)
    }

    @Test
    fun onlyTheOwnersCancelAsksTheGlassesToAbandonTheirPairing() {
        // A failed phone-side pairing step is not the owner leaving: the glasses may still finish
        // their own pairing, so its CLOSE must not end that run.
        val failed = fixture(FakeBackend(pairFailure = IOException("pairing failed")))
        assertTrue(failed.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))
        assertTrue(failed.engine.state is GlassesManualPairingState.ERROR)
        assertEquals(listOf(false), failed.control.abandonedPairingCloses)

        val cancelled = fixture()
        cancelled.engine.cancel()
        cancelled.engine.cancel(abandonPairing = false)
        assertEquals(listOf(true, false), cancelled.control.abandonedPairingCloses)

        // The hub shutting down closes the engine; nobody cancelled anything.
        val closed = fixture()
        closed.engine.close()
        assertEquals(listOf(false), closed.control.abandonedPairingCloses)
    }

    @Test
    fun submitAfterCloseFailsTheAttemptInsteadOfThrowing() {
        val fixture = fixture(
            worker = ManualPairingTaskExecutor { throw RejectedExecutionException("shut down") },
        )

        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))

        assertTrue(fixture.engine.state is GlassesManualPairingState.ERROR)
    }

    @Test
    fun submitFromIdlePreservesThePhoneAssistedConnectPort() {
        val backend = FakeBackend()
        val fixture = fixture(backend)

        fixture.engine.onPhoneAssistedConnectPort(CONNECT_PORT)
        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))

        assertEquals(
            listOf(GlassesManualAdbEndpoint(HOST, CONNECT_PORT)),
            backend.connectedEndpoints,
        )
        assertEquals(0, backend.discoverConnectEndpointCalls)
    }

    @Test
    fun pushedConnectPortAfterPairingSkipsMdnsFallback() {
        val backend = FakeBackend()
        val worker = BackgroundExecutor()
        val fixture = fixture(
            backend = backend,
            worker = worker,
            connectPortWaitTimeoutMs = 5_000L,
        )
        val connecting = CountDownLatch(1)
        fixture.engine.observe { state ->
            if (state == GlassesManualPairingState.CONNECTING) connecting.countDown()
        }

        fixture.engine.start(awaitGlassesConfirmation = false)
        assertTrue(fixture.engine.showWirelessDebugging())
        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, null))
        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE))
        assertTrue(connecting.await(5, TimeUnit.SECONDS))

        assertTrue(fixture.engine.onGlassesConnectPort(requestId, CONNECT_PORT))

        assertTrue(worker.awaitFinished())
        assertEquals(GlassesManualPairingState.DONE, fixture.engine.state)
        assertEquals(
            listOf(GlassesManualAdbEndpoint(HOST, CONNECT_PORT)),
            backend.connectedEndpoints,
        )
        assertEquals(0, backend.discoverConnectEndpointCalls)
    }

    @Test
    fun missingPushedConnectPortStillFallsBackToMdns() {
        val backend = FakeBackend()
        val fixture = fixture(backend)

        fixture.engine.start(awaitGlassesConfirmation = false)
        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE))

        assertEquals(GlassesManualPairingState.DONE, fixture.engine.state)
        assertEquals(1, backend.discoverConnectEndpointCalls)
        assertEquals(
            listOf(GlassesManualAdbEndpoint(HOST, CONNECT_PORT)),
            backend.connectedEndpoints,
        )
    }

    @Test
    fun latePortPushMustMatchTheAcknowledgedLiveRequest() {
        val backend = FakeBackend()
        val fixture = fixture(backend)

        assertTrue(fixture.engine.showWirelessDebugging())
        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, null))
        fixture.engine.cancel()

        assertFalse(fixture.engine.onGlassesConnectPort(requestId, CONNECT_PORT))
        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))
        assertEquals(1, backend.discoverConnectEndpointCalls)
    }

    @Test
    fun invalidSubmitDoesNotChangeIdleOrWaitingState() {
        val fixture = fixture()
        val states = mutableListOf<GlassesManualPairingState>()
        fixture.engine.observe(states::add)

        assertFalse(fixture.engine.submit(" ", PAIR_PORT, CODE, allowStartFromIdle = true))
        assertFalse(fixture.engine.submit("a".repeat(256), PAIR_PORT, CODE, allowStartFromIdle = true))
        assertFalse(fixture.engine.submit(HOST, 0, CODE, allowStartFromIdle = true))
        assertFalse(fixture.engine.submit(HOST, 65536, CODE, allowStartFromIdle = true))
        assertFalse(fixture.engine.submit(HOST, PAIR_PORT, "12345", allowStartFromIdle = true))
        assertEquals(GlassesManualPairingState.IDLE, fixture.engine.state)
        assertEquals(listOf(GlassesManualPairingState.IDLE), states)

        fixture.engine.start()
        assertFalse(fixture.engine.submit(HOST, PAIR_PORT, "not-a-code"))
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)
        assertEquals(
            listOf(
                GlassesManualPairingState.IDLE,
                GlassesManualPairingState.WAITING_FOR_CODE,
            ),
            states,
        )
    }

    @Test
    fun submitFromIdleWaitsForGlassesConfirmationBeforeDone() {
        val fixture = fixture()

        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))

        assertEquals(GlassesManualPairingState.ARMING, fixture.engine.state)
        fixture.engine.onGlassesSetupReported(false)
        assertEquals(GlassesManualPairingState.ARMING, fixture.engine.state)
        fixture.engine.onGlassesSetupReported(true)
        assertEquals(GlassesManualPairingState.DONE, fixture.engine.state)
    }

    @Test
    fun happyPathWaitsForGlassesConfirmationBeforeDone() {
        val fixture = fixture()
        val states = mutableListOf<GlassesManualPairingState>()
        fixture.engine.observe(states::add)

        assertTrue(fixture.engine.start())
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)
        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE))

        assertEquals(GlassesManualPairingState.ARMING, fixture.engine.state)
        assertEquals(
            listOf(
                GlassesManualPairingState.IDLE,
                GlassesManualPairingState.WAITING_FOR_CODE,
                GlassesManualPairingState.PAIRING,
                GlassesManualPairingState.CONNECTING,
                GlassesManualPairingState.ARMING,
            ),
            states,
        )
        assertEquals(listOf(GlassesManualControlAction.CLOSE), fixture.control.actions)

        fixture.engine.onGlassesSetupReported(true)

        assertEquals(GlassesManualPairingState.DONE, fixture.engine.state)
        assertEquals(GlassesManualPairingState.DONE, states.last())
    }

    /**
     * The manual screen opens on IDLE and its buttons are the whole point of the screen. Requiring
     * WAITING_FOR_CODE first made every one of them return false, which the UI then reported as a
     * lost link while the link was perfectly healthy.
     */
    @Test
    fun settingsScreensOpenBeforeAnyPairingAttemptHasStarted() {
        val fixture = fixture()

        assertEquals(GlassesManualPairingState.IDLE, fixture.engine.state)
        // One command in flight at a time, so each is acknowledged before the next is offered —
        // which is exactly what the screen does by disabling its buttons while one is pending.
        assertTrue(fixture.engine.openAccessibilitySettings())
        assertTrue(fixture.engine.onManualControlResponse(fixture.control.requestIds.last(), null))
        assertTrue(fixture.engine.openDeveloperOptions())
        assertTrue(fixture.engine.onManualControlResponse(fixture.control.requestIds.last(), null))
        assertTrue(fixture.engine.showWirelessDebugging())

        assertEquals(
            listOf(
                GlassesManualControlAction.OPEN_ACCESSIBILITY_SETTINGS,
                GlassesManualControlAction.OPEN_DEVELOPER_OPTIONS,
                GlassesManualControlAction.OPEN_WIRELESS_DEBUGGING,
            ),
            fixture.control.actions,
        )
        assertEquals(GlassesManualPairingState.IDLE, fixture.engine.state)
    }

    /** Navigation is still refused once an attempt is genuinely in flight. */
    @Test
    fun settingsScreensAreRefusedWhilePairingIsRunning() {
        val fixture = fixture()
        fixture.engine.start()
        fixture.engine.submit(HOST, PAIR_PORT, CODE)

        assertEquals(GlassesManualPairingState.ARMING, fixture.engine.state)
        assertFalse(fixture.engine.showWirelessDebugging())
    }

    @Test
    fun assistedPathFinishesAfterArmWithoutOpeningOrClosingManualUi() {
        val fixture = fixture()

        assertTrue(fixture.engine.start(awaitGlassesConfirmation = false))
        fixture.engine.onPhoneAssistedConnectPort(CONNECT_PORT)
        assertTrue(fixture.engine.submit(HOST, PAIR_PORT, CODE))

        assertEquals(GlassesManualPairingState.DONE, fixture.engine.state)
        assertTrue(fixture.control.actions.isEmpty())
    }

    @Test
    fun submitFromDoneOrErrorIsRefusedWithoutTransition() {
        val doneFixture = fixture()
        doneFixture.engine.start(awaitGlassesConfirmation = false)
        assertTrue(doneFixture.engine.submit(HOST, PAIR_PORT, CODE))
        assertEquals(GlassesManualPairingState.DONE, doneFixture.engine.state)
        val doneStates = mutableListOf<GlassesManualPairingState>()
        doneFixture.engine.observe(doneStates::add)

        assertFalse(doneFixture.engine.submit(HOST, PAIR_PORT, CODE))
        assertFalse(doneFixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))
        assertEquals(listOf(GlassesManualPairingState.DONE), doneStates)

        val errorFixture = fixture(FakeBackend(pairFailure = IOException("pairing failed")))
        errorFixture.engine.start()
        assertTrue(errorFixture.engine.submit(HOST, PAIR_PORT, CODE))
        val error = errorFixture.engine.state as GlassesManualPairingState.ERROR
        val errorStates = mutableListOf<GlassesManualPairingState>()
        errorFixture.engine.observe(errorStates::add)

        assertFalse(errorFixture.engine.submit(HOST, PAIR_PORT, CODE))
        assertFalse(errorFixture.engine.submit(HOST, PAIR_PORT, CODE, allowStartFromIdle = true))
        assertEquals(error, errorFixture.engine.state)
        assertEquals(listOf(error), errorStates)
    }

    @Test
    fun pairingFailureBecomesSanitizedError() {
        val backend = FakeBackend(pairFailure = IOException("bad code $CODE at $HOST"))
        val fixture = fixture(backend)

        fixture.engine.start()
        fixture.engine.submit(HOST, PAIR_PORT, CODE)

        val error = fixture.engine.state as GlassesManualPairingState.ERROR
        assertTrue(error.userMessage.contains("pairing code"))
        assertFalse(error.supportDetail.contains(CODE))
        assertFalse(error.supportDetail.contains(HOST))
        assertEquals(GlassesManualControlAction.CLOSE, fixture.control.actions.last())
    }

    @Test
    fun armingFailureBecomesError() {
        val fixture = fixture(FakeBackend(armFailure = IOException("watchdog verification failed")))

        fixture.engine.start()
        fixture.engine.submit(HOST, PAIR_PORT, CODE)

        val error = fixture.engine.state as GlassesManualPairingState.ERROR
        assertTrue(error.userMessage.contains("secure setup"))
        assertTrue(error.supportDetail.contains("watchdog verification failed"))
    }

    @Test
    fun cancelReturnsToIdleAndIgnoresQueuedWork() {
        val queued = QueuedExecutor()
        val fixture = fixture(worker = queued)

        fixture.engine.start()
        fixture.engine.submit(HOST, PAIR_PORT, CODE)
        assertEquals(GlassesManualPairingState.PAIRING, fixture.engine.state)

        fixture.engine.cancel()
        queued.runPending()

        assertEquals(GlassesManualPairingState.IDLE, fixture.engine.state)
        assertEquals(GlassesManualControlAction.CLOSE, fixture.control.actions.last())
    }

    @Test
    fun oldGlassesManualControlErrorIsShownFromASettingsButton() {
        val fixture = fixture()

        fixture.engine.start()
        assertTrue(fixture.engine.openDeveloperOptions())
        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, "NO_LOCAL_CLIENT"))

        val error = fixture.engine.state as GlassesManualPairingState.ERROR
        assertTrue(error.userMessage.contains("newer Nexus app"))
        assertTrue(error.userMessage.contains("Update the glasses app"))
        assertEquals(
            listOf(
                GlassesManualControlAction.OPEN_DEVELOPER_OPTIONS,
                GlassesManualControlAction.CLOSE,
            ),
            fixture.control.actions,
        )
    }

    @Test
    fun disabledDeveloperOptionsExplainsThatStepTwoMustFinish() {
        val fixture = fixture()

        fixture.engine.start()
        assertTrue(fixture.engine.showWirelessDebugging())
        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, "DEVELOPER_OPTIONS_DISABLED"))

        val error = fixture.engine.state as GlassesManualPairingState.ERROR
        assertTrue(error.userMessage.contains("still disabled"))
        assertTrue(error.userMessage.contains("step 2"))
    }

    @Test
    fun wirelessDebuggingFailureExplainsThatWifiOrSettingsCouldNotOpen() {
        val fixture = fixture()

        fixture.engine.start()
        assertTrue(fixture.engine.showWirelessDebugging())
        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, "WIRELESS_DEBUGGING_UNAVAILABLE"))

        val error = fixture.engine.state as GlassesManualPairingState.ERROR
        assertTrue(error.userMessage.contains("Wi-Fi"))
        assertTrue(error.userMessage.contains("retry step 4"))
    }

    @Test
    fun accessibilitySettingsButtonSendsItsOwnActionAndKeepsTheCodeFormState() {
        val fixture = fixture()

        fixture.engine.start()
        assertTrue(fixture.engine.openAccessibilitySettings())
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)

        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, null))
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)
        assertEquals(
            listOf(GlassesManualControlAction.OPEN_ACCESSIBILITY_SETTINGS),
            fixture.control.actions,
        )
        assertEquals(
            "open_accessibility_settings",
            GlassesManualControlAction.OPEN_ACCESSIBILITY_SETTINGS.wireValue,
        )
    }

    @Test
    fun accessibilityUnavailableTellsTheUserToRunStepOne() {
        val fixture = fixture()

        fixture.engine.start()
        assertTrue(fixture.engine.openDeveloperOptions())
        val requestId = fixture.control.requestIds.single()
        assertTrue(fixture.engine.onManualControlResponse(requestId, "ACCESSIBILITY_UNAVAILABLE"))

        val error = fixture.engine.state as GlassesManualPairingState.ERROR
        assertTrue(error.userMessage.contains("step 1"))
        assertTrue(error.userMessage.contains("Accessibility access"))
    }

    @Test
    fun manualSettingsButtonsUseSeparateActionsAndKeepTheCodeFormState() {
        val fixture = fixture()

        fixture.engine.start()
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)
        assertTrue(fixture.control.actions.isEmpty())

        assertTrue(fixture.engine.openAccessibilitySettings())
        val accessibilityId = fixture.control.requestIds.last()
        assertTrue(fixture.engine.onManualControlResponse(accessibilityId, null))

        assertTrue(fixture.engine.enableDeveloperOptions())
        val enableId = fixture.control.requestIds.last()
        assertTrue(fixture.engine.onManualControlResponse(enableId, null))

        assertTrue(fixture.engine.openDeveloperOptions())
        val developerId = fixture.control.requestIds.last()
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)
        assertTrue(fixture.engine.onManualControlResponse(developerId, null))

        assertTrue(fixture.engine.showWirelessDebugging())
        val wirelessId = fixture.control.requestIds.last()
        assertTrue(fixture.engine.onManualControlResponse(wirelessId, null))
        assertEquals(GlassesManualPairingState.WAITING_FOR_CODE, fixture.engine.state)
        assertEquals(
            listOf(
                GlassesManualControlAction.OPEN_ACCESSIBILITY_SETTINGS,
                GlassesManualControlAction.ENABLE_DEVELOPER_OPTIONS,
                GlassesManualControlAction.OPEN_DEVELOPER_OPTIONS,
                GlassesManualControlAction.OPEN_WIRELESS_DEBUGGING,
            ),
            fixture.control.actions,
        )
    }

    @Test
    fun errorSanitizerWalksCauseChainAndRemovesPairingCodeAndIpv4() {
        val failure = IOException(
            "outer $HOST",
            IllegalStateException("inner pairing token $CODE"),
        )

        val detail = ManualPairingSupportDiagnostic.causeChain(failure)

        assertTrue(detail.contains("outer"))
        assertTrue(detail.contains("inner"))
        assertFalse(detail.contains(HOST))
        assertFalse(detail.contains(CODE))
        assertTrue(detail.length <= 96)
    }

    private fun fixture(
        backend: FakeBackend = FakeBackend(),
        worker: ManualPairingTaskExecutor = DirectExecutor,
        connectPortWaitTimeoutMs: Long = 0L,
    ): Fixture {
        val control = FakeControl()
        val scheduler = FakeScheduler()
        return Fixture(
            engine = GlassesManualPairingEngine(
                control = control,
                backend = backend,
                worker = worker,
                timeoutScheduler = scheduler,
                connectPortWaitTimeoutMs = connectPortWaitTimeoutMs,
            ),
            control = control,
        )
    }

    private data class Fixture(
        val engine: GlassesManualPairingEngine,
        val control: FakeControl,
    )

    private class FakeControl : GlassesManualControlSender {
        val actions = mutableListOf<GlassesManualControlAction>()
        val requestIds = mutableListOf<String>()

        val abandonedPairingCloses = mutableListOf<Boolean>()

        override fun send(
            requestId: String,
            action: GlassesManualControlAction,
            armed: Boolean,
            abandonPairing: Boolean,
        ): String? {
            requestIds += requestId
            actions += action
            if (action == GlassesManualControlAction.CLOSE) abandonedPairingCloses += abandonPairing
            return null
        }
    }

    private class FakeBackend(
        private val pairFailure: Throwable? = null,
        private val armFailure: Throwable? = null,
    ) : GlassesManualPairingBackend {
        var discoverConnectEndpointCalls = 0
        val connectedEndpoints = mutableListOf<GlassesManualAdbEndpoint>()

        override fun pair(host: String, pairPort: Int, code: String) {
            pairFailure?.let { throw it }
        }

        override fun discoverConnectEndpoint(dialogHost: String): GlassesManualAdbEndpoint {
            discoverConnectEndpointCalls += 1
            return GlassesManualAdbEndpoint(dialogHost, CONNECT_PORT)
        }

        override fun connect(endpoint: GlassesManualAdbEndpoint): ManualAdbSession {
            connectedEndpoints += endpoint
            return FakeSession
        }

        override fun arm(session: ManualAdbSession, dialogHost: String) {
            armFailure?.let { throw it }
        }
    }

    private object FakeSession : ManualAdbSession {
        override fun getHost(): String = HOST
        override fun getPort(): Int = CONNECT_PORT
        override fun shell(command: String): ManualShellResult = error("not used")
        override fun close() = Unit
    }

    private object DirectExecutor : ManualPairingTaskExecutor {
        override fun submit(task: () -> Unit): ManualPairingCancellation {
            task()
            return ManualPairingCancellation {}
        }
    }

    private class QueuedExecutor : ManualPairingTaskExecutor {
        private var pending: (() -> Unit)? = null
        private var cancelled = false

        override fun submit(task: () -> Unit): ManualPairingCancellation {
            pending = task
            return ManualPairingCancellation { cancelled = true }
        }

        fun runPending() {
            if (!cancelled) pending?.invoke()
        }
    }

    private class BackgroundExecutor : ManualPairingTaskExecutor {
        private val finished = CountDownLatch(1)

        override fun submit(task: () -> Unit): ManualPairingCancellation {
            val thread = Thread {
                try {
                    task()
                } finally {
                    finished.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
            return ManualPairingCancellation { thread.interrupt() }
        }

        fun awaitFinished(): Boolean = finished.await(5, TimeUnit.SECONDS)
    }

    private class FakeScheduler : ManualPairingTimeoutScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): ManualPairingCancellation =
            ManualPairingCancellation {}
    }

    private companion object {
        const val HOST = "192.168.10.42"
        const val PAIR_PORT = 37123
        const val CONNECT_PORT = 39876
        const val CODE = "123456"
    }
}
