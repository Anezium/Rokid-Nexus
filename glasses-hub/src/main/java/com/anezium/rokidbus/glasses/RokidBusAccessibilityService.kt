package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class RokidBusAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        log("AccessibilityService connected; starting glasses hub")
        GlassesHub.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        log("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        log("AccessibilityService destroyed")
        super.onDestroy()
    }
}
