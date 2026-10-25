package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.anezium.rokidbus.shared.BusConstants
import java.util.concurrent.ConcurrentHashMap

/**
 * The binding behind an invocation lease. Skills and activity commands use separate instances,
 * independent of the foreground, page and camera bindings: Android keeps the provider alive
 * while any connection is bound, so ending a call never closes a provider that is open on the
 * HUD, and closing the HUD never strands a call still in flight.
 */
class AndroidSkillLeaseRuntime(
    private val context: Context,
    private val bindingDied: (PhonePluginPrincipal) -> Unit,
) {
    private val connections = ConcurrentHashMap<PluginGrantKey, ServiceConnection>()

    fun rebind(principal: PhonePluginPrincipal): Boolean {
        val key = principal.grantKey()
        val previous = connections.remove(key)
        if (!bind(principal)) {
            previous?.let { connections.putIfAbsent(key, it) }
            return false
        }
        previous?.let { runCatching { context.unbindService(it) } }
        return true
    }

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
                // Importance holds the process, but is not a Samsung Freecess exemption.
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
