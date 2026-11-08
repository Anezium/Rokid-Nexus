package com.anezium.rokidbus.plugin.meta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the process alive while a hidden settings session finishes a pending pairing.
 * The session itself ends the hold when the pairing settles or its deadline passes;
 * this service only adds a hard stop in case that never happens.
 */
class WhatsAppPairingGraceService : Service() {
    private companion object {
        const val HARD_STOP_MS = 200_000L
        const val CHANNEL = "pairing-grace"
    }

    private val main = Handler(Looper.getMainLooper())
    private val hardStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "WhatsApp pairing", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("WhatsApp pairing waiting")
            .setContentText("Enter the code in WhatsApp within 3 minutes, then come back to Nexus.")
            .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        main.removeCallbacks(hardStop)
        main.postDelayed(hardStop, HARD_STOP_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(hardStop)
        super.onDestroy()
    }
}
