package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        log("BootReceiver received ${intent.action}; asking glasses hub to start")
        GlassesHub.start(context.applicationContext)
    }
}
