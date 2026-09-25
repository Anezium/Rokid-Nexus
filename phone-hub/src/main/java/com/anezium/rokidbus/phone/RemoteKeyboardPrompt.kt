package com.anezium.rokidbus.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brings Keyboard & remote forward when a plugin on the glasses asks for text.
 *
 * Only for sessions the glasses marked `keyboardRequested` (BUSSPEC, Remote
 * input). A field the wearer merely lands on still waits for the user to open
 * the screen, for the reason RemoteInputActivity keeps its keyboard down.
 *
 * Android blocks a background app from starting an activity unless it may draw
 * over other apps. With that grant the screen opens by itself; without it, or
 * behind the lock screen where the screen would open unseen, a heads-up
 * notification opens it in one tap.
 */
internal object RemoteKeyboardPrompt {
    private const val TAG = "RemoteKeyboardPrompt"
    private const val CHANNEL_ID = "keyboard_requests"
    private const val NOTIFICATION_TAG = "remote-keyboard"
    private const val NOTIFICATION_ID = 1

    /** A backstop only: the prompt is cancelled as soon as the field closes. */
    private const val NOTIFICATION_TIMEOUT_MS = 5 * 60_000L

    private val visibleScreens = AtomicInteger(0)

    fun onScreenStarted(context: Context) {
        visibleScreens.incrementAndGet()
        dismiss(context)
    }

    fun onScreenStopped() {
        visibleScreens.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun canOpenByItself(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun bringForward(context: Context) {
        // An open screen raises its own keyboard from the state broadcast.
        if (visibleScreens.get() > 0) return
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (canOpenByItself(context) && !locked) {
            val opened = runCatching { context.startActivity(screenIntent(context)) }
                .onFailure { Log.w(TAG, "Could not open the keyboard screen", it) }
                .isSuccess
            if (opened) return
        }
        postNotification(context)
    }

    fun dismiss(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        }
    }

    private fun postNotification(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Keyboard prompt skipped: POST_NOTIFICATIONS permission not granted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.remote_input_prompt_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            screenIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.remote_input_prompt_title))
            .setContentText(context.getString(R.string.remote_input_prompt_body))
            .setSmallIcon(R.drawable.ic_nexus_status)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the keyboard prompt", it) }
    }

    private fun screenIntent(context: Context): Intent =
        Intent(context, RemoteInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
