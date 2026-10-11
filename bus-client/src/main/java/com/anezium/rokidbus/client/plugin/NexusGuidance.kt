package com.anezium.rokidbus.client.plugin

import java.util.Locale

/**
 * The platform glyphs a guidance activity speaks in: maneuvers, the vehicle of the current
 * leg, and arrival. Every plugin that guides draws with the same words, so a route looks the
 * same on the glasses whoever follows it.
 */
object NexusGuidanceGlyphs {
    const val STRAIGHT = "straight"
    const val TURN_LEFT = "turn-left"
    const val TURN_RIGHT = "turn-right"
    const val TURN_SLIGHT_LEFT = "turn-slight-left"
    const val TURN_SLIGHT_RIGHT = "turn-slight-right"
    const val TURN_SHARP_LEFT = "turn-sharp-left"
    const val TURN_SHARP_RIGHT = "turn-sharp-right"
    const val U_TURN = "u-turn"
    const val ROUNDABOUT = "roundabout"
    const val ARRIVE = "arrive"
    const val WALK = "walk"
    const val BUS = "bus"
    const val TRAM = "tram"
    const val TRAIN = "train"
    const val METRO = "metro"

    /**
     * The vehicle glyph for a GTFS-style transit mode name (`BUS`, `SUBWAY`, `REGIONAL_RAIL`,
     * `TRAM`, ...), or null when the platform has no glyph for it.
     */
    fun forTransitMode(mode: String?): String? = when (mode?.trim()?.uppercase(Locale.ROOT)) {
        "WALK", "FOOT" -> WALK
        "BUS", "COACH", "TROLLEYBUS", "BUS_RAPID_TRANSIT" -> BUS
        "TRAM", "STREETCAR", "LIGHT_RAIL", "CABLE_CAR" -> TRAM
        "SUBWAY", "METRO" -> METRO
        "RAIL", "TRAIN", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "SUBURBAN", "HIGHSPEED_RAIL",
        "LONG_DISTANCE", "NIGHT_RAIL", "COMMUTER_RAIL",
        -> TRAIN
        else -> null
    }
}

/**
 * One piece of guidance as the wearer should see it. Everything visible maps onto a
 * [NexusActivity]; [stepKey] identifies the step (a new key is the next maneuver or leg),
 * [imminent] says the step is about to happen, and [arrived] marks the end of the route.
 */
data class NexusGuidanceStep(
    val glyph: String,
    val primary: String,
    val secondary: String? = null,
    val eta: String? = null,
    val detail: List<String> = emptyList(),
    val badge: String? = null,
    val track: NexusActivityTrack? = null,
    val measure: String? = null,
    val progressPercent: Int? = null,
    val stepKey: String,
    val imminent: Boolean = false,
    val arrived: Boolean = false,
) {
    /** The activity for this step. [maxDurationMs] and [wakeDisplay] only matter on a start. */
    fun toActivity(maxDurationMs: Long? = null, wakeDisplay: Boolean = false): NexusActivity = NexusActivity(
        glyph = glyph,
        primary = primary,
        secondary = secondary,
        progress = progressPercent?.let { NexusActivityProgress.Percent(it.coerceIn(0, 100)) },
        eta = eta,
        detail = detail,
        maxDurationMs = maxDurationMs,
        wakeDisplay = wakeDisplay,
        badge = badge,
        track = track,
        measure = measure,
    )

    internal fun visibleEquals(other: NexusGuidanceStep): Boolean =
        glyph == other.glyph && primary == other.primary && secondary == other.secondary &&
            eta == other.eta && detail == other.detail && badge == other.badge && measure == other.measure &&
            track == other.track && progressPercent == other.progressPercent
}

/** What to send for one piece of guidance. */
sealed class NexusGuidancePlan {
    data class Start(val step: NexusGuidanceStep) : NexusGuidancePlan()
    data class Update(val step: NexusGuidanceStep, val significant: Boolean, val urgent: Boolean) : NexusGuidancePlan()
    object Unchanged : NexusGuidancePlan()
}

/**
 * Turns successive guidance into activity traffic, identically for every plugin that guides.
 *
 * A new step (the next maneuver, the next leg, arrival) is significant, so the platform may
 * flare it. The moment a step becomes imminent is urgent, once per step; the urgent tone is
 * only valid on a significant update, so that moment is significant too. Everything else is a
 * quiet update, and guidance that changes nothing the wearer sees sends nothing.
 *
 * The planner only decides; the plugin sends, and calls [reset] whenever the activity it
 * describes is gone (ended, closed, or lost with a registration), so the next step starts it
 * again.
 */
class NexusGuidancePlanner {
    private var shown: NexusGuidanceStep? = null
    private var urgentStepKey: String? = null

    fun plan(next: NexusGuidanceStep): NexusGuidancePlan {
        val previous = shown
        shown = next
        if (previous == null) {
            urgentStepKey = next.stepKey.takeIf { next.imminent }
            return NexusGuidancePlan.Start(next)
        }
        val urgent = next.imminent && urgentStepKey != next.stepKey
        if (urgent) urgentStepKey = next.stepKey
        val significant = urgent || next.stepKey != previous.stepKey || next.arrived != previous.arrived
        if (!significant && next.visibleEquals(previous)) return NexusGuidancePlan.Unchanged
        return NexusGuidancePlan.Update(next, significant = significant, urgent = urgent)
    }

    fun reset() {
        shown = null
        urgentStepKey = null
    }
}
