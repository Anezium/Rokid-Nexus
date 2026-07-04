package com.anezium.rokidbus.glasses

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BusHubService : Service() {
    override fun onCreate() {
        super.onCreate()
        GlassesHub.start(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder {
        GlassesHub.start(applicationContext)
        return GlassesHub.binder(applicationContext)
    }
}
