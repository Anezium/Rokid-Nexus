package com.anezium.rokidbus.client.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.R
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import com.anezium.rokidbus.shared.plugin.PluginDescriptorParseResult
import com.anezium.rokidbus.shared.plugin.PluginDescriptorParser
import org.json.JSONObject

abstract class NexusPluginService : Service(), NexusPluginCallbacks {
    private val localBinder = Binder()
    private var client: NexusPluginClient? = null
    private var descriptor: PluginDescriptor? = null
    private var sessionOpen = false

    protected val nexusClient: NexusPluginClient?
        get() = client

    protected open val hubTarget: HubTarget = HubTarget.PHONE

    protected val isNexusSessionOpen: Boolean
        get() = sessionOpen

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
        this.descriptor = descriptor
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
        sessionOpen = false
        stopNexusSessionForeground()
        super.onDestroy()
    }

    final override fun onOpen() {
        sessionOpen = true
        promoteNexusSessionForeground()
        onNexusOpen()
    }

    final override fun onClose() {
        try {
            onNexusClose()
        } finally {
            sessionOpen = false
            stopNexusSessionForeground()
        }
    }

    final override fun onInput(event: NexusInputEvent) = onNexusInput(event)
    final override fun onLinkState(state: Int) = onNexusLinkState(state)
    final override fun onRegistrationState(result: Int) {
        if (result == PluginRegistrationResult.APPROVED) {
            onNexusRegistrationState(result)
            return
        }
        sessionOpen = false
        try {
            onNexusRegistrationState(result)
        } finally {
            stopNexusSessionForeground()
        }
    }
    final override fun onMessage(path: String, id: String, payload: JSONObject) =
        onNexusMessage(path, id, payload)

    protected abstract fun onNexusOpen()
    protected abstract fun onNexusClose()
    protected abstract fun onNexusInput(event: NexusInputEvent)
    protected open fun onNexusLinkState(state: Int) = Unit
    protected open fun onNexusRegistrationState(result: Int) = Unit
    protected open fun onNexusMessage(path: String, id: String, payload: JSONObject) = Unit

    /**
     * Re-promotes the single plugin-service notification with any foreground types needed by
     * the active plugin feature. The glasses session special-use type is always included on
     * Android 14+, and failures are deliberately non-fatal so the plugin can keep operating in
     * a degraded state when the OS rejects a foreground-service transition.
     */
    protected fun promoteNexusSessionForeground(
        additionalTypes: Int = 0,
        onFailure: ((Throwable) -> Unit)? = null,
    ): Boolean {
        if (!sessionOpen) return false
        createSessionNotificationChannel()
        return runCatching {
            val notification = buildSessionNotification()
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    startForeground(
                        SESSION_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or additionalTypes,
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && additionalTypes != 0 -> {
                    startForeground(SESSION_NOTIFICATION_ID, notification, additionalTypes)
                }
                else -> startForeground(SESSION_NOTIFICATION_ID, notification)
            }
        }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                if (onFailure != null) {
                    onFailure(failure)
                } else {
                    Log.w(TAG, "Glasses session foreground start rejected: ${failure.javaClass.simpleName}")
                }
                false
            },
        )
    }

    protected fun stopNexusSessionForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createSessionNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "Glasses session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun buildSessionNotification(): Notification =
        Notification.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle(descriptor?.displayName ?: applicationInfo.loadLabel(packageManager))
            .setContentText("Active on your glasses")
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_plugin_bus)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

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
        private const val SESSION_CHANNEL_ID = "nexus_glasses_session"
        private const val SESSION_NOTIFICATION_ID = 40
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
