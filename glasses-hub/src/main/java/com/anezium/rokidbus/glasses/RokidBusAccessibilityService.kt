package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.glasses.input.InputArbiter
import com.anezium.rokidbus.glasses.input.InputContext
import com.anezium.rokidbus.glasses.input.InputDecision
import com.anezium.rokidbus.glasses.input.KeyEventAdapter
import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.glasses.input.RawKeyEvent
import com.anezium.rokidbus.glasses.input.RoutedIntent
import com.anezium.rokidbus.glasses.session.HostScreen
import com.anezium.rokidbus.glasses.session.NoticePreview
import com.anezium.rokidbus.glasses.session.OpenFailure
import com.anezium.rokidbus.glasses.session.RootStops
import com.anezium.rokidbus.glasses.session.SessionEffect
import com.anezium.rokidbus.glasses.session.SessionEffectSink
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionHost
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionRunner
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.SessionStatus
import com.anezium.rokidbus.glasses.session.SessionTimer
import com.anezium.rokidbus.glasses.session.SessionWindow
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.SetupCompletionMode
import com.anezium.rokidbus.shared.SetupPairingResult
import com.anezium.rokidbus.shared.SetupNote
import com.anezium.rokidbus.shared.SetupStage
import org.json.JSONObject

class RokidBusAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val displayStandbyWatchdog by lazy(LazyThreadSafetyMode.NONE) {
        DisplayStandbyWatchdog(this, main)
    }
    private var wirelessDebuggingAutomator: SelfArmWirelessDebuggingAutomator? = null
    private var developerOptionsEnabler: SelfArmDeveloperOptionsEnabler? = null
    private var wirelessBootstrapActive = false
    private var wirelessBootstrapSessionId = ""
    private var wirelessBootstrapForced = false
    private var setupWifiEnableActive = false
    private var setupWifiEnableSessionId = ""
    private var setupWifiEnableForced = false
    private var setupWifiFallbackRunnable: Runnable? = null
    private var wifiEnableActive = false
    private var repairWifiEnableActive = false
    private var repairWifiEnableCompletion: ((Boolean) -> Unit)? = null
    private var manualWifiEnableActive = false
    private var manualNavigationActive = false
    private var forcedWirelessBootstrap = false
    private var pendingManualTarget: SelfArmManualTarget? = null
    private var pendingManualCompletion: ((Boolean) -> Unit)? = null
    private var manualNavigationSessionId = ""
    private var manualWaitingForNetwork = false
    private var manualOpenDeadlineAt = 0L
    private var manualWifiRequestGeneration = 0L
    private var manualOpenVerifier: Runnable? = null
    private var wifiResumeSessionId = ""
    private var wifiResumeForced = false
    private var wifiResumeManual = false
    private var wifiResumeCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiResumeRunnable: Runnable? = null
    private var lastNativeAssistantBackAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = eventTypes or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        wirelessDebuggingAutomator = SelfArmWirelessDebuggingAutomator(this, main)
        developerOptionsEnabler = SelfArmDeveloperOptionsEnabler(this, main)
        liveInstance = this
        RemoteNavigationController.onServiceConnected(this)
        RemotePointerController.onServiceConnected(this)
        log("AccessibilityService connected; starting glasses hub")
        RingFocusBroadcastCoordinator.onServiceConnected(
            this,
            surfaceActive = SurfaceController.activeSurface() != null,
            noticeOwnsRing = NoticeController.ownsRingInput(),
        )
        SurfaceOverlayRenderer.onServiceConnected(this)
        PinOverlayRenderer.onServiceConnected(this)
        ActivityController.onServiceConnected(applicationContext)
        NoticeController.onServiceConnected(applicationContext) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        ActivityOverlayRenderer.onServiceConnected(this)
        NoticeOverlayRenderer.onServiceConnected(this)
        LauncherOverlayRenderer.onServiceConnected(this)
        NexusInput.onServiceConnected(this)
        NexusSession.onServiceConnected(this)
        StatusBadgeOverlayRenderer.onServiceConnected(this)
        GlassesHub.start(applicationContext)
        displayStandbyWatchdog.start()
        AccessibilityRearmWatcher.start(applicationContext, "accessibility_service_connected")
        // A service connect is the only trigger the boot repair listens to: the radio observers
        // and the demand latch stay background-only and must never reach the display.
        SelfArmBootRepairCoordinator.onAccessibilityServiceConnected(applicationContext)
        // If a manual pairing was awaiting the phone's arm when the ROM tore the service down,
        // the staged assets may have been lost with it — put them back so the phone can still read
        // them once it reconnects. Best-effort; a genuine terminal event clears the flag.
        if (SelfArmOnboardingStore.isManualArmInProgress(applicationContext)) {
            runCatching { SelfArmManualArmAssets.stage(applicationContext) }
                .onFailure {
                    log(
                        "Manual self-arm asset re-stage on reconnect failed: " +
                            sanitizeSupportDiagnostic(it.message.orEmpty()),
                    )
                }
        }
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
                if (SelfArmOnboardingStore.currentActiveSessionId(applicationContext).isBlank()) {
                    SelfArmOnboardingStore.beginSession(applicationContext)
                }
                SelfArmOnboardingStore.requestSetup(applicationContext)
            }
        }
        if (SelfArmOnboardingStore.isSetupRequested(applicationContext)) {
            resumeSetupSessionFromObservedState()
        }
        if (isNativeAssistantDismissArmed()) {
            scheduleNativeAssistantDismissChecks("service_connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        displayStandbyWatchdog.noteAccessibilityEvent(event)
        AccessibilityWindowRoots.noteEvent(event, packageName)
        wirelessDebuggingAutomator?.onAccessibilityEvent(event)
        developerOptionsEnabler?.onAccessibilityEvent(event)
        StatusBadgeOverlayRenderer.onAccessibilityEvent(event)
        if (event != null && isNativeAssistantDismissArmed()) {
            val packageName = event.packageName?.toString().orEmpty()
            if (packageName in NATIVE_ASSISTANT_PACKAGES) {
                dismissNativeAssistantWindow("event:$packageName")
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        displayStandbyWatchdog.noteKeyEvent(event)
        if (event.keyCode == KEYCODE_PROG_BLUE) return false
        if (event.device?.name?.uppercase()?.contains("R08") == true) {
            return handleRingKeyEvent(event)
        }
        // An editable card owns confirm/direction the same keys would
        // otherwise answer a notice with — Enter submits the field, arrows
        // move the caret — so the notice claim steps aside while one is the
        // active surface, the same way the notice itself already steps aside
        // for it (see startTyping). It also means the touchpad's tap gesture
        // must never reach the triple-tap trigger: a hand resting near the
        // touchpad while typing on a keyboard bonded to the glasses reads as
        // exactly the tap burst that opens it (seen on hardware — the
        // launcher appearing mid-reply, unrelated to anything the wearer
        // meant to do). The arbiter skips recognition while a field is focused.
        val editableSurfaceActive = SurfaceController.hasFocusedEditableSurface()
        // Raw gesture trace: the temple firmware's key bursts keep surprising us
        // (duplicated swipe pairs, tap contacts); keep the evidence cheap to grab.
        // Skipped while a field is focused — every keycode typed there is now a
        // character of the wearer's reply or note, not a gesture to debug.
        if (!editableSurfaceActive) {
            log("key code=${event.keyCode} action=${event.action} repeat=${event.repeatCount} t=${event.eventTime}")
        }
        return NexusInput.onKey(event)
    }

    /** The R08 ring passes the session's gate and open session before its own policy. */
    private fun handleRingKeyEvent(event: KeyEvent): Boolean = NexusInput.onKey(event)

    override fun onInterrupt() {
        wirelessDebuggingAutomator?.stop()
        developerOptionsEnabler?.stop()
        pauseSetupWifiEnableIfActive(SetupStage.ENABLING_WIFI)
        unregisterWifiResumeCallback()
        finishWifiEnableIfActive(false)
        pauseWirelessBootstrapIfActive("wireless_setup_interrupted")
        pauseManualNavigationIfActive("manual_pairing_interrupted")
        log("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        log("AccessibilityService destroyed")
        AssistantDisplayEpisode.end(DisplayHoldReleaseReason.SERVICE_DESTROYED)
        displayStandbyWatchdog.stop()
        NexusSession.onServiceDestroyed()
        NexusInput.onServiceDestroyed()
        wirelessDebuggingAutomator?.stop()
        developerOptionsEnabler?.stop()
        pauseSetupWifiEnableIfActive(SetupStage.ENABLING_WIFI)
        unregisterWifiResumeCallback()
        finishWifiEnableIfActive(false)
        pauseWirelessBootstrapIfActive("wireless_setup_service_restarting")
        pauseManualNavigationIfActive("manual_pairing_service_restarting")
        wirelessDebuggingAutomator = null
        developerOptionsEnabler = null
        if (liveInstance === this) liveInstance = null
        RemoteNavigationController.onServiceDestroyed(this)
        RemotePointerController.onServiceDestroyed(this)
        LauncherOverlayRenderer.onServiceDestroyed(this)
        StatusBadgeOverlayRenderer.onServiceDestroyed(this)
        PinOverlayRenderer.onServiceDestroyed(this)
        ActivityOverlayRenderer.onServiceDestroyed(this)
        ActivityController.onServiceDestroyed()
        NoticeController.onServiceDestroyed()
        SurfaceOverlayRenderer.onServiceDestroyed(this)
        NoticeOverlayRenderer.onServiceDestroyed(this)
        SurfaceController.cancelRingInput()
        NoticeController.cancelRingInput()
        NoticeKeyDispatcher.reset()
        RingFocusBroadcastCoordinator.onServiceDestroyed(this)
        super.onDestroy()
    }

    private fun scheduleNativeAssistantDismissChecks(reason: String) {
        NATIVE_ASSISTANT_DISMISS_DELAYS_MS.forEach { delayMs ->
            main.postDelayed(
                {
                    if (isNativeAssistantDismissArmed()) {
                        dismissNativeAssistantWindow("burst:$reason")
                    }
                },
                delayMs,
            )
        }
    }

    private fun dismissNativeAssistantWindow(reason: String): Boolean {
        if (!isNativeAssistantDismissArmed()) return false
        val activePackage = activeWindowPackage()
        // The back lands on whatever window is in front. Once our own overlay is
        // the active window the burst must hold fire, or it closes the plugin
        // surface it just cleared the way for.
        val nativePackage = when (activePackage) {
            null -> nativeAssistantWindowPackage() ?: return false
            in NATIVE_ASSISTANT_PACKAGES -> activePackage
            else -> return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastNativeAssistantBackAtMs < NATIVE_ASSISTANT_BACK_DEBOUNCE_MS) {
            return false
        }
        lastNativeAssistantBackAtMs = now
        val dismissed = performGlobalAction(GLOBAL_ACTION_BACK)
        log(
            "native assistant dismiss reason=$reason active=$activePackage " +
                "native=$nativePackage back=$dismissed",
        )
        return dismissed
    }

    private fun activeWindowPackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.let { return it }
        return windows
            .asSequence()
            .filter { window -> window.isActive || window.isFocused }
            .mapNotNull { window -> window.root?.packageName?.toString() }
            .firstOrNull()
    }

    private fun nativeAssistantWindowPackage(): String? =
        windows
            .asSequence()
            .mapNotNull { window -> window.root?.packageName?.toString() }
            .firstOrNull { packageName -> packageName in NATIVE_ASSISTANT_PACKAGES }

    private fun resumeSetupSessionFromObservedState() {
        val sessionId = SelfArmOnboardingStore.currentActiveSessionId(applicationContext)
        if (sessionId.isBlank()) return
        val snapshot = SelfArmOnboardingStore.snapshot(applicationContext)
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        if (snapshot.coreReady) {
            returnToOnboarding(sessionId)
            SelfArmOnboardingStore.finish(
                context = applicationContext,
                sessionId = sessionId,
                setupState = "wireless_bootstrap_complete",
                success = true,
                completionMode = if (snapshot.maintenanceReady) {
                    SetupCompletionMode.AUTOMATIC
                } else {
                    SetupCompletionMode.PM_GRANT
                },
            )
            return
        }
        if (!snapshot.wifiReady) {
            val wifiEnabled = SelfArmWirelessAdbController.isWifiEnabled(applicationContext)
            if (SelfArmWifiAutomationPolicy.shouldAutomate(
                    accessibilityServiceArmed = true,
                    wifiEnabled = wifiEnabled,
                )
            ) {
                startSetupWifiEnable(sessionId, force = false)
            } else if (wifiEnabled && snapshot.stage == SetupStage.ENABLING_WIFI) {
                awaitValidatedWifiAfterAutomaticEnable(sessionId, force = false)
            } else {
                waitForWifi(sessionId, force = false)
            }
            return
        }
        startWirelessBootstrap(sessionId)
    }

    private fun startWirelessBootstrap(
        sessionId: String,
        force: Boolean = false,
    ) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        if (manualNavigationActive) return
        if (wirelessBootstrapActive && wirelessBootstrapSessionId == sessionId) return
        if (wirelessBootstrapActive) wirelessDebuggingAutomator?.stop()
        finishWifiEnableIfActive(false)
        val forced = force || forcedWirelessBootstrap
        forcedWirelessBootstrap = false
        val snapshot = SelfArmOnboardingStore.snapshot(applicationContext)
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        if (!forced && snapshot.coreReady) {
            returnToOnboarding(sessionId)
            SelfArmOnboardingStore.finish(
                context = applicationContext,
                sessionId = sessionId,
                setupState = "wireless_bootstrap_complete",
                success = true,
                completionMode = if (snapshot.maintenanceReady) {
                    SetupCompletionMode.AUTOMATIC
                } else {
                    SetupCompletionMode.PM_GRANT
                },
            )
            return
        }
        if (!snapshot.wifiReady) {
            if (SelfArmWifiAutomationPolicy.shouldAutomate(
                    accessibilityServiceArmed = true,
                    wifiEnabled = SelfArmWirelessAdbController.isWifiEnabled(applicationContext),
                )
            ) {
                startSetupWifiEnable(sessionId, forced)
            } else {
                waitForWifi(sessionId, forced)
            }
            return
        }
        unregisterWifiResumeCallback()
        wirelessBootstrapActive = true
        wirelessBootstrapSessionId = sessionId
        wirelessBootstrapForced = forced
        SelfArmOnboardingStore.markRunning(applicationContext, sessionId)
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        wirelessDebuggingAutomator?.start(
            SelfArmWirelessDebuggingAutomator.OperationMode.FULL_BOOTSTRAP,
            sessionId = sessionId,
        )
    }

    private fun startWifiEnable() {
        if (wirelessBootstrapActive || setupWifiEnableActive || manualNavigationActive ||
            repairWifiEnableActive
        ) {
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

    /**
     * The boot-repair Wi-Fi enable. Same WIFI_ONLY automation the camera flow uses, with its own
     * completion so the repair coordinator hears the outcome instead of the hub's Wi-Fi
     * ownership machinery. It yields to every flow the wearer can already see — setup, manual
     * pairing, camera acquisition — because they all drive the one automator, and interleaving
     * two runs leaves it serving whichever started last.
     */
    private fun startRepairWifiEnable(onFinished: (Boolean) -> Unit) {
        if (wirelessBootstrapActive || setupWifiEnableActive || manualNavigationActive ||
            wifiEnableActive || repairWifiEnableActive
        ) {
            onFinished(false)
            return
        }
        val automator = wirelessDebuggingAutomator
        if (automator == null) {
            onFinished(false)
            return
        }
        repairWifiEnableActive = true
        repairWifiEnableCompletion = onFinished
        automator.start(SelfArmWirelessDebuggingAutomator.OperationMode.WIFI_ONLY)
    }

    private fun finishRepairWifiEnableIfActive(success: Boolean) {
        if (!repairWifiEnableActive) return
        val completion = repairWifiEnableCompletion
        repairWifiEnableActive = false
        repairWifiEnableCompletion = null
        wirelessDebuggingAutomator?.stop()
        completion?.invoke(success)
    }

    private fun startSetupWifiEnable(sessionId: String, force: Boolean) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        if (manualNavigationActive) {
            waitForWifi(sessionId, force)
            return
        }
        if (setupWifiEnableActive && setupWifiEnableSessionId == sessionId) return
        if (setupWifiEnableActive) pauseSetupWifiEnableIfActive("waiting_for_wifi_network")
        finishWifiEnableIfActive(false)
        val automator = wirelessDebuggingAutomator
        if (automator == null) {
            waitForWifi(sessionId, force)
            return
        }
        SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
            context = applicationContext,
            sessionId = sessionId,
            wifiCurrentlyEnabled = SelfArmWirelessAdbController.isWifiEnabled(applicationContext),
        )
        if (!SelfArmSetupWifiOwnershipStore.isPreparedForEnable(applicationContext, sessionId)) {
            waitForWifi(sessionId, force)
            return
        }
        unregisterWifiResumeCallback()
        setupWifiEnableActive = true
        setupWifiEnableSessionId = sessionId
        setupWifiEnableForced = force
        SelfArmOnboardingStore.markRunning(applicationContext, sessionId)
        SelfArmOnboardingStore.reportProgress(applicationContext, sessionId, SetupStage.ENABLING_WIFI)
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        returnToOnboarding(sessionId)
        automator.start(
            SelfArmWirelessDebuggingAutomator.OperationMode.WIFI_ONLY,
            sessionId = sessionId,
        )
    }

    internal fun onSetupWaitingForWifi(sessionId: String) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        waitForWifi(sessionId, wirelessBootstrapForced)
    }

    private fun waitForWifi(sessionId: String, force: Boolean) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        setupWifiFallbackRunnable?.let(main::removeCallbacks)
        setupWifiFallbackRunnable = null
        wirelessDebuggingAutomator?.stop()
        setupWifiEnableActive = false
        setupWifiEnableSessionId = ""
        setupWifiEnableForced = false
        wirelessBootstrapActive = false
        wirelessBootstrapSessionId = ""
        wirelessBootstrapForced = false
        SelfArmSetupWifiOwnershipStore.clearIfRadioObservedOff(
            applicationContext,
            SelfArmWirelessAdbController.isWifiEnabled(applicationContext),
        )
        SelfArmOnboardingStore.pause(
            applicationContext,
            sessionId,
            "waiting_for_wifi_network",
        )
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        registerWifiResumeCallback(sessionId, force, resumeManual = false)
        returnToOnboarding(sessionId)
    }

    private fun registerWifiResumeCallback(
        sessionId: String,
        force: Boolean,
        resumeManual: Boolean,
    ) {
        unregisterWifiResumeCallback()
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        wifiResumeSessionId = sessionId
        wifiResumeForced = force
        wifiResumeManual = resumeManual
        val resumeRunnable = Runnable { resumeFromValidatedWifi(sessionId) }
        wifiResumeRunnable = resumeRunnable
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post(resumeRunnable)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                main.post(resumeRunnable)
            }
        }
        wifiResumeCallback = callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure {
                if (wifiResumeCallback === callback) unregisterWifiResumeCallback()
                log(
                    "Validated Wi-Fi callback registration failed: " +
                        sanitizeSupportDiagnostic(it.message.orEmpty()),
                )
            }
    }

    private fun resumeFromValidatedWifi(sessionId: String) {
        if (wifiResumeSessionId != sessionId) return
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) {
            unregisterWifiResumeCallback()
            return
        }
        if (!SelfArmOnboardingStore.isWifiReady(applicationContext)) return
        val force = wifiResumeForced
        val resumeManual = wifiResumeManual
        unregisterWifiResumeCallback()
        if (resumeManual) {
            launchPendingManualNavigation(sessionId)
        } else {
            startWirelessBootstrap(sessionId, force)
        }
    }

    private fun unregisterWifiResumeCallback() {
        setupWifiFallbackRunnable?.let(main::removeCallbacks)
        setupWifiFallbackRunnable = null
        wifiResumeRunnable?.let(main::removeCallbacks)
        wifiResumeRunnable = null
        val callback = wifiResumeCallback
        wifiResumeCallback = null
        wifiResumeSessionId = ""
        wifiResumeForced = false
        wifiResumeManual = false
        if (callback != null) {
            val manager = getSystemService(ConnectivityManager::class.java)
            runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }

    internal fun onWirelessBootstrapFinished(sessionId: String) {
        if (wirelessBootstrapSessionId != sessionId) return
        wirelessBootstrapActive = false
        wirelessBootstrapSessionId = ""
        wirelessBootstrapForced = false
    }

    internal fun onWifiEnableFinished(success: Boolean, sessionId: String) {
        if (repairWifiEnableActive) {
            // The automator already stopped itself for a finishing WIFI_ONLY run; only the
            // completion hand-off remains.
            val completion = repairWifiEnableCompletion
            repairWifiEnableActive = false
            repairWifiEnableCompletion = null
            completion?.invoke(success)
            return
        }
        if (manualWifiEnableActive) {
            if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
                manualNavigationSessionId != sessionId
            ) {
                return
            }
            manualWifiEnableActive = false
            if (success) {
                if (SelfArmOnboardingStore.isWifiReady(applicationContext)) {
                    launchPendingManualNavigation(sessionId)
                } else {
                    SelfArmOnboardingStore.pause(
                        applicationContext,
                        sessionId,
                        "waiting_for_wifi_network",
                    )
                    if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
                    registerWifiResumeCallback(sessionId, force = false, resumeManual = true)
                    returnToOnboarding(sessionId)
                }
            } else {
                SelfArmSetupWifiOwnershipStore.clearIfRadioObservedOff(
                    applicationContext,
                    SelfArmWirelessAdbController.isWifiEnabled(applicationContext),
                )
                finishManualNavigationRequest(sessionId, false)
            }
            return
        }
        if (setupWifiEnableActive) {
            if (setupWifiEnableSessionId != sessionId ||
                !SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)
            ) {
                return
            }
            setupWifiEnableActive = false
            setupWifiEnableSessionId = ""
            val force = setupWifiEnableForced
            setupWifiEnableForced = false
            if (!success) {
                waitForWifi(sessionId, force)
                return
            }
            if (SelfArmOnboardingStore.isWifiReady(applicationContext)) {
                startWirelessBootstrap(sessionId, force)
                return
            }
            awaitValidatedWifiAfterAutomaticEnable(sessionId, force)
            return
        }
        if (!wifiEnableActive) return
        wifiEnableActive = false
        GlassesHub.onWifiEnableAutomationFinished(success)
    }

    private fun awaitValidatedWifiAfterAutomaticEnable(sessionId: String, force: Boolean) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        SelfArmOnboardingStore.markRunning(applicationContext, sessionId)
        SelfArmOnboardingStore.reportProgress(applicationContext, sessionId, SetupStage.ENABLING_WIFI)
        registerWifiResumeCallback(sessionId, force, resumeManual = false)
        returnToOnboarding(sessionId)
        val fallback = Runnable {
            setupWifiFallbackRunnable = null
            if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return@Runnable
            if (SelfArmOnboardingStore.isWifiReady(applicationContext)) {
                resumeFromValidatedWifi(sessionId)
            } else {
                waitForWifi(sessionId, force)
            }
        }
        setupWifiFallbackRunnable = fallback
        main.postDelayed(fallback, SelfArmWifiAutomationPolicy.NETWORK_SETTLE_TIMEOUT_MS)
    }

    internal fun onManualNavigationFinished(sessionId: String) {
        if (manualNavigationSessionId != sessionId) return
        manualNavigationActive = false
        manualNavigationSessionId = ""
    }

    private fun openManualNavigation(
        sessionId: String,
        target: SelfArmManualTarget,
        onFinished: (Boolean) -> Unit,
    ) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        developerOptionsEnabler?.stop()
        finishWifiEnableIfActive(false)
        manualNavigationSessionId
            .takeIf(String::isNotBlank)
            ?.let { finishManualNavigationRequest(it, false) }
        if (wirelessBootstrapActive) {
            wirelessDebuggingAutomator?.stop()
            pauseWirelessBootstrapIfActive("manual_pairing_opening")
        }
        if (!manualNavigationActive) {
            // Staging prepares the scripts the phone will read once it drives the arm. Opening a
            // Settings screen needs none of them, so a staging failure is recorded and the
            // navigation goes ahead. It used to abort here, which meant a file problem -- the
            // channel directory refusing to be created, for one -- killed a button whose whole
            // job was to fire an intent, and dropped the owner into an error screen blaming the
            // Wi-Fi instead of the instruction that would have got them there by hand.
            runCatching { SelfArmManualArmAssets.stage(applicationContext) }
                .onFailure {
                    val detail = sanitizeSupportDiagnostic(it.message.orEmpty())
                    log("Manual self-arm asset staging failed: $detail")
                    SelfArmOnboardingStore.note(
                        applicationContext,
                        sessionId,
                        SetupNote.MANUAL_ASSETS_FAILED,
                        detail,
                    )
                }
            manualNavigationActive = true
        }
        manualNavigationSessionId = sessionId
        // Assets are now staged for the phone to read; protect them from the AccessibilityService
        // churn the ROM inflicts during the Wireless Debugging toggle until the phone is done.
        SelfArmOnboardingStore.markManualArmInProgress(applicationContext)
        pendingManualTarget = target
        pendingManualCompletion = onFinished
        if (target.requiresWifi() && !SelfArmWirelessAdbController.isWifiEnabled(applicationContext)) {
            startManualWifiEnable(sessionId)
            return
        }
        if (target.requiresWifi() && !SelfArmOnboardingStore.isWifiReady(applicationContext)) {
            SelfArmOnboardingStore.pause(
                applicationContext,
                sessionId,
                "waiting_for_wifi_network",
            )
            if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
            registerWifiResumeCallback(sessionId, force = false, resumeManual = true)
            returnToOnboarding(sessionId)
            return
        }
        launchPendingManualNavigation(sessionId)
    }

    private fun startManualWifiEnable(sessionId: String) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        val automator = wirelessDebuggingAutomator
        if (automator == null) {
            finishManualNavigationRequest(sessionId, false)
            return
        }
        SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
            context = applicationContext,
            sessionId = sessionId,
            wifiCurrentlyEnabled = SelfArmWirelessAdbController.isWifiEnabled(applicationContext),
        )
        if (!SelfArmSetupWifiOwnershipStore.isPreparedForEnable(applicationContext, sessionId)) {
            finishManualNavigationRequest(sessionId, false)
            return
        }
        manualWifiEnableActive = true
        val generation = ++manualWifiRequestGeneration
        Thread {
            val ownershipRecorded = SelfArmSetupWifiOwnershipStore.markEnableIssued(
                applicationContext,
                sessionId,
                requestInFlight = true,
            )
            val enabledThroughBridge = ownershipRecorded && runCatching {
                SelfArmCommandBridgeClient.setWifiEnabled(applicationContext, true)
            }.onFailure {
                log("Manual Wi-Fi bridge enable failed: ${sanitizeSupportDiagnostic(it.message.orEmpty())}")
            }.getOrDefault(false)
            if (ownershipRecorded && enabledThroughBridge) {
                SelfArmSetupWifiOwnershipStore.markEnableRequestFinished(applicationContext, sessionId)
            }
            GlassesHub.requestWifiOwnershipReconciliation(
                applicationContext,
                "setup_wifi_enable_request_finished",
            )
            main.post {
                if (
                    !SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
                    manualNavigationSessionId != sessionId ||
                    generation != manualWifiRequestGeneration ||
                    pendingManualCompletion == null ||
                    !manualWifiEnableActive
                ) {
                    return@post
                }
                if (!ownershipRecorded) {
                    finishManualNavigationRequest(sessionId, false)
                } else if (enabledThroughBridge || SelfArmWirelessAdbController.isWifiEnabled(applicationContext)) {
                    onWifiEnableFinished(true, sessionId)
                } else {
                    log("Manual Wi-Fi bridge unavailable; using Settings accessibility fallback")
                    automator.start(
                        SelfArmWirelessDebuggingAutomator.OperationMode.WIFI_ONLY,
                        sessionId = sessionId,
                    )
                }
            }
        }.apply {
            name = "RokidNexusManualWifi"
            isDaemon = true
            start()
        }
    }

    private fun launchPendingManualNavigation(sessionId: String) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
            manualNavigationSessionId != sessionId
        ) {
            return
        }
        val target = pendingManualTarget ?: return finishManualNavigationRequest(sessionId, false)
        val automator = wirelessDebuggingAutomator
        if (automator == null) {
            finishManualNavigationRequest(sessionId, false)
            return
        }
        automator.updateManualTarget(target, sessionId)
        manualOpenDeadlineAt = SystemClock.uptimeMillis() + MANUAL_OPEN_TIMEOUT_MS
        scheduleManualNavigationVerification(sessionId, MANUAL_OPEN_INITIAL_DELAY_MS)
    }

    private fun scheduleManualNavigationVerification(sessionId: String, delayMs: Long) {
        manualOpenVerifier?.let(main::removeCallbacks)
        val verifier = Runnable { verifyManualNavigation(sessionId) }
        manualOpenVerifier = verifier
        main.postDelayed(verifier, delayMs)
    }

    private fun verifyManualNavigation(sessionId: String) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
            manualNavigationSessionId != sessionId
        ) {
            return
        }
        val target = pendingManualTarget ?: return
        if (wirelessDebuggingAutomator?.isManualTargetVisible(target) == true) {
            finishManualNavigationRequest(sessionId, true)
            return
        }
        if (SystemClock.uptimeMillis() >= manualOpenDeadlineAt) {
            finishManualNavigationRequest(sessionId, false)
            return
        }
        scheduleManualNavigationVerification(sessionId, MANUAL_OPEN_POLL_MS)
    }

    private fun finishManualNavigationRequest(sessionId: String, success: Boolean) {
        val completion = pendingManualCompletion
        manualWifiRequestGeneration++
        pendingManualCompletion = null
        pendingManualTarget = null
        manualWifiEnableActive = false
        manualWaitingForNetwork = false
        manualOpenDeadlineAt = 0L
        manualOpenVerifier?.let(main::removeCallbacks)
        manualOpenVerifier = null
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
            manualNavigationSessionId != sessionId
        ) {
            return
        }
        if (!success && completion != null) {
            manualNavigationActive = false
            manualNavigationSessionId = ""
            wirelessDebuggingAutomator?.stop()
            cleanupManualAssetsUnlessArmInProgress()
            SelfArmOnboardingStore.reportProgress(
                applicationContext,
                sessionId,
                "manual_pairing_settings_unavailable",
            )
            if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
            returnToOnboarding(sessionId)
        }
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        completion?.invoke(success)
    }

    private fun enableDeveloperOptionsManually(
        sessionId: String,
        onFinished: (Boolean) -> Unit,
    ) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        finishWifiEnableIfActive(false)
        if (wirelessBootstrapActive) {
            wirelessDebuggingAutomator?.stop()
            pauseWirelessBootstrapIfActive("manual_developer_enable_opening")
        }
        if (!manualNavigationActive) {
            val staged = runCatching { SelfArmManualArmAssets.stage(applicationContext) }.isSuccess
            if (!staged) {
                if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
                SelfArmOnboardingStore.reportProgress(
                    applicationContext,
                    sessionId,
                    "manual_pairing_assets_failed",
                )
                returnToOnboarding(sessionId)
                onFinished(false)
                return
            }
            manualNavigationActive = true
        }
        manualNavigationSessionId = sessionId
        val enabler = developerOptionsEnabler
        if (enabler == null) {
            manualNavigationActive = false
            cleanupManualAssetsUnlessArmInProgress()
            onFinished(false)
            return
        }
        enabler.start { success ->
            if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
                manualNavigationSessionId != sessionId
            ) {
                return@start
            }
            if (!success) {
                manualNavigationActive = false
                manualNavigationSessionId = ""
                cleanupManualAssetsUnlessArmInProgress()
                SelfArmOnboardingStore.reportProgress(
                    applicationContext,
                    sessionId,
                    "manual_developer_enable_failed",
                )
                if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return@start
                returnToOnboarding(sessionId)
            }
            if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return@start
            onFinished(success)
        }
    }

    /**
     * Deletes the staged manual-arm assets, but ONLY when no manual arm is in progress. During a
     * manual pairing the ROM churns the AccessibilityService (destroy/recreate) while the wearer
     * toggles Wireless Debugging; those transient teardowns must not wipe the scripts the phone
     * still needs to read. The genuine terminal paths (phone CLOSE, success, timeout) clear the
     * in-progress flag first, so cleanup runs normally there.
     */
    private fun cleanupManualAssetsUnlessArmInProgress() {
        if (SelfArmOnboardingStore.isManualArmInProgress(applicationContext)) return
        SelfArmManualArmAssets.cleanup(applicationContext)
    }

    private fun closeManualNavigation(
        sessionId: String,
        armed: Boolean,
    ) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId) ||
            manualNavigationSessionId != sessionId
        ) {
            return
        }
        // Terminal: the phone is done with the manual flow, so let the assets go. Clear the
        // in-progress flag first so both this cleanup and the automator.stop() below actually run.
        SelfArmOnboardingStore.clearManualArmInProgress(applicationContext)
        developerOptionsEnabler?.stop()
        wirelessDebuggingAutomator?.stop()
        unregisterWifiResumeCallback()
        finishManualNavigationRequest(sessionId, false)
        SelfArmManualArmAssets.cleanup(applicationContext)
        manualNavigationActive = false
        manualNavigationSessionId = ""
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        returnToOnboarding(sessionId)
        if (armed && SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) {
            SelfArmPhoneArmConfirmation.confirm(applicationContext, sessionId)
        } else if (!armed) {
            // The session may legitimately continue (the phone can retry the manual flow), so it
            // is not invalidated here; the reconcile gates on the setup lease and the standing
            // sweep restores a Nexus-enabled radio once that lease lapses.
            GlassesHub.requestWifiOwnershipReconciliation(applicationContext, "manual_close_unarmed")
        }
    }

    private fun finishWifiEnableIfActive(success: Boolean) {
        finishRepairWifiEnableIfActive(success)
        if (manualWifiEnableActive) {
            val sessionId = manualNavigationSessionId
            wirelessDebuggingAutomator?.stop()
            manualWifiEnableActive = false
            if (sessionId.isNotBlank()) finishManualNavigationRequest(sessionId, false)
        }
        if (!wifiEnableActive) return
        wirelessDebuggingAutomator?.stop()
        wifiEnableActive = false
        GlassesHub.onWifiEnableAutomationFinished(success)
    }

    private fun pauseSetupWifiEnableIfActive(progressState: String) {
        if (!setupWifiEnableActive && setupWifiFallbackRunnable == null) return
        val sessionId = setupWifiEnableSessionId.ifBlank { wifiResumeSessionId }
        unregisterWifiResumeCallback()
        setupWifiEnableActive = false
        setupWifiEnableSessionId = ""
        setupWifiEnableForced = false
        if (SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) {
            SelfArmOnboardingStore.pause(applicationContext, sessionId, progressState)
        }
    }

    private fun pauseWirelessBootstrapIfActive(progressState: String) {
        if (!wirelessBootstrapActive) return
        val sessionId = wirelessBootstrapSessionId
        wirelessBootstrapActive = false
        wirelessBootstrapSessionId = ""
        wirelessBootstrapForced = false
        if (SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) {
            SelfArmOnboardingStore.pause(applicationContext, sessionId, progressState)
        }
    }

    private fun pauseManualNavigationIfActive(progressState: String) {
        if (!manualNavigationActive) return
        val sessionId = manualNavigationSessionId
        developerOptionsEnabler?.stop()
        wirelessDebuggingAutomator?.stop()
        unregisterWifiResumeCallback()
        if (sessionId.isNotBlank()) finishManualNavigationRequest(sessionId, false)
        manualNavigationActive = false
        manualNavigationSessionId = ""
        cleanupManualAssetsUnlessArmInProgress()
        if (SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) {
            SelfArmOnboardingStore.pause(applicationContext, sessionId, progressState)
        }
    }

    internal fun returnToOnboarding() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    internal fun returnToOnboarding(sessionId: String) {
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        returnToOnboarding()
    }

    /**
     * The same hand-back, once the home action that cleared Settings has actually settled.
     *
     * Going home is asynchronous. Fired immediately before starting our own activity it lands
     * after it, and the wearer ends up looking at the ROM launcher instead of the screen telling
     * them setup is done. Deliberately posted on the service's handler and not the automator's,
     * whose callbacks are cancelled the moment a run ends -- which is exactly when this has to
     * happen.
     */
    internal fun returnToOnboardingAfter(sessionId: String, delayMs: Long) {
        // The session check happens here, while the caller's session is still the live one, and
        // not when the post fires: finishing a run closes the session within those few hundred
        // milliseconds, so a guard evaluated late always says no and the wearer is left on the ROM
        // launcher -- the exact thing this delay exists to prevent.
        if (!SelfArmOnboardingStore.isCurrentSession(applicationContext, sessionId)) return
        main.postDelayed({ returnToOnboarding() }, delayMs)
    }

    private fun cancelSetupSessionWorkInternal(expectedSessionId: String? = null) {
        val trackedSessions = listOf(
            wirelessBootstrapSessionId,
            setupWifiEnableSessionId,
            manualNavigationSessionId,
            wifiResumeSessionId,
        ).filter(String::isNotBlank)
        if (expectedSessionId != null &&
            trackedSessions.isNotEmpty() &&
            expectedSessionId !in trackedSessions
        ) {
            return
        }
        // The unconditional automator stop below would otherwise strand a live repair run with
        // its completion never called; fail it honestly first.
        finishRepairWifiEnableIfActive(false)
        wirelessDebuggingAutomator?.stop()
        developerOptionsEnabler?.stop()
        unregisterWifiResumeCallback()
        manualOpenVerifier?.let(main::removeCallbacks)
        manualOpenVerifier = null
        pendingManualCompletion = null
        pendingManualTarget = null
        manualWifiRequestGeneration++
        manualWifiEnableActive = false
        manualWaitingForNetwork = false
        manualOpenDeadlineAt = 0L
        setupWifiEnableActive = false
        setupWifiEnableSessionId = ""
        setupWifiEnableForced = false
        wirelessBootstrapActive = false
        wirelessBootstrapSessionId = ""
        wirelessBootstrapForced = false
        manualNavigationActive = false
        manualNavigationSessionId = ""
        SelfArmManualArmAssets.cleanup(applicationContext)
    }

    companion object {
        private const val KEYCODE_PROG_BLUE = 186
        private const val MANUAL_OPEN_INITIAL_DELAY_MS = 350L
        private const val MANUAL_OPEN_POLL_MS = 250L
        private const val MANUAL_OPEN_TIMEOUT_MS = 30_000L
        private const val MANUAL_WIFI_NETWORK_POLL_MS = 500L
        private const val MANUAL_WIFI_NETWORK_TIMEOUT_MS = 30_000L
        // The phone's CXR sendExit closes the native scene in ~150 ms
        // (measured 2026-08-02); this burst is the fallback for when that
        // race is lost, so it no longer needs to hunt for seconds. The ROM
        // launcher is deliberately absent from the target set: the only
        // launcher windows ever observed here are the home screen resuming
        // after the scene died, and BACK on a home screen is pure noise.
        private const val NATIVE_ASSISTANT_DISMISS_ARM_MS = 3_000L
        private const val NATIVE_ASSISTANT_BACK_DEBOUNCE_MS = 120L
        private val NATIVE_ASSISTANT_DISMISS_DELAYS_MS =
            longArrayOf(0L, 120L, 280L, 600L, 1_000L, 1_800L)
        private val NATIVE_ASSISTANT_PACKAGES = setOf(
            "com.rokid.os.sprite.assistserver",
            "com.rokid.overlayrec",
        )
        @Volatile private var liveInstance: RokidBusAccessibilityService? = null
        @Volatile private var nativeAssistantDismissUntilMs = 0L

        /** True while the AccessibilityService is connected and able to drive Settings. */
        internal fun isLive(): Boolean = liveInstance != null

        internal fun isSetupAutomationActive(): Boolean {
            val service = liveInstance ?: return false
            return service.wirelessBootstrapActive ||
                service.setupWifiEnableActive ||
                service.wifiEnableActive ||
                service.repairWifiEnableActive ||
                service.manualWifiEnableActive ||
                service.manualNavigationActive ||
                service.manualWaitingForNetwork ||
                service.pendingManualTarget != null ||
                service.wifiResumeCallback != null ||
                service.setupWifiFallbackRunnable != null ||
                service.manualOpenVerifier != null
        }

        /**
         * Called only by [GlassesHub] for the phone hub's capability-gated arm envelope. The
         * deadline is retained across a short AccessibilityService recreation, but can never
         * outlive the fixed arm window.
         */
        internal fun requestNativeAssistantDismiss(): Boolean {
            nativeAssistantDismissUntilMs = maxOf(
                nativeAssistantDismissUntilMs,
                SystemClock.uptimeMillis() + NATIVE_ASSISTANT_DISMISS_ARM_MS,
            )
            val service = liveInstance ?: return false
            service.main.post {
                service.scheduleNativeAssistantDismissChecks("phone_ai_assist_start")
            }
            return true
        }

        private fun isNativeAssistantDismissArmed(): Boolean =
            SystemClock.uptimeMillis() <= nativeAssistantDismissUntilMs

        internal fun requestWirelessBootstrap(context: Context, force: Boolean = false): Boolean {
            val appContext = context.applicationContext
            SelfArmOnboardingStore.requestSetup(appContext)
            val sessionId = SelfArmOnboardingStore.currentActiveSessionId(appContext)
            if (sessionId.isBlank()) return false
            val service = liveInstance ?: return false
            service.main.post {
                if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return@post
                service.startWirelessBootstrap(sessionId, force)
            }
            return true
        }

        internal fun resumeSetupSessionIfNeeded(context: Context): Boolean {
            val appContext = context.applicationContext
            val sessionId = SelfArmOnboardingStore.currentActiveSessionId(appContext)
            val service = liveInstance ?: return false
            if (sessionId.isBlank()) return false
            service.main.post {
                if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return@post
                service.resumeSetupSessionFromObservedState()
            }
            return true
        }

        internal fun onPhoneAssistedPairingResult(
            context: Context,
            result: SetupPairingResult,
        ): Boolean {
            val appContext = context.applicationContext
            if (!SelfArmOnboardingStore.isCurrentSession(appContext, result.sessionId)) {
                return false
            }
            val service = liveInstance ?: return false
            service.main.post {
                val handled =
                    service.wirelessDebuggingAutomator?.onPhoneAssistedPairingResult(result) == true
                log(
                    if (handled) {
                        "phone-assisted pairing result accepted"
                    } else {
                        "phone-assisted pairing result ignored reason=NO_MATCH"
                    },
                )
            }
            return true
        }

        @Suppress("UNUSED_PARAMETER")
        internal fun requestWifiEnable(context: Context): Boolean {
            val service = liveInstance ?: return false
            service.main.post(service::startWifiEnable)
            return true
        }

        /**
         * Called only by [SelfArmBootRepairCoordinator]. False means no connected service, so no
         * automation can run at all; otherwise [onFinished] reports whether Wi-Fi came up.
         */
        internal fun requestRepairWifiEnable(onFinished: (Boolean) -> Unit): Boolean {
            val service = liveInstance ?: return false
            service.main.post { service.startRepairWifiEnable(onFinished) }
            return true
        }

        @Suppress("UNUSED_PARAMETER")
        internal fun requestManualAction(
            context: Context,
            action: SelfArmManualAction,
            armed: Boolean = false,
            onFinished: (Boolean) -> Unit = {},
        ): Boolean {
            val appContext = context.applicationContext
            val sessionId = SelfArmOnboardingStore.currentActiveSessionId(appContext).ifBlank {
                if (action == SelfArmManualAction.CLOSE) return false
                SelfArmOnboardingStore.beginSession(appContext)
            }
            val service = liveInstance ?: return false
            service.main.post {
                if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return@post
                val guardedCompletion: (Boolean) -> Unit = { success ->
                    if (SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) {
                        onFinished(success)
                    }
                }
                when (action) {
                    SelfArmManualAction.ENABLE_DEVELOPER_OPTIONS ->
                        service.enableDeveloperOptionsManually(sessionId, guardedCompletion)
                    SelfArmManualAction.OPEN_DEVELOPER_OPTIONS ->
                        service.openManualNavigation(
                            sessionId,
                            SelfArmManualTarget.DEVELOPER_OPTIONS,
                            guardedCompletion,
                        )
                    SelfArmManualAction.OPEN_WIRELESS_DEBUGGING ->
                        service.openManualNavigation(
                            sessionId,
                            SelfArmManualTarget.WIRELESS_DEBUGGING,
                            guardedCompletion,
                        )
                    SelfArmManualAction.OPEN_PAIRING_DIALOG ->
                        service.openManualNavigation(
                            sessionId,
                            SelfArmManualTarget.PAIRING_DIALOG,
                            guardedCompletion,
                        )
                    // Handled directly by GlassesHub without the accessibility service; a request
                    // arriving here is unexpected, so report failure instead of guessing.
                    SelfArmManualAction.OPEN_ACCESSIBILITY_SETTINGS -> guardedCompletion(false)
                    SelfArmManualAction.CLOSE -> {
                        service.closeManualNavigation(sessionId, armed)
                        guardedCompletion(true)
                    }
                }
            }
            return true
        }

        internal fun cancelSetupSessionWork() {
            val service = liveInstance ?: return
            if (Looper.myLooper() == service.main.looper) {
                service.cancelSetupSessionWorkInternal()
            } else {
                service.main.post { service.cancelSetupSessionWorkInternal() }
            }
        }

        internal fun onSetupSessionEnded(sessionId: String) {
            val service = liveInstance ?: return
            if (Looper.myLooper() == service.main.looper) {
                service.cancelSetupSessionWorkInternal(sessionId)
            } else {
                service.main.post { service.cancelSetupSessionWorkInternal(sessionId) }
            }
        }

    }
}

