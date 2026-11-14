package com.anezium.rokidbus.glasses

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Scoped to one generated network and two specific Android activities. */
internal object SelfArmHotspotConsent {
    private var id = ""
    private var ssid = ""
    private var debugging = false
    private var selectionIssued = false
    private var foregroundClass = ""
    private var lastClick = 0L

    fun begin(id: String, ssid: String) {
        this.id = id
        this.ssid = ssid
        debugging = false
        selectionIssued = false
        foregroundClass = ""
        lastClick = 0L
    }

    fun awaitDebugging(id: String) { if (this.id == id) debugging = true }

    fun end(id: String) {
        if (this.id != id) return
        this.id = ""
        ssid = ""
        foregroundClass = ""
    }

    fun onEvent(service: RokidBusAccessibilityService, event: AccessibilityEvent?) {
        if (id.isBlank() || ssid.isBlank()) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundClass = event.className?.toString().orEmpty()
        }
        val expectedPackage = if (debugging) "com.android.systemui" else "com.android.settings"
        if (!SelfArmHotspotConsentPolicy.mayHandle(debugging, foregroundClass,
                event?.packageName?.toString().orEmpty())) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastClick < 750L) return
        val root = AccessibilityWindowRoots.getNavigationRoot(service, expectedPackage) ?: return
        try {
            if (root.packageName?.toString() != expectedPackage) return
            val target = root.findAccessibilityNodeInfosByText(ssid)
            try {
                if (target.isEmpty()) return
                val buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                try {
                    val button = buttons.firstOrNull { it.isEnabled && it.isVisibleToUser && it.isClickable }
                    if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        lastClick = now
                        return
                    }
                } finally { buttons.forEach { it.recycle() } }
                if (!debugging && !selectionIssued) {
                    val row = target.firstOrNull { it.text?.toString() == ssid } ?: return
                    if (clickRow(row)) { selectionIssued = true; lastClick = now }
                }
            } finally { target.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    private fun clickRow(node: AccessibilityNodeInfo): Boolean {
        var current = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(6) {
                if (current.isClickable && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = current.parent ?: return false
                current.recycle()
                current = parent
            }
            return false
        } finally { current.recycle() }
    }
}

internal object SelfArmHotspotConsentPolicy {
    fun mayHandle(debugging: Boolean, activityClass: String, packageName: String): Boolean =
        if (debugging) packageName == "com.android.systemui" &&
            activityClass == "com.android.systemui.wifi.WifiDebuggingActivity"
        else packageName == "com.android.settings" &&
            activityClass == "com.android.settings.wifi.NetworkRequestDialogActivity"
}
