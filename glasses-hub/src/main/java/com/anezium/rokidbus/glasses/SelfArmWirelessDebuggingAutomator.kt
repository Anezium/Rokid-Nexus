package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.anezium.rokidbus.glasses.SelfArmVerifiedSettingsScroller.Outcome as ScrollSearchOutcome
import com.anezium.rokidbus.glasses.SelfArmVerifiedSettingsScroller.Surface as ScrollSurface
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.SetupCompletionMode
import com.anezium.rokidbus.shared.SetupNote
import com.anezium.rokidbus.shared.SetupPairingOfferContract
import com.anezium.rokidbus.shared.SetupPairingResult
import com.anezium.rokidbus.shared.SetupStage
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.regex.Pattern

internal class SelfArmWirelessDebuggingAutomator(
    private val service: RokidBusAccessibilityService,
    private val handler: Handler,
) {
    private val settingsStrings = SelfArmSettingsStringResolver(service.applicationContext)
    private val settingsScroller = SelfArmVerifiedSettingsScroller(service)

    internal enum class OperationMode {
        FULL_BOOTSTRAP,
        WIFI_ONLY,
        MANUAL_NAVIGATION,
    }

    private var active = false
    private var operationMode = OperationMode.FULL_BOOTSTRAP
    private var manualTarget = SelfArmManualTarget.PAIRING_DIALOG
    private var deadlineAt = 0L
    private var lastClickAt = 0L

    private var wifiConfirmed = false
    private var wifiClickIssued = false
    private var wifiClickAttempts = 0
    private var wifiScrolls = 0
    private var wifiSettingsOpened = false
    private var wifiActivityObserved = false
    private var wifiNetworkWaitStartedAt = 0L

    private var pairingRequested = false
    private var pairingRequestedAt = 0L
    private var pairingDialogDumped = false
    private var pairingReadyReported = false
    private var pairingReadyReportedAt = 0L
    private var pairingCodeOnlySeenAt = 0L
    private var lastPairingReadyReportAt = 0L
    private var lastPairingReadyToken = ""
    private var lastPairingCode = ""
    private var lastPairingHost = ""
    private var lastPairingPort = 0
    private var lastPairingConnectPort = 0
    private var localSelfPairingToken = ""
    private var localSelfPairingRunning = false
    private var localSelfPairingStartedAt = 0L
    private var localSelfPairingComplete = false
    private var localSelfPairingFailedToken = ""
    private var localSelfPairingLastError = ""
    private var localSelfPairingThread: Thread? = null
    private var lastLocalSelfPairingStatusAt = 0L
    private var lastPhoneAssistedConnectPortReport = 0
    private var lastReportedProgressState = ""
    private val phonePairingOffer = SelfArmPhonePairingOfferTracker()
    private val displayHold = SetupDisplayHoldLifecycle(
        isInteractive = {
            service.getSystemService(PowerManager::class.java)?.isInteractive != false
        },
        backstop = SetupDisplayHoldLifecycle.EXECUTOR_BACKSTOP,
        onBackstopRelease = { android.util.Log.i(TAG, formatSetupDisplayHoldTransition(it)) },
        sleptScope = SetupDisplaySleptScope.PROCESS,
    )

    private var awaitingWirelessDebugConfirmation = false
    private var wirelessToggleRequestedAt = 0L
    private var deviceInfoFallback = false
    private var developerEnableFlow = false
    private var buildNumberTaps = 0
    private var developerOpenAttempts = 0
    private var lastDeveloperOpenAt = 0L
    private var developerOpenStartedAt = 0L
    private var developerScreenSeen = false
    private var lastConnectHost = ""
    private var lastConnectPort = 0
    private var directWirelessProbePending = false
    private var directWirelessProbeStartedAt = 0L
    private var directWirelessFallbackUsed = false
    private var scheduledStepAt = SelfArmTickSchedulePolicy.NONE
    private var scheduledStepRunnable: Runnable? = null
    private val callbackToken = Any()
    private var runToken = 0L
    private var sessionId = ""
    private var lastHeartbeatAt = 0L

    fun start(
        mode: OperationMode = OperationMode.FULL_BOOTSTRAP,
        target: SelfArmManualTarget = SelfArmManualTarget.PAIRING_DIALOG,
        sessionId: String = "",
    ) {
        cancelLiveWork()
        if (mode != OperationMode.WIFI_ONLY &&
            !SelfArmOnboardingStore.isCurrentSession(service.applicationContext, sessionId)
        ) {
            releaseDisplayHold(SetupDisplayHoldEnd.SESSION_ENDED)
            return
        }
        runToken++
        this.sessionId = sessionId
        active = true
        operationMode = mode
        manualTarget = target
        settingsStrings.refresh()
        deadlineAt = SystemClock.uptimeMillis() + when (mode) {
            OperationMode.MANUAL_NAVIGATION -> MANUAL_TIMEOUT_MS
            OperationMode.WIFI_ONLY -> SelfArmWifiAutomationPolicy.AUTOMATION_TIMEOUT_MS
            OperationMode.FULL_BOOTSTRAP -> TIMEOUT_MS
        }
        renewDisplayHold(SystemClock.uptimeMillis())
        lastClickAt = 0L
        wifiConfirmed = false
        wifiClickIssued = false
        wifiClickAttempts = 0
        wifiScrolls = 0
        wifiSettingsOpened = false
        wifiActivityObserved = false
        wifiNetworkWaitStartedAt = 0L
        pairingRequested = false
        pairingRequestedAt = 0L
        pairingDialogDumped = false
        pairingReadyReported = false
        pairingReadyReportedAt = 0L
        pairingCodeOnlySeenAt = 0L
        lastPairingReadyReportAt = 0L
        lastPairingReadyToken = ""
        lastPairingCode = ""
        lastPairingHost = wifiIpv4()
        lastPairingPort = 0
        lastPairingConnectPort = 0
        localSelfPairingToken = ""
        localSelfPairingRunning = false
        localSelfPairingComplete = false
        localSelfPairingFailedToken = ""
        localSelfPairingLastError = ""
        lastLocalSelfPairingStatusAt = 0L
        lastPhoneAssistedConnectPortReport = 0
        lastReportedProgressState = ""
        lastHeartbeatAt = 0L
        awaitingWirelessDebugConfirmation = false
        wirelessToggleRequestedAt = 0L
        deviceInfoFallback = false
        developerEnableFlow = false
        buildNumberTaps = 0
        developerOpenAttempts = 0
        lastDeveloperOpenAt = 0L
        developerOpenStartedAt = 0L
        developerScreenSeen = false
        resetDirectWirelessRoute()
        lastConnectHost = lastPairingHost
        lastConnectPort = SelfArmWirelessAdbController.readWirelessPort()
        settingsScroller.resetAll()
        beatLeaseIfDue(force = true)
        if (operationMode == OperationMode.FULL_BOOTSTRAP) {
            Log.d(TAG, "start: wireless debugging setup automator started")
            android.util.Log.i(TAG, "Wireless Debugging setup")
            report("starting_wireless_debugging_setup")
        } else if (operationMode == OperationMode.WIFI_ONLY) {
            Log.d(TAG, "start: Wi-Fi enable automator started")
            report("starting_wifi_enable")
        } else {
            Log.d(TAG, "start: manual navigation automator started target=$manualTarget")
            report("opening_developer_options")
        }
        if (operationMode == OperationMode.FULL_BOOTSTRAP &&
            !SelfArmOnboardingStore.isWifiReady(service.applicationContext)
        ) {
            service.onSetupWaitingForWifi(sessionId)
            return
        }
        if (!wifiEnabled()) {
            if (sessionId.isNotBlank()) {
                SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
                    context = service.applicationContext,
                    sessionId = sessionId,
                    wifiCurrentlyEnabled = false,
                )
                if (!SelfArmSetupWifiOwnershipStore.isPreparedForEnable(
                        service.applicationContext,
                        sessionId,
                    )
                ) {
                    finish("wifi_ownership_record_failed", false)
                    return
                }
            }
            report("enabling_wifi")
            openWifiSettings()
            schedule(1200L)
            return
        }
        onWifiEnabled()
    }

    fun stop(reason: SetupDisplayHoldEnd = SetupDisplayHoldEnd.STOPPED) {
        val wasManual = operationMode == OperationMode.MANUAL_NAVIGATION
        if (reason == SetupDisplayHoldEnd.SERVICE_DESTROYED) {
            releaseDisplayHoldNow(reason)
        } else {
            releaseDisplayHold(reason)
        }
        cancelLiveWork()
        // Don't wipe the staged assets on a transient stop (the ROM churns the AccessibilityService
        // during the Wireless Debugging toggle); the phone still needs them. Terminal paths clear
        // the in-progress flag first so this cleanup runs then.
        if (wasManual && !SelfArmOnboardingStore.isManualArmInProgress(service.applicationContext)) {
            SelfArmManualArmAssets.cleanup(service.applicationContext)
        }
    }

    fun updateManualTarget(target: SelfArmManualTarget, sessionId: String) {
        if (!active ||
            operationMode != OperationMode.MANUAL_NAVIGATION ||
            this.sessionId != sessionId
        ) {
            start(OperationMode.MANUAL_NAVIGATION, target, sessionId)
            return
        }
        if (!isLiveRun()) return
        manualTarget = target
        // Each wizard button press deserves a fresh manual window; without this the 5-minute
        // budget runs from the first step and can expire while the wearer is still typing.
        deadlineAt = SystemClock.uptimeMillis() + MANUAL_TIMEOUT_MS
        settingsScroller.resetAll()
        resetDirectWirelessRoute()
        if (
            wifiConfirmed &&
            target != SelfArmManualTarget.ENABLE_DEVELOPER_OPTIONS
        ) {
            openPreferredSettingsTarget()
        }
        schedule(0L)
    }

    fun closeManual() {
        if (!isLiveRun()) return
        if (!active || operationMode != OperationMode.MANUAL_NAVIGATION) {
            SelfArmManualArmAssets.cleanup(service.applicationContext)
            service.returnToOnboarding()
            return
        }
        finish("manual_pairing_closed", true)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isLiveRun()) return
        if (wifiSettingsOpened &&
            event?.packageName?.toString() == AccessibilityWindowRoots.SETTINGS_PACKAGE &&
            SelfArmSettingsNodePolicy.isWifiActivityClass(event.className?.toString())
        ) {
            wifiActivityObserved = true
        }
        if (!wifiConfirmed && wifiClickIssued) {
            schedule(WIFI_POLL_INTERVAL_MS)
            return
        }
        schedule(180L)
    }

    fun isManualTargetVisible(target: SelfArmManualTarget): Boolean {
        val root = AccessibilityWindowRoots.getNavigationRoot(
            service,
            AccessibilityWindowRoots.SETTINGS_PACKAGE,
        ) ?: return false
        return when (target) {
            SelfArmManualTarget.DEVELOPER_OPTIONS -> isDeveloperOptionsScreen(root)
            SelfArmManualTarget.WIRELESS_DEBUGGING,
            SelfArmManualTarget.PAIRING_DIALOG,
            -> isWirelessDebuggingPage(root)
            SelfArmManualTarget.ENABLE_DEVELOPER_OPTIONS -> false
        }
    }

    private fun step(expectedRunToken: Long, expectedSessionId: String) {
        if (!isLiveRun(expectedRunToken, expectedSessionId)) return
        beatLeaseIfDue()
        val now = SystemClock.uptimeMillis()
        if (phonePairingOffer.expire(now)) {
            Log.i(TAG, "phone-assisted pairing offer expired")
            finish(
                "phone_assisted_pairing_manual_step_needed",
                false,
                "PHONE-OFFER-EXPIRED",
            )
            return
        }
        // Neither deadline may cut a pairing that is actually running; see SelfArmPairingGracePolicy.
        val pairingInFlight = selfPairingSuspendsExpiry(now)
        if (!pairingInFlight && now > deadlineAt) {
            if (operationMode == OperationMode.WIFI_ONLY) {
                finish("wifi_enable_timeout", false)
            } else if (operationMode == OperationMode.MANUAL_NAVIGATION) {
                finish("manual_pairing_timeout", false)
            } else {
                finish(
                    "wireless_setup_timeout",
                    false,
                    sanitizeSupportDiagnostic("TMO: $lastReportedProgressState"),
                )
            }
            return
        }
        if (
            !pairingInFlight &&
            pairingReadyReported &&
            pairingReadyReportedAt > 0L &&
            now - pairingReadyReportedAt > pairingDialogHoldMs()
        ) {
            val diagnostic = when {
                localSelfPairingLastError.isNotBlank() ->
                    pairingFailureDiagnostic(localSelfPairingLastError)
                lastPairingPort <= 0 -> "PAIR-NOPORT"
                lastPairingConnectPort <= 0 -> "PAIR-NOTLS"
                else -> "PAIR-STALL"
            }
            finish("pairing_code_expired", false, diagnostic)
            return
        }
        renewDisplayHold(now, pairingInFlight)

        if (!wifiConfirmed) {
            stepWifi()
            return
        }

        val root = AccessibilityWindowRoots.getNavigationRoot(
            service,
            AccessibilityWindowRoots.SETTINGS_PACKAGE,
        )
        if (pairingReadyReported) {
            if (readPairingDialogFromAnyRoot(root)) return
            reportCachedPairingReady()
            schedule(PAIRING_DIALOG_POLL_MS)
            return
        }
        if (root == null) {
            if (fallbackFromUnverifiedDirectRouteIfDue()) return
            report("waiting_for_settings")
            schedule(STEP_DELAY_MS)
            return
        }

        if (operationMode == OperationMode.MANUAL_NAVIGATION) {
            if (manualTarget == SelfArmManualTarget.DEVELOPER_OPTIONS &&
                isDeveloperOptionsScreen(root)
            ) {
                report("opening_developer_options")
                schedule(MANUAL_HOLD_POLL_MS)
                return
            }
            if (manualTarget == SelfArmManualTarget.WIRELESS_DEBUGGING &&
                isWirelessDebuggingPage(root)
            ) {
                report("opening_wireless_debugging")
                schedule(MANUAL_HOLD_POLL_MS)
                return
            }
        }

        if (pairingRequested) {
            if (readPairingDialogFromAnyRoot(root)) return
            if (isWirelessDebuggingPage(root)) {
                report("waiting_for_pairing_code")
                schedule(PAIRING_DIALOG_POLL_MS)
                return
            }
            if (
                pairingRequestedAt > 0L &&
                SystemClock.uptimeMillis() - pairingRequestedAt > PAIRING_DIALOG_MAX_WAIT_MS
            ) {
                finish("wireless_debugging_manual_step_needed", false)
                return
            }
            report("waiting_for_pairing_code")
            schedule(PAIRING_DIALOG_POLL_MS)
            return
        }

        if (readPairingDialogFromAnyRoot(root)) return
        if (awaitingWirelessDebugConfirmation) {
            if (SelfArmWirelessAdbController.isEnabled(service)) {
                awaitingWirelessDebugConfirmation = false
                wirelessToggleRequestedAt = 0L
            } else if (clickConfirmation(root)) {
                awaitingWirelessDebugConfirmation = false
                wirelessToggleRequestedAt = 0L
                report("confirming_wireless_debugging")
                schedule(1200L)
                return
            } else if (
                wirelessToggleRequestedAt > 0L &&
                SystemClock.uptimeMillis() - wirelessToggleRequestedAt < WIRELESS_CONFIRMATION_WAIT_MS
            ) {
                report("waiting_for_wireless_debugging_confirmation")
                schedule(CONFIRMATION_POLL_MS)
                return
            } else {
                awaitingWirelessDebugConfirmation = false
                wirelessToggleRequestedAt = 0L
            }
        }

        firstEndpoint(root)?.let {
            lastConnectHost = it.host
            lastConnectPort = it.port
            android.util.Log.i(
                TAG,
                "selfarm-wireless wireless_debugging_open endpointDetected=true",
            )
        }

        when {
            isWirelessDebuggingPage(root) -> handleWirelessDebuggingPage(root)
            deviceInfoFallback -> handleDeviceInfoPage(root)
            isDeveloperOptionsDisabledPrompt(root) -> startDeveloperOptionsEnableFlow()
            !SelfArmWirelessAdbController.areDeveloperOptionsUsable(service) -> startDeveloperOptionsEnableFlow()
            !isDeveloperOptionsScreen(root) -> waitForDeveloperOptions(root)
            else -> {
                developerScreenSeen = true
                handleDeveloperOptionsPage(root)
            }
        }
    }

    private fun stepWifi() {
        if (wifiEnabled()) {
            onWifiEnabled()
            return
        }
        if (wifiClickIssued) {
            val elapsed = SystemClock.uptimeMillis() - lastClickAt
            if (elapsed < WIFI_CLICK_RETRY_WAIT_MS) {
                schedule(WIFI_POLL_INTERVAL_MS)
                return
            }
            if (wifiClickAttempts >= SelfArmWifiAutomationPolicy.MAX_TOGGLE_ATTEMPTS) {
                finish("wifi_enable_timeout", false)
                return
            }
            wifiClickIssued = false
        }

        val root = AccessibilityWindowRoots.getNavigationRoot(
            service,
            AccessibilityWindowRoots.SETTINGS_PACKAGE,
        )
        if (root == null) {
            schedule(STEP_DELAY_MS)
            return
        }
        if (!isWifiSettingsScreen(root)) {
            openWifiSettings()
            schedule(900L)
            return
        }
        if (
            clickWifiToggle(root) {
                sessionId.isBlank() ||
                    SelfArmSetupWifiOwnershipStore.markEnableIssued(
                        service.applicationContext,
                        sessionId,
                        requestInFlight = true,
                    )
            }
        ) {
            wifiClickIssued = true
            wifiClickAttempts++
            report("enabling_wifi")
            schedule(WIFI_POLL_INTERVAL_MS)
            return
        }
        when (
            settingsScroller.continueSearch(
                root = root,
                surface = ScrollSurface.WIFI_SETTINGS,
                maxBack = MAX_WIFI_SCROLLS,
                maxForward = MAX_WIFI_SCROLLS,
            )
        ) {
            ScrollSearchOutcome.WAITING,
            ScrollSearchOutcome.MOVED,
            ScrollSearchOutcome.PHASE_CHANGED,
            -> {
                wifiScrolls++
                schedule(settingsScroller.settleDelayMs)
                return
            }
            ScrollSearchOutcome.EXHAUSTED -> Unit
        }
        wifiScrolls = 0
        settingsScroller.reset(ScrollSurface.WIFI_SETTINGS)
        openWifiSettings()
        schedule(1200L)
    }

    private fun onWifiEnabled() {
        if (!isLiveRun()) return
        if (wifiConfirmed) return
        if (sessionId.isNotBlank()) {
            SelfArmSetupWifiOwnershipStore.markEnableRequestFinished(
                service.applicationContext,
                sessionId,
            )
            SelfArmSetupWifiOwnershipStore.discardUnissued(service.applicationContext, sessionId)
        }
        if (operationMode == OperationMode.WIFI_ONLY) {
            wifiConfirmed = true
            returnFromWifiSettings()
            finish("wifi_on", true)
            return
        }
        if (!SelfArmOnboardingStore.isWifiReady(service.applicationContext)) {
            service.onSetupWaitingForWifi(sessionId)
            return
        }
        wifiConfirmed = true
        report("wifi_on")
        if (!isLiveRun()) return
        runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        postForRun(800L) {
            if (!SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
                startDeveloperOptionsEnableFlow()
            } else {
                openPreferredSettingsTarget()
                schedule(900L)
            }
        }
    }

    private fun handleDeveloperOptionsPage(root: AccessibilityNodeInfo) {
        markDirectWirelessRouteVerifiedOrBypassed()
        if (clickSettingsLabel(root, SelfArmSettingsLabel.WIRELESS_DEBUGGING)) {
            settingsScroller.clearPending()
            report("opening_wireless_debugging")
            schedule(1100L)
            return
        }
        when (
            settingsScroller.continueSearch(
                root = root,
                surface = ScrollSurface.DEVELOPER_OPTIONS,
                maxBack = MAX_DEVELOPER_SCROLLS,
                maxForward = MAX_DEVELOPER_SCROLLS,
            )
        ) {
            ScrollSearchOutcome.WAITING,
            ScrollSearchOutcome.MOVED,
            ScrollSearchOutcome.PHASE_CHANGED,
            -> {
                report("searching_wireless_debugging")
                schedule(settingsScroller.settleDelayMs)
            }
            ScrollSearchOutcome.EXHAUSTED -> {
                if (developerScreenSeen) {
                    finish(
                        "wireless_debugging_manual_step_needed",
                        false,
                        "SCROLL_NO_PROGRESS",
                    )
                } else {
                    waitForDeveloperOptions(root)
                }
            }
        }
    }

    private fun waitForDeveloperOptions(root: AccessibilityNodeInfo) {
        if (fallbackFromUnverifiedDirectRouteIfDue()) return
        if (isDeveloperOptionsDisabledPrompt(root) || developerOpenAttemptsTimedOut()) {
            startDeveloperOptionsEnableFlow()
            return
        }
        if (!SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
            startDeveloperOptionsEnableFlow()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (developerOpenAttempts < MAX_DEVELOPER_OPEN_ATTEMPTS && now - lastDeveloperOpenAt > 2200L) {
            openPreferredSettingsTarget()
        }
        report("opening_developer_options")
        schedule(STEP_DELAY_MS)
    }

    private fun startDeveloperOptionsEnableFlow() {
        if (SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
            developerEnableFlow = false
            deviceInfoFallback = false
            openPreferredSettingsTarget()
            schedule(900L)
            return
        }
        developerEnableFlow = true
        deviceInfoFallback = true
        resetDirectWirelessRoute()
        report("developer_options_disabled")
        openDeviceInfoSettings()
        schedule(1000L)
    }

    private fun handleDeviceInfoPage(root: AccessibilityNodeInfo) {
        if (!developerEnableFlow) {
            finish("wireless_debugging_manual_step_needed", false)
            return
        }
        if (SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
            developerEnableFlow = false
            deviceInfoFallback = false
            openPreferredSettingsTarget()
            schedule(900L)
            return
        }
        val buildNumber = findBuildNumberByBuildIdentifier(root) ?: findFirst(root) {
            it.isVisibleToUser &&
                it.isEnabled &&
                settingsStrings.matchesExactly(rawText(it), SelfArmSettingsLabel.BUILD_NUMBER)
        }
        if (buildNumber != null && buildNumberTaps < MAX_BUILD_NUMBER_TAPS) {
            settingsScroller.clearPending()
            if (!canClickNow()) {
                schedule(220L)
                return
            }
            if (clickNode(buildNumber)) {
                buildNumberTaps++
                report("enabling_developer_options")
                schedule(500L)
                if (buildNumberTaps >= MAX_BUILD_NUMBER_TAPS) {
                    developerOpenAttempts = 0
                    developerOpenStartedAt = 0L
                    lastDeveloperOpenAt = 0L
                    developerEnableFlow = false
                    deviceInfoFallback = false
                    postForRun(1200L) { openPreferredSettingsTarget() }
                }
                return
            }
        }
        when (
            settingsScroller.continueSearch(
                root = root,
                surface = ScrollSurface.DEVICE_INFO,
                maxBack = MAX_DEVICE_INFO_SCROLLS,
                maxForward = MAX_DEVICE_INFO_SCROLLS,
            )
        ) {
            ScrollSearchOutcome.WAITING,
            ScrollSearchOutcome.MOVED,
            ScrollSearchOutcome.PHASE_CHANGED,
            -> {
                report("searching_build_number")
                schedule(settingsScroller.settleDelayMs)
            }
            ScrollSearchOutcome.EXHAUSTED ->
                finish("developer_options_manual_step_needed", false, "SCROLL_NO_PROGRESS")
        }
    }

    private fun handleWirelessDebuggingPage(root: AccessibilityNodeInfo) {
        markDirectWirelessRouteVerifiedOrBypassed()
        val switchBar = firstVisibleByViewId(root, SETTINGS_SWITCH_BAR_ID)
            ?.takeIf(::isUsableToggle)
        val switchRoot = switchBar ?: root
        val switchNode = WIRELESS_SWITCH_IDS.firstNotNullOfOrNull { viewId ->
            firstVisibleByViewId(switchRoot, viewId)?.takeIf(::isUsableToggle)
        }
        val switchText = findFirst(switchRoot) {
            it.isVisibleToUser &&
                it.isEnabled &&
                settingsStrings.matches(rawText(it), SelfArmSettingsLabel.WIRELESS_DEBUGGING)
        }
        val genericSwitch = findFirst(switchRoot) {
            val className = className(it).lowercase(Locale.ROOT)
            it.isVisibleToUser &&
                it.isEnabled &&
                it.isCheckable &&
                className.endsWith("switch")
        }
        if (!SelfArmWirelessAdbController.isEnabled(service) && canClickNow()) {
            val clicked = listOfNotNull(switchNode, switchBar, switchText, genericSwitch)
                .distinctBy { node ->
                    "${node.viewIdResourceName}|${node.className}|${rawText(node)}"
                }
                .any(::clickNode)
            if (clicked) {
                awaitingWirelessDebugConfirmation = true
                wirelessToggleRequestedAt = SystemClock.uptimeMillis()
                report("turning_wireless_debugging_on")
                schedule(1200L)
                return
            }
        }

        val livePort = SelfArmWirelessAdbController.readWirelessPort()
        if (livePort > 0) {
            lastConnectPort = livePort
            android.util.Log.i(
                TAG,
                "selfarm-wireless wireless_debugging_on connectPortKnown=true",
            )
        }

        if (
            !pairingRequested &&
            clickSettingsLabel(root, SelfArmSettingsLabel.PAIR_WITH_CODE)
        ) {
            settingsScroller.clearPending()
            pairingRequested = true
            pairingRequestedAt = SystemClock.uptimeMillis()
            pairingDialogDumped = false
            report("opening_pairing_code")
            schedule(1200L)
            return
        }
        when (
            settingsScroller.continueSearch(
                root = root,
                surface = ScrollSurface.WIRELESS_DEBUGGING,
                maxBack = MAX_WIRELESS_SCROLLS,
                maxForward = MAX_WIRELESS_SCROLLS,
            )
        ) {
            ScrollSearchOutcome.WAITING,
            ScrollSearchOutcome.MOVED,
            ScrollSearchOutcome.PHASE_CHANGED,
            -> report("searching_pairing_code")
            ScrollSearchOutcome.EXHAUSTED -> report("waiting_for_pairing_code")
        }
        schedule(settingsScroller.settleDelayMs)
    }

    private fun readPairingDialogFromAnyRoot(primary: AccessibilityNodeInfo?): Boolean {
        if (primary != null && readPairingDialog(primary)) return true
        return AccessibilityWindowRoots.anyReadableRoot(service) { root ->
            readPairingDialog(root)
        }
    }

    private fun readPairingDialog(root: AccessibilityNodeInfo): Boolean {
        val codeNode = firstVisibleByViewId(root, "com.android.settings:id/pairing_code")
        var code = codeNode?.let { firstCodeInText(rawText(it)) }.orEmpty()
        val endpoint = textByViewId(root, "com.android.settings:id/ip_addr")
        val hasPairingContext = codeNode != null || endpoint.isNotBlank() || hasPairingDialogText(root)
        if (!hasPairingContext) return false
        if (code.isBlank()) code = firstCode(root)

        var host = ""
        var pairPort = 0
        if (endpoint.isNotBlank()) {
            val matcher = IPV4_ENDPOINT.matcher(endpoint)
            if (matcher.find()) {
                host = matcher.group(1).orEmpty()
                pairPort = parsePort(matcher.group(2))
            }
        }
        if (host.isBlank() || pairPort <= 0) {
            firstEndpoint(root)?.let {
                host = it.host
                pairPort = it.port
            }
        }
        if (pairingRequested && !pairingDialogDumped && code.isNotBlank()) {
            pairingDialogDumped = true
            dumpPairingDialogNodes(root)
        }
        if (pairPort <= 0 && code.isNotBlank()) {
            pairPort = firstStandalonePort(root, code)
        }
        if (host.isBlank() && lastConnectHost.isNotBlank()) host = lastConnectHost
        if (host.isBlank()) host = wifiIpv4()

        val connectPort = SelfArmWirelessAdbController.readWirelessPort()
            .takeIf { it > 0 }
            ?: lastConnectPort
        if (code.isBlank()) return false
        if (pairPort <= 0) {
            val now = SystemClock.uptimeMillis()
            if (!pairingReadyReported) {
                if (pairingCodeOnlySeenAt == 0L) pairingCodeOnlySeenAt = now
                if (now - pairingCodeOnlySeenAt < PAIRING_PORT_GRACE_MS) {
                    report("waiting_for_pairing_code")
                    schedule(PAIRING_DIALOG_POLL_MS)
                    return true
                }
            }
            return reportPairingReadyAndHold(code, host, 0, connectPort, "ADB pairing code ready")
        }
        return reportPairingReadyAndHold(code, host, pairPort, connectPort, "ADB pairing ready")
    }

    private fun hasPairingDialogText(root: AccessibilityNodeInfo): Boolean =
        containsSettingsLabel(root, SelfArmSettingsLabel.PAIRING_DIALOG_TITLE) ||
            containsSettingsLabel(root, SelfArmSettingsLabel.PAIRING_CODE_LABEL) ||
            containsSettingsLabel(root, SelfArmSettingsLabel.IP_ADDRESS_AND_PORT)

    private fun reportPairingReadyAndHold(
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
        feedback: String,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val token = "$code|$host|$pairPort|$connectPort"
        lastPairingCode = code
        lastPairingHost = host
        lastPairingPort = pairPort
        lastPairingConnectPort = connectPort
        lastConnectHost = host.ifBlank { lastConnectHost }
        lastConnectPort = connectPort
        if (connectPort > 0) reportKnownConnectPort(connectPort)
        pairingRequested = true
        awaitingWirelessDebugConfirmation = false
        deviceInfoFallback = false
        developerEnableFlow = false
        if (!pairingReadyReported) {
            pairingReadyReported = true
            pairingReadyReportedAt = now
            android.util.Log.i(TAG, feedback)
        }
        if (token != lastPairingReadyToken || now - lastPairingReadyReportAt >= PAIRING_READY_REPORT_INTERVAL_MS) {
            if (maybeStartLocalSelfPairing(token, code, host, pairPort, connectPort)) {
                schedule(PAIRING_DIALOG_POLL_MS)
                return true
            }
            sendPairingReadyStatus(token, code, host, pairPort, connectPort)
        }
        schedule(PAIRING_DIALOG_POLL_MS)
        return true
    }

    private fun maybeStartLocalSelfPairing(
        token: String,
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
    ): Boolean {
        if (operationMode == OperationMode.MANUAL_NAVIGATION) return false
        if (localSelfPairingComplete) return true
        if (localSelfPairingRunning && localSelfPairingToken == token) {
            reportLocalSelfPairingStarted(host, pairPort, connectPort)
            return true
        }
        if (localSelfPairingFailedToken == token) return false
        if (localSelfPairingRunning || code.length != 6 || pairPort <= 0 || connectPort <= 0) return false

        localSelfPairingToken = token
        localSelfPairingRunning = true
        localSelfPairingStartedAt = SystemClock.uptimeMillis()
        localSelfPairingLastError = ""
        reportLocalSelfPairingStarted(host, pairPort, connectPort)
        val workerSessionId = sessionId
        val workerRunToken = runToken
        val worker = Thread {
            val result = runCatching {
                SelfArmLocalAdbBootstrapper(service.applicationContext).bootstrap(
                    pairPort = pairPort,
                    pairingCode = code,
                    connectPort = connectPort,
                    sessionId = workerSessionId,
                )
            }
            postForRun(workerRunToken, workerSessionId, 0L) {
                if (localSelfPairingToken == token) {
                    localSelfPairingRunning = false
                    localSelfPairingStartedAt = 0L
                    localSelfPairingThread = null
                    result.onSuccess { bootstrap ->
                        localSelfPairingComplete = true
                        lastConnectHost = bootstrap.connectHost
                        lastConnectPort = bootstrap.connectPort
                        Log.i(
                            TAG,
                            "local self-pair bootstrap complete",
                        )
                        finish("wireless_bootstrap_complete", true)
                    }.onFailure { throwable ->
                        localSelfPairingFailedToken = token
                        localSelfPairingLastError = causeChainMessage(throwable)
                        val diagnostic = pairingFailureDiagnostic(localSelfPairingLastError)
                        Log.w(TAG, "local self-pair bootstrap failed: $diagnostic")
                        if (isLiveRun(workerRunToken, workerSessionId)) {
                            offerPairingViaPhone(code, host, pairPort, connectPort)
                        }
                    }
                }
            }
        }.apply {
            name = "RokidNexusLocalWirelessSelfArm"
            isDaemon = true
        }
        localSelfPairingThread = worker
        worker.start()
        report("self_pairing_in_progress")
        return true
    }

    private fun offerPairingViaPhone(
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
    ) {
        if (!isLiveRun() || phonePairingOffer.hasOutstanding()) {
            schedule(PAIRING_DIALOG_POLL_MS)
            return
        }
        val currentSessionId =
            SelfArmOnboardingStore.currentActiveSessionId(service.applicationContext)
        if (currentSessionId.isBlank() || currentSessionId != sessionId) return
        if (!GlassesHub.supportsPhoneAssistedSetup()) {
            Log.i(TAG, "phone-assisted pairing unavailable reason=UNSUPPORTED")
            finish(
                "phone_assisted_pairing_manual_step_needed",
                false,
                "PHONE-UNSUPPORTED",
            )
            return
        }

        val issuedAt = System.currentTimeMillis()
        val offer = SetupPairingOfferContract.createOffer(
            sessionId = currentSessionId,
            issuedAt = issuedAt,
            expiresAt = issuedAt + SetupPairingOfferContract.MAX_TTL_MS,
            host = host,
            pairingPort = pairPort,
            connectPort = connectPort,
            pairingCode = code,
        )
        val ttlMillis = offer?.let(SetupPairingOfferContract::ttlMillis)
        val startedAt = SystemClock.uptimeMillis()
        if (offer == null || ttlMillis == null ||
            !phonePairingOffer.begin(
                currentSessionId,
                offer.offerId,
                startedAt,
                ttlMillis,
            )
        ) {
            Log.i(TAG, "phone-assisted pairing unavailable reason=INVALID_OFFER")
            finish(
                "phone_assisted_pairing_manual_step_needed",
                false,
                "PHONE-INVALID-OFFER",
            )
            return
        }

        report(SetupStage.PAIRING_VIA_PHONE)
        val sent = GlassesHub.sendToPhone(
            BusPaths.GLASSES_SETUP_PAIRING_OFFER,
            SetupPairingOfferContract.offerToJson(offer),
        )
        if (!sent) {
            phonePairingOffer.clear()
            Log.i(TAG, "phone-assisted pairing offer rejected reason=NO_LINK")
            finish(
                "phone_assisted_pairing_manual_step_needed",
                false,
                "PHONE-NO-LINK",
            )
            return
        }
        lastPhoneAssistedConnectPortReport = connectPort
        Log.i(TAG, "phone-assisted pairing offer sent")
        schedule(PAIRING_DIALOG_POLL_MS)
    }

    private fun reportKnownConnectPort(connectPort: Int) {
        GlassesHub.reportWirelessConnectPort(connectPort)
        val correlation = phonePairingOffer.correlation() ?: return
        if (lastPhoneAssistedConnectPortReport == connectPort) return
        if (GlassesHub.reportPhoneAssistedConnectPort(correlation, connectPort)) {
            lastPhoneAssistedConnectPortReport = connectPort
        }
    }

    internal fun onPhoneAssistedPairingResult(result: SetupPairingResult): Boolean {
        if (!isLiveRun() || !phonePairingOffer.resolve(result)) return false
        val resultSessionId = sessionId
        if (!SelfArmOnboardingStore.isCurrentSession(
                service.applicationContext,
                resultSessionId,
            )
        ) {
            return false
        }
        if (!result.ok) {
            finish(
                "phone_assisted_pairing_manual_step_needed",
                false,
                "PHONE-${result.reason}",
            )
            return true
        }

        releaseDisplayHold(SetupDisplayHoldEnd.SUCCEEDED)
        service.returnToOnboarding(resultSessionId)
        service.onWirelessBootstrapFinished(resultSessionId)
        cancelLiveWork()
        SelfArmPhoneArmConfirmation.confirm(
            context = service.applicationContext,
            sessionId = resultSessionId,
            completionMode = SetupCompletionMode.PHONE_ASSISTED,
            onFailure = {
                if (SelfArmOnboardingStore.isCurrentSession(
                        service.applicationContext,
                        resultSessionId,
                    )
                ) {
                    SelfArmOnboardingStore.finish(
                        context = service.applicationContext,
                        sessionId = resultSessionId,
                        setupState = "phone_assisted_verification_manual_step_needed",
                        success = false,
                        diagnostic = "PHONE-VERIFY",
                    )
                }
            },
        )
        return true
    }

    private fun reportLocalSelfPairingStarted(host: String, pairPort: Int, connectPort: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLocalSelfPairingStatusAt < PAIRING_READY_REPORT_INTERVAL_MS) return
        lastLocalSelfPairingStatusAt = now
        android.util.Log.i(
            TAG,
            "selfarm-wireless self_pairing_started endpointComplete=" +
                (host.isNotBlank() && pairPort > 0 && connectPort > 0),
        )
    }

    private fun sendPairingReadyStatus(
        token: String,
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
    ) {
        lastPairingReadyToken = token
        lastPairingReadyReportAt = SystemClock.uptimeMillis()
        if (operationMode == OperationMode.MANUAL_NAVIGATION) report("manual_pairing_waiting")
        android.util.Log.i(
            TAG,
            "selfarm-wireless pairing_ready codeLen=${code.length} " +
                "endpointComplete=${host.isNotBlank() && pairPort > 0 && connectPort > 0}",
        )
    }

    private fun reportCachedPairingReady() {
        if (lastPairingCode.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPairingReadyReportAt < PAIRING_READY_REPORT_INTERVAL_MS) return
        lastPairingReadyReportAt = now
        android.util.Log.i(
            TAG,
            "selfarm-wireless pairing_ready codeLen=${lastPairingCode.length} " +
                "endpointComplete=${lastPairingHost.isNotBlank() && lastPairingPort > 0 && lastPairingConnectPort > 0}",
        )
    }

    private fun dumpPairingDialogNodes(root: AccessibilityNodeInfo) {
        val builder = StringBuilder("pairingDialog DUMP:")
        collectNodeStrings(root, builder, 0)
        Log.d(TAG, builder.toString())
    }

    private fun collectNodeStrings(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null) return
        val text = rawText(node)
        val viewId = node.viewIdResourceName.orEmpty()
        if (text.isNotBlank() || viewId.isNotBlank()) {
            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            out.append("\n  depth=")
                .append(depth)
                .append(" viewId=")
                .append(viewId.ifBlank { "(none)" })
                .append(" class=")
                .append(className(node).ifBlank { "(none)" })
                .append(" bounds=")
                .append(bounds.toShortString())
                .append(" visible=")
                .append(node.isVisibleToUser)
                .append(" clickable=")
                .append(node.isClickable)
                .append(" textLength=")
                .append(text.length)
        }
        for (index in 0 until node.childCount) {
            collectNodeStrings(node.getChild(index), out, depth + 1)
        }
    }

    private fun clickConfirmation(root: AccessibilityNodeInfo): Boolean {
        if (!isWirelessDebuggingConfirmation(root)) return false
        val button = CONFIRM_BUTTON_IDS.firstNotNullOfOrNull { viewId ->
            firstVisibleByViewId(root, viewId)?.takeIf { node ->
                node.isEnabled && node.isClickable
            }
        } ?: findFirst(root) { node ->
            node.isClickable &&
                className(node).endsWith("Button") &&
                normalizedText(node) in POSITIVE_BUTTON_FALLBACKS
        }
        return button != null && canClickNow() && clickNode(button)
    }

    private fun isWirelessDebuggingConfirmation(root: AccessibilityNodeInfo): Boolean =
        isSettingsRoot(root) &&
            CONFIRM_BUTTON_IDS.any { viewId ->
                firstVisibleByViewId(root, viewId)?.let { it.isEnabled && it.isClickable } == true
            } &&
            containsSettingsLabel(root, SelfArmSettingsLabel.WIRELESS_DEBUGGING)

    private fun isWirelessDebuggingPage(root: AccessibilityNodeInfo): Boolean =
        isSettingsRoot(root) &&
            firstVisibleByViewId(root, SETTINGS_SWITCH_BAR_ID) != null &&
            (
                settingsStrings.matches(
                    subtreeText(firstVisibleByViewId(root, SETTINGS_APP_BAR_ID)),
                    SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                ) ||
                    settingsStrings.matches(
                        textByViewId(root, SETTINGS_SWITCH_TEXT_ID),
                        SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                    )
            )

    private fun isDeveloperOptionsScreen(root: AccessibilityNodeInfo): Boolean =
        isSettingsRoot(root) &&
            (
                settingsStrings.matches(
                    subtreeText(firstVisibleByViewId(root, SETTINGS_APP_BAR_ID)),
                    SelfArmSettingsLabel.DEVELOPER_OPTIONS,
                ) ||
                    (
                        firstVisibleByViewId(root, SETTINGS_SWITCH_BAR_ID) != null &&
                            containsSettingsLabel(root, SelfArmSettingsLabel.DEVELOPER_OPTIONS)
                    )
            )

    private fun isDeveloperOptionsDisabledPrompt(root: AccessibilityNodeInfo): Boolean =
        containsSettingsLabel(root, SelfArmSettingsLabel.DEVELOPER_OPTIONS_DISABLED)

    private fun clickWifiToggle(
        root: AccessibilityNodeInfo,
        beforeClick: () -> Boolean,
    ): Boolean {
        if (!isWifiSettingsScreen(root)) return false
        val target = wifiToggleByStableId(root)
        return target != null && canClickNow() && beforeClick() && clickNode(target)
    }

    private fun isWifiSettingsScreen(root: AccessibilityNodeInfo): Boolean =
        SelfArmSettingsNodePolicy.isWifiScreen(
            settingsPackage = isSettingsRoot(root),
            openedByAutomator = wifiSettingsOpened,
            wifiActivityObserved = wifiActivityObserved,
            hasStableToggleId = wifiToggleByStableId(root) != null,
        )

    private fun wifiToggleByStableId(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { node ->
            node.isVisibleToUser &&
                node.isEnabled &&
                SelfArmSettingsNodePolicy.isWifiToggleId(node.viewIdResourceName) &&
                hasClickableAncestor(node)
        }

    private fun isUsableToggle(node: AccessibilityNodeInfo): Boolean =
        SelfArmSettingsNodePolicy.isUsableToggle(
            visible = node.isVisibleToUser,
            enabled = node.isEnabled,
            checkable = node.isCheckable,
            clickable = node.isClickable,
        )

    private fun clickSettingsLabel(
        root: AccessibilityNodeInfo,
        label: SelfArmSettingsLabel,
    ): Boolean {
        val content = firstVisibleByViewId(root, SETTINGS_RECYCLER_ID) ?: root
        val target = findFirst(content) { node ->
            SelfArmSettingsNodePolicy.isActionTitle(
                visible = node.isVisibleToUser,
                enabled = node.isEnabled,
                viewId = node.viewIdResourceName,
                className = className(node),
                exactLabelMatch = settingsStrings.matchesExactly(rawText(node), label),
                hasClickableAncestor = hasClickableAncestor(node),
            )
        }
        return target != null && canClickNow() && clickNode(target)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!isLiveRun()) return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val candidate = current ?: return false
            if (
                candidate.isVisibleToUser &&
                candidate.isEnabled &&
                candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                lastClickAt = SystemClock.uptimeMillis()
                return true
            }
            current = candidate.parent
        }
        return false
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_CLICK_ANCESTOR_DEPTH) {
            if (current.isClickable) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun openPreferredSettingsTarget() {
        val wantsWirelessPage =
            operationMode != OperationMode.MANUAL_NAVIGATION ||
                manualTarget == SelfArmManualTarget.WIRELESS_DEBUGGING ||
                manualTarget == SelfArmManualTarget.PAIRING_DIALOG
        if (!wantsWirelessPage) {
            report("opening_developer_options")
            openDeveloperSettings()
            return
        }
        report("opening_wireless_debugging")
        settingsScroller.reset(ScrollSurface.DEVELOPER_OPTIONS)
        settingsScroller.reset(ScrollSurface.WIRELESS_DEBUGGING)
        if (!directWirelessFallbackUsed && !directWirelessProbePending) {
            noteDeveloperOpenAttempt()
            if (
                SelfArmManualSettingsLauncher.open(
                    service.applicationContext,
                    SelfArmManualTarget.WIRELESS_DEBUGGING,
                )
            ) {
                directWirelessProbePending = true
                directWirelessProbeStartedAt = SystemClock.uptimeMillis()
                return
            }
            directWirelessFallbackUsed = true
        }
        directWirelessProbePending = false
        noteDeveloperOpenAttempt()
        startDeveloperSettingsIntent()
    }

    private fun fallbackFromUnverifiedDirectRouteIfDue(): Boolean {
        if (
            !SelfArmDirectSettingsRoutePolicy.shouldFallback(
                pending = directWirelessProbePending,
                startedAt = directWirelessProbeStartedAt,
                now = SystemClock.uptimeMillis(),
                verificationWindowMs = DIRECT_WIRELESS_VERIFICATION_MS,
            )
        ) {
            if (directWirelessProbePending) {
                schedule(DIRECT_WIRELESS_VERIFICATION_POLL_MS)
                return true
            }
            return false
        }
        directWirelessProbePending = false
        directWirelessFallbackUsed = true
        // The ROM took the direct fragment intent and put us somewhere else. From here the run
        // depends on scrolling the developer options list, which is the part that does not always
        // work -- so it is worth writing down either way.
        SelfArmOnboardingStore.note(
            service.applicationContext,
            sessionId,
            SetupNote.DIRECT_ROUTE_REDIRECTED,
            if (operationMode == OperationMode.MANUAL_NAVIGATION) {
                "wireless debugging: direct route redirected, handing back to the wearer"
            } else {
                "wireless debugging: falling back to the developer options traversal"
            },
        )
        // Someone who pressed this button is already in the manual flow: they have accepted doing
        // it by hand. Replaying the traversal that the automatic run just failed at would hold
        // them on "Waiting for the glasses…" for up to the five-minute manual window to arrive at
        // the same place. Give up now so the screen can tell them how to open it themselves.
        if (operationMode == OperationMode.MANUAL_NAVIGATION) {
            finish("wireless_debugging_direct_route_failed", false)
            return true
        }
        report("opening_developer_options")
        noteDeveloperOpenAttempt()
        settingsScroller.reset(ScrollSurface.DEVELOPER_OPTIONS)
        startDeveloperSettingsIntent()
        schedule(900L)
        return true
    }

    private fun markDirectWirelessRouteVerifiedOrBypassed() {
        directWirelessProbePending = false
        directWirelessProbeStartedAt = 0L
        directWirelessFallbackUsed = true
    }

    private fun resetDirectWirelessRoute() {
        directWirelessProbePending = false
        directWirelessProbeStartedAt = 0L
        directWirelessFallbackUsed = false
    }

    private fun openDeveloperSettings() {
        noteDeveloperOpenAttempt()
        settingsScroller.reset(ScrollSurface.DEVELOPER_OPTIONS)
        startDeveloperSettingsIntent()
    }

    private fun noteDeveloperOpenAttempt() {
        val now = SystemClock.uptimeMillis()
        developerOpenAttempts++
        lastDeveloperOpenAt = now
        if (developerOpenStartedAt == 0L) developerOpenStartedAt = now
    }

    private fun startDeveloperSettingsIntent() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .setPackage(AccessibilityWindowRoots.SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (tryStart(intent)) return
        tryStart(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun openDeviceInfoSettings() {
        settingsScroller.reset(ScrollSurface.DEVICE_INFO)
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            .setPackage(AccessibilityWindowRoots.SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(intent)) return
        if (
            tryStart(
                Intent()
                    .setComponent(
                        ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings\$MyDeviceInfoActivity",
                        ),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        ) {
            return
        }
        tryStart(
            Intent()
                .setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$DeviceInfoSettingsActivity",
                    ),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openWifiSettings() {
        settingsScroller.reset(ScrollSurface.WIFI_SETTINGS)
        wifiSettingCandidates().forEach { candidate ->
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(candidate)) {
                wifiSettingsOpened = true
                return
            }
        }
    }

    /**
     * Leave Settings behind before handing the lens back to the wearer.
     *
     * A run walks through Wi-Fi settings, developer options and wireless debugging, and every one
     * of those pages stays on the Settings task. returnToOnboarding then brings Nexus forward in
     * its own task and draws over them, so the wearer sees the right screen and has no idea what
     * is underneath -- until they press back and fall into the pile one page at a time, never
     * reaching the launcher. Which is exactly what a first run left behind: Wi-Fi settings,
     * developer options twice, and the wireless debugging page on top.
     *
     * Home is the one action that gets out whatever depth we reached, without having to count the
     * pages we opened or guess how many the firmware added.
     */
    private fun leaveSettings() {
        wifiSettingsOpened = false
        runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    private fun returnFromWifiSettings() {
        if (!isLiveRun()) return
        if (!wifiSettingsOpened) return
        wifiSettingsOpened = false
        runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun wifiSettingCandidates(): List<Intent> {
        val candidates = mutableListOf<Intent>()
        // YodaOS exposes the full Wi-Fi page reliably, while its Android Q Wi-Fi panel can
        // immediately return to the launcher and does not expose a usable accessibility switch.
        // Match the proven R08 Access Bridge flow: full Wi-Fi Settings first, panel last.
        candidates += Intent(Settings.ACTION_WIFI_SETTINGS)
        candidates += Intent(Settings.ACTION_WIFI_SETTINGS)
            .setPackage("com.android.settings")
        candidates += Intent()
            .setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$WifiSettingsActivity",
                ),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            candidates += Intent("android.settings.panel.action.WIFI")
                .setPackage("com.android.settings")
            candidates += Intent("android.settings.panel.action.WIFI")
                .setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.panel.SettingsPanelActivity",
                    ),
                )
        }
        return candidates
    }

    private fun developerOpenAttemptsTimedOut(): Boolean =
        !developerScreenSeen &&
            developerOpenAttempts >= MAX_DEVELOPER_OPEN_ATTEMPTS &&
            developerOpenStartedAt > 0L &&
            SystemClock.uptimeMillis() - developerOpenStartedAt >= DEVELOPER_OPEN_TIMEOUT_MS

    private fun tryStart(intent: Intent): Boolean {
        if (!isLiveRun()) return false
        return try {
            service.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Log.d(TAG, "settings target unavailable: $intent")
            false
        } catch (exception: RuntimeException) {
            Log.w(TAG, "settings launch failed: $intent", exception)
            false
        }
    }

    private fun firstVisibleByViewId(
        root: AccessibilityNodeInfo,
        viewId: String,
    ): AccessibilityNodeInfo? =
        runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId)
                ?.firstOrNull { node -> node.isVisibleToUser }
        }.getOrNull()

    private fun findFirst(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            findFirst(root.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun containsSettingsLabel(
        root: AccessibilityNodeInfo,
        label: SelfArmSettingsLabel,
    ): Boolean =
        findFirst(root) { node ->
            node.isVisibleToUser &&
                settingsStrings.matches(rawText(node), label)
        } != null

    private fun isSettingsRoot(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == AccessibilityWindowRoots.SETTINGS_PACKAGE

    private fun findBuildNumberByBuildIdentifier(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) {
            it !== root &&
                it.isVisibleToUser &&
                it.isEnabled &&
                it.isClickable &&
                settingsStrings.matches(
                    subtreeText(it),
                    SelfArmSettingsLabel.BUILD_NUMBER,
                ) &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    subtreeText(it),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        } ?: findFirst(root) {
            it !== root &&
                it.isVisibleToUser &&
                it.isEnabled &&
                it.isClickable &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    subtreeText(it),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        } ?: findFirst(root) {
            it !== root &&
                it.isVisibleToUser &&
                it.isEnabled &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    rawText(it),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        }

    private fun textByViewId(root: AccessibilityNodeInfo, viewId: String): String =
        firstVisibleByViewId(root, viewId)?.let { rawText(it) }.orEmpty()

    private fun firstCode(root: AccessibilityNodeInfo): String {
        val node = findFirst(root) { PAIRING_CODE.matcher(rawText(it)).find() } ?: return ""
        return firstCodeInText(rawText(node))
    }

    private fun firstCodeInText(text: String): String {
        val matcher = PAIRING_CODE.matcher(text)
        return if (matcher.find()) matcher.group(1).orEmpty() else ""
    }

    private fun firstEndpoint(root: AccessibilityNodeInfo): Endpoint? {
        val node = findFirst(root) { IPV4_ENDPOINT.matcher(rawText(it)).find() } ?: return null
        val matcher = IPV4_ENDPOINT.matcher(rawText(node))
        if (!matcher.find()) return null
        val port = parsePort(matcher.group(2))
        return if (port > 0) Endpoint(matcher.group(1).orEmpty(), port) else null
    }

    private fun firstStandalonePort(root: AccessibilityNodeInfo, code: String): Int {
        val allTexts = mutableListOf<String>()
        collectAllTexts(root, allTexts)
        allTexts.forEach { text ->
            val matcher = STANDALONE_PORT.matcher(text)
            while (matcher.find()) {
                val digits = matcher.group(1).orEmpty()
                if (digits == code || digits.length == 6) continue
                val port = parsePort(digits)
                if (port in 1024..65535) return port
            }
        }
        return 0
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        rawText(node).takeIf { it.isNotBlank() }?.let { out += it }
        for (index in 0 until node.childCount) {
            collectAllTexts(node.getChild(index), out)
        }
    }

    private fun subtreeText(node: AccessibilityNodeInfo?): String {
        val allTexts = mutableListOf<String>()
        collectAllTexts(node, allTexts)
        return allTexts.joinToString(" ")
    }

    private fun rawText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = node.text?.takeIf { it.isNotEmpty() } ?: node.contentDescription
        return text?.toString()?.trim().orEmpty()
    }

    private fun normalizedText(node: AccessibilityNodeInfo): String =
        normalize(rawText(node))

    private fun normalize(value: String): String =
        SelfArmSettingsTextMatcher.normalize(value)

    private fun className(node: AccessibilityNodeInfo?): String =
        node?.className?.toString().orEmpty()

    private fun parsePort(value: String?): Int =
        value?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0

    private fun shortMessage(throwable: Throwable): String =
        throwable.message.orEmpty().trim().ifBlank { throwable::class.java.simpleName }

    /**
     * Walk the cause chain so the sanitized support detail keeps the underlying KADB/socket reason
     * (e.g. "connection closed", "Connection refused") instead of only a generic wrapper.
     */
    private fun causeChainMessage(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current) && parts.size < 5) {
            val simpleName = current::class.java.simpleName.ifBlank { "Throwable" }
            val message = current.message.orEmpty().trim()
            val piece = if (message.isBlank()) simpleName else "$simpleName: $message"
            if (parts.isEmpty() || parts.last() != piece) parts.add(piece)
            current = current.cause
        }
        return parts.joinToString(" <- ").take(400).ifBlank { "self pairing failed" }
    }

    private fun wifiEnabled(): Boolean =
        wifiManager()?.isWifiEnabled == true

    private fun wifiManager(): WifiManager? =
        runCatching {
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()

    private fun wifiIpv4(): String =
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val name = networkInterface.name.lowercase(Locale.US)
                if (!(name == "wlan0" || name.startsWith("wlan") || name.contains("wifi"))) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress.orEmpty()
                    if (address is Inet4Address && isPrivateLanAddress(host)) return@runCatching host
                }
            }
            ""
        }.getOrDefault("")

    private fun isPrivateLanAddress(host: String): Boolean {
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        val parts = host.split(".")
        if (parts.size < 2) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 172 && second in 16..31
    }

    private fun report(setupState: String) {
        if (!isLiveRun()) return
        lastReportedProgressState = setupState
        if (operationMode != OperationMode.WIFI_ONLY || sessionId.isNotBlank()) {
            if (!SelfArmOnboardingStore.isCurrentSession(service.applicationContext, sessionId)) return
            SelfArmOnboardingStore.reportProgress(
                service.applicationContext,
                sessionId,
                setupState,
            )
        }
        val wifiReady = wifiIpv4().isNotBlank() ||
            lastPairingHost.isNotBlank() ||
            lastConnectHost.isNotBlank()
        val connectPortKnown =
            SelfArmWirelessAdbController.readWirelessPort() > 0 || lastConnectPort > 0
        android.util.Log.i(
            TAG,
            "selfarm-wireless $setupState wifiReady=$wifiReady " +
                "connectPortKnown=$connectPortKnown",
        )
    }

    private fun finish(setupState: String, success: Boolean, diagnostic: String = "") {
        if (!isLiveRun()) return
        releaseDisplayHold(SetupDisplayHoldEnd.forFinish(setupState, success))
        val finishingMode = operationMode
        val finishingSessionId = sessionId
        report(setupState)
        if (operationMode == OperationMode.WIFI_ONLY) {
            returnFromWifiSettings()
            cancelLiveWork()
            android.util.Log.i(TAG, if (success) "Wi-Fi enabled" else "Wi-Fi enable needs a tap")
            service.onWifiEnableFinished(success, finishingSessionId)
            return
        }
        if (operationMode == OperationMode.MANUAL_NAVIGATION) {
            if (!SelfArmOnboardingStore.isCurrentSession(service.applicationContext, finishingSessionId)) return
            SelfArmOnboardingStore.pause(
                service.applicationContext,
                finishingSessionId,
                setupState,
            )
            // Terminal for the manual flow (timeout or explicit close): release the assets.
            SelfArmOnboardingStore.clearManualArmInProgress(service.applicationContext)
            SelfArmManualArmAssets.cleanup(service.applicationContext)
            if (!SelfArmOnboardingStore.isCurrentSession(service.applicationContext, finishingSessionId)) return
            service.returnToOnboarding(finishingSessionId)
            cancelLiveWork()
            service.onManualNavigationFinished(finishingSessionId)
            return
        }
        if (!SelfArmOnboardingStore.isCurrentSession(service.applicationContext, finishingSessionId)) return
        leaveSettings()
        service.returnToOnboardingAfter(finishingSessionId, SETTINGS_EXIT_SETTLE_MS)
        service.onWirelessBootstrapFinished(finishingSessionId)
        if (!SelfArmOnboardingStore.isCurrentSession(service.applicationContext, finishingSessionId)) return
        SelfArmOnboardingStore.finish(
            context = service.applicationContext,
            sessionId = finishingSessionId,
            setupState = setupState,
            success = success,
            diagnostic = diagnostic,
            completionMode = if (success) {
                SetupCompletionMode.AUTOMATIC
            } else {
                SetupCompletionMode.UNKNOWN
            },
        )
        if (finishingMode == OperationMode.FULL_BOOTSTRAP) cancelLiveWork()
        android.util.Log.i(TAG, if (success) "Wireless Debugging ready" else "Wireless setup needs a tap")
    }

    private fun canClickNow(): Boolean =
        SystemClock.uptimeMillis() - lastClickAt >= CLICK_COOLDOWN_MS

    private fun schedule(delayMs: Long) {
        if (!isLiveRun()) return
        val requestedAt = SystemClock.uptimeMillis() + delayMs.coerceAtLeast(0L)
        val nextAt = SelfArmTickSchedulePolicy.nextScheduledAt(scheduledStepAt, requestedAt)
        if (nextAt == scheduledStepAt) return
        scheduledStepRunnable?.let(handler::removeCallbacks)
        scheduledStepAt = nextAt
        val expectedRunToken = runToken
        val expectedSessionId = sessionId
        val runnable = Runnable {
            if (!isLiveRun(expectedRunToken, expectedSessionId)) {
                releaseDisplayHold(SetupDisplayHoldEnd.SESSION_ENDED, expectedRunToken)
                return@Runnable
            }
            scheduledStepAt = SelfArmTickSchedulePolicy.NONE
            scheduledStepRunnable = null
            step(expectedRunToken, expectedSessionId)
        }
        scheduledStepRunnable = runnable
        handler.postAtTime(runnable, callbackToken, nextAt)
    }

    private fun postForRun(
        delayMs: Long,
        action: () -> Unit,
    ) {
        postForRun(runToken, sessionId, delayMs, action)
    }

    private fun postForRun(
        expectedRunToken: Long,
        expectedSessionId: String,
        delayMs: Long,
        action: () -> Unit,
    ) {
        val runnable = Runnable {
            if (isLiveRun(expectedRunToken, expectedSessionId)) {
                action()
            } else {
                releaseDisplayHold(SetupDisplayHoldEnd.SESSION_ENDED, expectedRunToken)
            }
        }
        handler.postAtTime(
            runnable,
            callbackToken,
            SystemClock.uptimeMillis() + delayMs.coerceAtLeast(0L),
        )
    }

    private fun beatLeaseIfDue(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val sessionCurrent = sessionId.isNotBlank() &&
            SelfArmOnboardingStore.isCurrentSession(service.applicationContext, sessionId)
        if (!SelfArmHeartbeatPolicy.shouldHeartbeat(
                sessionCurrent = sessionCurrent,
                automatonActive = active,
                workerAlive = localSelfPairingThread?.isAlive == true ||
                    phonePairingOffer.hasOutstanding(),
                nowMillis = now,
                lastHeartbeatMillis = lastHeartbeatAt,
                cadenceMillis = SelfArmOnboardingStore.HEARTBEAT_CADENCE_MS,
                force = force,
            )
        ) {
            return
        }
        SelfArmOnboardingStore.heartbeat(service.applicationContext, sessionId)
        lastHeartbeatAt = now
    }

    private fun isLiveRun(
        expectedRunToken: Long = runToken,
        expectedSessionId: String = sessionId,
    ): Boolean {
        if (!active || expectedRunToken != runToken || expectedSessionId != sessionId) return false
        if (expectedSessionId.isBlank()) return operationMode == OperationMode.WIFI_ONLY
        return SelfArmOnboardingStore.isCurrentSession(
            service.applicationContext,
            expectedSessionId,
        )
    }

    private fun cancelLiveWork() {
        active = false
        runToken++
        handler.removeCallbacksAndMessages(callbackToken)
        scheduledStepRunnable = null
        scheduledStepAt = SelfArmTickSchedulePolicy.NONE
        settingsScroller.clearPending()
        directWirelessProbePending = false
        localSelfPairingThread
            ?.takeIf { it !== Thread.currentThread() }
            ?.interrupt()
        localSelfPairingThread = null
        localSelfPairingRunning = false
        localSelfPairingStartedAt = 0L
        phonePairingOffer.clear()
        sessionId = ""
        lastHeartbeatAt = 0L
    }

    private fun renewDisplayHold(nowMillis: Long, pairingInFlight: Boolean = false) {
        // A running pairing may outlive deadlineAt, bounded by SelfArmPairingGracePolicy;
        // the panel is held through it one lease at a time.
        val holdDeadlineAt = if (pairingInFlight) {
            maxOf(deadlineAt, nowMillis + SetupDisplayHoldLifecycle.LEASE_MS)
        } else {
            deadlineAt
        }
        // A session's chained runs answer to one wearer request; a sessionless run is its own.
        val scope = sessionId.ifBlank { "run:$runToken" }
        displayHold.renew(runToken, scope, nowMillis, holdDeadlineAt) {
            SetupDisplayHoldLifecycle.newLease(service.applicationContext)
        }
            ?.takeIf { it !is SetupDisplayHoldTransition.Renew }
            ?.let { android.util.Log.i(TAG, formatSetupDisplayHoldTransition(it)) }
    }

    /**
     * Deferred to the end of the current main-thread message: the service often
     * stops one run and starts the next in the same callback, and releasing in
     * between would blank and relight the panel. A run started meanwhile takes
     * the lease over, which turns this release into a no-op.
     */
    private fun releaseDisplayHold(
        reason: SetupDisplayHoldEnd,
        heldBy: Long? = displayHold.heldRunToken(),
    ) {
        val token = heldBy ?: return
        handler.post { releaseDisplayHoldNow(reason, token) }
    }

    private fun releaseDisplayHoldNow(reason: SetupDisplayHoldEnd, heldBy: Long? = null) {
        displayHold.release(reason, SystemClock.uptimeMillis(), heldBy)
            ?.let { android.util.Log.i(TAG, formatSetupDisplayHoldTransition(it)) }
    }

    private fun selfPairingSuspendsExpiry(nowMillis: Long): Boolean =
        SelfArmPairingGracePolicy.suspendsExpiry(
            workerAlive = localSelfPairingThread?.isAlive == true,
            nowMillis = nowMillis,
            pairingStartedAtMillis = localSelfPairingStartedAt,
            maxGraceMillis = SELF_PAIRING_GRACE_MS,
        ) || phonePairingOffer.suspendsExpiry(nowMillis, SELF_PAIRING_GRACE_MS)

    private fun pairingDialogHoldMs(): Long =
        if (operationMode == OperationMode.MANUAL_NAVIGATION) MANUAL_TIMEOUT_MS else PAIRING_DIALOG_HOLD_MS

    private data class Endpoint(val host: String, val port: Int)

    companion object {
        private const val TAG = "NexusWirelessSetup"

        /** Long enough for the home action to take, short enough not to read as a stall. */
        private const val SETTINGS_EXIT_SETTLE_MS = 700L
        private const val TIMEOUT_MS = 110_000L
        private const val STEP_DELAY_MS = 450L
        private const val CLICK_COOLDOWN_MS = 850L
        private const val CONFIRMATION_POLL_MS = 300L
        private const val WIRELESS_CONFIRMATION_WAIT_MS = 6_000L
        private const val DEVELOPER_OPEN_TIMEOUT_MS = 5_500L
        private const val DIRECT_WIRELESS_VERIFICATION_MS = 1_800L
        private const val DIRECT_WIRELESS_VERIFICATION_POLL_MS = 250L
        private const val WIFI_POLL_INTERVAL_MS = 1_000L
        private const val WIFI_CLICK_RETRY_WAIT_MS = 13_000L
        private const val WIFI_NETWORK_WAIT_MS = 30_000L
        private const val WIFI_NETWORK_POLL_INTERVAL_MS = 1_500L
        private const val PAIRING_DIALOG_POLL_MS = 600L
        private const val PAIRING_DIALOG_MAX_WAIT_MS = 9_000L
        private const val PAIRING_DIALOG_HOLD_MS = 60_000L
        /** Hard bound on a suspended run: comfortably past the 60–110 s a real bootstrap takes. */
        private const val SELF_PAIRING_GRACE_MS = 150_000L
        private const val MANUAL_TIMEOUT_MS = 5 * 60_000L
        private const val MANUAL_HOLD_POLL_MS = 1_000L
        private const val PAIRING_PORT_GRACE_MS = 1_800L
        private const val PAIRING_READY_REPORT_INTERVAL_MS = 2_000L
        private const val MAX_WIFI_SCROLLS = 8
        private const val MAX_DEVELOPER_OPEN_ATTEMPTS = 3
        private const val MAX_DEVELOPER_SCROLLS = 48
        private const val MAX_DEVICE_INFO_SCROLLS = 32
        private const val MAX_WIRELESS_SCROLLS = 16
        private const val MAX_BUILD_NUMBER_TAPS = 7
        private const val MAX_CLICK_ANCESTOR_DEPTH = 8
        private const val SETTINGS_APP_BAR_ID = "com.android.settings:id/app_bar"
        private const val SETTINGS_RECYCLER_ID = "com.android.settings:id/recycler_view"
        private const val SETTINGS_SWITCH_BAR_ID = "com.android.settings:id/switch_bar"
        private const val SETTINGS_SWITCH_TEXT_ID = "com.android.settings:id/switch_text"
        private val WIRELESS_SWITCH_IDS = listOf(
            "com.android.settings:id/switchWidget",
            "com.android.settings:id/switch_widget",
            "android:id/switch_widget",
        )
        private val CONFIRM_BUTTON_IDS = listOf(
            "android:id/button1",
            "com.android.settings:id/button1",
        )
        private val POSITIVE_BUTTON_FALLBACKS = setOf(
            "ok",
            "allow",
            "enable",
            "turn on",
            "activer",
            "autoriser",
            "oui",
            "yes",
        )
        private val IPV4_ENDPOINT = Pattern.compile("\\b((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{2,5})\\b")
        private val PAIRING_CODE = Pattern.compile("\\b(\\d{6})\\b")
        private val STANDALONE_PORT = Pattern.compile("\\b(\\d{4,5})\\b")
    }
}
