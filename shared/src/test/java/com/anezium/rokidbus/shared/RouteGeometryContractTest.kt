package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryContractTest {
    private val walk = RouteGeometry(
        spanMeters = 400.0,
        paths = listOf(
            RoutePath(RoutePathRole.STREET, listOf(RoutePoint(0, 500), RoutePoint(1000, 500))),
            RoutePath(RoutePathRole.COMPLETED, listOf(RoutePoint(500, 1000), RoutePoint(500, 700))),
            RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 700), RoutePoint(500, 500), RoutePoint(800, 500))),
        ),
        labels = listOf(RouteLabel("Rue de Rivoli", 620, 480, 0)),
        position = RoutePosition(RoutePositionQuality.LIVE, RoutePoint(500, 700), fixAgeS = 1),
        turn = RoutePoint(500, 500),
        destination = RoutePoint(800, 500),
        stops = listOf(RouteStopMark(300, 400, "A")),
    )

    @Test
    fun `a full geometry round trips through its canonical wire form`() {
        val encoded = JSONObject(RouteGeometryContract.encode(walk).toString())
        assertEquals(RouteGeometryResult.Valid(walk), RouteGeometryContract.validate(encoded))
        assertTrue(RouteGeometryContract.isValid(walk))
        // Points travel flat: x0,y0,x1,y1.
        assertEquals(4, encoded.getJSONArray("paths").getJSONObject(0).getJSONArray("points").length())
    }

    @Test
    fun `a position of quality none never keeps a point`() {
        val payload = RouteGeometryContract.encode(walk).put(
            "position",
            JSONObject().put("quality", "none").put("x", 10).put("y", 10),
        )
        val parsed = RouteGeometryContract.parseOrNull(payload)!!
        assertEquals(RoutePosition(RoutePositionQuality.NONE, null), parsed.position)
        val stale = RouteGeometryContract.encode(walk).put("position", JSONObject().put("quality", "stale").put("x", 10).put("y", 20).put("fixAgeS", 42))
        assertEquals(RoutePosition(RoutePositionQuality.STALE, RoutePoint(10, 20), 42), RouteGeometryContract.parseOrNull(stale)!!.position)
        // Stale or live without a point is malformed.
        assertInvalid(RouteGeometryContract.encode(walk).put("position", JSONObject().put("quality", "stale")))
    }

    @Test
    fun `path and point budgets hold exactly at their limits`() {
        fun geometry(paths: Int, pointsEach: Int) = JSONObject().put("spanMeters", 1000).put(
            "paths",
            JSONArray().apply {
                repeat(paths) {
                    put(JSONObject().put("role", "street").put("points", JSONArray().apply { repeat(pointsEach) { put(it % 1001).put(0) } }))
                }
            },
        )
        assertValid(geometry(RouteGeometryContract.MAX_PATHS, 2))
        assertInvalid(geometry(RouteGeometryContract.MAX_PATHS + 1, 2))
        // 1024 points in total pass; one more does not, whatever the path count.
        assertValid(geometry(4, RouteGeometryContract.MAX_POINTS / 4))
        assertInvalid(geometry(5, RouteGeometryContract.MAX_POINTS / 5 + 1))
        assertInvalid(geometry(1, 1))
    }

    @Test
    fun `serialized size is bounded even when counts are not exceeded`() {
        val label = "x".repeat(RouteGeometryContract.MAX_LABEL_CHARS)
        val wide = RouteGeometryContract.encode(walk).put(
            "padding",
            "y".repeat(RouteGeometryContract.MAX_BYTES),
        )
        assertInvalid(wide)
        assertValid(RouteGeometryContract.encode(walk.copy(labels = List(4) { RouteLabel(label, 1, 1, 3) })))
    }

    @Test
    fun `out of schema values are refused`() {
        val base = { JSONObject(RouteGeometryContract.encode(walk).toString()) }
        listOf(
            base().put("spanMeters", 0),
            base().put("spanMeters", 50_001),
            base().put("spanMeters", "400"),
            base().put("paths", JSONArray().put(JSONObject().put("role", "river").put("points", JSONArray().put(0).put(0).put(1).put(1)))),
            base().put("paths", JSONArray().put(JSONObject().put("role", "route").put("points", JSONArray().put(0).put(0).put(1)))),
            base().put("paths", JSONArray().put(JSONObject().put("role", "route").put("points", JSONArray().put(0).put(0).put(1001).put(1)))),
            base().put("paths", JSONArray().put(JSONObject().put("role", "route").put("points", JSONArray().put(0).put(0).put(-1).put(1)))),
            base().put("paths", JSONArray().put(JSONObject().put("role", "route").put("points", JSONArray().put(0).put(0).put(1.5).put(1)))),
            base().put("labels", JSONArray().apply { repeat(5) { put(JSONObject().put("text", "a").put("x", 1).put("y", 1).put("priority", 0)) } }),
            base().put("labels", JSONArray().put(JSONObject().put("text", "x".repeat(25)).put("x", 1).put("y", 1).put("priority", 0))),
            base().put("labels", JSONArray().put(JSONObject().put("text", "a").put("x", 1).put("y", 1).put("priority", 4))),
            base().put("stops", JSONArray().apply { repeat(5) { put(JSONObject().put("x", 1).put("y", 1).put("label", "A")) } }),
            base().put("stops", JSONArray().put(JSONObject().put("x", 1).put("y", 1).put("label", "ABCDE"))),
            base().put("turn", JSONObject().put("x", 1)),
            base().put("position", JSONObject().put("quality", "great").put("x", 1).put("y", 1)),
            base().put("position", JSONObject().put("quality", "live").put("x", 1).put("y", 1).put("fixAgeS", -1)),
        ).forEach { assertInvalid(it) }
        assertTrue(RouteGeometryContract.validate("not an object") is RouteGeometryResult.Invalid)
        assertNull(RouteGeometryContract.parseOrNull(null))
    }

    private fun assertValid(payload: JSONObject) {
        val result = RouteGeometryContract.validate(payload)
        assertTrue("$result", result is RouteGeometryResult.Valid)
    }

    private fun assertInvalid(payload: JSONObject) {
        val result = RouteGeometryContract.validate(payload)
        assertTrue("$payload", result is RouteGeometryResult.Invalid)
    }
}
