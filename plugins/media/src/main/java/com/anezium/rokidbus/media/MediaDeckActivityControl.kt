package com.anezium.rokidbus.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityCommand
import com.anezium.rokidbus.client.plugin.NexusActivityCommandResult
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.media.session.MediaDeckMonitorHub
import com.anezium.rokidbus.plugin.media.MediaDeckPluginService
import com.anezium.rokidbus.shared.LinkStateBits

/** Media Deck's own settings: the user's switch, and what the last process left on the hub. */
internal class MediaDeckPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "Show in activities"; off by default, and kept through revocation and process death. */
    var showInActivities: Boolean
        get() = prefs.getBoolean(KEY_SHOW_IN_ACTIVITIES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_IN_ACTIVITIES, value).apply()
        }

    var activityOnHub: Boolean
        get() = prefs.getBoolean(KEY_ACTIVITY_ON_HUB, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ACTIVITY_ON_HUB, value).apply()
        }

    private companion object {
        const val FILE = "media_deck"
        const val KEY_SHOW_IN_ACTIVITIES = "show_in_activities"
        const val KEY_ACTIVITY_ON_HUB = "activity_on_hub"
    }
}

/**
 * Media Deck's process-wide owner of the "Show in activities" activity: the user's switch, the one
 * publisher, and the binding that keeps [MediaDeckPluginService] (and so its one client and
 * registration) alive while there is something to publish. The service reports its client and
 * its bus events here; nothing else registers a client. Everything runs on the main thread, and
 * only on events the plugin already receives: the service starting, its listener connecting, its
 * settings opening, the sessions it watches changing. Nothing polls.
 */
internal object MediaDeckActivityControl {
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var client: NexusPluginClient? = null
    private var connection: ServiceConnection? = null
    private var publisher: MediaDeckActivityPublisher? = null

    private val port = object : MediaDeckActivityPort {
        override val approved: Boolean
            get() = client?.isApproved == true

        override fun start(activity: NexusActivity): NexusSdkResult =
            client?.startActivity(activity) ?: NexusSdkResult.NOT_REGISTERED

        override fun update(activity: NexusActivity, significant: Boolean): NexusSdkResult =
            client?.updateActivity(activity, significant) ?: NexusSdkResult.NOT_REGISTERED

        override fun end(): NexusSdkResult = client?.endActivity() ?: NexusSdkResult.NOT_REGISTERED

        override fun hold(): Boolean = bind()

        override fun release() = unbind()
    }

    fun publisher(context: Context): MediaDeckActivityPublisher = publisher ?: run {
        val app = context.applicationContext
        appContext = app
        val preferences = MediaDeckPreferences(app)
        MediaDeckActivityPublisher(
            hub = MediaDeckMonitorHub.get(app),
            port = port,
            now = SystemClock::elapsedRealtime,
            schedule = { delayMs, task ->
                val runnable = Runnable { task() }
                main.postDelayed(runnable, delayMs)
                ({ main.removeCallbacks(runnable) })
            },
            onHubAtStart = preferences.activityOnHub,
            rememberOnHub = { preferences.activityOnHub = it },
        ).also { publisher = it }
    }

    /** Applies the stored switch and tries again whatever Nexus stopped. */
    fun resume(context: Context) {
        val current = publisher(context)
        val stored = MediaDeckPreferences(context).showInActivities
        if (stored != current.enabled) current.setEnabled(stored) else current.rearm()
    }

    /** The user's switch in Media Deck's settings; the caller checked notification access. */
    fun setEnabled(context: Context, enabled: Boolean) {
        MediaDeckPreferences(context).showInActivities = enabled
        publisher(context).setEnabled(enabled)
    }

    fun status(context: Context): MediaDeckActivityStatus = publisher(context).status

    fun serviceCreated(context: Context, created: NexusPluginClient) {
        client = created
        val current = publisher(context)
        val stored = MediaDeckPreferences(context).showInActivities
        if (stored != current.enabled) current.setEnabled(stored)
        current.onBusReady()
    }

    fun serviceDestroyed(destroyed: NexusPluginClient) {
        if (client !== destroyed) return
        client = null
        publisher?.onBusLost()
    }

    fun registrationState(result: Int) {
        val current = client ?: return
        publisher?.onRegistration(result == PluginRegistrationResult.APPROVED, current.registrationGeneration)
    }

    fun linkState(state: Int) {
        publisher?.onLinkState(state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0)
    }

    fun activityClosed(reason: String) {
        publisher?.onActivityClosed(reason)
    }

    fun error(code: String) {
        publisher?.onError(code)
    }

    /** A launcher-bundle command; null for one already answered, so nothing is sent twice. */
    fun command(command: NexusActivityCommand): NexusActivityCommandResult? {
        val current = publisher ?: return NexusActivityCommandResult.rejected(NOT_SHOWN)
        return current.onCommand(command.invocationId, command.actionId, command.typed)?.toActivityResult()
    }

    fun legacyAction(actionId: String) {
        publisher?.onLegacyAction(actionId)
    }

    private fun bind(): Boolean {
        if (connection != null) return true
        val app = appContext ?: return false
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            app.bindService(Intent(app, MediaDeckPluginService::class.java), next, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) {
            connection = next
        } else {
            // A refused bind still registers the connection.
            runCatching { app.unbindService(next) }
        }
        return bound
    }

    private fun unbind() {
        val app = appContext ?: return
        val current = connection ?: return
        connection = null
        runCatching { app.unbindService(current) }
    }

    private const val NOT_SHOWN = "Not shown here"
}
