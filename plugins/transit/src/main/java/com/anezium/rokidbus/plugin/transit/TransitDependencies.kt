package com.anezium.rokidbus.plugin.transit

internal interface TransitRepositorySource {
    fun nearbyStops(location: TransitCoordinate): List<TransitStop>
    fun departures(stopId: String): List<TransitDeparture>
    fun searchStops(query: String): List<TransitStopMatch>
}

internal interface TransitLocationSource {
    fun hasLocationPermission(): Boolean
    suspend fun currentLocation(): TransitCoordinate?
}

internal interface TransitFavoritesSource {
    fun list(): List<TransitStop>
    fun add(stop: TransitStop)
    fun remove(id: String)
    fun lastMode(): TransitMode
    fun setLastMode(mode: TransitMode)
}

internal data class TransitDependencies(
    val repository: TransitRepositorySource,
    val location: TransitLocationSource,
    val favorites: TransitFavoritesSource,
)

internal interface TransitRuntimeHost {
    fun sendCard(card: TransitCardContent, show: Boolean)
    fun hideSurface()
    fun post(action: () -> Unit)
    fun log(message: String)
    fun setNearMeForeground(active: Boolean): Boolean
}
