package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.anezium.rokidbus.shared.BusConstants
import java.util.concurrent.ConcurrentHashMap

/**
 * The binding behind a skill invocation lease. It is a connection of its own, separate from
 * the foreground and camera bindings: Android keeps the provider alive while any connection is
 * bound, so ending a call releases only this one and never closes a provider that is open on the
 * HUD, and closing the HUD never strands a call still in flight.
 */
class AndroidSkillLeaseRuntime(
    private val context: Context,
    private val bindingDied: (PhonePluginPrincipal) -> Unit,
) {
    private val connections = ConcurrentHashMap<PluginGrantKey, ServiceConnection>()

    fun bind(principal: PhonePluginPrincipal): Boolean {
        val key = principal.grantKey()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

            // A transient disconnect keeps the auto-create binding; the registration path reports it.
            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onBindingDied(name: ComponentName) = release()

            override fun onNullBinding(name: ComponentName) = release()

            private fun release() {
                val wasCurrent = connections.remove(key, this)
                runCatching { context.unbindService(this) }
                if (wasCurrent) bindingDied(principal)
            }
        }
        if (connections.putIfAbsent(key, connection) != null) return true
        val bound = runCatching {
            context.bindService(
                Intent(BusConstants.ACTION_PLUGIN).setComponent(principal.serviceComponent),
                connection,
                // As for an open plugin: the hub's foreground importance must reach the provider,
                // or an OEM freezer can stop it mid-call.
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        if (!bound) connections.remove(key, connection)
        return bound
    }

    fun unbind(principal: PhonePluginPrincipal) {
        val connection = connections.remove(principal.grantKey()) ?: return
        runCatching { context.unbindService(connection) }
    }
}
