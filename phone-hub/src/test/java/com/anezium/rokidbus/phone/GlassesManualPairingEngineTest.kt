package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbSession
import com.anezium.rokidbus.phone.selfarm.adb.ManualShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GlassesManualPairingEngineTest {
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
    ): Fixture {
        val control = FakeControl()
        val scheduler = FakeScheduler()
        return Fixture(
            engine = GlassesManualPairingEngine(
                control = control,
                backend = backend,
                worker = worker,
                timeoutScheduler = scheduler,
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

        override fun send(
            requestId: String,
            action: GlassesManualControlAction,
            armed: Boolean,
        ): String? {
            requestIds += requestId
            actions += action
            return null
        }
    }

    private class FakeBackend(
        private val pairFailure: Throwable? = null,
        private val armFailure: Throwable? = null,
    ) : GlassesManualPairingBackend {
        override fun pair(host: String, pairPort: Int, code: String) {
            pairFailure?.let { throw it }
        }

        override fun discoverConnectEndpoint(dialogHost: String): GlassesManualAdbEndpoint =
            GlassesManualAdbEndpoint(dialogHost, CONNECT_PORT)

        override fun connect(endpoint: GlassesManualAdbEndpoint): ManualAdbSession = FakeSession

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
