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
    private var manualNavigationActive = false

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
        if (!wifiEnableActive) return
        wifiEnableActive = false
        GlassesHub.onWifiEnableAutomationFinished(success)
    }

    internal fun onManualNavigationFinished() {
        manualNavigationActive = false
    }

    private fun openManualNavigation(target: SelfArmManualTarget): Boolean {
        developerOptionsEnabler?.stop()
        finishWifiEnableIfActive(false)
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
                return false
            }
            manualNavigationActive = true
        }
        if (!SelfArmManualSettingsLauncher.open(applicationContext, target)) {
            manualNavigationActive = false
            SelfArmManualArmAssets.cleanup(applicationContext)
            SelfArmOnboardingStore.reportProgress(applicationContext, "manual_pairing_settings_unavailable")
            returnToOnboarding()
            return false
        }
        return true
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
        SelfArmManualArmAssets.cleanup(applicationContext)
        manualNavigationActive = false
        returnToOnboarding()
        if (armed) SelfArmPhoneArmConfirmation.confirm(applicationContext)
    }

    private fun finishWifiEnableIfActive(success: Boolean) {
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
                        onFinished(service.openManualNavigation(SelfArmManualTarget.DEVELOPER_OPTIONS))
                    SelfArmManualAction.OPEN_WIRELESS_DEBUGGING ->
                        onFinished(service.openManualNavigation(SelfArmManualTarget.WIRELESS_DEBUGGING))
                    SelfArmManualAction.OPEN_PAIRING_DIALOG ->
                        onFinished(service.openManualNavigation(SelfArmManualTarget.PAIRING_DIALOG))
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
