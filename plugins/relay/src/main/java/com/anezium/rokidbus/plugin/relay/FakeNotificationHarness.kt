package com.anezium.rokidbus.plugin.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.concurrent.CopyOnWriteArrayList

internal data class FakeHarnessSnapshot(
    val messageCount: Int,
    val imageAttached: Boolean,
    val deliveredReply: String?,
)

internal object FakeNotificationHarness {
    private data class Message(val sender: String, val text: String, val timestamp: Long)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val messages = mutableListOf<Message>()
    private val secondMessages = mutableListOf<Message>()
    private var imageAttached = false
    private var deliveredReply: String? = null

    @Synchronized
    fun resetAndPost(context: Context): Boolean {
        messages.clear()
        val now = System.currentTimeMillis()
        messages += Message("Mika", "Can you check the build when you have a minute?", now - 2_000L)
        messages += Message("Mika", "I added a second message to exercise thread extraction.", now - 1_000L)
        messages += Message("Mika", "Reply from the glasses when you are ready.", now)
        deliveredReply = null
        return post(context)
    }

    fun postReadOnly(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(FAKE_CHANNEL_ID, "Relay test thread", NotificationManager.IMPORTANCE_HIGH),
        )
        val posted = runCatching {
            manager.notify("relay_fake_read_only", 17_003,
                Notification.Builder(context, FAKE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Parcel update")
                    .setContentText("Your parcel is ready to collect. This alert has no reply action.")
                    .build())
        }.isSuccess
        notifyChanged()
        return posted
    }

    /**
     * A code-shaped thread — which does NOT reproduce Android's redaction.
     *
     * Android 15 blanks a notification carrying a one-time code before an
     * unprivileged listener sees it, and that is the most reported way Relay
     * shows the wrong thing. This button was written to exercise it and does
     * not: measured on hardware, the platform classifier ignored this thread
     * entirely, and so did a code-carrying notification posted from the shell —
     * the app-op was never even consulted, while a real message from a real app
     * had it rejected minutes earlier on the same phone.
     *
     * So the classifier wants more than digits, and redaction cannot be staged
     * from here. Kept because a code-shaped thread is still the right fixture
     * for the fallback rendering, but do not read a pass here as proof that
     * sensitive notifications arrive whole: only a genuine one answers that.
     */
    @Synchronized
    fun postCodeThread(context: Context): Boolean {
        messages.clear()
        val now = System.currentTimeMillis()
        messages += Message("Mika", "Sending the code now.", now - 1_000L)
        messages += Message("Mika", "Your verification code is 847291. Do not share it.", now)
        deliveredReply = null
        return post(context)
    }

    @Synchronized
    fun appendAndPost(context: Context): Boolean {
        if (messages.isEmpty()) return resetAndPost(context)
        val ordinal = messages.size + 1
        messages += Message(
            sender = if (ordinal % 2 == 0) "Nina" else "Mika",
            text = "Appended message $ordinal keeps the same notification identity.",
            timestamp = System.currentTimeMillis(),
        )
        return post(context)
    }

    @Synchronized
    fun attachImageAndPost(context: Context): Boolean {
        if (messages.isEmpty()) resetAndPost(context)
        imageAttached = true
        return post(context)
    }

    /**
     * A second conversation, from someone else, under its own notification id.
     *
     * The inbox is a list, and a list of one proves nothing: it cannot show
     * whether selection moves, whether the newest thread sorts first, or whether
     * two rows are told apart at a glance. Relay's own harness kept a second
     * test thread for the same reason.
     */
    @Synchronized
    fun postSecondThread(context: Context): Boolean {
        val now = System.currentTimeMillis()
        secondMessages.clear()
        secondMessages += Message("Nina", "Are we still on for tonight?", now - 1_000L)
        secondMessages += Message("Nina", "Say yes from the glasses.", now)
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                FAKE_CHANNEL_ID,
                "Relay test thread",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val posted = runCatching {
            manager.notify(
                SECOND_NOTIFICATION_TAG,
                SECOND_NOTIFICATION_ID,
                buildNotification(appContext, secondMessages, "Nina", SECOND_NOTIFICATION_ID),
            )
        }.isSuccess
        notifyChanged()
        return posted
    }

