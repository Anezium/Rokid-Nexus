package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class RokidBusAccessibilityService : AccessibilityService() {
    private val tripleTapDetector = TripleTapDetector()
    private val main = Handler(Looper.getMainLooper())
    private val tapExpiry = Runnable { flushPendingTaps() }
    // Keys whose DOWN we consumed. Their UP must be consumed too even if the
    // consumer vanished in between (selecting a launcher entry hides the
    // overlay before the UP arrives; the orphan ENTER UP then reaches the
    // Rokid launcher, whose key-up handler starts phone music playback).
    private val consumedDownKeys = mutableSetOf<Int>()
    private var wirelessDebuggingAutomator: SelfArmWirelessDebuggingAutomator? = null
    private var developerOptionsEnabler: SelfArmDeveloperOptionsEnabler? = null
    private var wirelessBootstrapActive = false
    private var wifiEnableActive = false
    private var manualWifiEnableActive = false
    private var manualNavigationActive = false
    private var pendingManualTarget: SelfArmManualTarget? = null
    private var pendingManualCompletion: ((Boolean) -> Unit)? = null
    private var manualWaitingForNetwork = false
    private var manualOpenDeadlineAt = 0L
    private val manualOpenVerifier = Runnable(::verifyManualNavigation)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        wirelessDebuggingAutomator = SelfArmWirelessDebuggingAutomator(this, main)
        developerOptionsEnabler = SelfArmDeveloperOptionsEnabler(this, main)
        liveInstance = this
        log("AccessibilityService connected; starting glasses hub")
        SurfaceOverlayRenderer.onServiceConnected(this)
        LauncherOverlayRenderer.onServiceConnected(this)
        GlassesHub.start(applicationContext)
        AccessibilityRearmWatcher.start(applicationContext, "accessibility_service_connected")
        SelfArmOnboardingStore.refreshNetworkPosture(applicationContext)
        SelfArmOnboardingStore.notifyChanged(applicationContext)
        if (SelfArmOnboardingStore.consumeAwaitingAccessibility(applicationContext)) {
            // The user just switched us on inside Android Settings — pull them
            // straight back to the onboarding instead of leaving them stranded.
            returnToOnboarding()
            // Tapping OPEN SETTINGS was the consent; chain straight into the secure
            // self-arm instead of asking for a second FINISH SETUP tap.
            val stage = SelfArmOnboardingStateMachine
                .evaluate(SelfArmOnboardingStore.snapshot(applicationContext))
                .stage
            if (stage == SelfArmOnboardingState.Stage.READY_FOR_WIRELESS) {
                SelfArmOnboardingStore.requestSetup(applicationContext)
            }
        }
        if (SelfArmOnboardingStore.isSetupRequested(applicationContext)) {
            startWirelessBootstrap()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        wirelessDebuggingAutomator?.onAccessibilityEvent(event)
        developerOptionsEnabler?.onAccessibilityEvent(event)
        if (pendingManualCompletion != null && !manualWifiEnableActive) {
            main.removeCallbacks(manualOpenVerifier)
            main.postDelayed(manualOpenVerifier, MANUAL_OPEN_EVENT_SETTLE_MS)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KEYCODE_PROG_BLUE) return false
        // Raw gesture trace: the temple firmware's key bursts keep surprising us
        // (duplicated swipe pairs, tap contacts); keep the evidence cheap to grab.
        log("key code=${event.keyCode} action=${event.action} repeat=${event.repeatCount} t=${event.eventTime}")

        if (event.action == KeyEvent.ACTION_UP && consumedDownKeys.remove(event.keyCode)) {
            return true
        }

        val decision = tripleTapDetector.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != TripleTapDetector.KEYCODE_NOTIFICATION) {
            main.removeCallbacks(tapExpiry)
        }

        val handled = when (decision) {
            TripleTapDetector.Decision.TRIGGER -> {
                main.removeCallbacks(tapExpiry)
                if (!LauncherOverlayRenderer.isShown()) {
                    LauncherOverlayRenderer.show(this)
                }
                true
            }
            TripleTapDetector.Decision.CONSUME -> true
            TripleTapDetector.Decision.PASS -> {
                if (event.keyCode == TripleTapDetector.KEYCODE_NOTIFICATION &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    event.repeatCount == 0
                ) {
                    main.removeCallbacks(tapExpiry)
                    main.postDelayed(tapExpiry, TripleTapDetector.DEFAULT_WINDOW_MS + 1L)
                }
                when {
                    LauncherOverlayRenderer.handleKeyEvent(event) -> true
                    SurfaceController.handleKeyEvent(event) -> true
                    else -> false
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handled) consumedDownKeys.add(event.keyCode) else consumedDownKeys.remove(event.keyCode)
        }
        return handled
    }

    override fun onInterrupt() {
        wirelessDebuggingAutomator?.stop()
        developerOptionsEnabler?.stop()
        finishWifiEnableIfActive(false)
        pauseWirelessBootstrapIfActive("wireless_setup_interrupted")
        pauseManualNavigationIfActive("manual_pairing_interrupted")
        log("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        log("AccessibilityService destroyed")
        main.removeCallbacks(tapExpiry)
        wirelessDebuggingAutomator?.stop()
        developerOptionsEnabler?.stop()
        finishWifiEnableIfActive(false)
        pauseWirelessBootstrapIfActive("wireless_setup_service_restarting")
        pauseManualNavigationIfActive("manual_pairing_service_restarting")
        wirelessDebuggingAutomator = null
        developerOptionsEnabler = null
        if (liveInstance === this) liveInstance = null
        LauncherOverlayRenderer.onServiceDestroyed(this)
        SurfaceOverlayRenderer.onServiceDestroyed(this)
        super.onDestroy()
    }

    private fun flushPendingTaps() {
        val tapCount = tripleTapDetector.consumeExpiredTapCount(SystemClock.uptimeMillis())
        if (tapCount <= 0 || SurfaceController.activeSurface() == null) return
        repeat(tapCount) {
            SurfaceController.forwardSurfaceInput(
                TripleTapDetector.KEYCODE_NOTIFICATION,
                KeyEvent.ACTION_DOWN,
            )
        }
    }

    private fun startWirelessBootstrap() {
        if (manualNavigationActive) return
        if (wirelessBootstrapActive) return
        finishWifiEnableIfActive(false)
        val state = SelfArmOnboardingStateMachine.evaluate(
            SelfArmOnboardingStore.snapshot(applicationContext),
        )
        if (state.stage == SelfArmOnboardingState.Stage.COMPLETE) {
            SelfArmOnboardingStore.finish(applicationContext, "wireless_bootstrap_complete", true)
            returnToOnboarding()
            return
        }
        wirelessBootstrapActive = true
        SelfArmOnboardingStore.markRunning(applicationContext)
        wirelessDebuggingAutomator?.start(
            SelfArmWirelessDebuggingAutomator.OperationMode.FULL_BOOTSTRAP,
        )
    }

    private fun startWifiEnable() {
        if (wirelessBootstrapActive || manualNavigationActive) {
            GlassesHub.onWifiEnableAutomationFinished(false)
            return
        }
        if (wifiEnableActive) return
        val automator = wirelessDebuggingAutomator
        if (automator == null) {
            GlassesHub.onWifiEnableAutomationFinished(false)
            return
        }
        wifiEnableActive = true
        automator.start(SelfArmWirelessDebuggingAutomator.OperationMode.WIFI_ONLY)
    }

    internal fun onWirelessBootstrapFinished() {
        wirelessBootstrapActive = false
    }

    internal fun onWifiEnableFinished(success: Boolean) {
        if (manualWifiEnableActive) {
            manualWifiEnableActive = false
            if (success) {
                manualWaitingForNetwork = true
                manualOpenDeadlineAt = SystemClock.uptimeMillis() + MANUAL_WIFI_NETWORK_TIMEOUT_MS
                main.removeCallbacks(manualOpenVerifier)
                main.post(manualOpenVerifier)
            } else {
                finishManualNavigationRequest(false)
            }
            return
        }
        if (!wifiEnableActive) return
        wifiEnableActive = false
        GlassesHub.onWifiEnableAutomationFinished(success)
    }

    internal fun onManualNavigationFinished() {
        manualNavigationActive = false
    }

    private fun openManualNavigation(
        target: SelfArmManualTarget,
        onFinished: (Boolean) -> Unit,
    ) {
        developerOptionsEnabler?.stop()
        finishWifiEnableIfActive(false)
        finishManualNavigationRequest(false)
        if (wirelessBootstrapActive) {
            wirelessDebuggingAutomator?.stop()
            pauseWirelessBootstrapIfActive("manual_pairing_opening")
        }
        if (!manualNavigationActive) {
            val staged = runCatching { SelfArmManualArmAssets.stage(applicationContext) }
                .onFailure {
                    log(
                        "Manual self-arm asset staging failed: " +
                            sanitizeSupportDiagnostic(it.message.orEmpty()),
                    )
                }
                .isSuccess
            if (!staged) {
                SelfArmOnboardingStore.reportProgress(applicationContext, "manual_pairing_assets_failed")
                returnToOnboarding()
                onFinished(false)
                return
            }
            manualNavigationActive = true
        }
        pendingManualTarget = target
        pendingManualCompletion = onFinished
        if (target.requiresWifi() && !SelfArmWirelessAdbController.isWifiEnabled(applicationContext)) {
            val automator = wirelessDebuggingAutomator
            if (automator == null) {
                finishManualNavigationRequest(false)
                return
            }
            manualWifiEnableActive = true
            automator.start(SelfArmWirelessDebuggingAutomator.OperationMode.WIFI_ONLY)
            return
        }
        launchPendingManualNavigation()
    }

    private fun launchPendingManualNavigation() {
        val target = pendingManualTarget ?: return finishManualNavigationRequest(false)
        if (!SelfArmManualSettingsLauncher.open(applicationContext, target)) {
            finishManualNavigationRequest(false)
            return
        }
        manualOpenDeadlineAt = SystemClock.uptimeMillis() + MANUAL_OPEN_TIMEOUT_MS
        main.removeCallbacks(manualOpenVerifier)
        main.postDelayed(manualOpenVerifier, MANUAL_OPEN_INITIAL_DELAY_MS)
    }

    private fun verifyManualNavigation() {
        val target = pendingManualTarget ?: return
        if (manualWaitingForNetwork) {
            if (SelfArmWirelessAdbController.isWifiNetworkReady(applicationContext)) {
                manualWaitingForNetwork = false
                launchPendingManualNavigation()
                return
            }
            if (SystemClock.uptimeMillis() >= manualOpenDeadlineAt) {
                finishManualNavigationRequest(false)
                return
            }
            main.postDelayed(manualOpenVerifier, MANUAL_WIFI_NETWORK_POLL_MS)
            return
        }
        if (wirelessDebuggingAutomator?.isManualTargetVisible(target) == true) {
            finishManualNavigationRequest(true)
            return
        }
        if (SystemClock.uptimeMillis() >= manualOpenDeadlineAt) {
            finishManualNavigationRequest(false)
            return
        }
        main.postDelayed(manualOpenVerifier, MANUAL_OPEN_POLL_MS)
    }

    private fun finishManualNavigationRequest(success: Boolean) {
        val completion = pendingManualCompletion
        pendingManualCompletion = null
        pendingManualTarget = null
        manualWifiEnableActive = false
        manualWaitingForNetwork = false
        manualOpenDeadlineAt = 0L
        main.removeCallbacks(manualOpenVerifier)
        if (!success && completion != null) {
            manualNavigationActive = false
            SelfArmManualArmAssets.cleanup(applicationContext)
            SelfArmOnboardingStore.reportProgress(
                applicationContext,
                "manual_pairing_settings_unavailable",
            )
            returnToOnboarding()
        }
        completion?.invoke(success)
    }

    private fun enableDeveloperOptionsManually(onFinished: (Boolean) -> Unit) {
        finishWifiEnableIfActive(false)
        if (wirelessBootstrapActive) {
            wirelessDebuggingAutomator?.stop()
            pauseWirelessBootstrapIfActive("manual_developer_enable_opening")
        }
        if (!manualNavigationActive) {
            val staged = runCatching { SelfArmManualArmAssets.stage(applicationContext) }.isSuccess
            if (!staged) {
                SelfArmOnboardingStore.reportProgress(applicationContext, "manual_pairing_assets_failed")
                returnToOnboarding()
                onFinished(false)
                return
            }
            manualNavigationActive = true
        }
        val enabler = developerOptionsEnabler
        if (enabler == null) {
            manualNavigationActive = false
            SelfArmManualArmAssets.cleanup(applicationContext)
            onFinished(false)
            return
        }
        enabler.start { success ->
            if (!success) {
                manualNavigationActive = false
                SelfArmManualArmAssets.cleanup(applicationContext)
                SelfArmOnboardingStore.reportProgress(
                    applicationContext,
                    "manual_developer_enable_failed",
                )
                returnToOnboarding()
            }
            onFinished(success)
        }
    }

    private fun closeManualNavigation(armed: Boolean) {
        developerOptionsEnabler?.stop()
        wirelessDebuggingAutomator?.stop()
        finishManualNavigationRequest(false)
        SelfArmManualArmAssets.cleanup(applicationContext)
        manualNavigationActive = false
        returnToOnboarding()
        if (armed) SelfArmPhoneArmConfirmation.confirm(applicationContext)
    }

    private fun finishWifiEnableIfActive(success: Boolean) {
        if (manualWifiEnableActive) {
            wirelessDebuggingAutomator?.stop()
            manualWifiEnableActive = false
            finishManualNavigationRequest(false)
        }
        if (!wifiEnableActive) return
        wirelessDebuggingAutomator?.stop()
        wifiEnableActive = false
        GlassesHub.onWifiEnableAutomationFinished(success)
    }

    private fun pauseWirelessBootstrapIfActive(progressState: String) {
        if (!wirelessBootstrapActive) return
        wirelessBootstrapActive = false
        SelfArmOnboardingStore.pause(applicationContext, progressState)
    }

    private fun pauseManualNavigationIfActive(progressState: String) {
        if (!manualNavigationActive) return
        developerOptionsEnabler?.stop()
        wirelessDebuggingAutomator?.stop()
        finishManualNavigationRequest(false)
        manualNavigationActive = false
        SelfArmManualArmAssets.cleanup(applicationContext)
        SelfArmOnboardingStore.pause(applicationContext, progressState)
    }

    internal fun returnToOnboarding() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        private const val KEYCODE_PROG_BLUE = 186
        private const val MANUAL_OPEN_INITIAL_DELAY_MS = 350L
        private const val MANUAL_OPEN_EVENT_SETTLE_MS = 120L
        private const val MANUAL_OPEN_POLL_MS = 250L
        private const val MANUAL_OPEN_TIMEOUT_MS = 6_000L
        private const val MANUAL_WIFI_NETWORK_POLL_MS = 500L
        private const val MANUAL_WIFI_NETWORK_TIMEOUT_MS = 30_000L
        @Volatile private var liveInstance: RokidBusAccessibilityService? = null

        internal fun requestWirelessBootstrap(context: Context): Boolean {
            SelfArmOnboardingStore.requestSetup(context.applicationContext)
            val service = liveInstance ?: return false
            service.main.post(service::startWirelessBootstrap)
            return true
        }

        @Suppress("UNUSED_PARAMETER")
        internal fun requestWifiEnable(context: Context): Boolean {
            val service = liveInstance ?: return false
            service.main.post(service::startWifiEnable)
            return true
        }

        @Suppress("UNUSED_PARAMETER")
        internal fun requestManualAction(
            context: Context,
            action: SelfArmManualAction,
            armed: Boolean = false,
            onFinished: (Boolean) -> Unit = {},
        ): Boolean {
            val service = liveInstance ?: return false
            service.main.post {
                when (action) {
                    SelfArmManualAction.ENABLE_DEVELOPER_OPTIONS ->
                        service.enableDeveloperOptionsManually(onFinished)
                    SelfArmManualAction.OPEN_DEVELOPER_OPTIONS ->
                        service.openManualNavigation(SelfArmManualTarget.DEVELOPER_OPTIONS, onFinished)
                    SelfArmManualAction.OPEN_WIRELESS_DEBUGGING ->
                        service.openManualNavigation(SelfArmManualTarget.WIRELESS_DEBUGGING, onFinished)
                    SelfArmManualAction.OPEN_PAIRING_DIALOG ->
                        service.openManualNavigation(SelfArmManualTarget.PAIRING_DIALOG, onFinished)
                    SelfArmManualAction.CLOSE -> {
                        service.closeManualNavigation(armed)
                        onFinished(true)
                    }
                }
            }
            return true
        }

    }
}

private fun SelfArmManualTarget.requiresWifi(): Boolean =
    this == SelfArmManualTarget.WIRELESS_DEBUGGING || this == SelfArmManualTarget.PAIRING_DIALOG
