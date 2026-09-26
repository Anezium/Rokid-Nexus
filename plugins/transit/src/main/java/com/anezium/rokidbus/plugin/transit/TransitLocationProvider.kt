package com.anezium.rokidbus.plugin.transit

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TransitLocationProvider(private val context: Context) : TransitLocationSource {
    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)
    private var updatesListener: LocationListener? = null

    override fun access(): TransitLocationAccess = context.transitLocationAccess()

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): TransitCoordinate? {
        if (access() != TransitLocationAccess.READY) return null
        val provider = bestProvider() ?: return null
        val current = suspendCancellableCoroutine<Location?> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                locationManager?.getCurrentLocation(
                    provider,
                    signal,
                    context.mainExecutor,
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }
        return current?.toCoordinate()
    }

    /**
     * Fixes every [intervalMs] while journey guidance runs, from the same provider choice as
     * Near Me. The caller holds the location foreground service for as long as updates run.
     */
    @SuppressLint("MissingPermission")
    fun startUpdates(intervalMs: Long, onFix: (TransitCoordinate) -> Unit): Boolean {
        if (access() != TransitLocationAccess.READY) return false
        val manager = locationManager ?: return false
        val provider = bestProvider() ?: return false
        stopUpdates()
        val listener = LocationListener { location -> onFix(location.toCoordinate()) }
        return runCatching {
            manager.requestLocationUpdates(provider, intervalMs, UPDATE_MIN_DISTANCE_M, listener, Looper.getMainLooper())
            updatesListener = listener
        }.isSuccess
    }

    fun stopUpdates() {
        val listener = updatesListener ?: return
        updatesListener = null
        runCatching { locationManager?.removeUpdates(listener) }
    }

    private fun bestProvider(): String? {
        val manager = locationManager ?: return null
        return listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun Location.toCoordinate(): TransitCoordinate =
        TransitCoordinate(latitude, longitude)

    private companion object {
        const val UPDATE_MIN_DISTANCE_M = 10f
    }
}
