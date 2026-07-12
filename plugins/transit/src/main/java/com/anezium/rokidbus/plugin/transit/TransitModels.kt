package com.anezium.rokidbus.plugin.transit

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val UNKNOWN_DISTANCE_METERS = -1

data class TransitCoordinate(
    val lat: Double,
    val lon: Double,
)

data class TransitStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Int = UNKNOWN_DISTANCE_METERS,
    val modes: List<String> = emptyList(),
)

data class TransitStopMatch(
    val stop: TransitStop,
    val city: String,
)

data class TransitDeparture(
    val mode: String,
    val routeShortName: String?,
    val headsign: String,
    val departure: Instant,
    val scheduledDeparture: Instant?,
    val cancelled: Boolean,
)

data class TransitBoard(
    val stop: TransitStop,
    val departures: List<TransitDeparture>,
    val fetchedAt: Instant,
)

/**
 * One card body row. Plain rows carry only [text]; departure rows add the
 * route [badge] and a [trail] of wait times so the glasses HUD can render
 * a structured board instead of pre-formatted monospace strings.
 */
data class CardLine(
    val text: String,
    val badge: String = "",
    val trail: List<String> = emptyList(),
) {
    val isStructured: Boolean
        get() = badge.isNotBlank() || trail.isNotEmpty()
}

data class TransitCardContent(
    val title: String,
    val lines: List<CardLine>,
    val footer: String,
) {
    // NexusCard caps contentKey at 128 chars, so the content is hashed, never concatenated:
    // a real departure board easily exceeds the cap and the SDK rejects the card.
    fun contentKey(): String {
        val content = buildString {
            append(title)
            append('|')
            append(footer)
            append('|')
            append(
                lines.joinToString("\n") { line ->
                    "${line.badge}|${line.text}|${line.trail.joinToString(",")}"
                },
            )
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (index in 0 until 8) {
                val byte = digest[index].toInt() and 0xff
                append("0123456789abcdef"[byte ushr 4])
                append("0123456789abcdef"[byte and 0x0f])
            }
        }
    }
}

enum class TransitMode(val prefValue: String, val rowLabel: String) {
    NEAR_ME("near_me", "Near Me"),
    FAVORITES("favorites", "Favorites"),
    ;

    fun toggled(): TransitMode =
        if (this == NEAR_ME) FAVORITES else NEAR_ME

    companion object {
        fun fromPref(value: String?): TransitMode =
            values().firstOrNull { it.prefValue == value } ?: NEAR_ME
    }
}

fun haversineMeters(from: TransitCoordinate, to: TransitCoordinate): Int {
    val earthMeters = 6_371_000.0
    val fromLat = Math.toRadians(from.lat)
    val toLat = Math.toRadians(to.lat)
    val deltaLat = Math.toRadians(to.lat - from.lat)
    val deltaLon = Math.toRadians(to.lon - from.lon)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (earthMeters * c).roundToInt()
}

fun TransitStop.withDistanceFrom(location: TransitCoordinate): TransitStop =
    copy(distanceMeters = haversineMeters(location, TransitCoordinate(lat, lon)))

fun TransitStop.withUnknownDistance(): TransitStop =
    copy(distanceMeters = UNKNOWN_DISTANCE_METERS)

fun selectNearbyBoardStops(stops: List<TransitStop>, nearestCount: Int = 3): List<TransitStop> {
    val nearest = stops.take(nearestCount)
    if (nearest.size < nearestCount || nearest.any { it.servesRail() }) return nearest
    val nearestRail = stops.drop(nearestCount).firstOrNull { it.servesRail() }
    return if (nearestRail == null) nearest else nearest + nearestRail
}

private fun TransitStop.servesRail(): Boolean =
    modes.any { mode -> mode.trim().uppercase() in RAIL_MODES }

private val RAIL_MODES = setOf(
    "RAIL",
    "REGIONAL_RAIL",
    "SUBWAY",
    "METRO",
    "TRAM",
    "TRAIN",
    "FERRY",
)
