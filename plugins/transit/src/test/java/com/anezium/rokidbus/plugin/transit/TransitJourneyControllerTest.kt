package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TransitJourneyControllerTest {
    private class Sink : TransitJourneyActivitySink {
        val calls = mutableListOf<String>()
        val activities = mutableListOf<NexusActivity>()
        var accept = true
        override var registrationGeneration = 1
        override fun start(activity: NexusActivity): Boolean {
            calls += "start"
            activities += activity
            return accept
        }
        override fun update(activity: NexusActivity, significant: Boolean, urgent: Boolean): Boolean {
            calls += "update significant=$significant urgent=$urgent"
            activities += activity
            return accept
        }
        override fun end(): Boolean {
            calls += "end"
            return true
        }
    }

    private class Storage : TransitJourneyStorage {
        var saved: JourneyState? = null
        override fun load() = saved
        override fun save(state: JourneyState) {
            saved = state
        }
        override fun clear() {
            saved = null
        }
    }

    private class Planner(var result: TransitPlanResult) : TransitJourneyPlanner {
        var calls = 0
        override fun plan(from: TransitCoordinate, to: TransitCoordinate, departAt: java.time.Instant?, deadlineAtMs: Long): TransitPlanResult {
            calls++
            return result
        }
    }

    private var now = T0
    private var monotonic = 0L
    private val sink = Sink()
    private val storage = Storage()
    private val planner = Planner(TransitPlanResult.Planned(listOf(sampleItinerary())))
    private val activeChanges = mutableListOf<Boolean>()
    private val itinerary = sampleItinerary()
    private val home = TransitCoordinate(48.8262, 2.3503)
    private val controller = TransitJourneyController(
        sink = sink,
        storage = storage,
        planner = planner,
        boardingDepartures = { _, _ -> emptyList() },
        listener = { activeChanges += it },
        clock = { now },
        zone = { ZoneId.of("Europe/Paris") },
        monotonicMs = { monotonic },
        newId = { "journey-1" },
    )

    private fun near(place: TransitPlace) = TransitCoordinate(place.lat, place.lon)

    private fun start() = controller.start(itinerary, 2, "Home", home, near(itinerary.legs[0].from))

    @Test
    fun `starting guides the first step in a started, persisted activity`() {
        val journey = start()!!

        assertEquals("journey-1", journey.id)
        assertEquals(listOf("start"), sink.calls)
        assertEquals("walk", sink.activities.single().glyph)
        assertTrue(sink.activities.single().wakeDisplay)
        assertTrue(sink.activities.single().maxDurationMs!! > 0)
        assertEquals(journey, storage.saved)
        assertEquals(listOf(true), activeChanges)
    }

    @Test
    fun `an activity that cannot be shown leaves nothing running`() {
        sink.accept = false

        assertNull(start())
        assertNull(controller.active())
        assertNull(storage.saved)
        assertEquals(listOf(true, false), activeChanges)
    }

    @Test
    fun `positions drive the activity through the shared planner`() {
        start()
        now = at(4)
        controller.onPosition(near(itinerary.legs[0].to))
        now = at(7)
        controller.onPosition(near(itinerary.legs[1].stops[1]))
        now = at(9)
        controller.onPosition(near(itinerary.legs[1].stops[2]))

        assertEquals(
            listOf(
                "start",
                "update significant=true urgent=true",
                "update significant=true urgent=false",
                "update significant=true urgent=true",
            ),
            sink.calls,
        )
        assertEquals("Get off", sink.activities.last().primary)
    }

    @Test
    fun `stop ends the activity, clears the journey, and stays ended`() {
        start()

        assertTrue(controller.stop("journey-1"))
        assertEquals("end", sink.calls.last())
        assertNull(storage.saved)
        assertFalse(controller.stop("journey-1"))
        assertEquals(listOf(true, false), activeChanges)
    }

    @Test
    fun `a foreign journey id stops nothing`() {
        start()
        assertFalse(controller.stop("other"))
        assertTrue(controller.active() != null)
    }

    @Test
    fun `arrival lingers briefly, then ends the activity`() {
        start()
        // No fix along the way: the arrival grace after the planned arrival ends it by time.
        now = at(21 + 10)
        controller.onTick()
        assertEquals("Arrived", sink.activities.last().primary)
        assertTrue(controller.active() != null)

        monotonic += TransitJourneyController.ARRIVED_LINGER_MS
        controller.onTick()
        assertEquals("end", sink.calls.last())
        assertNull(controller.active())
    }

    @Test
    fun `a restart resumes the persisted journey and an expired one is dropped`() {
        start()
        val saved = storage.saved
        val restarted = TransitJourneyController(
            sink = sink,
            storage = storage,
            planner = planner,
            boardingDepartures = { _, _ -> emptyList() },
            listener = { },
            clock = { now },
            monotonicMs = { monotonic },
        )
        sink.calls.clear()
        assertTrue(restarted.resume())
        assertEquals(saved, restarted.active())
        assertEquals(listOf("start"), sink.calls)

        storage.saved = saved
        now = saved!!.expiresAt
        val late = TransitJourneyController(sink, storage, planner, { _, _ -> emptyList() }, { }, clock = { now })
        assertFalse(late.resume())
        assertNull(storage.saved)
    }

    @Test
    fun `a new registration starts the activity again at the current step`() {
        start()
        sink.registrationGeneration = 2
        controller.onRegistrationChanged()
        assertEquals(listOf("start", "start"), sink.calls)
    }

    @Test
    fun `a fix older than two minutes no longer holds the journey in place`() {
        start()
        now = at(6)
        monotonic = 60_000L
        controller.onTick()
        assertEquals(JourneyPhase.WALK, controller.active()!!.phase)

        // The same fix, now stale: time alone moves the walk on and boards by timetable.
        monotonic = TransitJourneyController.POSITION_MAX_AGE_MS
        controller.onTick()
        assertEquals(JourneyPhase.RIDE, controller.active()!!.phase)
    }

    @Test
    fun `a missed boarding replans once from the current position`() {
        start()
        now = at(4)
        controller.onPosition(near(itinerary.legs[0].to))
        now = at(7)
        planner.result = TransitPlanResult.NoRoute
        controller.onPosition(near(itinerary.legs[0].to))

        assertEquals(1, planner.calls)
        assertEquals(JourneyPhase.MISSED, controller.active()!!.phase)
        assertTrue(controller.active()!!.replanned)
        assertEquals("Missed", sink.activities.last().primary)

        now = at(8)
        controller.onTick()
        assertEquals(1, planner.calls)
    }

    @Test
    fun `a successful replan is announced as a significant new plan`() {
        start()
        now = at(4)
        controller.onPosition(near(itinerary.legs[0].to))
        now = at(7)
        controller.onPosition(near(itinerary.legs[0].to))

        val state = controller.active()!!
        assertTrue(state.replanned)
        assertEquals(1, state.planGeneration)
        assertTrue(sink.calls.last().startsWith("update significant=true"))
    }

    @Test
    fun `journey status and stop operations describe and end only the active journey`() {
        val skills = TransitJourneySkills(
            controller = controller,
            planner = planner,
            home = object : TransitHomeSource {
                override fun home() = null
            },
            environment = object : TransitJourneyEnvironment {
                override fun locationAccess() = TransitLocationAccess.READY
                override fun canShowActivity() = true
                override fun currentLocation(timeoutMs: Long): TransitCoordinate? = null
            },
            zone = { ZoneId.of("Europe/Paris") },
        )
        val idle = (skills.status(JSONObject()) as TransitSkillOutcome.Completed).data
        assertFalse(idle.getBoolean("active"))

        start()
        val status = (skills.status(JSONObject()) as TransitSkillOutcome.Completed).data
        assertMatchesOutput(TransitSkillContract.JOURNEY_STATUS, status)
        assertEquals("walk", status.getString("phase"))
        assertEquals("Stop A", status.getString("next_stop"))
        assertEquals("10:21", status.getString("arrival_local"))

        val stopped = (skills.stop(JSONObject().put("journey", "journey-1")) as TransitSkillOutcome.Completed).data
        assertMatchesOutput(TransitSkillContract.STOP_JOURNEY, stopped)
        assertTrue(stopped.getBoolean("was_active"))
        val again = (skills.stop(JSONObject().put("journey", "journey-1")) as TransitSkillOutcome.Completed).data
        assertFalse(again.getBoolean("was_active"))
        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS),
            skills.stop(JSONObject()),
        )
    }
}
