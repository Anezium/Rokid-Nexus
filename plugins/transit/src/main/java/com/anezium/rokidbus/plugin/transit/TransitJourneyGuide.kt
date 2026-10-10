package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.client.plugin.NexusActivityTrack
import com.anezium.rokidbus.client.plugin.NexusGuidanceGlyphs
import com.anezium.rokidbus.client.plugin.NexusGuidanceStep
import com.anezium.rokidbus.shared.GlyphContract
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class JourneyPhase(val wireValue: String) {
    /** On foot along a walking leg. */
    WALK("walk"),

    /** At or heading for the boarding stop of a ride leg, not yet aboard. */
    BOARD("board"),

    /** Aboard a ride leg. */
    RIDE("ride"),

    /** The boarding time passed without the wearer aboard. */
    MISSED("missed"),
    ARRIVED("arrived"),
}

/**
 * Everything guidance needs to continue after a process restart. [destination] is the saved
 * home or chosen stop; like the position it stays inside Transit and never reaches the caller.
 */
internal data class JourneyState(
    val id: String,
    val itinerary: TransitItinerary,
    val destinationLabel: String,
    val destination: TransitCoordinate,
    val alternatives: Int,
    val legIndex: Int,
    val phase: JourneyPhase,
    val rideStopIndex: Int,
    val replanned: Boolean,
    /** False once the wearer boarded a later vehicle: the itinerary's times no longer apply. */
    val onPlannedTrip: Boolean = true,
    /** Bumped by every new plan, so its first step is a new step even at the same leg. */
    val planGeneration: Int,
    val startedAt: Instant,
    val expiresAt: Instant,
    val arrivedAt: Instant? = null,
) {
    val currentLeg: TransitLeg?
        get() = itinerary.legs.getOrNull(legIndex)
}

/**
 * Journey guidance as a pure state machine: position and time in, the next state and the step
 * the activity shows out. A fix leads and never runs ahead of the wearer; the timetable moves
 * the journey only without one, and only along the trip that was planned. It never counts down
 * a departure that has left: a missed boarding becomes its own step, asks for one replan, and
 * still turns into a ride when the wearer boards a later vehicle.
 */
internal object TransitJourneyGuide {
    const val ARRIVE_RADIUS_M = 40
    const val STOP_RADIUS_M = 300
    const val BOARDED_DISTANCE_M = 150
    const val WALK_SPEED_MPS = 1.25
    const val WALK_END_GRACE_MS = 90_000L
    const val MISSED_GRACE_MS = 90_000L
    /** Without real-time data the timetable says nothing about a late vehicle: wait longer. */
    const val SCHEDULED_MISSED_GRACE_MS = 4L * 60L * 1000L
    const val RIDE_END_GRACE_MS = 120_000L
    /** With a fix aboard, the vehicle must be this late before the timetable ends the ride. */
    const val RIDE_END_LATE_MS = 10L * 60L * 1000L
    const val ARRIVAL_GRACE_MS = 10L * 60L * 1000L
    const val EXPIRY_MARGIN_MS = 30L * 60L * 1000L
    const val MAX_TRACK_POSITIONS = 12

    data class Advance(val state: JourneyState, val needsReplan: Boolean)

    fun initial(
        id: String,
        itinerary: TransitItinerary,
        destinationLabel: String,
        destination: TransitCoordinate,
        alternatives: Int,
        now: Instant,
        replanned: Boolean = false,
        planGeneration: Int = 0,
    ): JourneyState = JourneyState(
        id = id,
        itinerary = itinerary,
        destinationLabel = destinationLabel,
        destination = destination,
        alternatives = alternatives,
        legIndex = 0,
        phase = phaseFor(itinerary.legs.first()),
        rideStopIndex = 0,
        replanned = replanned,
        planGeneration = planGeneration,
        startedAt = now,
        expiresAt = itinerary.end.plusMillis(EXPIRY_MARGIN_MS),
    )

    fun isExpired(state: JourneyState, now: Instant): Boolean = !now.isBefore(state.expiresAt)

