package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class SelfArmWirelessAccessibilityService : AccessibilityService() {
    private var automator: SelfArmWirelessDebuggingAutomator? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        automator = SelfArmWirelessDebuggingAutomator(this, Handler(Looper.getMainLooper()))
        liveInstance = this
        Log.i(TAG, "Wireless self-arm accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        automator?.onAccessibilityEvent(event)
    }

    fun startWirelessBootstrap() {
        automator?.start()
    }

    fun stopWirelessBootstrap() {
        automator?.stop()
    }

    override fun onInterrupt() {
        stopWirelessBootstrap()
        Log.i(TAG, "Wireless self-arm accessibility service interrupted")
    }

    override fun onDestroy() {
        stopWirelessBootstrap()
        automator = null
        if (liveInstance === this) liveInstance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NexusWirelessSetup"
        @Volatile private var liveInstance: SelfArmWirelessAccessibilityService? = null

        internal fun startConnectedService(): Boolean {
            val service = liveInstance ?: return false
            service.startWirelessBootstrap()
            return true
        }
    }
}
