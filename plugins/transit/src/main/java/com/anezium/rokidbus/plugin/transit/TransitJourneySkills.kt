package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** What journey operations need from the plugin around them. */
internal interface TransitJourneyEnvironment {
    fun locationAccess(): TransitLocationAccess

    /** The plugin holds `surfaces` and the glasses speak activity v1. */
    fun canShowActivity(): Boolean

    /** One position fix within [timeoutMs], through Transit's existing location path. */
    fun currentLocation(timeoutMs: Long): TransitCoordinate?
}

/**
 * start_journey, journey_status, and stop_journey. Only the destination's label and the
 * itinerary reach the caller: the origin is "Your position", the destination its label, and no
 * coordinate is ever part of a result.
 */
internal class TransitJourneySkills(
    private val controller: TransitJourneyController,
    private val planner: TransitJourneyPlanner,
    private val home: TransitHomeSource,
    private val environment: TransitJourneyEnvironment,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun start(arguments: JSONObject, deadlineAtMs: Long): TransitSkillOutcome {
        val (label, destination) = when (arguments.optString("destination")) {
            "home" -> home.home()?.let { it.label to it.coordinate }
                ?: return TransitSkillOutcome.NeedsInput(
                    TransitSkillContract.INPUT_HOME_NOT_SET,
                    "No home is saved. Set one in Transit's settings on the phone.",
                )
            "stop" -> TransitSkillContract.decodeStop(arguments.optString("stop"))
                ?.let { it.name to TransitCoordinate(it.lat, it.lon) }
                ?: return TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
            else -> return TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        }
        val departAt = if (arguments.has("depart_at")) {
            parseInstant(arguments.getString("depart_at"))
                ?: return TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        } else {
            null
        }
        if (environment.locationAccess() != TransitLocationAccess.READY) {
            return TransitSkillOutcome.Failed(TransitSkillContract.ERROR_LOCATION_UNAVAILABLE)
        }
        if (!environment.canShowActivity()) return TransitSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED)
        val locationBudget = minOf(
            TransitSkillContract.JOURNEY_LOCATION_TIMEOUT_MS,
            deadlineAtMs - monotonicMs() - TransitSkillContract.JOURNEY_PLAN_RESERVE_MS,
        )
        if (locationBudget <= 0L) return TransitSkillOutcome.Failed(SkillErrorCodes.DEADLINE_EXCEEDED)
        val origin = environment.currentLocation(locationBudget)
            ?: return TransitSkillOutcome.Failed(TransitSkillContract.ERROR_LOCATION_UNAVAILABLE)
        val planned = when (val result = planner.plan(origin, destination, departAt, deadlineAtMs - PLAN_MARGIN_MS)) {
            is TransitPlanResult.Planned -> result
            TransitPlanResult.NoRoute -> return TransitSkillOutcome.Failed(TransitSkillContract.ERROR_NO_ROUTE)
            is TransitPlanResult.Failed -> return TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        }
        val itinerary = planned.itineraries.first()
        val journey = controller.start(
            itinerary = itinerary,
            alternatives = (planned.itineraries.size - 1).coerceIn(0, TransitSkillContract.MAX_ALTERNATIVES),
            destinationLabel = label,
            destination = destination,
            origin = origin,
        ) ?: return TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        return TransitSkillOutcome.Completed(summary(journey))
    }

    fun status(arguments: JSONObject): TransitSkillOutcome {
        val active = controller.active()
        val asked = arguments.optString("journey").takeIf(String::isNotEmpty)
        if (active == null || (asked != null && asked != active.id)) {
            return TransitSkillOutcome.Completed(JSONObject().put("active", false))
        }
        val zone = zone()
        val legs = active.itinerary.legs
        val leg = active.currentLeg
        val json = JSONObject()
            .put("active", true)
            .put("journey", active.id)
            .put("destination", active.destinationLabel)
            .put("phase", active.phase.wireValue)
            .put("leg", (active.legIndex + 1).coerceIn(1, MAX_LEGS))
            .put("legs", legs.size.coerceIn(1, MAX_LEGS))
            .put("arrival_local", TransitSkillContract.localTime(active.itinerary.end, zone))
            .put("replanned", active.replanned)
        if (leg != null && active.phase != JourneyPhase.ARRIVED) {
            leg.line?.let { json.put("line", it.take(TransitSkillContract.MAX_LINE_CHARS)) }
            leg.headsign?.let { json.put("direction", it.take(TransitSkillContract.MAX_DIRECTION_CHARS)) }
            when (active.phase) {
                JourneyPhase.WALK -> json.put("next_stop", placeName(active, leg.to, isLast = active.legIndex == legs.lastIndex))
                    .put("next_time_local", TransitSkillContract.localTime(leg.end, zone))
                JourneyPhase.BOARD, JourneyPhase.MISSED -> json.put("next_stop", name(leg.from.name))
                    .put("next_time_local", TransitSkillContract.localTime(leg.start, zone))
                JourneyPhase.RIDE -> json.put("next_stop", name(leg.to.name))
                    .put("next_time_local", TransitSkillContract.localTime(leg.end, zone))
                    .put("stops_remaining", (leg.stops.lastIndex - active.rideStopIndex).coerceIn(0, MAX_STOPS))
                JourneyPhase.ARRIVED -> Unit
            }
        }
        return TransitSkillOutcome.Completed(json)
    }

    fun stop(arguments: JSONObject): TransitSkillOutcome {
        val journeyId = arguments.optString("journey").takeIf(String::isNotEmpty)
            ?: return TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        val wasActive = controller.stop(journeyId)
        return TransitSkillOutcome.Completed(JSONObject().put("ended", true).put("was_active", wasActive))
    }

    private fun summary(journey: JourneyState): JSONObject {
        val zone = zone()
        val itinerary = journey.itinerary
        val firstRide = itinerary.rideLegs.firstOrNull()
        val firstDeparture = firstRide?.start ?: itinerary.start
        return JSONObject()
            .put("journey", journey.id)
            .put("destination", journey.destinationLabel.take(TransitSkillContract.MAX_NAME_CHARS))
            .put("first_departure", firstDeparture.toString())
            .put("first_departure_local", TransitSkillContract.localTime(firstDeparture, zone))
            .put("arrival", itinerary.end.toString())
            .put("arrival_local", TransitSkillContract.localTime(itinerary.end, zone))
            .put("duration_minutes", minutesBetween(itinerary.start, itinerary.end).coerceIn(0, 2880))
            .put("transfers", itinerary.transfers.coerceIn(0, 20))
            .put("alternatives", journey.alternatives)
            .put(
                "legs",
                JSONArray().apply {
                    itinerary.legs.take(MAX_LEGS).forEachIndexed { index, leg ->
                        put(
                            JSONObject()
                                .put("mode", leg.mode.take(24))
                                .apply {
                                    leg.line?.let { put("line", it.take(TransitSkillContract.MAX_LINE_CHARS)) }
                                    leg.headsign?.let { put("direction", it.take(TransitSkillContract.MAX_DIRECTION_CHARS)) }
                                }
                                .put("from", if (index == 0) ORIGIN_LABEL else name(leg.from.name))
                                .put("to", placeName(journey, leg.to, isLast = index == itinerary.legs.lastIndex))
                                .put("departure_local", TransitSkillContract.localTime(leg.start, zone))
                                .put("arrival_local", TransitSkillContract.localTime(leg.end, zone))
                                .put("realtime", leg.realTime)
                                .apply { if (!leg.isWalk) put("stops", (leg.intermediateStops.size + 1).coerceAtMost(MAX_STOPS)) }
                                .put("minutes", minutesBetween(leg.start, leg.end).coerceIn(0, 2880)),
                        )
                    }
                },
            )
    }

    private fun placeName(journey: JourneyState, place: TransitPlace, isLast: Boolean): String =
        if (isLast) journey.destinationLabel.take(TransitSkillContract.MAX_NAME_CHARS) else name(place.name)

    // The router names the journey's own ends START and END; neither is a place name.
    private fun name(value: String): String =
        value.takeUnless { it.isBlank() || it == "START" || it == "END" }?.take(TransitSkillContract.MAX_NAME_CHARS)
            ?: ORIGIN_LABEL

    private fun minutesBetween(from: Instant, to: Instant): Int =
        ((to.toEpochMilli() - from.toEpochMilli()) / 60_000L).toInt()

    private fun parseInstant(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()

    private companion object {
        const val ORIGIN_LABEL = "Your position"
        const val MAX_LEGS = 12
        const val MAX_STOPS = 300
        const val PLAN_MARGIN_MS = 500L
    }
}
