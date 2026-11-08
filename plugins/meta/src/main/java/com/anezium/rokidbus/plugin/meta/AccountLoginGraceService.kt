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
import android.os.SystemClock
import org.json.JSONObject

/**
 * Keeps pending logins alive for a bounded time after their settings are hidden, so
 * the owner can approve them in the official app on this same phone. It never
 * connects, reads or sends; it ends when every hidden login settles, the deadline
 * passes, or the settings become visible again and take their engine back.
 */
class AccountLoginGraceService : Service() {
    companion object {
        const val GRACE_MS = 180_000L
        private const val CHECK_MS = 2_000L
        private const val CHANNEL = "login-grace"
        private val PENDING = setOf("running", "approval_required", "code_required")
    }

    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var finished = false
    private val check = Runnable { check() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Login", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Login waiting")
            .setContentText("Approve it within 3 minutes, then come back to Nexus.")
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // A new hidden login restarts the window; older ones are bounded by their own engine limit.
        deadline = SystemClock.elapsedRealtime() + GRACE_MS
        finished = false
        main.removeCallbacks(check)
        main.postDelayed(check, CHECK_MS)
        return START_NOT_STICKY
    }

    private fun hidden() = MetaAccount.entries.filter { it.open != null && !it.host.visible }

    private fun check() {
        if (finished) return
        val accounts = hidden()
        if (accounts.isEmpty()) return finish(expired = false)
        val probe = MetaAccount.INSTAGRAM.host.probe
        probe.execute {
            val states = accounts.associateWith { account ->
                runCatching { account.host.engine?.status()?.let { JSONObject(it).getString("login") } }.getOrNull().orEmpty()
            }
            states.forEach { (account, login) -> if (login !in PENDING) account.host.graceOutcome = login }
            val busy = states.values.any { it in PENDING }
            main.post {
                when {
                    finished -> Unit
                    !busy -> finish(expired = false)
                    SystemClock.elapsedRealtime() >= deadline -> finish(expired = true)
                    else -> main.postDelayed(check, CHECK_MS)
                }
            }
        }
    }

    private fun finish(expired: Boolean) {
        finished = true
        main.removeCallbacks(check)
        for (account in hidden()) {
            if (expired && account.host.graceOutcome.isEmpty()) account.host.loginInterrupted = true
            account.host.releaseIfHidden()
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) {
            finished = true
            main.removeCallbacks(check)
            hidden().forEach { it.host.releaseIfHidden() }
        }
        super.onDestroy()
    }
}
