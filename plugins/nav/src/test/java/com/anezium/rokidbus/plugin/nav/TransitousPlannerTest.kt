package com.anezium.rokidbus.plugin.nav

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each Transitous request is one the wearer asked for: bounded, walking only, never retried. */
class TransitousPlannerTest {
    private val calls = mutableListOf<Pair<String, Map<String, String>>>()
    private val destination = NavDestination("Paris City Hall", GeoPoint(48.8566, 2.3522))

    @Test
    fun `a plan is one walking-only request`() {
        val planner = TransitousPlanner(fetch = { path, query -> calls += path to query; fixture() })
        val route = planner.plan(GeoPoint(48.853, 2.349), destination)
        assertEquals(1, calls.size)
        val (path, query) = calls.single()
        assertEquals("/api/v2/plan", path)
        assertEquals("WALK", query["directModes"])
        assertEquals("", query["transitModes"])
        assertEquals("1", query["numItineraries"])
        assertEquals("48.853,2.349", query["fromPlace"])
        assertEquals(destination, route.destination)
    }

    @Test
    fun `a failed plan is reported once and never retried`() {
        val planner = TransitousPlanner(fetch = { path, query -> calls += path to query; error("Transitous is busy. Try again later.") })
        assertThrows(IllegalStateException::class.java) { planner.plan(GeoPoint(48.853, 2.349), destination) }
        assertEquals(1, calls.size)
    }

    @Test
    fun `a cancelled planner sends nothing`() {
        val planner = TransitousPlanner(fetch = { path, query -> calls += path to query; fixture() })
        planner.cancel()
        assertThrows(IllegalStateException::class.java) { planner.plan(GeoPoint(48.853, 2.349), destination) }
        assertThrows(IllegalStateException::class.java) { planner.search("Gare de Lyon") }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a search is bounded and keeps at most five distinct places`() {
        val planner = TransitousPlanner(fetch = { path, query ->
            calls += path to query
            JSONArray().apply {
                for (index in 0 until 8) {
                    put(
                        JSONObject().put("name", "Place $index").put("lat", 48.0 + (index / 2) * 0.01).put("lon", 2.0)
                            .put("areas", JSONArray().put(JSONObject().put("name", "France")).put(JSONObject().put("name", "Paris"))),
                    )
                }
            }.toString()
        })
        assertThrows(IllegalArgumentException::class.java) { planner.search("ab") }
        assertTrue(calls.isEmpty())
        val places = planner.search("  Gare de Lyon ")
        assertEquals(1, calls.size)
        assertEquals("/api/v1/geocode", calls.single().first)
        assertEquals("Gare de Lyon", calls.single().second["text"])
        // Five read, two of them at the same point as an earlier one.
        assertEquals(listOf("Place 0, France, Paris", "Place 2, France, Paris", "Place 4, France, Paris"), places.map { it.name })
    }

    private fun fixture() = javaClass.getResourceAsStream("/transitous-public-paris-walk.json")!!.bufferedReader().use { it.readText() }
}
