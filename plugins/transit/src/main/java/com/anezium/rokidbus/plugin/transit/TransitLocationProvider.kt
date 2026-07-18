package com.anezium.rokidbus.plugin.transit

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TransitLocationProvider(private val context: Context) : TransitLocationSource {
    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

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
}
