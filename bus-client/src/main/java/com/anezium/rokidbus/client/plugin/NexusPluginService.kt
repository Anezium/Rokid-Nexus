package com.anezium.rokidbus.client.plugin

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import com.anezium.rokidbus.shared.plugin.PluginDescriptorParseResult
import com.anezium.rokidbus.shared.plugin.PluginDescriptorParser
import org.json.JSONObject

abstract class NexusPluginService : Service(), NexusPluginCallbacks {
    private val localBinder = Binder()
    private var client: NexusPluginClient? = null

    protected val nexusClient: NexusPluginClient?
        get() = client

    protected open val hubTarget: HubTarget = HubTarget.PHONE

    protected fun nexusSurfaceSession(localSurfaceId: String): NexusSurfaceSession? =
        client?.surfaceSession(localSurfaceId)

    override fun onCreate() {
        super.onCreate()
        val descriptor = readOwnDescriptor()
        if (descriptor == null) {
            Log.e(TAG, "Plugin service descriptor is invalid")
            stopSelf()
            return
        }
        client = NexusPluginClient.create(
            context = applicationContext,
            pluginId = descriptor.id,
            callbacks = this,
            hubTarget = hubTarget,
        ).also(NexusPluginClient::connect)
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }

    final override fun onOpen() = onNexusOpen()
    final override fun onClose() = onNexusClose()
    final override fun onInput(event: NexusInputEvent) = onNexusInput(event)
    final override fun onLinkState(state: Int) = onNexusLinkState(state)
    final override fun onRegistrationState(result: Int) = onNexusRegistrationState(result)
    final override fun onMessage(path: String, id: String, payload: JSONObject) =
        onNexusMessage(path, id, payload)

    protected abstract fun onNexusOpen()
    protected abstract fun onNexusClose()
    protected abstract fun onNexusInput(event: NexusInputEvent)
    protected open fun onNexusLinkState(state: Int) = Unit
    protected open fun onNexusRegistrationState(result: Int) = Unit
    protected open fun onNexusMessage(path: String, id: String, payload: JSONObject) = Unit

    private fun readOwnDescriptor(): PluginDescriptor? {
        val component = ComponentName(this, javaClass)
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
            }
        }.getOrNull() ?: return null
        val metadata = buildMap<String, String?> {
            val bundle = info.metaData
            METADATA_KEYS.forEach { key ->
                if (bundle?.containsKey(key) == true) put(key, bundle.get(key)?.toString())
            }
        }
        return (PluginDescriptorParser.parse(metadata) as? PluginDescriptorParseResult.Valid)?.descriptor
    }

    companion object {
        private const val TAG = "NexusPluginService"
        private val METADATA_KEYS = listOf(
            BusConstants.META_PLUGIN_ID,
            BusConstants.META_PLUGIN_DISPLAY_NAME,
            BusConstants.META_PLUGIN_API_VERSION,
            BusConstants.META_PLUGIN_CAPABILITIES,
            BusConstants.META_PLUGIN_RECEIVE_PREFIXES,
            BusConstants.META_PLUGIN_SETTINGS_ACTIVITY,
            BusConstants.META_PLUGIN_LAUNCHABLE,
        )
    }
}
