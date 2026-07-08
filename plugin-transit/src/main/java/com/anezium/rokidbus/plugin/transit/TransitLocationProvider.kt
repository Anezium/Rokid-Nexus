package com.anezium.rokidbus.plugin.transit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TransitLocationProvider(private val context: Context) {
    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): TransitCoordinate? {
        if (!hasLocationPermission()) return null
        val provider = bestProvider() ?: return lastKnownLocation()
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
        return current?.toCoordinate() ?: lastKnownLocation()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): TransitCoordinate? {
        if (!hasLocationPermission()) return null
        val manager = locationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toCoordinate()
    }

    private fun bestProvider(): String? {
        val manager = locationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
    }

    private fun Location.toCoordinate(): TransitCoordinate =
        TransitCoordinate(latitude, longitude)
}