private fun SelfArmManualTarget.requiresWifi(): Boolean =
    this == SelfArmManualTarget.WIRELESS_DEBUGGING || this == SelfArmManualTarget.PAIRING_DIALOG

/**
 * The running Nexus session: the PR1 reducer behind its runner, the one overlay window that
 * draws it, and the bus. Only the input arbiter opens it, and only on the session backend.
 */
internal object NexusSession {
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var host: SessionHost? = null
    private var status: SessionStatus? = null
    private var statusFresh = false
    private var renderedState: SessionState? = null
    private var noticeUnsubscribe: (() -> Unit)? = null
    private var surfaceUnsubscribe: (() -> Unit)? = null
    private var noticeArmed: Boolean? = null
    private var noticeShownId: String? = null
    private var editableFocused: Boolean? = null
    private var surfaceShownId: String? = null
    private var phoneLinked: Boolean? = null
    private val runner = SessionRunner(
        reducer = SessionReducer(),
        clock = SystemClock::uptimeMillis,
        timer = HandlerSessionTimer(main),
        sink = Sink,
        onError = { logError("session effect failed", it) },
    )

    val state: SessionState get() = runner.state

    fun onServiceConnected(service: AccessibilityService) {
        runOnMain {
            appContext = service.applicationContext
            host = SessionHost(SessionOverlayWindow(service), abort = { dispatch(SessionEvent.Abort) }, log = ::log)
            noticeUnsubscribe?.invoke()
            noticeUnsubscribe = NoticeController.observe { runOnMain(::onNoticeChanged) }
            surfaceUnsubscribe?.invoke()
            surfaceUnsubscribe = SurfaceController.observe { surface -> runOnMain { onSurfaceChanged(surface) } }
        }
    }

