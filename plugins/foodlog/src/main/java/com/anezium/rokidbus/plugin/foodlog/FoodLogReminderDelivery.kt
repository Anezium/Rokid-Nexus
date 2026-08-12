package com.anezium.rokidbus.plugin.foodlog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusPin
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import java.util.concurrent.Executors

/** Only starts delivery for a receiver-authenticated alarm. Boot work is rescheduling only. */
class FoodLogReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FoodLogReminderContract.ACTION_FIRE) {
            intent.getStringExtra(FoodLogReminderContract.EXTRA_ID)?.let {
                FoodLogReminderDeliveryService.start(
                    context,
                    it,
                    intent.getBooleanExtra(FoodLogReminderContract.EXTRA_LATE, false),
                )
            }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val scheduler = foodLogReminderScheduler(context)
                FoodLogReminderStore(context).all().filter(FoodLogReminder::enabled).forEach {
                    runCatching { scheduler.reschedule(it) }
                }
            } finally {
                result.finish()
                executor.shutdown()
            }
        }
    }
}

class FoodLogReminderDeliveryService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() { super.onCreate(); createChannels() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        beginForeground()
        val id = intent?.getStringExtra(FoodLogReminderContract.EXTRA_ID)
        if (id == null) { finish(startId); return START_NOT_STICKY }
        val late = intent.getBooleanExtra(FoodLogReminderContract.EXTRA_LATE, false)
        worker.execute {
            try {
                val reminder = FoodLogReminderStore(applicationContext).takeForDelivery(id) ?: return@execute
                postNotification(reminder, late)
                main.post { OneShotFoodLogGlassesDelivery(applicationContext).deliver(reminder, late) }
            } finally {
                // The glasses handshake is bounded independently and cannot keep this service alive.
                main.postDelayed({ finish(startId) }, DELIVERY_LIFETIME_MS)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun beginForeground() {
        val notification = Notification.Builder(this, DELIVERY_CHANNEL).setSmallIcon(R.drawable.nexus_glyph_foodlog)
            .setContentTitle("Food Log reminder").setContentText("Delivering reminder").setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(DELIVERY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(DELIVERY_ID, notification)
    }
    private fun postNotification(value: FoodLogReminder, late: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = when (value.kind) { FoodLogReminderKind.MEAL -> "Meal reminder"; FoodLogReminderKind.HYDRATION -> "Hydration reminder" } + if (late) " (late)" else ""
        notifications.notify(value.id.hashCode(), Notification.Builder(this, REMINDER_CHANNEL).setSmallIcon(R.drawable.nexus_glyph_foodlog).setContentTitle(title).setContentText(value.label).setStyle(Notification.BigTextStyle().bigText(value.label)).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).build())
    }
    private fun createChannels() {
        notifications.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Food Log reminders", NotificationManager.IMPORTANCE_HIGH))
        notifications.createNotificationChannel(NotificationChannel(DELIVERY_CHANNEL, "Food Log reminder delivery", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }
    private fun finish(startId: Int) { if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE) }
    companion object {
        private const val REMINDER_CHANNEL = "food_log_reminders"
        private const val DELIVERY_CHANNEL = "food_log_reminder_delivery"
        private const val DELIVERY_ID = 0x464c
        private const val DELIVERY_LIFETIME_MS = 8_000L

        internal fun start(context: Context, id: String, late: Boolean) {
            context.startForegroundService(
                Intent(context, FoodLogReminderDeliveryService::class.java)
                    .putExtra(FoodLogReminderContract.EXTRA_ID, id)
                    .putExtra(FoodLogReminderContract.EXTRA_LATE, late),
            )
        }
    }
}

/** A single short connection; it never retries and is released after one attempted notice or pin. */
private class OneShotFoodLogGlassesDelivery(private val context: Context) : NexusPluginCallbacks {
    private var client: NexusPluginClient? = null
    private var value: FoodLogReminder? = null
    private var late = false
    private var approved = false
    private var linkState: Int? = null
    private val timeout = Handler(Looper.getMainLooper())
    fun deliver(reminder: FoodLogReminder, late: Boolean) {
        value = reminder; this.late = late
        client = NexusPluginClient.create(context, "foodlog", this).also { it.connect() }
        timeout.postDelayed(::close, 5_000L)
    }
    override fun onOpen() = Unit; override fun onClose() = close(); override fun onInput(event: NexusInputEvent) = Unit; override fun onMessage(path: String, id: String, payload: JSONObject) = Unit
    override fun onRegistrationState(result: Int) { approved = result == PluginRegistrationResult.APPROVED; if (!approved && result != PluginRegistrationResult.PENDING_USER_APPROVAL) close() else tryDeliver() }
    override fun onLinkState(state: Int) { linkState = state; tryDeliver() }
    private fun tryDeliver() {
        val current = client ?: return; val state = linkState ?: return; val reminder = value ?: return
        if (!approved) return
        val title = when (reminder.kind) { FoodLogReminderKind.MEAL -> "Meal"; FoodLogReminderKind.HYDRATION -> "Hydration" } + if (late) " (late)" else ""
        if (state and LinkStateBits.SPP_DATA_UP != 0 && current.supportsNoticeSurface) current.showNotice(NexusNotice(title, reminder.label, ttlMs = 20_000L, wakeDisplay = true))
        else if (current.supportsPinSurface) current.showPin(NexusPin(title.take(24), listOf(reminder.label.take(28))))
        close()
    }
    private fun close() { timeout.removeCallbacksAndMessages(null); client?.close(); client = null; value = null }
}
