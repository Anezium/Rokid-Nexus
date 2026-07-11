package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.BusConstants
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class AndroidExternalPluginRuntime(
    private val context: Context,
    private val isRegisteredCallback: (PhonePluginPrincipal) -> Boolean,
    private val deliverCallback: (PhonePluginPrincipal, String, String, JSONObject) -> Boolean,
    private val hideCallback: (String) -> Unit,
    private val disconnectedCallback: (PhonePluginPrincipal) -> Unit,
) : ExternalPluginRuntime {
    private val connections = ConcurrentHashMap<PluginGrantKey, ServiceConnection>()

    override fun bind(principal: PhonePluginPrincipal): Boolean {
        if (connections.containsKey(principal.grantKey())) return true
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) = Unit
            override fun onServiceDisconnected(name: android.content.ComponentName) {
                connections.remove(principal.grantKey())
                disconnectedCallback(principal)
            }

            override fun onBindingDied(name: android.content.ComponentName) {
                connections.remove(principal.grantKey())
                disconnectedCallback(principal)
            }

            override fun onNullBinding(name: android.content.ComponentName) {
                connections.remove(principal.grantKey())
                disconnectedCallback(principal)
            }
        }
        val bound = runCatching {
            context.bindService(
                Intent(BusConstants.ACTION_PLUGIN).setComponent(principal.serviceComponent),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (bound) connections[principal.grantKey()] = connection
        return bound
    }

    override fun isRegistered(principal: PhonePluginPrincipal): Boolean =
        isRegisteredCallback(principal)

    override fun deliver(
        principal: PhonePluginPrincipal,
        path: String,
        id: String,
        payload: JSONObject,
    ): Boolean = deliverCallback(principal, path, id, payload)

    override fun hideOwnedSurfaces(pluginId: String) = hideCallback(pluginId)

    override fun unbind(principal: PhonePluginPrincipal) {
        val connection = connections.remove(principal.grantKey()) ?: return
        runCatching { context.unbindService(connection) }
    }
}

class MainThreadExternalPluginScheduler : ExternalPluginScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, Runnable>()

    override fun schedule(key: String, delayMs: Long, action: () -> Unit) {
        cancel(key)
        val runnable = Runnable {
            callbacks.remove(key)
            action()
        }
        callbacks[key] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel(key: String) {
        callbacks.remove(key)?.let(handler::removeCallbacks)
    }
}
