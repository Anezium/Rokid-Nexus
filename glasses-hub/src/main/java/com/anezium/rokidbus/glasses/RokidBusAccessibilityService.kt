package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class RokidBusAccessibilityService : AccessibilityService() {
    private val tripleTapDetector = TripleTapDetector()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        log("AccessibilityService connected; starting glasses hub")
        SurfaceOverlayRenderer.onServiceConnected(this)
        LauncherOverlayRenderer.onServiceConnected(this)
        GlassesHub.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KEYCODE_PROG_BLUE) return false

        return when (tripleTapDetector.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)) {
            TripleTapDetector.Decision.TRIGGER -> {
                if (!LauncherOverlayRenderer.isShown()) {
                    LauncherOverlayRenderer.show(this)
                }
                true
            }
            TripleTapDetector.Decision.CONSUME -> true
            TripleTapDetector.Decision.PASS -> {
                when {
                    LauncherOverlayRenderer.handleKeyEvent(event) -> true
                    SurfaceController.handleKeyEvent(event) -> true
                    else -> false
                }
            }
        }
    }

    override fun onInterrupt() {
        log("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        log("AccessibilityService destroyed")
        LauncherOverlayRenderer.onServiceDestroyed(this)
        SurfaceOverlayRenderer.onServiceDestroyed(this)
        super.onDestroy()
    }

    private companion object {
        private const val KEYCODE_PROG_BLUE = 186
    }
}
