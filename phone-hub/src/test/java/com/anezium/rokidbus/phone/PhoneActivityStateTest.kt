package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActivityStateTest {
    private var now = 10_000L
    private val state = PhoneActivityState(nowMs = { now }, initialSequence = 40L)

    @Test
    fun `validates identity and content before consuming the update rate budget`() {
        assertRejected(
            state.start("alpha", startPayload("alpha").put("primary", "x".repeat(13))),
            ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
        )
        assertRejected(
            state.start("alpha", startPayload("alpha").put("ownerPluginId", "beta")),
            ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
        )
        assertRejected(
            state.start("alpha", startPayload("alpha").put("surfaceId", "alpha:other")),
            ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
        )

        repeat(ActivitySurfaceContract.MAX_UPDATES_PER_SECOND + 2) {
            assertTrue(state.start("alpha", startPayload("alpha")) is PhoneActivityStartResult.Accepted)
        }
        repeat(ActivitySurfaceContract.MAX_UPDATES_PER_SECOND) {
            assertTrue(
                state.update("alpha", updatePayload("alpha"))
                    is PhoneActivityUpdateResult.Accepted,
            )
        }
        assertUpdateRejected(
            state.update("alpha", updatePayload("alpha")),
            ActivitySurfaceContract.ERROR_ACTIVITY_RATE_LIMITED,
        )
    }

    @Test
    fun `start normalizes canonical identity and accepts a future glyph`() {
        val accepted = state.start(
            "maps",
            startPayload("maps")
                .put("glyph", "future-maneuver")
                .put("primary", " 300 m ")
                .put("secondary", " Rue de la Paix "),
        ) as PhoneActivityStartResult.Accepted

        assertEquals("maps", accepted.activity.ownerPluginId)
        assertEquals("maps:activity", accepted.payload.getString("surfaceId"))
        assertEquals("activity", accepted.payload.getString("localSurfaceId"))
        assertEquals("maps", accepted.payload.getString("ownerPluginId"))
        assertEquals(41L, accepted.payload.getLong("seq"))
        assertEquals("300 m", accepted.payload.getString("primary"))
        assertEquals("Rue de la Paix", accepted.payload.getString("secondary"))
        assertFalse(accepted.payload.has("significant"))
        assertFalse(accepted.activity.content.wakeDisplay)
        assertFalse(accepted.payload.has("wakeDisplay"))
        assertNull(accepted.replaced)
    }

    @Test
    fun `start wake request survives canonical updates and reconnect`() {
        val started = state.start(
            "maps",
            startPayload("maps").put("wakeDisplay", true),
        ) as PhoneActivityStartResult.Accepted
        assertTrue(started.activity.content.wakeDisplay)
        assertTrue(started.payload.getBoolean("wakeDisplay"))

        val updated = state.update(
            "maps",
            updatePayload("maps").put("significant", true),
        ) as PhoneActivityUpdateResult.Accepted
        assertTrue(updated.activity.content.wakeDisplay)
        assertFalse(updated.payload.has("wakeDisplay"))
        assertTrue(state.payloadsForResend().single().getBoolean("wakeDisplay"))

        assertRejected(
            state.start("other", startPayload("other").put("wakeDisplay", "yes")),
            ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
        )
    }

    @Test
    fun `starts do not consume the sliding per-plugin update budget`() {
        repeat(3) {
            assertTrue(state.start("alpha", startPayload("alpha")) is PhoneActivityStartResult.Accepted)
        }
        repeat(ActivitySurfaceContract.MAX_UPDATES_PER_SECOND) { index ->
            assertTrue(
                state.update(
                    "alpha",
                    updatePayload("alpha").put("primary", "${index + 1} min"),
                ) is PhoneActivityUpdateResult.Accepted,
            )
        }

        assertUpdateRejected(
            state.update("alpha", updatePayload("alpha").put("primary", "9 min")),
            ActivitySurfaceContract.ERROR_ACTIVITY_RATE_LIMITED,
        )
        // Other owners have independent windows.
        assertTrue(state.start("beta", startPayload("beta")) is PhoneActivityStartResult.Accepted)

        now += 999L
        assertUpdateRejected(
            state.update("alpha", updatePayload("alpha").put("primary", "8 min")),
            ActivitySurfaceContract.ERROR_ACTIVITY_RATE_LIMITED,
        )
        now += 1L
        assertTrue(
            state.update("alpha", updatePayload("alpha").put("primary", "8 min"))
                is PhoneActivityUpdateResult.Accepted,
        )
    }

    @Test
    fun `update patches canonical state but significance is transient`() {
        state.start(
            "maps",
            startPayload("maps")
                .put("secondary", "Rue de la Paix")
                .put("eta", "12:41")
                .put("detail", JSONArray().put("then right")),
        )

        val updated = state.update(
            "maps",
            updatePayload("maps")
                .put("primary", "250 m")
                .put("significant", true),
        ) as PhoneActivityUpdateResult.Accepted

        assertTrue(updated.significant)
        assertTrue(updated.payload.getBoolean("significant"))
        assertEquals("250 m", updated.payload.getString("primary"))
        assertEquals("Rue de la Paix", updated.payload.getString("secondary"))
        assertEquals("12:41", updated.payload.getString("eta"))
        assertEquals("then right", updated.payload.getJSONArray("detail").getString(0))
        assertEquals("maps", state.primaryOwnerPluginId())

        val resend = state.payloadsForResend().single()
        assertEquals("250 m", resend.getString("primary"))
        assertEquals("Rue de la Paix", resend.getString("secondary"))
        assertFalse(resend.has("significant"))
    }

    @Test
    fun `same owner start replaces in place without a close result`() {
        val first = state.start("alpha", startPayload("alpha")) as PhoneActivityStartResult.Accepted
        now += 1_000L
        val replacement = state.start(
            "alpha",
            startPayload("alpha").put("primary", "new"),
        ) as PhoneActivityStartResult.Accepted

        assertNull(first.replaced)
        assertNull(replacement.replaced)
        assertEquals(setOf("alpha"), state.ownerPluginIds())
        assertEquals("new", state.payloadsForResend().single().getString("primary"))
        assertTrue(replacement.payload.getLong("seq") > first.payload.getLong("seq"))
    }

    @Test
    fun `third owner protects the oldest fallback primary before significance`() {
        state.start("alpha", startPayload("alpha"))
        state.start("beta", startPayload("beta"))

        val third = state.start("gamma", startPayload("gamma")) as PhoneActivityStartResult.Accepted
        val replaced = assertNotNull(third.replaced).let { third.replaced!! }

        assertEquals("beta", replaced.ownerPluginId)
        assertEquals(ActivityCloseReason.REPLACED, replaced.reason)
        assertEquals("beta:activity", replaced.payload.getString("surfaceId"))
        assertEquals(setOf("alpha", "gamma"), state.ownerPluginIds())
        assertEquals("alpha", state.primaryOwnerPluginId())
    }

    @Test
    fun `capacity eviction protects the most recently significant primary`() {
        state.start("alpha", startPayload("alpha"))
        state.start("beta", startPayload("beta"))
        state.update(
            "beta",
            updatePayload("beta").put("primary", "nearby").put("significant", true),
        )
        // Make alpha newer overall; primary protection, not raw recency, decides the victim.
        state.update("alpha", updatePayload("alpha").put("primary", "4 min"))

        val third = state.start("gamma", startPayload("gamma")) as PhoneActivityStartResult.Accepted

        assertEquals("alpha", third.replaced?.ownerPluginId)
        assertEquals(setOf("beta", "gamma"), state.ownerPluginIds())
        assertEquals("beta", state.primaryOwnerPluginId())
    }

    @Test
    fun `primary falls back to the previous significant resident when the latest leaves`() {
        state.start("alpha", startPayload("alpha"))
        state.start("beta", startPayload("beta"))
        state.update(
            "alpha",
            updatePayload("alpha").put("primary", "alpha").put("significant", true),
        )
        state.update(
            "beta",
            updatePayload("beta").put("primary", "beta").put("significant", true),
        )
        assertEquals("beta", state.primaryOwnerPluginId())

        state.end("beta")
        assertEquals("alpha", state.primaryOwnerPluginId())
        state.start("gamma", startPayload("gamma"))
        val fourth = state.start("delta", startPayload("delta")) as PhoneActivityStartResult.Accepted

        assertEquals("gamma", fourth.replaced?.ownerPluginId)
        assertEquals(setOf("alpha", "delta"), state.ownerPluginIds())
    }

    @Test
    fun `no duration means no deadline and an update cannot extend a fixed deadline`() {
        state.start("untimed", startPayload("untimed"))
        assertNull(state.nextExpiryDeadlineMs())

        val timedState = PhoneActivityState(nowMs = { now }, initialSequence = 0L)
        timedState.start(
            "timer",
            startPayload("timer").put(
                "maxDurationMs",
                ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            ),
        )
        val fixedDeadline = now + ActivitySurfaceContract.MIN_MAX_DURATION_MS
        assertEquals(fixedDeadline, timedState.nextExpiryDeadlineMs())

        now += 30_000L
        timedState.update("timer", updatePayload("timer").put("primary", "30 sec"))
        assertEquals(fixedDeadline, timedState.nextExpiryDeadlineMs())
        assertTrue(timedState.expireIfDue().isEmpty())

        now = fixedDeadline
        val expired = timedState.expireIfDue().single()
        assertEquals("timer", expired.ownerPluginId)
        assertEquals(ActivityCloseReason.MAX_DURATION, expired.reason)
        assertNull(timedState.nextExpiryDeadlineMs())
    }

    @Test
    fun `all clear causes use owner scoped payloads and remove state once`() {
        state.start("owner", startPayload("owner"))
        val ended = state.end("owner") as PhoneActivityClearResult.Cleared
        assertEquals(ActivityCloseReason.OWNER, ended.reason)
        assertEquals("owner:activity", ended.payload.getString("surfaceId"))
        assertTrue(state.end("owner") is PhoneActivityClearResult.Ignored)

        now += 1_000L
        state.start("drop", startPayload("drop"))
        val disconnected = state.ownerDisconnected("drop") as PhoneActivityClearResult.Cleared
        assertEquals(ActivityCloseReason.DISCONNECT, disconnected.reason)

        now += 1_000L
        state.start("remote", startPayload("remote"))
        assertTrue(
            state.closedByGlasses("wrong:activity", ActivityCloseReason.REPLACED)
                is PhoneActivityClearResult.Ignored,
        )
        val remote = state.closedByGlasses(
            "remote:activity",
            ActivityCloseReason.REPLACED,
        ) as PhoneActivityClearResult.Cleared
        assertEquals(ActivityCloseReason.REPLACED, remote.reason)
        assertTrue(remote.payload.getLong("seq") > disconnected.payload.getLong("seq"))
    }

    @Test
    fun `action owner resolution accepts only a current advertised action`() {
        state.start(
            "player",
            startPayload("player").put(
                "actions",
                JSONArray()
                    .put(action("pause", "pause", "Pause"))
                    .put(action("skip", "forward", "Skip")),
            ),
        )

        assertEquals("player", state.ownerForActivity("player:activity"))
        assertEquals("player", state.ownerForAction("player:activity", "pause"))
        assertNull(state.ownerForAction("other:activity", "pause"))
        assertNull(state.ownerForAction("player:activity", "missing"))

        state.update("player", updatePayload("player").put("actions", JSONArray()))
        assertNull(state.ownerForAction("player:activity", "pause"))
    }

    @Test
    fun `reconnect assert is global and resends every canonical activity without flare replay`() {
        state.start(
            "alpha",
            startPayload("alpha").put(
                "maxDurationMs",
                ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            ),
        )
        state.start("beta", startPayload("beta"))
        state.update(
            "beta",
            updatePayload("beta").put("primary", "near").put("significant", true),
        )
        now += ActivitySurfaceContract.MIN_MAX_DURATION_MS - 500L

        val assertEmpty = state.emptySlotAssertPayload()
        val payloads = state.payloadsForResend()

        assertEquals("@nexus-hub:activity", assertEmpty.getString("surfaceId"))
        assertEquals("@nexus-hub", assertEmpty.getString("ownerPluginId"))
        assertTrue(ActivitySurfaceContract.isEmptySlotAssert(assertEmpty))
        assertEquals(setOf("alpha:activity", "beta:activity"), payloads.map {
            it.getString("surfaceId")
        }.toSet())
        assertEquals("beta", payloads.first().getString("ownerPluginId"))
        payloads.forEach { assertFalse(it.has("significant")) }
        val alpha = payloads.single { it.getString("ownerPluginId") == "alpha" }
        assertEquals(
            ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            alpha.getLong("maxDurationMs"),
        )
        val resendSequences = payloads.map { it.getLong("seq") }
        assertTrue(resendSequences.all { it > assertEmpty.getLong("seq") })
        assertEquals(resendSequences.sorted(), resendSequences)
        assertEquals(resendSequences.toSet().size, resendSequences.size)
    }

    @Test
    fun `expiry clears every activity due on the same tick`() {
        state.start(
            "alpha",
            startPayload("alpha").put(
                "maxDurationMs",
                ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            ),
        )
        state.start(
            "beta",
            startPayload("beta").put(
                "maxDurationMs",
                ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            ),
        )
        now += ActivitySurfaceContract.MIN_MAX_DURATION_MS

        val expired = state.expireIfDue()

        assertEquals(setOf("alpha", "beta"), expired.map { it.ownerPluginId }.toSet())
        assertTrue(expired.all { it.reason == ActivityCloseReason.MAX_DURATION })
        assertTrue(state.ownerPluginIds().isEmpty())
    }

    @Test
    fun `hub shutdown disconnects every resident exactly once`() {
        state.start("alpha", startPayload("alpha"))
        state.start("beta", startPayload("beta"))

        val cleared = state.disconnectAll()

        assertEquals(setOf("alpha", "beta"), cleared.map { it.ownerPluginId }.toSet())
        assertTrue(cleared.all { it.reason == ActivityCloseReason.DISCONNECT })
        assertTrue(state.ownerPluginIds().isEmpty())
        assertTrue(state.disconnectAll().isEmpty())
    }

    @Test
    fun `extras survive canonical state while the urgent tone stays transient`() {
        state.start(
            "nav",
            startPayload("nav")
                .put("badge", "38")
                .put(
                    "track",
                    JSONObject().put("count", 5).put("at", 2).put("target", 4).put("label", "Luxembourg"),
                ),
        )

        val urgent = state.update(
            "nav",
            updatePayload("nav")
                .put("primary", "Get off")
                .put("significant", true)
                .put("tone", ActivitySurfaceContract.TONE_URGENT),
        ) as PhoneActivityUpdateResult.Accepted
        assertEquals(ActivitySurfaceContract.TONE_URGENT, urgent.payload.getString("tone"))
        assertEquals("38", urgent.payload.getString("badge"))
        assertEquals(2, urgent.payload.getJSONObject("track").getInt("at"))

        val resend = state.payloadsForResend().single()
        assertEquals("38", resend.getString("badge"))
        assertEquals("Luxembourg", resend.getJSONObject("track").getString("label"))
        assertFalse(resend.has("tone"))

        // An urgent tone without significance is refused before it spends budget.
        assertUpdateRejected(
            state.update(
                "nav",
                updatePayload("nav").put("tone", ActivitySurfaceContract.TONE_URGENT),
            ),
            ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
        )
    }

    private fun startPayload(ownerPluginId: String) = JSONObject()
        .put("surfaceId", "$ownerPluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)
        .put("kind", ActivitySurfaceContract.KIND)
        .put("glyph", "straight")
        .put("primary", "300 m")

    private fun updatePayload(ownerPluginId: String) = JSONObject()
        .put("surfaceId", "$ownerPluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)

    private fun action(id: String, glyph: String, label: String) = JSONObject()
        .put("id", id)
        .put("glyph", glyph)
        .put("label", label)

    private fun assertRejected(result: PhoneActivityStartResult, code: String) {
        assertTrue(result is PhoneActivityStartResult.Rejected)
        assertEquals(code, (result as PhoneActivityStartResult.Rejected).code)
    }

    private fun assertUpdateRejected(result: PhoneActivityUpdateResult, code: String) {
        assertTrue(result is PhoneActivityUpdateResult.Rejected)
        assertEquals(code, (result as PhoneActivityUpdateResult.Rejected).code)
    }
}
