package com.anezium.rokidbus.glasses

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BusHubService : Service() {
    override fun onCreate() {
        super.onCreate()
        GlassesHub.start(applicationContext)
        AccessibilityRearmWatcher.start(applicationContext, "bus_hub_service")
    }

    override fun onBind(intent: Intent?): IBinder {
        GlassesHub.start(applicationContext)
        return GlassesHub.binder(applicationContext)
    }
}
