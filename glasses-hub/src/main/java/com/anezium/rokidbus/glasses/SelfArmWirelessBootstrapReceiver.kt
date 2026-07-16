package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class SelfArmWirelessBootstrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!SelfArmWirelessBootstrapPolicy.mayStart(
                action = intent?.action,
                callerTrust = SelfArmWirelessBootstrapPolicy.CallerTrust.SAME_APP_OR_SIGNATURE,
                sdkInt = Build.VERSION.SDK_INT,
            )
        ) {
            Log.w(TAG, "Ignoring wireless bootstrap request that failed policy")
            return
        }
        if (!RokidBusAccessibilityService.requestWirelessBootstrap(context)) {
            Log.w(TAG, "Main Nexus accessibility service is not enabled or connected")
        }
    }

    companion object {
        const val ACTION_SELFARM_WIRELESS_START =
            "com.anezium.rokidbus.glasses.ACTION_SELFARM_WIRELESS_START"
        private const val TAG = "NexusWirelessSetup"
    }
}
