package com.anezium.rokidbus.plugin.transit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal interface TransitJourneyStorage {
    fun load(): JourneyState?
    fun save(state: JourneyState)
    fun clear()
}

/**
 * The active journey in Transit's private preferences, so a process restart resumes guidance
 * at the same leg and step. It holds the itinerary and the destination; nothing here is ever
 * sent anywhere.
 */
internal class TransitJourneyStore(context: Context) : TransitJourneyStorage {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): JourneyState? = prefs.getString(KEY_STATE, null)?.let(TransitJourneyCodec::decode)

    // commit, not apply: the process may be killed right after a transition.
    override fun save(state: JourneyState) {
        prefs.edit().putString(KEY_STATE, TransitJourneyCodec.encode(state)).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_STATE).commit()
    }

    private companion object {
        const val PREFS = "nexus_plugin_transit_journey"
        const val KEY_STATE = "state"
    }
}

internal object TransitJourneyCodec {
    private const val VERSION = 1

    fun encode(state: JourneyState): String = JSONObject()
        .put("v", VERSION)
        .put("id", state.id)
        .put("label", state.destinationLabel)
        .put("dLat", state.destination.lat)
        .put("dLon", state.destination.lon)
        .put("alternatives", state.alternatives)
        .put("leg", state.legIndex)
        .put("phase", state.phase.name)
        .put("rideStop", state.rideStopIndex)
        .put("replanned", state.replanned)
        .put("planned", state.onPlannedTrip)
        .put("generation", state.planGeneration)
        .put("started", state.startedAt.toEpochMilli())
        .put("expires", state.expiresAt.toEpochMilli())
        .putOpt("arrived", state.arrivedAt?.toEpochMilli())
        .put("itinerary", itinerary(state.itinerary))
        .toString()

    fun decode(text: String): JourneyState? = runCatching {
        val json = JSONObject(text)
        if (json.getInt("v") != VERSION) return null
        val itinerary = itinerary(json.getJSONObject("itinerary"))
        JourneyState(
            id = json.getString("id"),
            itinerary = itinerary,
            destinationLabel = json.getString("label"),
            destination = TransitCoordinate(json.getDouble("dLat"), json.getDouble("dLon")),
            alternatives = json.getInt("alternatives"),
            legIndex = json.getInt("leg").coerceIn(0, itinerary.legs.lastIndex),
            phase = JourneyPhase.valueOf(json.getString("phase")),
            rideStopIndex = json.getInt("rideStop"),
            replanned = json.getBoolean("replanned"),
            onPlannedTrip = json.optBoolean("planned", true),
            planGeneration = json.getInt("generation"),
            startedAt = Instant.ofEpochMilli(json.getLong("started")),
            expiresAt = Instant.ofEpochMilli(json.getLong("expires")),
            arrivedAt = if (json.has("arrived")) Instant.ofEpochMilli(json.getLong("arrived")) else null,
        )
    }.getOrNull()

    private fun itinerary(itinerary: TransitItinerary): JSONObject = JSONObject()
        .put("start", itinerary.start.toEpochMilli())
        .put("end", itinerary.end.toEpochMilli())
        .put("transfers", itinerary.transfers)
        .put("legs", JSONArray().apply { itinerary.legs.forEach { put(leg(it)) } })

    private fun itinerary(json: JSONObject): TransitItinerary {
        val legs = json.getJSONArray("legs")
        require(legs.length() > 0)
        return TransitItinerary(
            start = Instant.ofEpochMilli(json.getLong("start")),
            end = Instant.ofEpochMilli(json.getLong("end")),
            transfers = json.getInt("transfers"),
            legs = (0 until legs.length()).map { leg(legs.getJSONObject(it)) },
        )
    }

    private fun leg(leg: TransitLeg): JSONObject = JSONObject()
        .put("mode", leg.mode)
        .putOpt("line", leg.line)
        .putOpt("headsign", leg.headsign)
        .put("from", place(leg.from))
        .put("to", place(leg.to))
        .put("start", leg.start.toEpochMilli())
        .put("end", leg.end.toEpochMilli())
        .putOpt("sStart", leg.scheduledStart?.toEpochMilli())
        .putOpt("sEnd", leg.scheduledEnd?.toEpochMilli())
        .put("rt", leg.realTime)
        .putOpt("trip", leg.tripId)
        .put("via", JSONArray().apply { leg.intermediateStops.forEach { put(place(it)) } })
        .putOpt("distance", leg.distanceMeters)

    private fun leg(json: JSONObject): TransitLeg {
        val via = json.optJSONArray("via") ?: JSONArray()
        return TransitLeg(
            mode = json.getString("mode"),
            line = json.optString("line").takeIf(String::isNotBlank),
            headsign = json.optString("headsign").takeIf(String::isNotBlank),
            from = place(json.getJSONObject("from")),
            to = place(json.getJSONObject("to")),
            start = Instant.ofEpochMilli(json.getLong("start")),
            end = Instant.ofEpochMilli(json.getLong("end")),
            scheduledStart = if (json.has("sStart")) Instant.ofEpochMilli(json.getLong("sStart")) else null,
            scheduledEnd = if (json.has("sEnd")) Instant.ofEpochMilli(json.getLong("sEnd")) else null,
            realTime = json.getBoolean("rt"),
            tripId = json.optString("trip").takeIf(String::isNotBlank),
            intermediateStops = (0 until via.length()).map { place(via.getJSONObject(it)) },
            distanceMeters = if (json.has("distance")) json.getInt("distance") else null,
        )
    }

    private fun place(place: TransitPlace): JSONObject = JSONObject()
        .put("name", place.name)
        .put("lat", place.lat)
        .put("lon", place.lon)
        .putOpt("stop", place.stopId)
        .putOpt("arr", place.arrival?.toEpochMilli())
        .putOpt("dep", place.departure?.toEpochMilli())

    private fun place(json: JSONObject): TransitPlace = TransitPlace(
        name = json.getString("name"),
        lat = json.getDouble("lat"),
        lon = json.getDouble("lon"),
        stopId = json.optString("stop").takeIf(String::isNotBlank),
        arrival = if (json.has("arr")) Instant.ofEpochMilli(json.getLong("arr")) else null,
        departure = if (json.has("dep")) Instant.ofEpochMilli(json.getLong("dep")) else null,
    )
}
