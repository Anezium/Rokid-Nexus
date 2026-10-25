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
    private val deliverBinaryCallback: (PhonePluginPrincipal, String, String, JSONObject, ByteArray) -> Boolean =
        { _, _, _, _, _ -> false },
    private val hideCallback: (String) -> Unit,
    private val disconnectedCallback: (PhonePluginPrincipal) -> Unit,
) : ExternalPluginRuntime, CameraCompanionRuntime {
    private val connections = ConcurrentHashMap<PluginGrantKey, ServiceConnection>()

    /** A new bind event thaws a frozen provider; an existing connection alone does not. */
    fun rebind(principal: PhonePluginPrincipal): Boolean {
        val key = principal.grantKey()
        val previous = connections.remove(key)
        if (!bind(principal)) {
            previous?.let { connections.putIfAbsent(key, it) }
            return false
        }
        // Acquire first so the last old connection cannot destroy a page-only service.
        previous?.let { runCatching { context.unbindService(it) } }
        return true
    }

    override fun bind(principal: PhonePluginPrincipal): Boolean {
        val key = principal.grantKey()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) = Unit
            override fun onServiceDisconnected(name: android.content.ComponentName) {
                if (connections[key] === this) disconnectedCallback(principal)
            }

            override fun onBindingDied(name: android.content.ComponentName) {
                releaseDeadBinding()
            }

            override fun onNullBinding(name: android.content.ComponentName) {
                releaseDeadBinding()
            }

            private fun releaseDeadBinding() {
                val wasCurrent = connections.remove(key, this)
                runCatching { context.unbindService(this) }
                if (wasCurrent) disconnectedCallback(principal)
            }
        }
        if (connections.putIfAbsent(key, connection) != null) return true
        val bound = runCatching {
            context.bindService(
                Intent(BusConstants.ACTION_PLUGIN).setComponent(principal.serviceComponent),
                connection,
                // Keep the provider important while needed. Freecess exemption additionally
                // requires the plugin's own SDK-managed session foreground service.
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        if (!bound) connections.remove(key, connection)
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

    override fun deliverBinary(
        principal: PhonePluginPrincipal,
        path: String,
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ): Boolean = deliverBinaryCallback(principal, path, id, payload, data)

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
