package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusGuidancePlan
import com.anezium.rokidbus.client.plugin.NexusGuidancePlanner
import java.time.Instant
import java.time.ZoneId

/** Where the journey's activity goes: the plugin's own registration, through the SDK. */
internal interface TransitJourneyActivitySink {
    val registrationGeneration: Int
    fun start(activity: NexusActivity): Boolean
    fun update(activity: NexusActivity, significant: Boolean, urgent: Boolean): Boolean
    fun end(): Boolean
}

/**
 * Owns the one active journey: its state, its persistence, and its activity. Positions and
 * ticks advance it through [TransitJourneyGuide]; the shared SDK planner turns each step into
 * start, significant, urgent, or quiet activity traffic, exactly as Navigation does.
 *
 * [listener] learns when a journey becomes active or ends, which is when the plugin holds and
 * releases its one location foreground service. Calls are serialized; the network work a tick
 * may do (refreshing the next boarding, one replan) is bounded by [networkBudgetMs].
 */
internal class TransitJourneyController(
    private val sink: TransitJourneyActivitySink,
    private val storage: TransitJourneyStorage,
    private val planner: TransitJourneyPlanner,
    private val boardingDepartures: (stopId: String, deadlineAtMs: Long) -> List<TransitDeparture>,
    private val listener: (active: Boolean) -> Unit,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val networkBudgetMs: Long = NETWORK_BUDGET_MS,
) {
    private val guidancePlanner = NexusGuidancePlanner()
    // Read without the lock by the HUD, which must not wait on a tick's network call.
    @Volatile private var state: JourneyState? = null
    private var position: TransitCoordinate? = null
    private var activityStarted = false
    private var activityGeneration = NO_GENERATION
    private var lastRefreshMs: Long? = null
    private var endAfterArrivalAtMs: Long? = null

    fun active(): JourneyState? = state

    @Synchronized
    fun lastPosition(): TransitCoordinate? = position

    /**
     * Starts guiding [itinerary]. Returns the new journey once its activity was accepted by the
     * SDK, or null, with nothing left running, when it could not be shown.
     */
    @Synchronized
    fun start(
        itinerary: TransitItinerary,
        alternatives: Int,
        destinationLabel: String,
        destination: TransitCoordinate,
        origin: TransitCoordinate?,
    ): JourneyState? {
        if (state != null) finish()
        position = origin
        val journey = TransitJourneyGuide.initial(
            id = newId(),
            itinerary = itinerary,
            destinationLabel = destinationLabel,
            destination = destination,
            alternatives = alternatives,
            now = clock(),
        )
        state = journey
        storage.save(journey)
        listener(true)
        if (!render()) {
            finish()
            return null
        }
        return journey
    }

    /** Ends guidance. False when [journeyId] names no active journey, which stays ended. */
    @Synchronized
    fun stop(journeyId: String?): Boolean {
        val current = state ?: return false
        if (journeyId != null && journeyId != current.id) return false
        finish()
        return true
    }

    /** Picks up a persisted journey after a restart; an expired one is discarded, never resumed. */
    @Synchronized
    fun resume(): Boolean {
        if (state != null) return true
        val saved = storage.load() ?: return false
        if (TransitJourneyGuide.isExpired(saved, clock())) {
            storage.clear()
            return false
        }
        state = saved
        listener(true)
        render()
        return true
    }

    @Synchronized
    fun onPosition(fix: TransitCoordinate) {
        position = fix
        tick()
    }

    @Synchronized
    fun onTick() = tick()

    /** A new registration holds no activity yet: start it again from the current step. */
    @Synchronized
    fun onRegistrationChanged() {
        if (state == null) return
        activityStarted = false
        render()
    }

    /** The activity is gone for a reason other than this controller ending it. */
    @Synchronized
    fun onActivityLost() {
        activityStarted = false
    }

    private fun tick() {
        val current = state ?: return
        val now = clock()
        if (TransitJourneyGuide.isExpired(current, now)) {
            finish()
            return
        }
        if (current.phase == JourneyPhase.ARRIVED) {
            val endAt = endAfterArrivalAtMs
            if (endAt != null && monotonicMs() >= endAt) finish()
            return
        }
        val refreshed = refreshBoarding(current)
        val advance = TransitJourneyGuide.advance(refreshed, position, now)
        var next = advance.state
        if (advance.needsReplan) next = replan(next, now)
        if (next != current) {
            state = next
            storage.save(next)
        }
        if (next.phase == JourneyPhase.ARRIVED && endAfterArrivalAtMs == null) {
            endAfterArrivalAtMs = monotonicMs() + ARRIVED_LINGER_MS
        }
        render()
    }

    /** Keeps the next boarding's time current, at most once per [BOARDING_REFRESH_MS]. */
    private fun refreshBoarding(current: JourneyState): JourneyState {
        val nowMs = monotonicMs()
        val last = lastRefreshMs
        if (last != null && nowMs - last < BOARDING_REFRESH_MS) return current
        val index = current.itinerary.legs.withIndex()
            .drop(current.legIndex)
            .firstOrNull { (i, leg) -> !leg.isWalk && (i > current.legIndex || current.phase == JourneyPhase.BOARD) }
            ?.index ?: return current
        val leg = current.itinerary.legs[index]
        val stopId = leg.from.stopId ?: return current
        lastRefreshMs = nowMs
        val departures = runCatching { boardingDepartures(stopId, nowMs + networkBudgetMs) }.getOrNull() ?: return current
        val match = departures.singleOrNull { departure ->
            if (leg.tripId != null && departure.tripId != null) {
                departure.tripId == leg.tripId
            } else {
                departure.routeShortName == leg.line && departure.scheduledDeparture != null &&
                    departure.scheduledDeparture == leg.scheduledStart
            }
        } ?: return current
        if (match.departure == leg.start) return current
        val updated = leg.copy(start = match.departure, realTime = match.realTime ?: leg.realTime)
        val legs = current.itinerary.legs.toMutableList().also { it[index] = updated }
        return current.copy(itinerary = current.itinerary.copy(legs = legs))
    }

    /** One replan from the current position after a missed boarding; a failure keeps the missed step. */
    private fun replan(missed: JourneyState, now: Instant): JourneyState {
        val from = position ?: return missed.copy(replanned = true)
        val planned = planner.plan(from, missed.destination, null, monotonicMs() + networkBudgetMs)
            as? TransitPlanResult.Planned ?: return missed.copy(replanned = true)
        val itinerary = planned.itineraries.firstOrNull() ?: return missed.copy(replanned = true)
        val alternatives = (planned.itineraries.size - 1).coerceIn(0, TransitSkillContract.MAX_ALTERNATIVES)
        return TransitJourneyGuide.replanned(missed, itinerary, alternatives, now)
    }

    private fun render(): Boolean {
        val current = state ?: return false
        if (!activityStarted || sink.registrationGeneration != activityGeneration) guidancePlanner.reset()
        val step = TransitJourneyGuide.guidance(current, position, clock(), zone())
        return when (val plan = guidancePlanner.plan(step)) {
            is NexusGuidancePlan.Start -> {
                val sent = sink.start(
                    plan.step.toActivity(
                        maxDurationMs = TransitJourneyGuide.maxDurationMs(current),
                        wakeDisplay = true,
                    ),
                )
                activityStarted = sent
                if (sent) activityGeneration = sink.registrationGeneration else guidancePlanner.reset()
                sent
            }
            is NexusGuidancePlan.Update -> {
                val sent = sink.update(plan.step.toActivity(), plan.significant, plan.urgent)
                if (!sent) {
                    activityStarted = false
                    guidancePlanner.reset()
                }
                sent
            }
            NexusGuidancePlan.Unchanged -> true
        }
    }

    private fun finish() {
        if (activityStarted) sink.end()
        activityStarted = false
        activityGeneration = NO_GENERATION
        guidancePlanner.reset()
        state = null
        lastRefreshMs = null
        endAfterArrivalAtMs = null
        storage.clear()
        listener(false)
    }

    companion object {
        const val ARRIVED_LINGER_MS = 30_000L
        const val BOARDING_REFRESH_MS = 60_000L
        const val NETWORK_BUDGET_MS = 8_000L
        private const val NO_GENERATION = -1
    }
}