    /**
     * Eight conversations at once, each from its own sender under its own
     * notification id.
     *
     * Two test threads can show selection moving; they cannot show what
     * happens when the list outgrows the glasses viewport, which is exactly
     * where the 2026-08 invisible-selection report lived. Eight is one more
     * than the optics hold, so the window has to move to keep up.
     */
    @Synchronized
    fun postCrowd(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                FAKE_CHANNEL_ID,
                "Relay test thread",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val now = System.currentTimeMillis()
        val posted = CROWD_SENDERS.withIndex().map { (index, sender) ->
            val thread = listOf(
                Message(
                    sender = sender,
                    text = "Crowd message ${index + 1} of ${CROWD_SENDERS.size}.",
                    timestamp = now - (CROWD_SENDERS.size - index) * 1_000L,
                ),
            )
            runCatching {
                manager.notify(
                    "$CROWD_NOTIFICATION_TAG$index",
                    CROWD_NOTIFICATION_ID_BASE + index,
                    buildNotification(appContext, thread, sender, CROWD_NOTIFICATION_ID_BASE + index),
                )
            }.isSuccess
        }.all { it }
        notifyChanged()
        return posted
    }

    @Synchronized
    fun receiveReply(text: String) {
        deliveredReply = text.takeIf(String::isNotBlank)
        notifyChanged()
    }

    @Synchronized
    fun snapshot(): FakeHarnessSnapshot = FakeHarnessSnapshot(
        messageCount = messages.size,
        imageAttached = imageAttached,
        deliveredReply = deliveredReply,
    )

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    /**
     * Posts through `NotificationManager`, exactly like the apps this plugin
     * exists to relay.
     *
     * The tempting shortcut — build a `StatusBarNotification` here and hand it
     * straight to the listener — tests almost nothing that can break. It skips
     * the grant, it skips `onNotificationPosted`, and above all it skips the
     * parcel: a real notification is written to a Parcel and rebuilt before the
     * listener sees it, which is where `MessagingStyle` is reconstructed and
     * bitmaps become `Icon`s. Extraction that passes on an in-memory object can
     * still fail on the real thing, and a harness that hides that is worse than
     * no harness.
     */
    @Synchronized
    private fun post(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                FAKE_CHANNEL_ID,
                "Relay test thread",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val posted = runCatching {
            manager.notify(
                FAKE_NOTIFICATION_TAG,
                FAKE_NOTIFICATION_ID,
                buildNotification(appContext, messages, "Mika", FAKE_NOTIFICATION_ID),
            )
        }.isSuccess
        notifyChanged()
        return posted
    }

    private fun buildNotification(
        context: Context,
        thread: List<Message>,
        title: String,
        requestCode: Int,
    ): Notification {
        val user = Person.Builder().setName("You").build()
        val style = Notification.MessagingStyle(user)
            .setConversationTitle(title)
            .setGroupConversation(true)
        thread.forEach { message ->
            style.addMessage(
                Notification.MessagingStyle.Message(
                    message.text,
                    message.timestamp,
                    Person.Builder().setName(message.sender).build(),
                ),
            )
        }
        val notification = Notification.Builder(context, FAKE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(thread.lastOrNull()?.text.orEmpty())
            .setWhen(thread.lastOrNull()?.timestamp ?: System.currentTimeMillis())
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .addAction(replyAction(context, requestCode))
            .build()
        if (imageAttached) {
            notification.extras.putParcelable(Notification.EXTRA_PICTURE, testImage())
        }
        return notification
    }

    private fun replyAction(context: Context, requestCode: Int): Notification.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, FakeReplyReceiver::class.java).setAction(ACTION_FAKE_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(RESULT_REPLY).setLabel("Reply").build()
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pendingIntent,
        ).addRemoteInput(input).build()
    }

    private fun testImage(): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(4, 12, 7))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (index in 0 until 12) {
            paint.color = Color.rgb(30 + index * 15, 90 + index * 10, 48 + index * 12)
            canvas.drawCircle(
                55f + index * 56f,
                95f + (index % 3) * 105f,
                42f,
                paint,
            )
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        canvas.drawRect(18f, 18f, 702f, 402f, paint)
        return bitmap
    }

    private fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
        RelaySettingsActivity.notifyDataChanged()
    }

    const val RESULT_REPLY = "relay_fake_reply"
    const val ACTION_FAKE_REPLY = "com.anezium.rokidbus.plugin.relay.FAKE_REPLY"
    private const val FAKE_CHANNEL_ID = "relay_fake_model"
    private const val FAKE_NOTIFICATION_ID = 17_001
    private const val FAKE_NOTIFICATION_TAG = "relay_fake_thread"
    private const val SECOND_NOTIFICATION_ID = 17_002
    private const val SECOND_NOTIFICATION_TAG = "relay_fake_thread_2"
    private const val CROWD_NOTIFICATION_ID_BASE = 17_100
    private const val CROWD_NOTIFICATION_TAG = "relay_fake_crowd_"
    private val CROWD_SENDERS =
        listOf("Ada", "Bruno", "Chloe", "Denis", "Emma", "Farid", "Gwen", "Hugo")
}
