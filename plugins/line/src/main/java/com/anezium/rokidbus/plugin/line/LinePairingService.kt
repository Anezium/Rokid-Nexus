package com.anezium.rokidbus.plugin.line

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** A user-started, three-minute hold so switching to LINE does not kill pairing. */
class LinePairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val owner = Any()
    private var started = false
    private var armed = false
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { stopSelf(); return START_NOT_STICKY }
        val credentials = LineSignInRequest.take()
        if (started) return START_NOT_STICKY
        if (credentials == null) { stopSelf(); return START_NOT_STICKY }
        started = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("line-pairing", "LINE sign-in", NotificationManager.IMPORTANCE_LOW))
        val reopen = PendingIntent.getActivity(this, 0, Intent(this, LineSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, LinePairingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "line-pairing")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("LINE sign-in waiting")
            .setContentText("Approve in LINE within 3 minutes, then return to Nexus.")
            .setContentIntent(reopen).addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        main.postDelayed(timeout, 185_000)
        LineSession.acquire(this, owner) {
            val phase = LineSession.login.phase
            if (phase in setOf("starting", "approve", "pin")) armed = true
            if (armed && phase in setOf("linked", "failed")) stopSelf()
        }
        LineSession.call(this, owner, { it.startLogin(credentials.email, credentials.password) }) { if (it.isFailure) stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        LineSignInRequest.clear()
        LineSession.abortLogin()
        LineSession.release(owner)
        super.onDestroy()
    }
}
