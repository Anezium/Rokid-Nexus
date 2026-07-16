package com.anezium.rokidbus.glasses

internal data class SelfArmOnboardingSnapshot(
    val wirelessDebuggingSupported: Boolean,
    val accessibilityEnabled: Boolean,
    val secureSettingsGranted: Boolean,
    val bootstrapComplete: Boolean,
    val legacyAdbSafe: Boolean,
    val setupRunning: Boolean,
    val failureState: String,
    val progressState: String,
)

internal data class SelfArmOnboardingState(
    val stage: Stage,
    val action: Action,
    val detail: String,
) {
    enum class Stage {
        UNSUPPORTED,
        ENABLE_ACCESSIBILITY,
        READY_FOR_WIRELESS,
        RUNNING,
        FAILED,
        COMPLETE,
    }

    enum class Action {
        NONE,
        OPEN_ACCESSIBILITY,
        START_WIRELESS,
        RETRY_WIRELESS,
    }
}

internal object SelfArmOnboardingStateMachine {
    fun evaluate(snapshot: SelfArmOnboardingSnapshot): SelfArmOnboardingState = when {
        snapshot.secureSettingsGranted && snapshot.accessibilityEnabled && snapshot.legacyAdbSafe ->
            state(SelfArmOnboardingState.Stage.COMPLETE)
        snapshot.setupRunning ->
            state(
                SelfArmOnboardingState.Stage.RUNNING,
                detail = snapshot.progressState,
            )
        snapshot.failureState.isNotBlank() ->
            state(
                SelfArmOnboardingState.Stage.FAILED,
                SelfArmOnboardingState.Action.RETRY_WIRELESS,
                snapshot.failureState,
            )
        !snapshot.wirelessDebuggingSupported ->
            state(SelfArmOnboardingState.Stage.UNSUPPORTED)
        !snapshot.accessibilityEnabled ->
            state(
                SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY,
                SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY,
                snapshot.progressState,
            )
        else ->
            state(
                SelfArmOnboardingState.Stage.READY_FOR_WIRELESS,
                SelfArmOnboardingState.Action.START_WIRELESS,
                if (snapshot.bootstrapComplete) "verifying_wireless_bootstrap" else snapshot.progressState,
            )
    }

    private fun state(
        stage: SelfArmOnboardingState.Stage,
        action: SelfArmOnboardingState.Action = SelfArmOnboardingState.Action.NONE,
        detail: String = "",
    ) = SelfArmOnboardingState(stage, action, detail)
}
