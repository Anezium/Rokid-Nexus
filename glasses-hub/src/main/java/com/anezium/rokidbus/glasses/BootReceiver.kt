package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        log("BootReceiver received ${intent.action}; asking glasses hub to start")
        val appContext = context.applicationContext
        GlassesHub.start(appContext)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            SelfArmController.ensureWatchdog(appContext, "boot_completed") {
                pendingResult.finish()
            }
        }
    }
}
