package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        log("BootReceiver received ${intent.action}; asking glasses hub to start")
        val appContext = context.applicationContext
        GlassesHub.start(appContext)
        // An APK self-update strips our accessibility service from the secure setting and
        // force-stops the app, so nothing would re-arm it until the next manual launch. This
        // broadcast wakes us right after our own update; ensureWatchdog repairs accessibility
        // directly via WRITE_SECURE_SETTINGS, which needs neither ADB nor a reboot.
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            else -> null
        }
        if (reason != null) {
            val pendingResult = goAsync()
            val finished = AtomicBoolean(false)
            val finish = Runnable {
                if (finished.compareAndSet(false, true)) pendingResult.finish()
            }
            // The arm keeps running on its own thread; the broadcast must not. A full
            // bootstrap can outlast the async-receiver window, and the system kills the
            // process for an unfinished goAsync() long before that.
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(finish, ASYNC_RESULT_TIMEOUT_MS)
            AccessibilityRearmWatcher.ensureWatchdog(appContext, reason) {
                handler.removeCallbacks(finish)
                finish.run()
            }
        }
    }

    private companion object {
        const val ASYNC_RESULT_TIMEOUT_MS = 8_000L
    }
}
