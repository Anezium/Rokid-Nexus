package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.client.plugin.NexusGuidancePlan
import com.anezium.rokidbus.client.plugin.NexusGuidancePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TransitJourneyGuideTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val home = TransitCoordinate(48.8262, 2.3503)
    private val itinerary = sampleItinerary()

    private fun journey() = TransitJourneyGuide.initial("j1", itinerary, "Home", home, alternatives = 2, now = T0)

    private fun near(place: TransitPlace, northMeters: Double = 0.0) =
        TransitCoordinate(place.lat + northMeters / 111_000.0, place.lon)

    private val legs get() = itinerary.legs

    @Test
    fun `a journey walks, boards, rides, transfers, and arrives from positions`() {
        var state = journey()
        assertEquals(JourneyPhase.WALK, state.phase)

        state = TransitJourneyGuide.advance(state, near(legs[0].to), at(4)).state
        assertEquals(JourneyPhase.BOARD, state.phase)
        assertEquals(1, state.legIndex)

        state = TransitJourneyGuide.advance(state, near(legs[1].stops[1]), at(7)).state
        assertEquals(JourneyPhase.RIDE, state.phase)
        assertEquals(1, state.rideStopIndex)

        state = TransitJourneyGuide.advance(state, near(legs[1].stops[2]), at(9)).state
        assertEquals(2, state.rideStopIndex)
        val getOff = TransitJourneyGuide.guidance(state, near(legs[1].stops[2]), at(9), zone)
        assertEquals("Get off", getOff.primary)
        assertTrue(getOff.imminent)

        // Reaching D completes the ride and, being at D' too, the short transfer walk.
        state = TransitJourneyGuide.advance(state, near(legs[2].to), at(11)).state
        assertEquals(3, state.legIndex)
        assertEquals(JourneyPhase.BOARD, state.phase)

        state = TransitJourneyGuide.advance(state, near(legs[3].stops[1]), at(16)).state
        state = TransitJourneyGuide.advance(state, near(legs[3].to), at(18)).state
        assertEquals(JourneyPhase.WALK, state.phase)
        assertEquals(4, state.legIndex)

        state = TransitJourneyGuide.advance(state, home, at(21)).state
        assertEquals(JourneyPhase.ARRIVED, state.phase)
        val arrived = TransitJourneyGuide.guidance(state, home, at(21), zone)
        assertTrue(arrived.arrived)
        assertEquals("Home", arrived.secondary)
    }

    @Test
    fun `the shared planner turns transitions into significant and urgent traffic`() {
        val planner = NexusGuidancePlanner()
        var state = journey()
        fun plan(position: TransitCoordinate, minute: Long): NexusGuidancePlan {
            state = TransitJourneyGuide.advance(state, position, at(minute)).state
            return planner.plan(TransitJourneyGuide.guidance(state, position, at(minute), zone))
        }

        assertTrue(plan(near(legs[0].from), 0) is NexusGuidancePlan.Start)
        val closer = plan(near(legs[0].to, northMeters = -150.0), 2) as NexusGuidancePlan.Update
        assertFalse(closer.significant)
        val board = plan(near(legs[0].to), 4) as NexusGuidancePlan.Update
        assertTrue(board.significant)
        val ride = plan(near(legs[1].stops[1]), 7) as NexusGuidancePlan.Update
        assertTrue(ride.significant)
        assertFalse(ride.urgent)
        val getOff = plan(near(legs[1].stops[2]), 9) as NexusGuidancePlan.Update
        assertTrue(getOff.significant && getOff.urgent)
    }

    @Test
    fun `a ride shows its line, the stops left as a track, and where to get off`() {
        val state = journey().copy(legIndex = 1, phase = JourneyPhase.RIDE, rideStopIndex = 1)
        val step = TransitJourneyGuide.guidance(state, null, at(7), zone)

        assertEquals("bus", step.glyph)
        assertEquals("38", step.badge)
        assertEquals("2 stops", step.primary)
        assertEquals("Get off at Stop D", step.secondary)
        assertEquals(4, step.track!!.count)
        assertEquals(1, step.track!!.at)
        assertEquals(3, step.track!!.target)
        assertEquals("10:21", step.eta)
        // Every step must satisfy the activity caps the SDK enforces.
        step.toActivity(maxDurationMs = 60_000L)
    }

    @Test
    fun `a long ride keeps the target visible within twelve track positions`() {
        val many = (0 until 20).map { place("S$it", 48.8 + it * 0.004, 2.35, "s$it") }
        val leg = TransitLeg("TRAM", "T3", "End", many.first(), many.last(), at(0), at(40), null, null, true, null, many.subList(1, 19))
        val state = TransitJourneyGuide.initial("j", itinerary.copy(legs = listOf(leg)), "Home", home, 0, T0)
            .copy(phase = JourneyPhase.RIDE, rideStopIndex = 3)

        val track = TransitJourneyGuide.guidance(state, null, at(5), zone).track!!

        assertEquals(12, track.count)
        assertEquals(0, track.at)
        assertEquals(11, track.target)
    }

    @Test
    fun `a missed boarding asks for one replan and never counts down the departed vehicle`() {
        var state = TransitJourneyGuide.advance(journey(), near(legs[0].to), at(4)).state
        val waiting = TransitJourneyGuide.guidance(state, near(legs[0].to), at(4), zone)
        assertEquals("1 min", waiting.primary)
        assertTrue(waiting.imminent)

        val missed = TransitJourneyGuide.advance(state, near(legs[0].to), at(7))
        assertTrue(missed.needsReplan)
        assertEquals(JourneyPhase.MISSED, missed.state.phase)
        val step = TransitJourneyGuide.guidance(missed.state, near(legs[0].to), at(7), zone)
        assertEquals("Missed", step.primary)
        assertNull(step.eta)

        state = TransitJourneyGuide.replanned(missed.state, itinerary, 1, at(7))
        assertTrue(state.replanned)
        assertEquals(1, state.planGeneration)
        val again = TransitJourneyGuide.advance(
            TransitJourneyGuide.advance(state, near(legs[0].to), at(8)).state,
            near(legs[0].to),
            at(12),
        )
        assertFalse(again.needsReplan)
    }

    @Test
    fun `without a fix, time alone moves the journey forward`() {
        var state = journey()
        state = TransitJourneyGuide.advance(state, null, at(5)).state
        assertEquals(JourneyPhase.RIDE, state.phase)
        state = TransitJourneyGuide.advance(state, null, at(9)).state
        assertEquals(2, state.rideStopIndex)
    }

    @Test
    fun `a journey past its arrival ends as arrived and expires later`() {
        val state = TransitJourneyGuide.advance(journey(), null, at(21 + 11)).state
        assertEquals(JourneyPhase.ARRIVED, state.phase)
        assertFalse(TransitJourneyGuide.isExpired(state, at(40)))
        assertTrue(TransitJourneyGuide.isExpired(state, at(21 + 31)))
    }

    @Test
    fun `persisted state round-trips`() {
        val state = journey().copy(legIndex = 3, phase = JourneyPhase.RIDE, rideStopIndex = 1, replanned = true)
        assertEquals(state, TransitJourneyCodec.decode(TransitJourneyCodec.encode(state)))
        assertNull(TransitJourneyCodec.decode("{\"v\":9}"))
    }
}
