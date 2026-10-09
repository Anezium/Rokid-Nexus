package com.anezium.rokidbus.plugin.relay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.anezium.rokidbus.client.plugin.NexusPluginClient

/**
 * Who holds Relay's one client. The client is [RelayPluginService]'s; nothing else creates one.
 * The notice band holds that service bound for as long as a notice exchange lasts, exactly as
 * Navigation's route does, instead of registering a second client. The hub's own bindings, for
 * the inbox immersion or for page demand, keep the same service and the same client alive, so
 * ending a notice never takes a page's client away and no second registration ever exists.
 *
 * Generic over the client so the hold logic can be tested without Android.
 */
internal class RelayClientOwner<C : Any>(
    private val bind: () -> Boolean,
    private val unbind: () -> Unit,
) {
    /** The live service's client, or null while the service does not exist. */
    var client: C? = null
        private set

    /** Told when a client becomes available, so a waiting notice can go up. */
    var onClientReady: ((C) -> Unit)? = null

    var noticeHeld = false
        private set

    /** The notice band needs the client: hold the service bound, once, and return the client if it is there. */
    fun holdForNotice(): C? {
        if (!noticeHeld) noticeHeld = bind()
        return client
    }

    /** The notice exchange is over: drop only the band's own hold, never the client itself. */
    fun releaseNotice() {
        if (!noticeHeld) return
        noticeHeld = false
        unbind()
    }

    fun serviceCreated(created: C) {
        client = created
        onClientReady?.invoke(created)
    }

    /** The service is gone with its client; nothing rebinds on its own, so nothing can loop. */
    fun serviceDestroyed(destroyed: C) {
        if (client !== destroyed) return
        client = null
    }
}

/** Relay's process-wide owner of [RelayPluginService]'s client. */
internal object RelayBus {
    private var context: Context? = null
    private var connection: ServiceConnection? = null

    private val owner = RelayClientOwner<NexusPluginClient>(
        bind = ::bind,
        unbind = ::unbind,
    )

    val client: NexusPluginClient?
        get() = owner.client

    fun setOnClientReady(listener: ((NexusPluginClient) -> Unit)?) {
        owner.onClientReady = listener
    }

    fun holdForNotice(context: Context): NexusPluginClient? {
        this.context = context.applicationContext
        return owner.holdForNotice()
    }

    fun releaseNotice() = owner.releaseNotice()

    fun serviceCreated(client: NexusPluginClient) = owner.serviceCreated(client)

    fun serviceDestroyed(client: NexusPluginClient) = owner.serviceDestroyed(client)

    private fun bind(): Boolean {
        val appContext = context ?: return false
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            appContext.bindService(Intent(appContext, RelayPluginService::class.java), next, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) connection = next
        return bound
    }

    private fun unbind() {
        val appContext = context ?: return
        val current = connection ?: return
        connection = null
        runCatching { appContext.unbindService(current) }
    }
}