    fun advance(state: JourneyState, position: TransitCoordinate?, now: Instant): Advance {
        var current = state
        // A fix can complete several short legs at once, e.g. a transfer walk.
        repeat(state.itinerary.legs.size + 1) {
            val next = step(current, position, now)
            if (next.needsReplan || next.state == current) return next
            current = next.state
        }
        return Advance(current, needsReplan = false)
    }

    private fun step(state: JourneyState, position: TransitCoordinate?, now: Instant): Advance {
        if (state.phase == JourneyPhase.ARRIVED) return Advance(state, false)
        val leg = state.currentLeg ?: return Advance(arrive(state, now), false)
        if (state.phase == JourneyPhase.MISSED) {
            // Only boarding a later vehicle moves a missed leg on; the planned times are then void.
            val aboardAt = position?.let { aboardIndex(leg, it) } ?: return Advance(state, false)
            return Advance(
                state.copy(
                    phase = JourneyPhase.RIDE,
                    rideStopIndex = aboardAt,
                    onPlannedTrip = false,
                    expiresAt = maxOf(state.expiresAt, now.plusMillis(EXPIRY_MARGIN_MS)),
                ),
                false,
            )
        }
        val timed = state.onPlannedTrip
        if (timed && !now.isBefore(state.itinerary.end.plusMillis(ARRIVAL_GRACE_MS))) return Advance(arrive(state, now), false)
        return when (state.phase) {
            JourneyPhase.WALK -> {
                // A fix that never comes within reach of the stop (a platform deep inside a
                // station) must not hold the walk forever: past the leg's end, time completes it.
                val byPosition = position?.let { haversineMeters(it, leg.to.coordinate) <= ARRIVE_RADIUS_M } == true
                val byTime = timed && !now.isBefore(leg.end.plusMillis(if (position == null) 0L else WALK_END_GRACE_MS))
                Advance(if (byPosition || byTime) nextLeg(state, now) else state, false)
            }
            JourneyPhase.BOARD -> {
                val aboardAt = position?.let { aboardIndex(leg, it) }
                val grace = if (leg.realTime) MISSED_GRACE_MS else SCHEDULED_MISSED_GRACE_MS
                when {
                    aboardAt != null -> Advance(state.copy(phase = JourneyPhase.RIDE, rideStopIndex = aboardAt), false)
                    position == null && timed && !now.isBefore(leg.start) ->
                        Advance(state.copy(phase = JourneyPhase.RIDE, rideStopIndex = timeIndex(leg, now)), false)
                    position != null && now.isAfter(leg.start.plusMillis(grace)) ->
                        Advance(state.copy(phase = JourneyPhase.MISSED), needsReplan = !state.replanned)
                    else -> Advance(state, false)
                }
            }
            JourneyPhase.RIDE -> {
                val last = leg.stops.lastIndex
                val index = when {
                    position != null -> nearestStopIndex(leg, position, from = state.rideStopIndex) ?: state.rideStopIndex
                    timed -> maxOf(state.rideStopIndex, timeIndex(leg, now))
                    else -> state.rideStopIndex
                }
                val lateBy = if (position == null) RIDE_END_GRACE_MS else RIDE_END_LATE_MS
                val done = index >= last || (timed && now.isAfter(leg.end.plusMillis(lateBy)))
                Advance(if (done) nextLeg(state, now) else state.copy(rideStopIndex = index), false)
            }
            else -> Advance(state, false)
        }
    }

    /** The replan that follows a missed boarding: a new plan, marked, at its first leg. */
    fun replanned(state: JourneyState, itinerary: TransitItinerary, alternatives: Int, now: Instant): JourneyState =
        initial(
            id = state.id,
            itinerary = itinerary,
            destinationLabel = state.destinationLabel,
            destination = state.destination,
            alternatives = alternatives,
            now = state.startedAt,
            replanned = true,
            planGeneration = state.planGeneration + 1,
        ).let { it.copy(expiresAt = maxOf(it.expiresAt, now.plusMillis(EXPIRY_MARGIN_MS))) }

