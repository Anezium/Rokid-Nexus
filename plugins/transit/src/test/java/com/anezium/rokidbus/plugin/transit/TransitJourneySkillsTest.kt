package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class TransitJourneySkillsTest {
    private class Sink : TransitJourneyActivitySink {
        var starts = 0
        override val registrationGeneration = 1
        override fun start(activity: NexusActivity): Boolean {
            starts++
            return true
        }
        override fun update(activity: NexusActivity, significant: Boolean, urgent: Boolean) = true
        override fun end() = true
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
        val requests = mutableListOf<Pair<TransitCoordinate, TransitCoordinate>>()
        var departAt: Instant? = null
        override fun plan(from: TransitCoordinate, to: TransitCoordinate, departAt: Instant?, deadlineAtMs: Long): TransitPlanResult {
            requests += from to to
            this.departAt = departAt
            return result
        }
    }

    private class Environment : TransitJourneyEnvironment {
        var access = TransitLocationAccess.READY
        var activity = true
        var fix: TransitCoordinate? = TransitCoordinate(48.8000, 2.3500)
        override fun locationAccess() = access
        override fun canShowActivity() = activity
        override fun currentLocation(timeoutMs: Long) = fix
    }

    private val home = TransitHome("Home sweet home", 48.8262, 2.3503)
    private var savedHome: TransitHome? = home
    private val sink = Sink()
    private val planner = Planner(TransitPlanResult.Planned(listOf(sampleItinerary(), sampleItinerary(), sampleItinerary())))
    private val environment = Environment()
    private val controller = TransitJourneyController(
        sink = sink,
        storage = Storage(),
        planner = planner,
        boardingDepartures = { _, _ -> emptyList() },
        listener = { },
        clock = { T0 },
        monotonicMs = { 0L },
        newId = { "journey-1" },
    )
    private val skills = TransitJourneySkills(
        controller = controller,
        planner = planner,
        home = object : TransitHomeSource {
            override fun home() = savedHome
        },
        environment = environment,
        monotonicMs = { 0L },
        zone = { ZoneId.of("Europe/Paris") },
    )

    private fun start(arguments: JSONObject = JSONObject().put("destination", "home")) =
        skills.start(arguments, deadlineAtMs = 14_000L)

    @Test
    fun `take me home plans from the position to home and starts guidance`() {
        val data = (start() as TransitSkillOutcome.Completed).data
        assertMatchesOutput(TransitSkillContract.START_JOURNEY, data)

        assertEquals("journey-1", data.getString("journey"))
        assertEquals("Home sweet home", data.getString("destination"))
        assertEquals(2, data.getInt("alternatives"))
        assertEquals(1, data.getInt("transfers"))
        assertEquals("10:05", data.getString("first_departure_local"))
        assertEquals("10:21", data.getString("arrival_local"))
        assertEquals(21, data.getInt("duration_minutes"))
        val legs = data.getJSONArray("legs")
        assertEquals("Your position", legs.getJSONObject(0).getString("from"))
        assertEquals("Home sweet home", legs.getJSONObject(4).getString("to"))
        assertEquals("38", legs.getJSONObject(1).getString("line"))
        assertEquals(3, legs.getJSONObject(1).getInt("stops"))
        assertEquals(1, sink.starts)
        assertEquals(home.coordinate, planner.requests.single().second)
    }

    @Test
    fun `the result never carries the position or home's location`() {
        val text = (start() as TransitSkillOutcome.Completed).data.toString()
        listOf("48.8262", "2.3503", "48.8", "START", "END").forEach { forbidden ->
            assertFalse("$forbidden in $text", text.contains(forbidden))
        }
    }

    @Test
    fun `without a home the wearer is asked, and nothing starts`() {
        savedHome = null
        val outcome = start()
        assertEquals(TransitSkillContract.INPUT_HOME_NOT_SET, (outcome as TransitSkillOutcome.NeedsInput).reason)
        assertEquals(0, sink.starts)
        assertTrue(planner.requests.isEmpty())
    }

    @Test
    fun `missing location access or fix is location_unavailable, and nothing starts`() {
        environment.access = TransitLocationAccess.MISSING_BACKGROUND
        assertEquals(TransitSkillOutcome.Failed(TransitSkillContract.ERROR_LOCATION_UNAVAILABLE), start())
        environment.access = TransitLocationAccess.READY
        environment.fix = null
        assertEquals(TransitSkillOutcome.Failed(TransitSkillContract.ERROR_LOCATION_UNAVAILABLE), start())
        assertEquals(0, sink.starts)
        assertNull(controller.active())
    }

    @Test
    fun `no itinerary is no_route, a failed request is unavailable, glasses without activities need setup`() {
        planner.result = TransitPlanResult.NoRoute
        assertEquals(TransitSkillOutcome.Failed(TransitSkillContract.ERROR_NO_ROUTE), start())
        planner.result = TransitPlanResult.Failed(IOException("offline"))
        assertEquals(TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE), start())
        environment.activity = false
        assertEquals(TransitSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED), start())
        assertEquals(0, sink.starts)
    }

    @Test
    fun `a stop from an earlier result can be the destination, with an optional departure time`() {
        val stop = TransitStop("stop-9", "Gare du Nord", 48.8809, 2.3553)
        val outcome = start(
            JSONObject()
                .put("destination", "stop")
                .put("stop", TransitSkillContract.encodeStop(stop))
                .put("depart_at", "2026-09-27T10:30:00+02:00"),
        )
        val data = (outcome as TransitSkillOutcome.Completed).data
        assertEquals("Gare du Nord", data.getString("destination"))
        assertEquals(TransitCoordinate(48.8809, 2.3553), planner.requests.single().second)
        assertEquals(Instant.parse("2026-09-27T08:30:00Z"), planner.departAt)

        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS),
            start(JSONObject().put("destination", "stop")),
        )
        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS),
            start(JSONObject().put("destination", "home").put("depart_at", "tomorrow at nine")),
        )
    }
}