    fun onServiceDestroyed() {
        runOnMain {
            runner.dispatch(SessionEvent.Abort)
            host?.detach()
            host = null
            noticeUnsubscribe?.invoke()
            noticeUnsubscribe = null
            surfaceUnsubscribe?.invoke()
            surfaceUnsubscribe = null
            appContext = null
        }
    }

    /** Link changes reach the reducer as they happen; a session that opens is told again. */
    fun onPhoneLink(connected: Boolean) {
        runOnMain {
            if (connected == phoneLinked) return@runOnMain
            phoneLinked = connected
            runner.dispatch(if (connected) SessionEvent.LinkRestored else SessionEvent.LinkLost)
        }
    }

    /**
     * The notice facts the reducer keeps: an armed band refuses the triple tap, and every new
     * notice updates the root's preview and counter, even while the session hides the band.
     * The preview opens the hub's notifications page, which lands with the notification centre.
     */
    private fun onNoticeChanged() {
        val armed = NoticeController.ownsRingInput()
        if (armed != noticeArmed) {
            noticeArmed = armed
            runner.dispatch(if (armed) SessionEvent.NoticeArmed else SessionEvent.NoticeCleared)
        }
        val notice = NoticeController.activeNotice()
        if (notice?.surfaceId == noticeShownId) return
        noticeShownId = notice?.surfaceId
        if (notice == null) return
        val content = notice.content
        val text = listOfNotNull(content.title, content.body, content.lines.firstOrNull())
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        runner.dispatch(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTIFICATIONS_PAGE_ID, text)))
    }

    private fun onSurfaceChanged(surface: NexusSurface?) {
        val editable = SurfaceController.hasFocusedEditableSurface()
        if (editable != editableFocused) {
            editableFocused = editable
            runner.dispatch(SessionEvent.EditableFocused(editable))
        }
        val surfaceId = surface?.surfaceId
        if (surface != null && surfaceId != surfaceShownId) {
            runner.dispatch(SessionEvent.SurfaceShown(surface.surfaceId, surface.ownerPluginId, SystemClock.uptimeMillis()))
        }
        surfaceShownId = surfaceId
    }

    fun dispatch(event: SessionEvent) {
        runOnMain { runner.dispatch(event) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private object Sink : SessionEffectSink {
        override fun execute(effect: SessionEffect) {
            when (effect) {
                SessionEffect.AttachHost -> attach()
                SessionEffect.DetachHost -> detach()
                // The base was never touched: lifting the band's suppression is all a restore does.
                is SessionEffect.RestoreUnderneath -> NoticeController.setSessionOverlayActive(false)
                is SessionEffect.RequestPage -> send(BusPaths.PAGE_REQUEST, effect.request.toPayload())
                is SessionEffect.SendAction -> send(BusPaths.PAGE_ACTION, effect.action.toPayload())
                is SessionEffect.SendVisibility -> send(
                    BusPaths.PAGE_VISIBILITY,
                    JSONObject()
                        .put("pageId", effect.pageId)
                        .put("visible", effect.visible)
                        .apply { effect.leaseUntilMs?.let { put("leaseUntilMs", it) } },
                )
                is SessionEffect.SendClosed -> send(
                    BusPaths.PAGE_CLOSED,
                    JSONObject().put("pageId", effect.pageId).put("reason", effect.reason),
                )
                is SessionEffect.SendLauncherOpen -> openPlugin(effect)
                is SessionEffect.CloseSurface -> SurfaceController.closeUnseen(effect.surfaceId)
                is SessionEffect.ShowStatus -> {
                    status = effect.status
                    statusFresh = true
                }
                SessionEffect.ShowGate,
                SessionEffect.ShowRoot,
                SessionEffect.ShowFrame,
                is SessionEffect.ScheduleDeadline,
                SessionEffect.CancelDeadline,
                SessionEffect.None,
                -> Unit
            }
        }

        /** The window is redrawn from the settled state; a status line lasts until the screen changes. */
        override fun settled(model: SessionModel) {
            if (statusFresh) {
                statusFresh = false
            } else if (model.state != renderedState) {
                status = null
            }
            renderedState = model.state
            host?.render(model.state, status)
        }

        private fun attach() {
            status = null
            val current = host
            if (current == null) {
                log("session opened without a service window; closing it")
                dispatch(SessionEvent.Abort)
                return
            }
            current.attach()
            if (!current.isAttached) return
            // No ambient band while the session is up; the notice keeps its deadline.
            NoticeController.setSessionOverlayActive(true)
            // The session owns the ring while it is up, exactly as the legacy launcher does.
            appContext?.let { RingFocusBroadcastCoordinator.setLauncherShown(it, shown = true) }
            // A new session starts linked; tell it if the phone is already gone.
            if (!GlassesHub.isPhoneConnected()) dispatch(SessionEvent.LinkLost)
        }

        private fun detach() {
            host?.detach()
            NoticeController.setSessionOverlayActive(false)
            appContext?.let { RingFocusBroadcastCoordinator.setLauncherShown(it, shown = false) }
            renderedState = null
        }

        /** Reuses the launcher's own open, and its ring handoff to the plugin's surface. */
        private fun openPlugin(effect: SessionEffect.SendLauncherOpen) {
            val result = GlassesHub.openLauncherEntry(effect.pluginId)
            log("Session plugin open result: $result")
            if (!result.startsWith("launcherOpen=true")) {
                dispatch(SessionEvent.OpenFailed(effect.token, OpenFailure.SEND_FAILED))
                return
            }
            if (GlassesHub.launcherEntryOpensSurface(effect.pluginId)) {
                appContext?.let(RingFocusBroadcastCoordinator::beginSurfaceHandoff)
            }
        }

        private fun send(path: String, payload: JSONObject) {
            if (!GlassesHub.sendToPhone(path, payload)) log("session send dropped path=$path")
        }
    }
}

/** The session's one opaque overlay; it holds the screen on only while it exists. */
private class SessionOverlayWindow(private val service: AccessibilityService) : SessionWindow {
    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var insetUnsubscribe: (() -> Unit)? = null

    override fun add(): Boolean {
        val manager = windowManager ?: return false
        val view = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BusTheme.glassesBg)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable: every key reaches the session through the filter, and the app
            // underneath keeps its window focus for when the session closes.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        )
        val added = runCatching { manager.addView(view, params) }
            .onFailure { logError("Session host window could not be added", it) }
            .isSuccess
        if (!added) return false
        root = view
        insetUnsubscribe = HudTopInset.observe(service) { inset ->
            view.setPadding(dp(18), dp(16 + HudTopInset.sanitize(inset)), dp(18), dp(12))
        }
        return true
    }

    override fun remove() {
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
    }

    override fun show(screen: HostScreen) {
        val view = root ?: return
        view.removeAllViews()
        screen.title?.let { view.addView(line(it, 20f, BusTheme.text, bold = true)) }
        screen.rows.forEach { row ->
            val marker = if (row.selected) "› " else "  "
            val color = if (row.selected) BusTheme.phosphor else BusTheme.text
            view.addView(line(marker + row.text, 18f, color, bold = row.selected))
        }
        screen.status?.let { view.addView(line(it, 14f, BusTheme.dim)) }
    }

    private fun line(value: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(service).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun dp(value: Int): Int = BusTheme.dp(service, value)
}

/** One pending task on the main looper, posted on the uptime clock the runner reads. */
private class HandlerSessionTimer(private val handler: Handler) : SessionTimer {
    private var pending: Runnable? = null

    override fun schedule(atUptimeMs: Long, task: () -> Unit) {
        cancel()
        val runnable = Runnable {
            pending = null
            task()
        }
        pending = runnable
        handler.postAtTime(runnable, atUptimeMs)
    }

    override fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}

/**
 * Every key of the glasses goes through one [InputArbiter]: the accessibility filter, the ring,
 * the late replay of unclassified contacts, and the key dispatch of the Nexus windows. This is
 * the arbiter's Android side: it reads the hub's state and hands each routed key to its owner.
 */
internal object NexusInput {
    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null

    /** The Android event being decided, for the owners that still take a `KeyEvent`. */
    private var current: KeyEvent? = null
    private val arbiter = InputArbiter(AndroidInputContext)
    private val tick = Runnable(::onTick)

    @Volatile
    var backend: LauncherBackend = LauncherBackend.DEFAULT
        private set

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        backend = LauncherBackend.parse(ActivityPresentationSettings.launcherBackend(service)) ?: LauncherBackend.DEFAULT
        log("Launcher backend=${backend.name}")
    }

    fun onServiceDestroyed() {
        main.removeCallbacks(tick)
        arbiter.reset()
        arbiter.forgetPresses()
        service = null
    }

    /** A key the accessibility filter sees, before any window does. */
    fun onKey(event: KeyEvent): Boolean = decide(event) { arbiter.onKey(it) }

    /** A key delivered to a Nexus window: only a press the filter never routed is decided here. */
    fun onWindowKey(event: KeyEvent): Boolean = decide(event) { arbiter.onWindowKey(it) }

    /**
     * Switches the launcher backend. The active one closes first, so at most one launcher ever
     * has a window and only the new one receives the global gesture.
     */
    fun setBackend(context: Context, next: LauncherBackend) {
        ActivityPresentationSettings.setLauncherBackend(context.applicationContext, next.name)
        runOnMain {
            if (next == backend) return@runOnMain
            when (backend) {
                LauncherBackend.LEGACY -> if (LauncherOverlayRenderer.isShown()) LauncherOverlayRenderer.hide()
                LauncherBackend.SESSION -> NexusSession.dispatch(SessionEvent.Abort)
            }
            backend = next
            arbiter.reset()
            scheduleTick()
            log("Launcher backend=${next.name}")
        }
    }

    private inline fun decide(event: KeyEvent, route: (RawKeyEvent) -> InputDecision): Boolean {
        val outer = current
        current = event
        try {
            return route(event.toRawKeyEvent()).consumed
        } finally {
            current = outer
            scheduleTick()
        }
    }

    private fun onTick() {
        arbiter.onTick(SystemClock.uptimeMillis())
        scheduleTick()
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        arbiter.nextDeadlineMs()?.let { main.postAtTime(tick, it) }
    }

    private fun openLegacyLauncher(): Boolean {
        val context = service ?: return false
        if (!LauncherOverlayRenderer.isShown()) LauncherOverlayRenderer.show(context)
        return true
    }

    /**
     * The ring's existing precedence. Claimed keys change notice state. While the band owns the
     * ring, every other R08 key stops here as a no-op so neither the bridge nor an underlying
     * Nexus layer can drive hidden native UI. It is asked before the launcher because the band
     * is drawn on top of it: a paged notice that arrives over an open launcher is what the
     * wearer is reading, and turning its pages must not scroll a tile row they cannot see.
     */
    private fun deliverRingKey(keyCode: Int, eventTimeMs: Long) {
        when {
            NoticeController.claimsRingKey(keyCode) -> NoticeController.handleRingKey(keyCode, eventTimeMs)
            NoticeController.ownsRingInput() -> Unit
            LauncherOverlayRenderer.isShown() -> LauncherOverlayRenderer.handleRingKey(keyCode, eventTimeMs)
            SurfaceController.activeSurface() != null -> SurfaceController.handleRingKey(keyCode, eventTimeMs)
            else -> Unit
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private object AndroidInputContext : InputContext {
        override val backend: LauncherBackend get() = NexusInput.backend
        override val editableFocused: Boolean get() = SurfaceController.hasFocusedEditableSurface()
        override val sessionGate: Boolean get() = NexusSession.state is SessionState.Opening
        override val sessionOpen: Boolean
            get() = NexusSession.state.let { it != SessionState.Closed && it !is SessionState.Opening }

        // The plan's armed notice: interactive, action-bearing, paged or backdrop. It reads false
        // while the camera overlay or an open session suppresses the band.
        override val noticeArmed: Boolean get() = NoticeController.ownsRingInput()
        override val legacyShown: Boolean get() = LauncherOverlayRenderer.isShown()
        override val surfaceOwnsKeys: Boolean get() = SurfaceController.activeSurface() != null
        override val activeSurfaceId: String? get() = SurfaceController.activeSurface()?.surfaceId
        override val nativeInFront: Boolean get() = !surfaceOwnsKeys && !legacyShown

        override fun noticeHandles(event: RawKeyEvent): Boolean =
            current?.let(NoticeKeyDispatcher::handleKeyEvent) == true

        override fun deliver(intent: RoutedIntent): Boolean = when (intent) {
            is RoutedIntent.ToSession -> {
                NexusSession.dispatch(intent.event)
                true
            }
            is RoutedIntent.ToLegacyLauncher ->
                if (intent.open) openLegacyLauncher() else current?.let(LauncherOverlayRenderer::handleKeyEvent) == true
            is RoutedIntent.ToSurface ->
                if (intent.unclassifiedContact) {
                    // Deliberately never the notice: a band is answered once, and this contact
                    // was never classified as a tap by the firmware.
                    SurfaceController.forwardSurfaceInput(TripleTapDetector.KEYCODE_NOTIFICATION, KeyEvent.ACTION_DOWN)
                } else {
                    current?.let(SurfaceController::handleKeyEvent) == true
                }
            is RoutedIntent.ToRing -> {
                deliverRingKey(intent.key.keyCode, intent.key.eventTime)
                true
            }
            is RoutedIntent.ToNotice, RoutedIntent.PassThrough -> false
        }
    }
}

private fun KeyEvent.toRawKeyEvent(): RawKeyEvent =
    KeyEventAdapter.from(keyCode, action, repeatCount, eventTime, downTime, deviceId, device?.name)
