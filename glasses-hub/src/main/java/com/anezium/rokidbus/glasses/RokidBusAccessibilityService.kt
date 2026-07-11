package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        log("AccessibilityService connected; starting glasses hub")
        SurfaceOverlayRenderer.onServiceConnected(this)
        LauncherOverlayRenderer.onServiceConnected(this)
        GlassesHub.start(applicationContext)
        AccessibilityRearmWatcher.start(applicationContext, "accessibility_service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
        log("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        log("AccessibilityService destroyed")
        main.removeCallbacks(tapExpiry)
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

    private companion object {
        private const val KEYCODE_PROG_BLUE = 186
    }
}
