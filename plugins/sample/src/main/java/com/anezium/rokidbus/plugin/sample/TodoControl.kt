package com.anezium.rokidbus.plugin.sample

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.plugin.PluginCapability

/** "Show in activities", in the plugin's own preferences; the hub never sees them. */
internal class TodoSettings(context: Context) : TodoActivityPrefs {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val showInActivities: Boolean
        get() = prefs.getBoolean(KEY_SHOW, false)

    override var endPending: Boolean
        get() = prefs.getBoolean(KEY_END_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_END_PENDING, value).apply()

    override var publishedRevision: Long?
        get() = if (prefs.contains(KEY_PUBLISHED)) prefs.getLong(KEY_PUBLISHED, 0L) else null
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_PUBLISHED) else editor.putLong(KEY_PUBLISHED, value)
            editor.apply()
        }

    /** Off owes the hub an end, even across a restart; on replaces whatever stood with a start. */
    fun setShowInActivities(value: Boolean) =
        prefs.edit().putBoolean(KEY_SHOW, value).putBoolean(KEY_END_PENDING, !value).apply()

    private companion object {
        const val PREFS = "todo_settings"
        const val KEY_SHOW = "show_in_activities"
        const val KEY_END_PENDING = "end_pending"
        const val KEY_PUBLISHED = "published_revision"
    }
}

/**
 * The process's one meeting point for the todo: the store, the activity publisher, and the
 * plugin service's client.
 *
 * The activity always goes out through [HelloPluginService]'s own client, the plugin's one
 * registration: a second client would be a second registration of `hello`, and the hub runs a
 * plugin's commands and pages only while it has exactly one. When the list or the setting changes
 * while that service is asleep, the settings screen binds it just long enough to publish, then
 * lets it go; Nexus keeps the published entry as an offline snapshot on its own.
 */
internal object TodoControl {
    private val main = Handler(Looper.getMainLooper())
    private var runtime: Runtime? = null
    private val listeners = linkedSetOf<() -> Unit>()

    @Volatile
    private var serviceClient: NexusPluginClient? = null
    private var busBinding: ServiceConnection? = null
    private val releaseIdle = Runnable { runtime?.let { releaseBus(it.context) } }

    /** Told when the list changed, so a visible page can follow it; set by the plugin service. */
    var pageListener: (() -> Unit)? = null

    private class Runtime(val context: Context) {
        val store = TodoStore(PrefsTodoStorage(context), System::currentTimeMillis)
        val settings = TodoSettings(context)
        val publisher = TodoActivityPublisher(
            prefs = settings,
            list = store::list,
            sink = { TodoControl.serviceClient?.let(::ClientSink) },
            now = SystemClock::elapsedRealtime,
            schedule = { delayMs, task -> TodoControl.main.postDelayed(Runnable { task() }, delayMs) },
            flushed = { result -> TodoControl.onFlushed(result) },
        )
    }

    fun store(context: Context): TodoStore = runtime(context).store

    fun showInActivities(context: Context): Boolean = runtime(context).settings.showInActivities

    fun status(context: Context): TodoActivityStatus = runtime(context).publisher.status

    /** Main thread. [listener] runs after every change of the list or the activity's status. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** The list changed, here on the phone or through a page. */
    fun changed(context: Context) = onMain {
        val current = runtime(context)
        pageListener?.invoke()
        publish(current)
    }

    fun setShowInActivities(context: Context, enabled: Boolean) = onMain {
        val current = runtime(context)
        current.settings.setShowInActivities(enabled)
        if (enabled) current.publisher.retryAfterRefusal()
        publish(current)
    }

    /** Recomputes the status for a screen that just opened; sends only what is still owed. */
    fun refresh(context: Context) = onMain { runtime(context).publisher.flush() }

    fun serviceCreated(context: Context, client: NexusPluginClient) = onMain {
        val current = runtime(context)
        serviceClient = client
        // A new client holds no entry yet: whatever it publishes is a start.
        current.publisher.reset()
        current.publisher.flush()
    }

    fun serviceDestroyed(client: NexusPluginClient) = onMain {
        if (serviceClient !== client) return@onMain
        serviceClient = null
        runtime?.publisher?.reset()
        notifyListeners()
    }

    /** A registration or link change: a fresh approved registration publishes the entry again. */
    fun registrationChanged(context: Context) = onMain { runtime(context).publisher.flush() }

    fun activityClosed(context: Context, reason: String) = onMain {
        runtime(context).publisher.closed(reason)
        notifyListeners()
    }

    /** The hub answered a start with `ACTIVITY_DISABLED`. */
    fun activityRefused(context: Context) = onMain {
        runtime(context).publisher.refused()
        notifyListeners()
    }

    /** The adb demo route takes this plugin's one activity while it runs, and gives it back. */
    fun pauseForDemo(context: Context, paused: Boolean) = onMain {
        val current = runtime(context)
        current.publisher.pausedForDemo = paused
        if (!paused) current.publisher.flush()
    }

    private fun publish(current: Runtime) {
        val result = current.publisher.flush()
        val owed = current.settings.showInActivities || current.settings.endPending
        // Asleep, or not registered yet: wake the service for this one change, never for longer.
        if (owed && (result == TodoFlush.WAITING || result == TodoFlush.DEFERRED)) holdBus(current.context)
    }

    private fun onFlushed(result: TodoFlush) {
        notifyListeners()
        if (busBinding == null) return
        when (result) {
            // Give the message a moment on the bus before the registration that sent it goes.
            TodoFlush.SENT, TodoFlush.IDLE -> {
                main.removeCallbacks(releaseIdle)
                main.postDelayed(releaseIdle, RELEASE_AFTER_SEND_MS)
            }
            // The hold's own deadline still applies.
            TodoFlush.WAITING, TodoFlush.DEFERRED -> Unit
        }
    }

    private fun holdBus(context: Context) {
        if (busBinding == null) {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            val bound = runCatching {
                context.bindService(Intent(context, HelloPluginService::class.java), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) return
            busBinding = connection
        }
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, HOLD_LIMIT_MS)
    }

    private fun releaseBus(context: Context) {
        main.removeCallbacks(releaseIdle)
        val connection = busBinding ?: return
        busBinding = null
        runCatching { context.unbindService(connection) }
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private fun runtime(context: Context): Runtime =
        runtime ?: Runtime(context.applicationContext).also { runtime = it }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private class ClientSink(private val client: NexusPluginClient) : TodoActivitySink {
        override val approved: Boolean get() = client.isApproved
        override val activitiesAvailable: Boolean
            get() = client.hasCapability(PluginCapability.SURFACES) && client.supportsActivitySurface
        override val launcherInteractive: Boolean get() = client.supportsLauncherInteractive()
        override val registrationGeneration: Int get() = client.registrationGeneration

        override fun start(activity: NexusActivity): NexusSdkResult = client.startActivity(activity)
        override fun update(activity: NexusActivity): NexusSdkResult = client.updateActivity(activity)
        override fun end(): NexusSdkResult = client.endActivity()
    }

    /** How long a change may keep the service awake while it waits to register. */
    private const val HOLD_LIMIT_MS = 10_000L
    private const val RELEASE_AFTER_SEND_MS = 3_000L
}
