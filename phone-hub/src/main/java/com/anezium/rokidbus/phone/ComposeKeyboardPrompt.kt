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
import android.os.Build
import android.util.Log
import com.anezium.rokidbus.shared.ComposeTypeStart

/** What the composer keyboard screen reports back; the hub's [PhoneComposeRouter] is the one. */
internal interface ComposeKeyboardSink {
    fun onText(captureId: String, text: String)
    fun onDone(captureId: String, commit: Boolean, text: String)
}

/** The open composer keyboard screen, told when its capture ends elsewhere. */
internal interface ComposeKeyboardScreen {
    fun onCaptureChanged(request: ComposeTypeStart, text: String)
    fun onCaptureClosed()
}

/**
 * Brings the phone keyboard forward for a glasses composer capture (`/core/compose/type/start`),
 * the way [RemoteKeyboardPrompt] does for an editable field: the screen opens by itself when
 * Android lets a background app start one and the phone is unlocked, else a heads-up notification
 * opens it in one tap. Process-wide, because the screen and the hub live in one process and the
 * capture must survive the screen being rebuilt.
 */
internal object ComposeKeyboardPrompt {
    private const val TAG = "ComposeKeyboardPrompt"
    private const val CHANNEL_ID = "compose_keyboard"
    private const val NOTIFICATION_TAG = "compose-keyboard"
    private const val NOTIFICATION_ID = 1

    /** A backstop only: the prompt is cancelled as soon as the capture ends. */
    private const val NOTIFICATION_TIMEOUT_MS = 5 * 60_000L

    @Volatile var sink: ComposeKeyboardSink? = null

    private var current: ComposeTypeStart? = null
    private var currentText: String = ""
    private var screen: ComposeKeyboardScreen? = null

    /**
     * Whether the keyboard is in front of the user or one tap away. When neither the screen nor
     * the notification could come up, the capture is forgotten here and the caller ends it, so
     * the glasses never wait for text nobody can type.
     */
    fun open(context: Context, request: ComposeTypeStart): Boolean {
        val visible = synchronized(this) {
            current = request
            currentText = request.initialText
            screen
        }
        if (visible != null) {
            visible.onCaptureChanged(request, request.initialText)
            return true
        }
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (RemoteKeyboardPrompt.canOpenByItself(context) && !locked) {
            val opened = runCatching { context.startActivity(screenIntent(context)) }
                .onFailure { Log.w(TAG, "Could not open the composer keyboard", it) }
                .isSuccess
            if (opened) return true
        }
        if (postNotification(context)) return true
        synchronized(this) {
            if (current?.captureId == request.captureId) {
                current = null
                currentText = ""
            }
        }
        return false
    }

    fun close(context: Context, captureId: String) {
        val visible = synchronized(this) {
            if (current?.captureId != captureId) return
            current = null
            currentText = ""
            screen
        }
        dismissNotification(context)
        visible?.onCaptureClosed()
    }

    /** The screen came up: the capture it serves and the field as last typed, or null for none. */
    fun attach(context: Context, view: ComposeKeyboardScreen): Pair<ComposeTypeStart, String>? {
        dismissNotification(context)
        return synchronized(this) {
            screen = view
            current?.let { it to currentText }
        }
    }

    fun detach(view: ComposeKeyboardScreen) {
        synchronized(this) { if (screen === view) screen = null }
    }

    fun onText(captureId: String, text: String) {
        synchronized(this) {
            if (current?.captureId != captureId) return
            currentText = text
        }
        sink?.onText(captureId, text)
    }

    fun onDone(context: Context, captureId: String, commit: Boolean, text: String) {
        synchronized(this) {
            if (current?.captureId != captureId) return
            current = null
            currentText = ""
        }
        dismissNotification(context)
        sink?.onDone(captureId, commit, text)
    }

    private fun dismissNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        }
    }

    /** Whether the prompt was posted. The runtime notification grant exists from Android 13 only. */
    private fun postNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Composer keyboard prompt skipped: POST_NOTIFICATIONS permission not granted")
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Glasses replies", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            screenIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Type your reply")
            .setContentText("Your glasses are waiting for text from the phone keyboard")
            .setSmallIcon(R.drawable.ic_nexus_status)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        return runCatching { manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the composer keyboard prompt", it) }
            .isSuccess
    }

    /**
     * In a task of its own, so closing it returns to whatever the user had open; one task only,
     * since there is one capture at a time and the screen follows it.
     */
    private fun screenIntent(context: Context): Intent =
        Intent(context, ComposeKeyboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
}
