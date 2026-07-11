package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.util.Log

class SelfArmWirelessBootstrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SELFARM_WIRELESS_START) return
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid() && callingUid != Process.SHELL_UID) {
            Log.w(TAG, "Ignoring wireless bootstrap broadcast from uid=$callingUid")
            return
        }
        if (!SelfArmWirelessAccessibilityService.startConnectedService()) {
            Log.w(TAG, "Wireless bootstrap service is not enabled or connected")
        }
    }

    companion object {
        const val ACTION_SELFARM_WIRELESS_START =
            "com.anezium.rokidbus.glasses.ACTION_SELFARM_WIRELESS_START"
        private const val TAG = "NexusWirelessSetup"
    }
}
