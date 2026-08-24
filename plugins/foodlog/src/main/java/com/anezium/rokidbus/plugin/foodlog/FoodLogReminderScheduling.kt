package com.anezium.rokidbus.plugin.foodlog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

internal sealed interface FoodLogAlarmPlan {
    data class Schedule(val id: String, val triggerAtMillis: Long) : FoodLogAlarmPlan
    data class DeliverImmediately(val id: String) : FoodLogAlarmPlan
}

internal object FoodLogReminderPlanner {
    fun plan(reminder: FoodLogReminder, nowMillis: Long): FoodLogAlarmPlan =
        if (reminder.epochMillis <= nowMillis) FoodLogAlarmPlan.DeliverImmediately(reminder.id)
        else FoodLogAlarmPlan.Schedule(reminder.id, reminder.epochMillis)
}

internal interface FoodLogAlarmGateway { fun schedule(id: String, triggerAtMillis: Long, exact: Boolean, late: Boolean): Boolean; fun cancel(id: String); fun deliverNow(id: String, late: Boolean) }
internal class FoodLogReminderScheduler(private val gateway: FoodLogAlarmGateway, private val exactAllowed: () -> Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    fun schedule(reminder: FoodLogReminder, lateIfPassed: Boolean = false): Boolean = when (val plan = FoodLogReminderPlanner.plan(reminder, clock())) {
        is FoodLogAlarmPlan.DeliverImmediately -> { gateway.deliverNow(plan.id, lateIfPassed); true }
        is FoodLogAlarmPlan.Schedule -> gateway.schedule(plan.id, plan.triggerAtMillis, exactAllowed(), false)
    }
    /** Restart receivers only restore alarm state; they never begin a delivery themselves. */
    fun reschedule(reminder: FoodLogReminder): Boolean = when (val plan = FoodLogReminderPlanner.plan(reminder, clock())) {
        is FoodLogAlarmPlan.DeliverImmediately -> gateway.schedule(plan.id, clock(), exactAllowed(), true)
        is FoodLogAlarmPlan.Schedule -> gateway.schedule(plan.id, plan.triggerAtMillis, exactAllowed(), false)
    }
    fun cancel(id: String) = gateway.cancel(id)
}

internal fun foodLogReminderScheduler(context: Context) = FoodLogReminderScheduler(
    AndroidFoodLogAlarmGateway(context.applicationContext),
    { Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() },
)

private class AndroidFoodLogAlarmGateway(private val context: Context) : FoodLogAlarmGateway {
    private val manager = context.getSystemService(AlarmManager::class.java)
    override fun schedule(id: String, triggerAtMillis: Long, exact: Boolean, late: Boolean): Boolean {
        val operation = pendingIntent(id, late)
        if (exact) try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation); return true } catch (_: SecurityException) { }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation); return false
    }
    override fun cancel(id: String) = manager.cancel(pendingIntent(id, false))
    override fun deliverNow(id: String, late: Boolean) {
        FoodLogReminderDeliveryService.start(context, id, late)
    }
    private fun pendingIntent(id: String, late: Boolean): PendingIntent = PendingIntent.getBroadcast(context, id.hashCode(), Intent(context, FoodLogReminderReceiver::class.java).setAction(FoodLogReminderContract.ACTION_FIRE).setData(Uri.parse("nexus-foodlog://reminder/$id")).putExtra(FoodLogReminderContract.EXTRA_ID, id).putExtra(FoodLogReminderContract.EXTRA_LATE, late), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
internal object FoodLogReminderContract { const val ACTION_FIRE = "com.anezium.rokidbus.plugin.foodlog.action.REMINDER_FIRE"; const val EXTRA_ID = "reminder_id"; const val EXTRA_LATE = "late" }
