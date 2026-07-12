package com.anezium.rokidbus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PhoneProbeBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("probe") == "image-surface") {
            BusHubService.startDebugImage(context.applicationContext)
        }
    }
}
