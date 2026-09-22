package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNoticeStateTest {

    private var now = 0L
    private val state = PhoneNoticeState(nowMs = { now }, initialSequence = 0L)

    @Test
    fun `accepts a well-formed show and stamps a sequence`() {
        val result = show("relay", showPayload("relay"))

        val notice = (result as PhoneNoticeShowResult.Accepted).notice
        assertEquals("relay", notice.ownerPluginId)
        assertEquals(1L, notice.payload.optLong("seq"))
        assertNull(result.replacedOwnerPluginId)
        assertFalse(notice.content.wakeDisplay)
        assertFalse(notice.payload.has("wakeDisplay"))
        assertFalse(notice.content.backdrop)
        assertFalse(notice.payload.has("backdrop"))
    }

    @Test
    fun `relays a requested wake on show and rejects it on update`() {
        val shown = show("relay", showPayload("relay").put("wakeDisplay", true))
            as PhoneNoticeShowResult.Accepted
        assertTrue(shown.notice.content.wakeDisplay)
        assertTrue(shown.notice.payload.getBoolean("wakeDisplay"))

        val rejected = update("relay", JSONObject().put("wakeDisplay", false))
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (rejected as PhoneNoticeUpdateResult.Rejected).code,
        )
        assertTrue(shown.notice.content.wakeDisplay)
    }

    @Test
    fun `relays a requested backdrop on show and rejects it on update`() {
        val shown = show("relay", showPayload("relay").put("backdrop", true))
            as PhoneNoticeShowResult.Accepted
        assertTrue(shown.notice.content.backdrop)
        assertTrue(shown.notice.payload.getBoolean("backdrop"))

        val refreshed = update("relay", JSONObject().put("footer", "Listening"))
            as PhoneNoticeUpdateResult.Accepted
        assertTrue(refreshed.notice.content.backdrop)
        assertFalse(refreshed.notice.payload.has("backdrop"))

        val rejected = update("relay", JSONObject().put("backdrop", false))
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (rejected as PhoneNoticeUpdateResult.Rejected).code,
        )
        assertTrue(refreshed.notice.content.backdrop)
    }

    @Test
    fun `rejects a payload whose owner does not match the sender`() {
        val result = show("relay", showPayload("maps"))

        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (result as PhoneNoticeShowResult.Rejected).code,
        )
    }

    @Test
    fun `reports the plugin whose notice was replaced`() {
        show("relay", showPayload("relay"))
        now += 500L

        val result = show("maps", showPayload("maps"))

        assertEquals("relay", (result as PhoneNoticeShowResult.Accepted).replacedOwnerPluginId)
    }

    @Test
    fun `show and update share one rate budget`() {
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) {
            assertTrue(show("relay", showPayload("relay")) is PhoneNoticeShowResult.Accepted)
        }

        val blockedUpdate = update("relay", JSONObject().put("footer", "Listening"))
        assertEquals(
            NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED,
            (blockedUpdate as PhoneNoticeUpdateResult.Rejected).code,
        )

        // The window slides rather than resetting on a fixed tick.
        now += 1_000L
        assertTrue(update("relay", JSONObject().put("footer", "Listening")) is PhoneNoticeUpdateResult.Accepted)
    }

    @Test
    fun `an update restarts the ttl but cannot outlive the hard deadline`() {
        show("relay", showPayload("relay").put("ttlMs", 20_000L))
        val hardDeadline = NoticeSurfaceContract.MAX_LIFETIME_MS

        // Keep updating well past the point where restarting a 20s TTL would
        // otherwise keep the banner up forever.
        var updates = 0
        while (now < hardDeadline) {
            now += 5_000L
            update("relay", JSONObject().put("footer", "still here $updates"))
            updates++
        }

        assertEquals(hardDeadline, state.expiryDeadlineMs())
        val cleared = state.expireIfDue()
        assertEquals(NoticeCloseReason.TIMEOUT, (cleared as PhoneNoticeClearResult.Cleared).reason)
    }

    @Test
    fun `an update from a plugin that does not own the slot is ignored`() {
        show("relay", showPayload("relay"))

        val result = update("maps", JSONObject().put("title", "Hijacked"))

        assertTrue(result is PhoneNoticeUpdateResult.Ignored)
        assertEquals("relay", state.ownerPluginId())
    }

    @Test
    fun `a glasses-side close for the wrong surface is ignored`() {
        show("relay", showPayload("relay"))

        val wrong = state.closedByGlasses("maps:notice", NoticeCloseReason.USER, currentIdentity)
        assertTrue(wrong is PhoneNoticeClearResult.Ignored)

        val right = state.closedByGlasses("relay:notice", NoticeCloseReason.USER, currentIdentity)
        assertEquals(NoticeCloseReason.USER, (right as PhoneNoticeClearResult.Cleared).reason)
        assertNull(state.ownerPluginId())
    }

    @Test
    fun `losing access closes the notice with the disconnect reason`() {
        show("relay", showPayload("relay"))

        val cleared = state.ownerLostAccess("relay")

        assertEquals(NoticeCloseReason.DISCONNECT, (cleared as PhoneNoticeClearResult.Cleared).reason)
        assertEquals("relay", cleared.ownerPluginId)
    }

    @Test
    fun `hide from a plugin that is not the owner changes nothing`() {
        show("relay", showPayload("relay"))

        assertTrue(state.hide("maps") is PhoneNoticeClearResult.Ignored)
        assertEquals("relay", state.ownerPluginId())
    }

    @Test
    fun `an action only reaches the plugin whose visible notice offers it`() {
        show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply", currentIdentity),
        )
    }

    @Test
    fun `a pick for another notice or another action is not current`() {
        show("relay", showPayload("relay").put("actions", actions()))

        // A pick that raced a replacement, an id this band never offered, and a
        // blank one all go nowhere rather than to whoever holds the slot now.
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer("maps:notice", "reply", currentIdentity))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, "later", currentIdentity))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, "", currentIdentity))
    }

    @Test
    fun `a notice takes exactly one answer`() {
        show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply", currentIdentity))
        // The duplicate temple tap. Told apart from not_current so logcat can
        // say which of the two refusals happened.
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeAnswer(noticeId, "reply", currentIdentity))
    }

    @Test
    fun `a notice with no actions answers no pick at all`() {
        show("relay", showPayload("relay"))

        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, "reply", currentIdentity))
    }

    @Test
    fun `a new show reopens the question`() {
        show("relay", showPayload("relay").put("actions", actions()))
        state.takeAnswer(noticeId, "reply", currentIdentity)

        show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply", currentIdentity))
    }

    @Test
    fun `an update carrying actions reopens the question and one without does not`() {
        show("relay", showPayload("relay").put("actions", actions()))
        state.takeAnswer(noticeId, "reply", currentIdentity)

        update("relay", JSONObject().put("body", "Sending"))
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply", currentIdentity),
        )

        update("relay", JSONObject().put("actions", actions()))
        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply", currentIdentity))
    }

    @Test
    fun `a plain interactive notice takes exactly one answer too`() {
        show("relay", showPayload("relay").put("interactive", true))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeInputAnswer(noticeId, currentIdentity))
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId, currentIdentity))
    }

    @Test
    fun `input for another notice or one that asked nothing is not current`() {
        show("relay", showPayload("relay"))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeInputAnswer(noticeId, currentIdentity))

        show("relay", showPayload("relay").put("interactive", true))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeInputAnswer("maps:notice", currentIdentity))
    }

    @Test
    fun `an action and an input are the same one answer`() {
        show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply", currentIdentity))
        // Whichever kind arrives first spends the band's one answer.
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId, currentIdentity))
    }

    @Test
    fun `show and update reopen a plain interactive question`() {
        show("relay", showPayload("relay").put("interactive", true))
        state.takeInputAnswer(noticeId, currentIdentity)

        update("relay", JSONObject().put("body", "Still here"))
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId, currentIdentity))

        update("relay", JSONObject().put("interactive", true))
        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeInputAnswer(noticeId, currentIdentity))

        state.takeInputAnswer(noticeId, currentIdentity)
        show("relay", showPayload("relay").put("interactive", true))
        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeInputAnswer(noticeId, currentIdentity))
    }

    /**
     * The forwarded update is the owner's patch, stamped -- not a
     * re-serialisation of canonical state. Only the patch form can say "clear
     * this", because on the receiving side an absent key means "leave it".
     */
    @Test
    fun `an update forwards the owner's patch, stamped with the hub's fields`() {
        show("relay", showPayload("relay").put("interactive", true))

        val accepted = update("relay", JSONObject().put("body", "Five out"))

        val payload = (accepted as PhoneNoticeUpdateResult.Accepted).notice.payload
        assertEquals(
            setOf("surfaceId", "body", "localSurfaceId", "ownerPluginId", "seq", "noticeInstanceId", "noticeQuestionId"),
            payload.keys().asSequence().toSet(),
        )
        assertEquals(noticeId, payload.getString("surfaceId"))
        assertEquals(NoticeSurfaceContract.LOCAL_SURFACE_ID, payload.getString("localSurfaceId"))
        assertEquals("relay", payload.getString("ownerPluginId"))
        assertEquals("Five out", payload.getString("body"))
        assertEquals(2L, payload.getLong("seq"))
    }

    @Test
    fun `a lines update replaces body and is relayed as the validated patch`() {
        show("relay", showPayload("relay"))

        val accepted = update(
            "relay",
            JSONObject().put(
                "lines",
                JSONArray()
                    .put("  First message  ")
                    .put("Second\nmessage")
                    .put("   "),
            ),
        ) as PhoneNoticeUpdateResult.Accepted

        assertNull(accepted.notice.content.body)
        assertEquals(listOf("First message", "Second message"), accepted.notice.content.lines)
        assertFalse(accepted.notice.payload.has("body"))
        assertEquals(
            listOf("First message", "Second message"),
            List(accepted.notice.payload.getJSONArray("lines").length()) { index ->
                accepted.notice.payload.getJSONArray("lines").getString(index)
            },
        )
        assertEquals(
            setOf("surfaceId", "lines", "localSurfaceId", "ownerPluginId", "seq", "noticeInstanceId", "noticeQuestionId"),
            accepted.notice.payload.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `a cleared field travels as a cleared field`() {
        show(
            "relay",
            showPayload("relay").put("footer", "tap to reply").put("interactive", true),
        )

        val accepted = update(
            "relay",
            JSONObject().put("footer", "").put("interactive", false),
        )

        val payload = (accepted as PhoneNoticeUpdateResult.Accepted).notice.payload
        // Present-and-empty, not absent. Absent is what used to reach the
        // glasses, and absent means "leave it alone".
        assertTrue(payload.has("footer"))
        assertEquals("", payload.getString("footer"))
        assertTrue(payload.has("interactive"))
        assertFalse(payload.getBoolean("interactive"))
        assertNull(accepted.notice.content.footer)
        assertFalse(accepted.notice.content.interactive)
    }

    @Test
    fun `an emptied row travels as an empty row`() {
        show("relay", showPayload("relay").put("actions", actions()))

        val accepted = update("relay", JSONObject().put("actions", JSONArray()))

        val payload = (accepted as PhoneNoticeUpdateResult.Accepted).notice.payload
        assertTrue(payload.has("actions"))
        assertEquals(0, payload.getJSONArray("actions").length())
        assertTrue(accepted.notice.content.actions.isEmpty())
    }

    /**
     * The property that used to need an explicit strip, now free: a text-only
     * patch simply does not carry the fields that reopen a question. Pinned
     * anyway, because the wearer being re-asked something they already answered
     * is exactly the failure this whole rule exists to prevent.
     */
    @Test
    fun `a text-only update to an answered notice carries neither interactivity field`() {
        show(
            "relay",
            showPayload("relay").put("interactive", true).put("actions", actions()),
        )
        state.takeAnswer(noticeId, "reply", currentIdentity)

        val answered = update("relay", JSONObject().put("body", "Sending"))

        val payload = (answered as PhoneNoticeUpdateResult.Accepted).notice.payload
        assertFalse(payload.has("actions"))
        assertFalse(payload.has("interactive"))
        assertEquals("Sending", payload.getString("body"))
        // The canonical content keeps both, so a duplicate reply of either kind
        // is still recognised as a real one rather than an unknown one.
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply", currentIdentity),
        )
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId, currentIdentity))
    }

    @Test
    fun `an invalid patch is rejected before anything is forwarded`() {
        show("relay", showPayload("relay"))
        val before = state.ownerPluginId()

        val rejected = update(
            "relay",
            JSONObject().put("footer", "x".repeat(NoticeSurfaceContract.MAX_FOOTER_CHARS + 1)),
        )

        assertTrue(rejected is PhoneNoticeUpdateResult.Rejected)
        assertEquals(before, state.ownerPluginId())
        // Emptying the band of all its text is refused for the same reason: the
        // patch never becomes something the glasses are asked to apply.
        assertTrue(
            update("relay", JSONObject().put("title", "").put("body", ""))
                is PhoneNoticeUpdateResult.Rejected,
        )
    }

    @Test
    fun `same owner replacement rejects every reply from the previous notice`() {
        val first = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        val second = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted

        assertNotEquals(first.notice.identity.instanceId, second.notice.identity.instanceId)
        assertNotEquals(first.notice.identity.questionId, second.notice.identity.questionId)
        assertEquals(first.notice, second.replacedNotice)
        assertEquals(
            PhoneNoticeActionResult.NotCurrent,
            state.takeAnswer(noticeId, "reply", first.notice.identity),
        )
        assertEquals(
            PhoneNoticeActionResult.NotCurrent,
            state.takeInputAnswer(noticeId, first.notice.identity),
        )
        assertEquals(
            PhoneNoticeClearResult.Ignored,
            state.closedByGlasses(noticeId, NoticeCloseReason.TIMEOUT, first.notice.identity),
        )
        assertEquals("relay", state.ownerPluginId())
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply", second.notice.identity),
        )
    }

    @Test
    fun `rearming a question preserves its instance but rejects old answers and closes`() {
        val shown = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        state.takeAnswer(noticeId, "reply", shown.notice.identity)
        val updated = update("relay", JSONObject().put("actions", actions()))
            as PhoneNoticeUpdateResult.Accepted

        assertEquals(shown.notice.identity.instanceId, updated.notice.identity.instanceId)
        assertNotEquals(shown.notice.identity.questionId, updated.notice.identity.questionId)
        assertEquals(
            PhoneNoticeActionResult.NotCurrent,
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
        assertEquals(
            PhoneNoticeClearResult.Ignored,
            state.closedByGlasses(noticeId, NoticeCloseReason.USER, shown.notice.identity),
        )
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply", updated.notice.identity),
        )
    }

    @Test
    fun `a text and ttl refresh preserves an unanswered in-flight question`() {
        val shown = show("relay", showPayload("relay").put("interactive", true))
            as PhoneNoticeShowResult.Accepted
        val refreshed = update("relay", JSONObject().put("body", "Still here").put("ttlMs", 20_000L))
            as PhoneNoticeUpdateResult.Accepted

        assertEquals(shown.notice.identity, refreshed.notice.identity)
        assertTrue(refreshed.notice.payload.getLong("seq") > shown.notice.payload.getLong("seq"))
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeInputAnswer(noticeId, shown.notice.identity),
        )
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeInputAnswer(noticeId, refreshed.notice.identity),
        )
    }

    @Test
    fun `cosmetic actions updates preserve the question and cannot reopen its answer`() {
        val shown = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        state.takeAnswer(noticeId, "reply", shown.notice.identity)
        val row = actions().apply { getJSONObject(0).put("label", "Answer") }
        val updated = update("relay", JSONObject().put("actions", row).put("rearm", false))
            as PhoneNoticeUpdateResult.Accepted

        assertEquals(shown.notice.identity, updated.notice.identity)
        assertTrue(updated.notice.answered)
        assertEquals("Answer", updated.notice.content.actions.single().label)
        assertFalse(updated.notice.payload.getBoolean("rearm"))
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
    }

    @Test
    fun `cosmetic labels keep an in-flight answer valid but action reordering is rejected`() {
        val row = actions().put(JSONObject().put("id", "later").put("glyph", "clock").put("label", "Later"))
        val shown = show("relay", showPayload("relay").put("actions", row))
            as PhoneNoticeShowResult.Accepted
        val reordered = JSONArray().put(row.getJSONObject(1)).put(row.getJSONObject(0))
        assertEquals(
            PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE),
            update("relay", JSONObject().put("actions", reordered).put("rearm", false)),
        )
        row.getJSONObject(0).put("label", "Answer")
        val updated = update("relay", JSONObject().put("actions", row).put("rearm", false))
            as PhoneNoticeUpdateResult.Accepted
        assertEquals(shown.notice.identity, updated.notice.identity)
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
    }

    @Test
    fun `cosmetic updates cannot change the meaning of an in-flight answer`() {
        val shown = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        val changedAction = actions().apply { getJSONObject(0).put("id", "delete") }
        val patches = listOf(
            JSONObject().put("actions", changedAction).put("rearm", false),
            JSONObject().put("actions", JSONArray()).put("rearm", false),
            JSONObject().put("interactive", true).put("rearm", false),
        )
        patches.forEach { patch ->
            assertEquals(
                PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE),
                update("relay", patch),
            )
        }
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
    }

    @Test
    fun `missing or mismatched identity never consumes the answer or closes the notice`() {
        val shown = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        val identities = listOf(
            null,
            shown.notice.identity.copy(instanceId = "another-instance"),
            shown.notice.identity.copy(questionId = "another-question"),
        )
        identities.forEach { identity ->
            assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, "reply", identity))
            assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeInputAnswer(noticeId, identity))
            assertEquals(
                PhoneNoticeClearResult.Ignored,
                state.closedByGlasses(noticeId, NoticeCloseReason.USER, identity),
            )
        }
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
    }

    @Test
    fun `plugin supplied hub identities are overwritten on show and update`() {
        val spoofed = NoticeInteractionIdentity("spoofed-instance", "spoofed-question")
        val shown = show(
            "relay",
            NoticeSurfaceContract.withInteractionIdentity(showPayload("relay"), spoofed).put("seq", 999L),
        ) as PhoneNoticeShowResult.Accepted
        assertNotEquals(spoofed, shown.notice.identity)
        assertEquals(shown.notice.identity, NoticeSurfaceContract.interactionIdentity(shown.notice.payload))
        assertEquals(1L, shown.notice.payload.getLong("seq"))

        val updated = update(
            "relay",
            NoticeSurfaceContract.withInteractionIdentity(JSONObject().put("body", "Updated"), spoofed),
        ) as PhoneNoticeUpdateResult.Accepted
        assertEquals(shown.notice.identity, NoticeSurfaceContract.interactionIdentity(updated.notice.payload))
    }

    @Test
    fun `invalid and rate limited rearming patches preserve the answered question`() {
        val shown = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        state.takeAnswer(noticeId, "reply", shown.notice.identity)
        assertTrue(
            update("relay", JSONObject().put("actions", actions()).put("rearm", "false"))
                is PhoneNoticeUpdateResult.Rejected,
        )
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND - 1) {
            assertTrue(update("relay", JSONObject().put("body", "Working")) is PhoneNoticeUpdateResult.Accepted)
        }
        assertEquals(
            PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED),
            update("relay", JSONObject().put("actions", actions())),
        )
        assertEquals(shown.notice.identity, currentIdentity)
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
    }

    @Test
    fun `canonical client token follows updates and is returned with answers and closes`() {
        val shown = show(
            "relay",
            showPayload("relay").put("actions", actions()).put("noticeClientToken", "client-first"),
        ) as PhoneNoticeShowResult.Accepted
        val refreshed = update("relay", JSONObject().put("body", "Still waiting"))
            as PhoneNoticeUpdateResult.Accepted
        assertEquals("client-first", refreshed.notice.clientToken)
        val updated = update(
            "relay",
            JSONObject().put("actions", actions()).put("noticeClientToken", "client-next"),
        ) as PhoneNoticeUpdateResult.Accepted
        assertEquals("client-next", updated.notice.payload.getString("noticeClientToken"))
        assertEquals(
            PhoneNoticeActionResult.Owner("relay", "client-next"),
            state.takeAnswer(noticeId, "reply", updated.notice.identity),
        )
        val cleared = state.closedByGlasses(noticeId, NoticeCloseReason.USER, updated.notice.identity)
            as PhoneNoticeClearResult.Cleared
        assertEquals(updated.notice.identity, cleared.identity)
        assertEquals("client-next", cleared.clientToken)
        assertEquals(updated.notice.identity, NoticeSurfaceContract.interactionIdentity(cleared.payload))
        assertEquals("client-first", shown.notice.clientToken)
    }

    @Test
    fun `malformed client tokens are rejected without replacing or rearming the notice`() {
        val shown = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        state.takeAnswer(noticeId, "reply", shown.notice.identity)
        val invalid = listOf(JSONObject.NULL, "", "with spaces", 12, true, "x".repeat(129))
        invalid.forEach { token ->
            assertEquals(
                PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE),
                show("relay", showPayload("relay").put("noticeClientToken", token)),
            )
            assertEquals(
                PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE),
                update("relay", JSONObject().put("actions", actions()).put("noticeClientToken", token)),
            )
        }
        assertEquals(shown.notice.identity, currentIdentity)
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply", shown.notice.identity),
        )
    }

    @Test
    fun `owner hide carries canonical identity and client token and ignores late close`() {
        val shown = show("relay", showPayload("relay").put("noticeClientToken", "client-token"))
            as PhoneNoticeShowResult.Accepted
        val hidden = state.hide("relay") as PhoneNoticeClearResult.Cleared
        assertEquals(shown.notice.identity, hidden.identity)
        assertEquals(shown.notice.identity, NoticeSurfaceContract.interactionIdentity(hidden.payload))
        assertEquals("client-token", hidden.clientToken)
        assertEquals(
            PhoneNoticeClearResult.Ignored,
            state.closedByGlasses(noticeId, NoticeCloseReason.OWNER, shown.notice.identity),
        )
    }

    @Test
    fun `hub restart cannot reuse the old identity even if sequences restart together`() {
        val old = show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        val restarted = PhoneNoticeState(nowMs = { now }, initialSequence = 0L)
        val current = restarted.show("relay", showPayload("relay").put("actions", actions()))
            as PhoneNoticeShowResult.Accepted
        assertEquals(old.notice.payload.getLong("seq"), current.notice.payload.getLong("seq"))
        assertNotEquals(old.notice.identity, current.notice.identity)
        assertEquals(
            PhoneNoticeActionResult.NotCurrent,
            restarted.takeAnswer(noticeId, "reply", old.notice.identity),
        )
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            restarted.takeAnswer(noticeId, "reply", current.notice.identity),
        )
    }

    private var currentIdentity: NoticeInteractionIdentity? = null

    private fun show(ownerPluginId: String, payload: JSONObject): PhoneNoticeShowResult =
        state.show(ownerPluginId, payload).also { result ->
            if (result is PhoneNoticeShowResult.Accepted) currentIdentity = result.notice.identity
        }

    private fun update(ownerPluginId: String, payload: JSONObject): PhoneNoticeUpdateResult =
        state.update(ownerPluginId, payload).also { result ->
            if (result is PhoneNoticeUpdateResult.Accepted) currentIdentity = result.notice.identity
        }

    private val noticeId = "relay:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"

    private fun actions() = JSONArray().put(
        JSONObject()
            .put("id", "reply")
            .put("glyph", "phone")
            .put("label", "Reply"),
    )

    private fun showPayload(ownerPluginId: String) = JSONObject()
        .put("surfaceId", "$ownerPluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)
        .put("kind", NoticeSurfaceContract.KIND)
        .put("title", "Marie")
        .put("body", "On my way")
}
