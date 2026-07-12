package com.anezium.rokidbus.plugin.transit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject

class TransitPluginService : NexusPluginService() {
    private var runtime: TransitRuntime? = null
    private var surface: NexusSurfaceSession? = null
    private var locationForeground = false
    private val statusStore by lazy { TransitPluginStatusStore(applicationContext) }
    private val migrationReceiver by lazy {
        TransitLegacyMigrationReceiver(AndroidTransitMigrationStorage(applicationContext))
    }

    private val runtimeHost = object : TransitRuntimeHost {
        override fun sendCard(card: TransitCardContent, show: Boolean) {
            val session = surface ?: return
            val sdkCard = NexusCard(
                title = card.title,
                lines = emptyList(),
                footer = card.footer,
                contentKey = card.contentKey(),
                richLines = card.lines.map { line ->
                    NexusCardLine(
                        text = line.text,
                        badge = line.badge.takeIf(String::isNotBlank),
                        trail = line.trail,
                    )
                },
                handlesBack = true,
            )
            if (show) session.showCard(sdkCard) else session.updateCard(sdkCard)
        }

        override fun hideSurface() {
            surface?.hide()
        }

        override fun post(action: () -> Unit) {
            mainExecutor.execute(action)
        }

        override fun log(message: String) {
            Log.i(TAG, message)
        }

        override fun setNearMeForeground(active: Boolean): Boolean =
            if (active) startLocationForeground() else {
                stopLocationForeground()
                true
            }
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        stopLocationForeground()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime?.input(event)
    }

    override fun onNexusRegistrationState(result: Int) {
        statusStore.setRegistration(result)
        if (result != PluginRegistrationResult.APPROVED) {
            runtime?.close()
            stopLocationForeground()
        }
    }

    override fun onNexusLinkState(state: Int) {
        statusStore.setLinkState(state)
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path != TransitLegacyMigrationReceiver.IMPORT_PATH) return
        val acknowledgement = migrationReceiver.receive(payload) ?: return
        nexusClient?.send(TransitLegacyMigrationReceiver.ACK_PATH, id, acknowledgement)
    }

    override fun onDestroy() {
        runtime?.close()
        runtime = null
        stopLocationForeground()
        surface = null
        statusStore.setDisconnected()
        super.onDestroy()
    }

    private fun ensureRuntime(): TransitRuntime = runtime ?: TransitRuntime(
        host = runtimeHost,
        dependencies = TransitDependencies(
            repository = TransitRepository(),
            location = TransitLocationProvider(applicationContext),
            favorites = TransitFavoritesStore(applicationContext),
        ),
    ).also { runtime = it }

    private fun startLocationForeground(): Boolean {
        if (!hasLocationPermission()) return false
        if (locationForeground) return true
        createNotificationChannel()
        return runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            locationForeground = true
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Location foreground start rejected: ${failure.javaClass.simpleName}")
            locationForeground = false
            false
        }
    }

    private fun stopLocationForeground() {
        if (!locationForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        locationForeground = false
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "$packageName.TransitSettingsActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Transit")
            .setContentText("Updating the active Near Me board")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openSettings)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "NexusTransit"
        const val SURFACE_ID = "transit"
        const val CHANNEL_ID = "nexus_transit_location"
        const val NOTIFICATION_ID = 41
    }
}
