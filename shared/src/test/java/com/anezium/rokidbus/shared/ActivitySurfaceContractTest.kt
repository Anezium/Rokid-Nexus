package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySurfaceContractTest {
    @Test
    fun `empty slot assert uses an owner that no plugin can claim`() {
        val marker = ActivitySurfaceContract.emptySlotAssertPayload(seq = 42)

        assertTrue(ActivitySurfaceContract.isEmptySlotAssert(marker))
        assertFalse(
            PluginDescriptor.isValidId(
                ActivitySurfaceContract.EMPTY_ASSERT_OWNER_PLUGIN_ID,
            ),
        )
        assertFalse(
            ActivitySurfaceContract.isEmptySlotAssert(
                JSONObject()
                    .put("surfaceId", "nexus-hub:activity")
                    .put("localSurfaceId", "activity")
                    .put("ownerPluginId", "nexus-hub")
                    .put("seq", 43),
            ),
        )
    }

    @Test
    fun `protocol constants remain wire stable`() {
        assertEquals(1, ActivitySurfaceContract.VERSION)
        assertEquals(1 shl 7, BusCapabilityBits.ACTIVITY_SURFACE)
        assertEquals("INVALID_ACTIVITY", ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        assertEquals(
            "ACTIVITY_RATE_LIMITED",
            ActivitySurfaceContract.ERROR_ACTIVITY_RATE_LIMITED,
        )
        assertEquals(
            "CAPABILITY_NOT_AVAILABLE",
            ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE,
        )
    }

    @Test
    fun `normalizes a full start and accepts a future well formed glyph`() {
        val result = ActivitySurfaceContract.validateStart(
            startPayload()
                .put("glyph", "  some-future-glyph  ")
                .put("primary", "  300 m  ")
                .put("secondary", "  Rue de la Paix  ")
                .put("progress", 42)
                .put("eta", "  12:41  ")
                .put(
                    "detail",
                    JSONArray()
                        .put("  then right  ")
                        .put("  continue straight  "),
                )
                .put(
                    "actions",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "  mute  ")
                            .put("glyph", "  pause  ")
                            .put("label", "  Mute  "),
                    ),
                )
                .put("maxDurationMs", 1L),
        )

        val content = (result as ActivitySurfaceValidationResult.Valid).content
        assertEquals("some-future-glyph", content.glyph)
        assertEquals("300 m", content.primary)
        assertEquals("Rue de la Paix", content.secondary)
        assertEquals(ActivityProgress.Percent(42), content.progress)
        assertEquals("12:41", content.eta)
        assertEquals(listOf("then right", "continue straight"), content.detail)
        assertEquals(listOf(ActivityAction("mute", "pause", "Mute")), content.actions)
        assertEquals(ActivitySurfaceContract.MIN_MAX_DURATION_MS, content.maxDurationMs)
        assertFalse(content.wakeDisplay)

        val canonical = ActivitySurfaceContract.toPayload("maps:activity", content)
        assertEquals("maps:activity", canonical.getString("surfaceId"))
        assertEquals("activity", canonical.getString("kind"))
        assertEquals(42, canonical.getInt("progress"))
        assertFalse(canonical.has("significant"))
        assertEquals(
            content,
            (ActivitySurfaceContract.validateStart(canonical) as
                ActivitySurfaceValidationResult.Valid).content,
        )
    }

    @Test
    fun `indeterminate progress round trips and absent duration stays absent`() {
        val result = ActivitySurfaceContract.validateStart(
            startPayload().put("progress", "indeterminate"),
        )

        val content = (result as ActivitySurfaceValidationResult.Valid).content
        assertEquals(ActivityProgress.Indeterminate, content.progress)
        assertNull(content.maxDurationMs)

        val payload = ActivitySurfaceContract.toPayload("timer:activity", content)
        assertEquals("indeterminate", payload.getString("progress"))
        assertFalse(payload.has("maxDurationMs"))
    }

    @Test
    fun `an update keeps absent fields and clears present optional fields`() {
        val current = ActivitySurfaceContent(
            glyph = "turn-left",
            primary = "300 m",
            secondary = "Rue de la Paix",
            progress = ActivityProgress.Percent(42),
            eta = "12:41",
            detail = listOf("then right"),
            actions = listOf(ActivityAction("mute", "pause", "Mute")),
            maxDurationMs = 600_000L,
        )
        val result = ActivitySurfaceContract.validateUpdate(
            JSONObject()
                .put("secondary", JSONObject.NULL)
                .put("progress", JSONObject.NULL)
                .put("eta", "   ")
                .put("detail", JSONArray())
                .put("actions", JSONObject.NULL)
                .put("significant", true),
        )

        val patch = (result as ActivitySurfacePatchResult.Valid).patch
        val updated = patch.applyTo(current)
        assertEquals("turn-left", updated.glyph)
        assertEquals("300 m", updated.primary)
        assertNull(updated.secondary)
        assertNull(updated.progress)
        assertNull(updated.eta)
        assertTrue(updated.detail.isEmpty())
        assertTrue(updated.actions.isEmpty())
        assertEquals(600_000L, updated.maxDurationMs)
        assertTrue(patch.significant)
    }

    @Test
    fun `full object update writes explicit nulls and never carries duration`() {
        val content = ActivitySurfaceContent(
            glyph = "timer",
            primary = "4 min",
            secondary = null,
            progress = null,
            eta = null,
            detail = emptyList(),
            actions = emptyList(),
            maxDurationMs = 900_000L,
        )

        val quiet = ActivitySurfaceContract.toUpdatePayload(
            "timer:activity",
            content,
            significant = false,
        )
        assertTrue(quiet.has("secondary"))
        assertTrue(quiet.isNull("secondary"))
        assertTrue(quiet.has("progress"))
        assertTrue(quiet.isNull("progress"))
        assertTrue(quiet.has("eta"))
        assertTrue(quiet.isNull("eta"))
        assertEquals(0, quiet.getJSONArray("detail").length())
        assertEquals(0, quiet.getJSONArray("actions").length())
        assertFalse(quiet.has("maxDurationMs"))
        assertFalse(quiet.has("significant"))

        val loud = ActivitySurfaceContract.toUpdatePayload(
            "timer:activity",
            content,
            significant = true,
        )
        assertTrue(loud.getBoolean("significant"))
        assertTrue(ActivitySurfaceContract.validateUpdate(loud) is ActivitySurfacePatchResult.Valid)
    }

    @Test
    fun `progress accepts only its exact wire forms and bounds`() {
        listOf(0, 100, "indeterminate").forEach { progress ->
            assertTrue(
                ActivitySurfaceContract.validateStart(startPayload().put("progress", progress))
                    is ActivitySurfaceValidationResult.Valid,
            )
        }
        listOf(-1, 101, 1.5, "42", " Indeterminate ").forEach { progress ->
            assertTrue(
                progress.toString(),
                ActivitySurfaceContract.validateStart(startPayload().put("progress", progress))
                    is ActivitySurfaceValidationResult.Invalid,
            )
        }
    }

    @Test
    fun `text and collection caps are measured after trim and never truncated`() {
        val boundary = startPayload()
            .put("primary", "  " + "p".repeat(ActivitySurfaceContract.MAX_PRIMARY_CHARS) + "  ")
            .put(
                "secondary",
                "  " + "s".repeat(ActivitySurfaceContract.MAX_SECONDARY_CHARS) + "  ",
            )
            .put("eta", "  " + "e".repeat(ActivitySurfaceContract.MAX_ETA_CHARS) + "  ")
            .put(
                "detail",
                JSONArray()
                    .put("d".repeat(ActivitySurfaceContract.MAX_DETAIL_CHARS))
                    .put("second"),
            )
            .put(
                "actions",
                JSONArray()
                    .put(action("a", "play", "A"))
                    .put(action("b", "pause", "B"))
                    .put(action("c", "stop", "C")),
            )
        assertTrue(
            ActivitySurfaceContract.validateStart(boundary) is
                ActivitySurfaceValidationResult.Valid,
        )

        val invalid = listOf(
            startPayload().put(
                "primary",
                "p".repeat(ActivitySurfaceContract.MAX_PRIMARY_CHARS + 1),
            ),
            startPayload().put(
                "secondary",
                "s".repeat(ActivitySurfaceContract.MAX_SECONDARY_CHARS + 1),
            ),
            startPayload().put("eta", "e".repeat(ActivitySurfaceContract.MAX_ETA_CHARS + 1)),
            startPayload().put(
                "detail",
                JSONArray()
                    .put("a")
                    .put("b")
                    .put("c"),
            ),
            startPayload().put(
                "detail",
                JSONArray().put("d".repeat(ActivitySurfaceContract.MAX_DETAIL_CHARS + 1)),
            ),
            startPayload().put(
                "actions",
                JSONArray()
                    .put(action("a", "play", "A"))
                    .put(action("b", "pause", "B"))
                    .put(action("c", "stop", "C"))
                    .put(action("d", "next", "D")),
            ),
        )
        invalid.forEach { payload ->
            assertTrue(
                payload.toString(),
                ActivitySurfaceContract.validateStart(payload) is
                    ActivitySurfaceValidationResult.Invalid,
            )
        }
    }

    @Test
    fun `action strings have no invented caps or uniqueness rule`() {
        val long = "x".repeat(512)
        val result = ActivitySurfaceContract.validateStart(
            startPayload().put(
                "actions",
                JSONArray()
                    .put(action(long, "some-future-glyph", long))
                    .put(action(long, "some-future-glyph", long)),
            ),
        )

        val actions = (result as ActivitySurfaceValidationResult.Valid).content.actions
        assertEquals(2, actions.size)
        assertEquals(long, actions.first().id)
        assertEquals(long, actions.first().label)
    }

    @Test
    fun `actions reject missing blank malformed and wrong shaped fields`() {
        val invalidActions = listOf(
            JSONObject().put("glyph", "play").put("label", "Play"),
            action(" ", "play", "Play"),
            action("play", "Turn_Left", "Play"),
            action("play", "play", " "),
            JSONObject().put("id", 4).put("glyph", "play").put("label", "Play"),
        )
        invalidActions.forEach { entry ->
            assertTrue(
                entry.toString(),
                ActivitySurfaceContract.validateStart(
                    startPayload().put("actions", JSONArray().put(entry)),
                ) is ActivitySurfaceValidationResult.Invalid,
            )
        }
        assertTrue(
            ActivitySurfaceContract.validateStart(
                startPayload().put("actions", JSONArray().put("play")),
            ) is ActivitySurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `duration clamps but rejects non integral values`() {
        val floor = ActivitySurfaceContract.validateStart(
            startPayload().put("maxDurationMs", 1L),
        )
        val ceiling = ActivitySurfaceContract.validateStart(
            startPayload().put("maxDurationMs", Long.MAX_VALUE),
        )
        val fractional = ActivitySurfaceContract.validateStart(
            startPayload().put("maxDurationMs", 1.5),
        )

        assertEquals(
            ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            (floor as ActivitySurfaceValidationResult.Valid).content.maxDurationMs,
        )
        assertEquals(
            ActivitySurfaceContract.MAX_MAX_DURATION_MS,
            (ceiling as ActivitySurfaceValidationResult.Valid).content.maxDurationMs,
        )
        assertTrue(fractional is ActivitySurfaceValidationResult.Invalid)
    }

    @Test
    fun `required fields and update-only significant fail closed`() {
        val invalid = listOf(
            JSONObject().put("kind", "activity").put("primary", "4 min"),
            JSONObject().put("kind", "activity").put("glyph", "timer"),
            startPayload().put("kind", "pin"),
            startPayload().put("glyph", "Turn_Left"),
            startPayload().put("primary", "   "),
            startPayload().put("significant", true),
            startPayload().put("significant", "yes"),
        )
        invalid.forEach { payload ->
            assertTrue(
                payload.toString(),
                ActivitySurfaceContract.validateStart(payload) is
                    ActivitySurfaceValidationResult.Invalid,
            )
        }
        assertTrue(
            ActivitySurfaceContract.validateUpdate(
                JSONObject().put("significant", JSONObject.NULL),
            ) is ActivitySurfacePatchResult.Invalid,
        )
        assertTrue(
            ActivitySurfaceContract.validateUpdate(
                JSONObject().put("glyph", JSONObject.NULL),
            ) is ActivitySurfacePatchResult.Invalid,
        )
        assertTrue(
            ActivitySurfaceContract.validateUpdate(
                JSONObject().put("primary", JSONObject.NULL),
            ) is ActivitySurfacePatchResult.Invalid,
        )
    }

    @Test
    fun `wake display is an optional start boolean serialized only when true`() {
        val requested = ActivitySurfaceContract.validateStart(
            startPayload().put("wakeDisplay", true),
        ) as ActivitySurfaceValidationResult.Valid
        assertTrue(requested.content.wakeDisplay)
        assertTrue(
            ActivitySurfaceContract.toPayload("maps:activity", requested.content)
                .getBoolean("wakeDisplay"),
        )
        assertFalse(
            ActivitySurfaceContract.toUpdatePayload(
                "maps:activity",
                requested.content,
                significant = true,
            ).has("wakeDisplay"),
        )
        assertTrue(
            ActivitySurfaceContract.validateStart(startPayload().put("wakeDisplay", 1))
                is ActivitySurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `text is trimmed but internal newlines are not reinterpreted`() {
        val result = ActivitySurfaceContract.validateStart(
            startPayload().put("primary", "  4\nmin  "),
        )

        assertEquals(
            "4\nmin",
            (result as ActivitySurfaceValidationResult.Valid).content.primary,
        )
    }

    @Test
    fun `action and closed payloads carry stable owner routing fields`() {
        val action = ActivitySurfaceContract.actionPayload("maps:activity", "mute")
        assertEquals("maps:activity", action.getString("activityId"))
        assertEquals("mute", action.getString("id"))

        ActivityCloseReason.entries.forEach { reason ->
            val closed = ActivitySurfaceContract.closedPayload("maps:activity", reason)
            assertEquals("maps:activity", closed.getString("activityId"))
            assertEquals(reason.wireValue, closed.getString("reason"))
            assertEquals(reason, ActivityCloseReason.fromWireValue(reason.wireValue))
        }
        assertNull(ActivityCloseReason.fromWireValue("future"))
    }

    @Test
    fun `extras keep the v1 version and take their own feature bit`() {
        assertEquals(1, ActivitySurfaceContract.VERSION)
        assertEquals(1, ActivitySurfaceContract.EXTRAS_VERSION)
        assertEquals(1 shl 12, BusCapabilityBits.ACTIVITY_EXTRAS)
    }

    @Test
    fun `start carries a trimmed badge and a track`() {
        val result = ActivitySurfaceContract.validateStart(
            startPayload()
                .put("badge", "  38  ")
                .put(
                    "track",
                    JSONObject()
                        .put("count", 5)
                        .put("at", 2)
                        .put("target", 4)
                        .put("label", "  Luxembourg  "),
                ),
        )

        val content = (result as ActivitySurfaceValidationResult.Valid).content
        assertEquals("38", content.badge)
        assertEquals(ActivityTrack(count = 5, at = 2, target = 4, label = "Luxembourg"), content.track)

        val payload = ActivitySurfaceContract.toPayload("maps:activity", content)
        assertEquals("38", payload.getString("badge"))
        assertEquals(4, payload.getJSONObject("track").getInt("target"))
        assertEquals(
            content,
            (ActivitySurfaceContract.validateStart(payload) as ActivitySurfaceValidationResult.Valid).content,
        )
    }

    @Test
    fun `a measure travels with the primary and an update can clear it`() {
        val content = (ActivitySurfaceContract.validateStart(
            startPayload().put("measure", "  250 m  "),
        ) as ActivitySurfaceValidationResult.Valid).content
        assertEquals("250 m", content.measure)
        assertEquals("250 m", ActivitySurfaceContract.toPayload("maps:activity", content).getString("measure"))

        val cleared = ActivitySurfaceContract.toUpdatePayload("maps:activity", content.copy(measure = null), false)
        assertTrue(cleared.isNull("measure"))
        val patch = (ActivitySurfaceContract.validateUpdate(cleared) as ActivitySurfacePatchResult.Valid).patch
        assertNull(patch.applyTo(content).measure)

        val kept = (ActivitySurfaceContract.validateUpdate(JSONObject().put("primary", "2 min")) as
            ActivitySurfacePatchResult.Valid).patch
        assertEquals("250 m", kept.applyTo(content).measure)
    }

    @Test
    fun `a v1 start round trips without any extras field`() {
        val content = (ActivitySurfaceContract.validateStart(startPayload()) as
            ActivitySurfaceValidationResult.Valid).content
        val payload = ActivitySurfaceContract.toPayload("maps:activity", content)

        assertNull(content.badge)
        assertNull(content.measure)
        assertNull(content.track)
        assertFalse(payload.has("badge"))
        assertFalse(payload.has("measure"))
        assertFalse(payload.has("track"))
        assertFalse(payload.has("tone"))
    }

    @Test
    fun `out of cap extras are rejected, never truncated`() {
        listOf(
            startPayload().put("badge", "RER B1"),
            startPayload().put("badge", 38),
            startPayload().put("measure", "1,25 km a"),
            startPayload().put("measure", 250),
            startPayload().put("track", "5 stops"),
            startPayload().put("track", track(count = 1, at = 0, target = 0)),
            startPayload().put("track", track(count = 13, at = 0, target = 1)),
            startPayload().put("track", track(count = 5, at = 5, target = 5)),
            startPayload().put("track", track(count = 5, at = 3, target = 2)),
            startPayload().put("track", track(count = 5, at = 0, target = 1).put("label", "x".repeat(21))),
            startPayload().put("track", track(count = 5, at = 0.5, target = 1)),
        ).forEach { payload ->
            assertTrue(
                payload.toString(),
                ActivitySurfaceContract.validateStart(payload) is ActivitySurfaceValidationResult.Invalid,
            )
        }
    }

    @Test
    fun `tone is update only and urgent needs significant`() {
        assertTrue(
            ActivitySurfaceContract.validateStart(startPayload().put("tone", "urgent")) is
                ActivitySurfaceValidationResult.Invalid,
        )
        assertTrue(
            ActivitySurfaceContract.validateUpdate(JSONObject().put("tone", "urgent")) is
                ActivitySurfacePatchResult.Invalid,
        )
        assertTrue(
            ActivitySurfaceContract.validateUpdate(
                JSONObject().put("tone", "loud").put("significant", true),
            ) is ActivitySurfacePatchResult.Invalid,
        )

        val urgent = ActivitySurfaceContract.validateUpdate(
            JSONObject().put("tone", "urgent").put("significant", true),
        ) as ActivitySurfacePatchResult.Valid
        assertTrue(urgent.patch.urgent)
        assertTrue(urgent.patch.significant)
    }

    @Test
    fun `full update clears extras explicitly and marks urgent only when asked`() {
        val content = (ActivitySurfaceContract.validateStart(
            startPayload().put("badge", "38").put("track", track(count = 3, at = 0, target = 2)),
        ) as ActivitySurfaceValidationResult.Valid).content

        val cleared = ActivitySurfaceContract.toUpdatePayload(
            surfaceId = "maps:activity",
            content = content.copy(badge = null, track = null),
            significant = false,
        )
        assertTrue(cleared.isNull("badge"))
        assertTrue(cleared.isNull("track"))
        assertFalse(cleared.has("tone"))
        val patch = (ActivitySurfaceContract.validateUpdate(cleared) as ActivitySurfacePatchResult.Valid).patch
        val applied = patch.applyTo(content)
        assertNull(applied.badge)
        assertNull(applied.track)

        val urgent = ActivitySurfaceContract.toUpdatePayload(
            surfaceId = "maps:activity",
            content = content,
            significant = true,
            urgent = true,
        )
        assertEquals("urgent", urgent.getString("tone"))
        val urgentPatch = (ActivitySurfaceContract.validateUpdate(urgent) as ActivitySurfacePatchResult.Valid).patch
        assertTrue(urgentPatch.urgent)
        assertEquals(content.track, urgentPatch.applyTo(content).track)
    }

    private fun track(count: Number, at: Number, target: Number): JSONObject = JSONObject()
        .put("count", count)
        .put("at", at)
        .put("target", target)

    private fun startPayload(): JSONObject = JSONObject()
        .put("kind", ActivitySurfaceContract.KIND)
        .put("glyph", "timer")
        .put("primary", "4 min")

    private fun action(id: String, glyph: String, label: String): JSONObject = JSONObject()
        .put("id", id)
        .put("glyph", glyph)
        .put("label", label)
}
