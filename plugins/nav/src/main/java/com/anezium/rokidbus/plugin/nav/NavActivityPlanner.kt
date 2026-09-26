package com.anezium.rokidbus.plugin.nav

/** What to send for one piece of guidance. */
internal sealed class NavPlan {
    data class Start(val guidance: NavGuidance) : NavPlan()
    data class Update(val guidance: NavGuidance, val significant: Boolean, val urgent: Boolean) : NavPlan()
    object Unchanged : NavPlan()
}

/**
 * Turns successive guidance into activity traffic.
 *
 * A new step (the next maneuver, the next leg, arrival) is significant, so the
 * platform may flare it. The moment a step becomes imminent is urgent, once
 * per step; the urgent tone is only valid on a significant update, so that
 * moment is significant too. Everything else is a quiet update, and guidance
 * that changes nothing the wearer sees sends nothing.
 */
internal class NavActivityPlanner {
    private var shown: NavGuidance? = null
    private var urgentStepKey: String? = null

    fun plan(next: NavGuidance): NavPlan {
        val previous = shown
        shown = next
        if (previous == null) {
            urgentStepKey = next.stepKey.takeIf { next.imminent }
            return NavPlan.Start(next)
        }
        val urgent = next.imminent && urgentStepKey != next.stepKey
        if (urgent) urgentStepKey = next.stepKey
        val significant = urgent || next.stepKey != previous.stepKey || next.arrived != previous.arrived
        if (!significant && next.visibleEquals(previous)) return NavPlan.Unchanged
        return NavPlan.Update(next, significant = significant, urgent = urgent)
    }

    fun reset() {
        shown = null
        urgentStepKey = null
    }

    private fun NavGuidance.visibleEquals(other: NavGuidance): Boolean =
        glyph == other.glyph && primary == other.primary && secondary == other.secondary &&
            eta == other.eta && detail == other.detail && badge == other.badge &&
            track == other.track && progressPercent == other.progressPercent
}