    /** What the activity shows for [state]. */
    fun guidance(state: JourneyState, position: TransitCoordinate?, now: Instant, zone: ZoneId): NexusGuidanceStep {
        // Aboard a later vehicle than planned, the itinerary's arrival means nothing.
        val eta = if (state.onPlannedTrip) TransitSkillContract.localTime(state.itinerary.end, zone) else null
        val generation = state.planGeneration
        val leg = state.currentLeg
        if (state.phase == JourneyPhase.ARRIVED || leg == null) {
            return NexusGuidanceStep(
                glyph = NexusGuidanceGlyphs.ARRIVE,
                primary = "Arrived",
                secondary = fit(state.destinationLabel, MAX_SECONDARY),
                progressPercent = 100,
                stepKey = "arrived",
                arrived = true,
            )
        }
        return when (state.phase) {
            JourneyPhase.WALK -> {
                val remainingMeters = position?.let { haversineMeters(it, leg.to.coordinate) }
                val minutes = if (remainingMeters != null) {
                    ceil(remainingMeters / WALK_SPEED_MPS / 60.0).toInt()
                } else {
                    ceil((leg.end.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt()
                }.coerceAtLeast(1)
                val nextRide = state.itinerary.legs.drop(state.legIndex + 1).firstOrNull { !it.isWalk }
                val target = if (state.legIndex == state.itinerary.legs.lastIndex) state.destinationLabel else leg.to.name
                NexusGuidanceStep(
                    glyph = NexusGuidanceGlyphs.WALK,
                    primary = "$minutes min",
                    measure = remainingMeters?.let(::distanceLabel),
                    secondary = fit(target, MAX_SECONDARY),
                    eta = eta,
                    detail = listOfNotNull(
                        nextRide?.let {
                            fit("Board ${it.line ?: vehicleName(it)} at ${TransitSkillContract.localTime(it.start, zone)}", MAX_DETAIL)
                        } ?: fit("then you arrive", MAX_DETAIL),
                    ),
                    stepKey = "walk-${state.legIndex}-$generation",
                )
            }
            JourneyPhase.BOARD -> {
                val minutes = ceil((leg.start.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt()
                NexusGuidanceStep(
                    glyph = vehicleGlyph(leg),
                    badge = leg.line?.takeIf { it.length <= MAX_BADGE },
                    primary = if (minutes <= 0) "Now" else "$minutes min",
                    secondary = fit(leg.headsign?.let { "to $it" } ?: leg.to.name, MAX_SECONDARY),
                    eta = eta,
                    detail = listOf(
                        fit("Board at ${leg.from.name}", MAX_DETAIL),
                        fit(departureLine(leg, zone), MAX_DETAIL),
                    ),
                    stepKey = "board-${state.legIndex}-$generation",
                    imminent = minutes <= 1,
                )
            }
            JourneyPhase.RIDE -> {
                val stops = leg.stops
                val remaining = (stops.lastIndex - state.rideStopIndex).coerceAtLeast(0)
                val alight = leg.to.name
                NexusGuidanceStep(
                    glyph = vehicleGlyph(leg),
                    badge = leg.line?.takeIf { it.length <= MAX_BADGE },
                    primary = when {
                        remaining <= 1 -> "Get off"
                        else -> "$remaining stops"
                    },
                    secondary = fit(if (remaining <= 1) "Next stop: $alight" else "Get off at $alight", MAX_SECONDARY),
                    eta = eta,
                    detail = listOfNotNull(leg.headsign?.let { fit("towards $it", MAX_DETAIL) }),
                    track = track(stops, state.rideStopIndex, alight),
                    progressPercent = if (stops.size > 1) (state.rideStopIndex * 100 / stops.lastIndex) else null,
                    stepKey = "ride-${state.legIndex}-$generation",
                    imminent = remaining <= 1,
                )
            }
            else -> NexusGuidanceStep(
                glyph = vehicleGlyph(leg),
                badge = leg.line?.takeIf { it.length <= MAX_BADGE },
                primary = "Missed",
                secondary = fit("${leg.line ?: vehicleName(leg)} has left", MAX_SECONDARY),
                eta = null,
                detail = listOf(fit(if (state.replanned) "Ask for a new route" else "Finding a new route", MAX_DETAIL)),
                stepKey = "missed-${state.legIndex}-$generation",
            )
        }
    }

    /** Hours the activity may live: the journey plus its expiry margin, within the platform cap. */
    fun maxDurationMs(state: JourneyState): Long =
        (state.expiresAt.toEpochMilli() - state.startedAt.toEpochMilli()).coerceIn(60_000L, 12L * 60L * 60L * 1000L)

    private fun phaseFor(leg: TransitLeg): JourneyPhase = if (leg.isWalk) JourneyPhase.WALK else JourneyPhase.BOARD

    private fun nextLeg(state: JourneyState, now: Instant): JourneyState {
        val index = state.legIndex + 1
        val leg = state.itinerary.legs.getOrNull(index) ?: return arrive(state, now)
        return state.copy(legIndex = index, phase = phaseFor(leg), rideStopIndex = 0)
    }

    private fun arrive(state: JourneyState, now: Instant): JourneyState =
        state.copy(phase = JourneyPhase.ARRIVED, arrivedAt = state.arrivedAt ?: now)

    /** The stop index the wearer is at once clearly away from the boarding stop along the route. */
    private fun aboardIndex(leg: TransitLeg, position: TransitCoordinate): Int? {
        if (haversineMeters(position, leg.from.coordinate) <= BOARDED_DISTANCE_M) return null
        return nearestStopIndex(leg, position, from = 1)
    }

    private fun nearestStopIndex(leg: TransitLeg, position: TransitCoordinate, from: Int): Int? =
        leg.stops.withIndex()
            .drop(from)
            .map { (index, place) -> index to haversineMeters(position, place.coordinate) }
            .filter { (_, meters) -> meters <= STOP_RADIUS_M }
            .minByOrNull { it.second }
            ?.first

    /** The last stop the timetable says the vehicle has reached, for when there is no fix. */
    private fun timeIndex(leg: TransitLeg, now: Instant): Int =
        leg.stops.indexOfLast { place -> (place.arrival ?: place.departure)?.isAfter(now) == false }.coerceAtLeast(0)

    private fun track(stops: List<TransitPlace>, index: Int, label: String): NexusActivityTrack? {
        if (stops.size < 2) return null
        val count = minOf(stops.size, MAX_TRACK_POSITIONS)
        val offset = stops.size - count
        return NexusActivityTrack(
            count = count,
            at = (index - offset).coerceIn(0, count - 1),
            target = count - 1,
            label = fit(label, MAX_TRACK_LABEL),
        )
    }

    private fun departureLine(leg: TransitLeg, zone: ZoneId): String {
        val time = TransitSkillContract.localTime(leg.start, zone)
        return if (leg.realTime) "Leaves $time" else "Scheduled $time"
    }

    private fun vehicleGlyph(leg: TransitLeg): String =
        NexusGuidanceGlyphs.forTransitMode(leg.mode) ?: GlyphContract.FALLBACK_GLYPH

    private fun vehicleName(leg: TransitLeg): String = when (vehicleGlyph(leg)) {
        NexusGuidanceGlyphs.BUS -> "the bus"
        NexusGuidanceGlyphs.TRAM -> "the tram"
        NexusGuidanceGlyphs.METRO -> "the metro"
        NexusGuidanceGlyphs.TRAIN -> "the train"
        else -> "the vehicle"
    }

    private fun distanceLabel(meters: Int): String = when {
        meters < 1000 -> "${(meters / 10.0).roundToInt() * 10} m"
        else -> String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000.0)
    }

    /** At most [max] characters, cut at a word where one is close, with an ellipsis. */
    fun fit(value: String, max: Int): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length <= max) return trimmed
        val room = max - 1
        val cut = trimmed.lastIndexOf(' ', room).takeIf { it >= room * 2 / 3 } ?: room
        return trimmed.substring(0, cut).trimEnd() + "…"
    }

    private const val MAX_SECONDARY = 28
    private const val MAX_DETAIL = 32
    private const val MAX_BADGE = 5
    private const val MAX_TRACK_LABEL = 20
}
