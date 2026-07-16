package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfArmOnboardingStateMachineTest {
    @Test
    fun firstLaunchRequiresOnlyTheMainAccessibilityService() {
        val state = evaluate(accessibilityEnabled = false)

        assertEquals(SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY, state.stage)
        assertEquals(SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY, state.action)
    }

    @Test
    fun enabledMainServiceAdvancesToWirelessPairing() {
        val state = evaluate(accessibilityEnabled = true)

        assertEquals(SelfArmOnboardingState.Stage.READY_FOR_WIRELESS, state.stage)
        assertEquals(SelfArmOnboardingState.Action.START_WIRELESS, state.action)
    }

    @Test
    fun runningAndFailedStatesRemainActionable() {
        val running = evaluate(
            accessibilityEnabled = true,
            setupRunning = true,
            progressState = "waiting_for_pairing_code",
        )
        val failed = evaluate(
            accessibilityEnabled = true,
            failureState = "wireless_setup_timeout",
        )

        assertEquals(SelfArmOnboardingState.Stage.RUNNING, running.stage)
        assertEquals("waiting_for_pairing_code", running.detail)
        assertEquals(SelfArmOnboardingState.Stage.FAILED, failed.stage)
        assertEquals(SelfArmOnboardingState.Action.RETRY_WIRELESS, failed.action)
    }

    @Test
    fun pmGrantFallbackCompletesWithoutWirelessBootstrapMarker() {
        val state = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            bootstrapComplete = false,
        )

        assertEquals(SelfArmOnboardingState.Stage.COMPLETE, state.stage)
        assertEquals(SelfArmOnboardingState.Action.NONE, state.action)
    }

    @Test
    fun unsupportedFirmwareStopsBeforeWirelessSetup() {
        val state = evaluate(
            wirelessDebuggingSupported = false,
            accessibilityEnabled = true,
        )

        assertEquals(SelfArmOnboardingState.Stage.UNSUPPORTED, state.stage)
    }

    private fun evaluate(
        wirelessDebuggingSupported: Boolean = true,
        accessibilityEnabled: Boolean,
        secureSettingsGranted: Boolean = false,
        bootstrapComplete: Boolean = false,
        setupRunning: Boolean = false,
        failureState: String = "",
        progressState: String = "",
    ): SelfArmOnboardingState = SelfArmOnboardingStateMachine.evaluate(
        SelfArmOnboardingSnapshot(
            wirelessDebuggingSupported = wirelessDebuggingSupported,
            accessibilityEnabled = accessibilityEnabled,
            secureSettingsGranted = secureSettingsGranted,
            bootstrapComplete = bootstrapComplete,
            setupRunning = setupRunning,
            failureState = failureState,
            progressState = progressState,
        ),
    )
}
