package com.anezium.rokidbus.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import com.anezium.rokidbus.shared.GlassesFilesHotspotCredentials
import com.anezium.rokidbus.shared.GlassesFilesHotspotPolicy
import com.anezium.rokidbus.shared.GlassesFilesRequest
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One memory-only reservation shared by the serial transfer queue. */
internal class GlassesFilesHotspotHost(
    private val context: Context,
    private val timers: ScheduledExecutorService,
    private val send: (GlassesFilesRequest) -> String?,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var batchId: String? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var credentials: GlassesFilesHotspotCredentials? = null
    private var activeId: String? = null
    private var lastTransferId: String? = null
    private var clientNetwork: GlassesFilesClientNetwork? = null
    private var failed: (() -> Unit)? = null
    private var idle: ScheduledFuture<*>? = null
    private var readiness: ScheduledFuture<*>? = null
    private var ready = false
    private var repairId: String? = null
    private var repairOffer: ((GlassesFilesHotspotCredentials) -> Unit)? = null
    private var repairClosed: (() -> Unit)? = null

    @Synchronized
    fun acquireRepair(id: String, deadline: Long, offered: (GlassesFilesHotspotCredentials) -> Unit,
        onClosed: () -> Unit): Boolean {
        if (batchId != null) return false
        repairId = id
        repairOffer = offered
        repairClosed = onClosed
        acquire(id, deadline) { }
        return true
    }

    @Synchronized
    fun releaseRepair(id: String) {
        if (repairId == id) close()
    }

    private fun offer(transferId: String, batch: String, credentials: GlassesFilesHotspotCredentials) {
        if (repairId == transferId) repairOffer?.invoke(credentials)
        else send(GlassesFilesRequest.HotspotOffer(transferId, batch, credentials, clientNetwork))
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun acquire(transferId: String, setupDeadline: Long, onFailed: () -> Unit) {
        if (repairId != null && repairId != transferId) {
            onFailed()
            return
        }
        idle?.cancel(false)
        activeId = transferId
        lastTransferId = transferId
        failed = onFailed
        val existing = batchId
        if (existing != null) {
            if (ready) credentials?.let { offer(transferId, existing, it) }
            return
        }
        val batch = "files_" + UUID.randomUUID().toString().replace("-", "")
        batchId = batch
        clientNetwork = GlassesFilesLaneClient.clientNetwork(context)
        val before = GlassesFilesHotspotReadiness.snapshot()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                synchronized(this@GlassesFilesHotspotHost) {
                    if (batchId != batch) {
                        started.close()
                        return
                    }
                    reservation = started
                    android.util.Log.i("GlassesFiles", "files hotspot started")
                    val config = started.softApConfiguration
                    val security = when (config.securityType) {
                        SoftApConfiguration.SECURITY_TYPE_OPEN -> "open"
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "wpa2"
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE,
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "wpa3"
                        else -> null
                    }
                    val offered = security?.let { GlassesFilesHotspotCredentials(config.ssid.orEmpty(), config.passphrase.orEmpty(), it) }
                    if (offered?.isValid() != true) {
                        fail(batch)
                        return
                    }
                    credentials = offered
                    awaitReady(batch, before, minOf(setupDeadline,
                        SystemClock.elapsedRealtime() + GlassesFilesHotspotReadiness.TIMEOUT_MS))
                }
            }

            override fun onFailed(reason: Int) = fail(batch)
            override fun onStopped() = fail(batch)
        }
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java) ?: error("Wi-Fi unavailable")
            val band = GlassesFilesHotspotCompatibility.configuredBand(Build.VERSION.SDK_INT, wifi.is5GHzBandSupported)
            if (Build.VERSION.SDK_INT >= 36 && band != null) {
                // Prefer 5 GHz to avoid the missing 2.4 GHz scan results observed during
                // concurrent Bluetooth uploads. Android still generates the credentials.
                val config = SoftApConfiguration.Builder()
                    .setChannels(android.util.SparseIntArray().apply { put(band, 0) })
                    .build()
                wifi.startLocalOnlyHotspotWithConfiguration(config, context.mainExecutor, callback)
            } else {
                wifi.startLocalOnlyHotspot(callback, main)
            }
        }.onFailure { fail(batch) }
    }

    @Synchronized
    private fun awaitReady(batch: String, before: List<GlassesFilesHotspotReadiness.Interface>, deadline: Long) {
        if (batchId != batch) return
        if (SystemClock.elapsedRealtime() >= deadline) {
            fail(batch)
        } else if (GlassesFilesHotspotReadiness.isReady(before, GlassesFilesHotspotReadiness.snapshot(), clientNetwork)) {
            ready = true
            credentials?.let { offered -> activeId?.let {
                offer(it, batch, offered)
            } }
        } else {
            readiness = timers.schedule({ awaitReady(batch, before, deadline) }, 100L, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun finish(transferId: String) {
        if (activeId != transferId) return
        activeId = null
        failed = null
        idle?.cancel(false)
        val batch = batchId
        idle = timers.schedule({ synchronized(this) {
            if (activeId == null && batchId == batch) close()
        } }, GlassesFilesHotspotPolicy.IDLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun fail(batch: String) {
        val callback = synchronized(this) {
            if (batchId != batch) return
            failed.also { close() }
        }
        callback?.invoke()
    }

    @Synchronized
    fun onPeerFailed(transferId: String) {
        if (lastTransferId == transferId) close()
    }

    @Synchronized
    override fun close() {
        val batch = batchId
        val recovery = repairClosed
        val wasRepair = repairId != null
        repairId = null
        repairOffer = null
        repairClosed = null
        batchId = null
        activeId = null
        lastTransferId = null
        credentials = null
        ready = false
        readiness?.cancel(false)
        readiness = null
        clientNetwork = null
        failed = null
        idle?.cancel(false)
        idle = null
        if (batch != null && !wasRepair) {
            send(GlassesFilesRequest.HotspotRelease(batch))
            android.util.Log.i("GlassesFiles", "files hotspot released")
        }
        runCatching { reservation?.close() }
        reservation = null
        recovery?.invoke()
    }

    companion object {
        fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION

        fun permitted(context: Context): Boolean = context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    }
}
