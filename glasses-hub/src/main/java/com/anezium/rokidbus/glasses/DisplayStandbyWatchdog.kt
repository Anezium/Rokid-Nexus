package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Screen-on-only Android edge around [DisplayStandbyPolicy]. */
internal class DisplayStandbyWatchdog(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    private var receiverRegistered = false
    private var armed = false
    private var policyState = DisplayStandbyPolicyState()
    private var lastUserInputAtMs = 0L

    private val evaluation = Runnable { evaluate() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> arm()
                Intent.ACTION_SCREEN_OFF -> disarm()
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            service.registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
            )
            receiverRegistered = true
        }
        if (readInteractiveState() == StandbyInteractiveState.INTERACTIVE) arm() else disarm()
    }

    fun stop() {
        disarm()
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { service.unregisterReceiver(screenReceiver) }
    }

    fun noteKeyEvent(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DisplayWakePolicy.noteUserInteraction()
        }
        noteUserInput(event.eventTime)
    }

    fun noteAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in USER_INTERACTION_EVENT_TYPES) return
        noteUserInput(event.eventTime)
    }

    private fun arm() {
        val now = nowMs()
        armed = true
        policyState = DisplayStandbyPolicyState()
        lastUserInputAtMs = now
        handler.removeCallbacks(evaluation)
        handler.post(evaluation)
    }

    private fun disarm() {
        armed = false
        policyState = DisplayStandbyPolicyState()
        handler.removeCallbacks(evaluation)
    }

    private fun noteUserInput(eventTimeMs: Long) {
        if (!armed) return
        val now = nowMs()
        lastUserInputAtMs = maxOf(lastUserInputAtMs, eventTimeMs.coerceIn(0L, now))
        handler.removeCallbacks(evaluation)
        handler.postDelayed(evaluation, EVALUATION_CADENCE_MS)
    }

    private fun evaluate() {
        if (!armed) return
        val now = nowMs()
        val result = DisplayStandbyPolicy.decide(
            conditions = readConditions(),
            state = policyState,
            nowMs = now,
            lastUserInputAtMs = lastUserInputAtMs,
        )
        policyState = result.nextState
        when (val decision = result.decision) {
            is DisplayStandbyDecision.Sleep -> confirmAndSleep()
            is DisplayStandbyDecision.Waiting -> scheduleNext(decision.remainingMs)
            is DisplayStandbyDecision.Blocked -> scheduleNext(EVALUATION_CADENCE_MS)
        }
    }

    /** Re-read every gate at the irreversible edge; a foreground switch must win the race. */
    private fun confirmAndSleep() {
        val confirmation = DisplayStandbyPolicy.decide(
            conditions = readConditions(),
            state = policyState,
            nowMs = nowMs(),
            lastUserInputAtMs = lastUserInputAtMs,
        )
        policyState = confirmation.nextState
        when (val decision = confirmation.decision) {
            is DisplayStandbyDecision.Sleep -> {
                // A lock request owns this wake episode from here onward. Even a
                // refused global action waits for a real SCREEN_ON before retrying.
                disarm()
                val locked = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                log(
                    "display standby idleMs=${decision.idleDurationMs} " +
                        "top=${decision.topWindow.logValue} locked=$locked",
                )
            }
            is DisplayStandbyDecision.Waiting -> scheduleNext(decision.remainingMs)
            is DisplayStandbyDecision.Blocked -> scheduleNext(EVALUATION_CADENCE_MS)
        }
    }

    private fun scheduleNext(delayMs: Long) {
        if (!armed) return
        handler.removeCallbacks(evaluation)
        handler.postDelayed(evaluation, minOf(EVALUATION_CADENCE_MS, delayMs.coerceAtLeast(1L)))
    }

    private fun readConditions(): DisplayStandbyConditions {
        val topWindow = readTopWindow()
        return DisplayStandbyConditions(
            interactiveState = readInteractiveState(),
            chargingState = readChargingState(),
            surfacePresenting = conservativeBoolean {
                SurfaceController.activeSurface() != null || LyricsWidgetDisplayHold.isHeld()
            },
            noticePresenting = conservativeBoolean { NoticeController.activeNotice() != null },
            activityPresenting = conservativeBoolean(ActivityController::isPresenting),
            cameraSessionActive = conservativeBoolean(GlassesHub::isCameraSessionActive),
            launcherOverlayShown = conservativeBoolean(LauncherOverlayRenderer::isShown),
            mainActivityInteractiveFlow = conservativeBoolean { MainActivity.isInteractiveFlowActive() },
            topWindow = topWindow,
            // STT capture is phone-local and has no glasses-side session edge. Its
            // 30s capture ceiling is shorter than the input-idle window; see report.
            speechCaptureActive = false,
            ttsUtteranceActive = conservativeBoolean(TtsController::isUtteranceActive),
            setupFlowActive = readSetupFlowActive(),
            mediaSyncActive = conservativeBoolean(MediaSyncEngine::isSessionActive),
        )
    }

    private fun readInteractiveState(): StandbyInteractiveState {
        val power = service.getSystemService(PowerManager::class.java)
            ?: return StandbyInteractiveState.UNKNOWN
        val interactive = runCatching { power.isInteractive }.getOrNull()
            ?: return StandbyInteractiveState.UNKNOWN
        return if (interactive) {
            StandbyInteractiveState.INTERACTIVE
        } else {
            StandbyInteractiveState.NOT_INTERACTIVE
        }
    }

    private fun readChargingState(): StandbyChargingState {
        val battery = runCatching {
            service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return StandbyChargingState.UNKNOWN
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return when {
            plugged > 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ->
                StandbyChargingState.CHARGING
            // STATUS_FULL can outlive the unplug at 100%, so it only means charging
            // while a plug is present; unplugged it is just a battery that is full.
            plugged == 0 && status != BatteryManager.BATTERY_STATUS_UNKNOWN ->
                StandbyChargingState.ON_BATTERY
            else -> StandbyChargingState.UNKNOWN
        }
    }

    private fun readTopWindow(): StandbyTopWindow {
        val read = StatusBadgeOverlayRenderer.readLauncher(service)
        if (!read.topWindowReadable) return StandbyTopWindow.UNKNOWN
        // The ROM launcher package also owns teleprompter/subtitle screens. Only
        // its home layout carries weather nodes, so absence is deliberately non-idle.
        if (read.launcherOnTop && read.weatherVisible) return StandbyTopWindow.ROM_HOME
        if (read.topPackageName == service.packageName && MainActivity.isResumed()) {
            return StandbyTopWindow.NEXUS_MAIN
        }
        return StandbyTopWindow.OTHER
    }

    private fun readSetupFlowActive(): Boolean = conservativeBoolean {
        SelfArmOnboardingStore.currentActiveSessionId(service).isNotBlank() ||
            SelfArmOnboardingStore.isManualArmInProgress(service) ||
            RokidBusAccessibilityService.isSetupAutomationActive() ||
            SelfArmController.isOperationRunning() ||
            SelfArmBootRepairCoordinator.isRepairRunning()
    }

    private inline fun conservativeBoolean(read: () -> Boolean): Boolean =
        runCatching(read).getOrDefault(true)

    internal companion object {
        const val EVALUATION_CADENCE_MS = 45_000L

        private val USER_INTERACTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
        )
    }
}
