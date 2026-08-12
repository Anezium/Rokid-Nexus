package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNoticeStateTest {

    private var now = 0L
    private val state = PhoneNoticeState(nowMs = { now }, initialSequence = 0L)

    @Test
    fun `accepts a well-formed show and stamps a sequence`() {
        val result = state.show("relay", showPayload("relay"))

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
        val shown = state.show("relay", showPayload("relay").put("wakeDisplay", true))
            as PhoneNoticeShowResult.Accepted
        assertTrue(shown.notice.content.wakeDisplay)
        assertTrue(shown.notice.payload.getBoolean("wakeDisplay"))

        val rejected = state.update("relay", JSONObject().put("wakeDisplay", false))
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (rejected as PhoneNoticeUpdateResult.Rejected).code,
        )
        assertTrue(shown.notice.content.wakeDisplay)
    }

    @Test
    fun `relays a requested backdrop on show and rejects it on update`() {
        val shown = state.show("relay", showPayload("relay").put("backdrop", true))
            as PhoneNoticeShowResult.Accepted
        assertTrue(shown.notice.content.backdrop)
        assertTrue(shown.notice.payload.getBoolean("backdrop"))

        val refreshed = state.update("relay", JSONObject().put("footer", "Listening"))
            as PhoneNoticeUpdateResult.Accepted
        assertTrue(refreshed.notice.content.backdrop)
        assertFalse(refreshed.notice.payload.has("backdrop"))

        val rejected = state.update("relay", JSONObject().put("backdrop", false))
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (rejected as PhoneNoticeUpdateResult.Rejected).code,
        )
        assertTrue(refreshed.notice.content.backdrop)
    }

    @Test
    fun `rejects a payload whose owner does not match the sender`() {
        val result = state.show("relay", showPayload("maps"))

        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (result as PhoneNoticeShowResult.Rejected).code,
        )
    }

    @Test
    fun `reports the plugin whose notice was replaced`() {
        state.show("relay", showPayload("relay"))
        now += 500L

        val result = state.show("maps", showPayload("maps"))

        assertEquals("relay", (result as PhoneNoticeShowResult.Accepted).replacedOwnerPluginId)
    }

    @Test
    fun `show and update share one rate budget`() {
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) {
            assertTrue(state.show("relay", showPayload("relay")) is PhoneNoticeShowResult.Accepted)
        }

        val blockedUpdate = state.update("relay", JSONObject().put("footer", "Listening"))
        assertEquals(
            NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED,
            (blockedUpdate as PhoneNoticeUpdateResult.Rejected).code,
        )

        // The window slides rather than resetting on a fixed tick.
        now += 1_000L
        assertTrue(state.update("relay", JSONObject().put("footer", "Listening")) is PhoneNoticeUpdateResult.Accepted)
    }

    @Test
    fun `an update restarts the ttl but cannot outlive the hard deadline`() {
        state.show("relay", showPayload("relay").put("ttlMs", 20_000L))
        val hardDeadline = NoticeSurfaceContract.MAX_LIFETIME_MS

        // Keep updating well past the point where restarting a 20s TTL would
        // otherwise keep the banner up forever.
        var updates = 0
        while (now < hardDeadline) {
            now += 5_000L
            state.update("relay", JSONObject().put("footer", "still here $updates"))
            updates++
        }

        assertEquals(hardDeadline, state.expiryDeadlineMs())
        val cleared = state.expireIfDue()
        assertEquals(NoticeCloseReason.TIMEOUT, (cleared as PhoneNoticeClearResult.Cleared).reason)
    }

    @Test
    fun `an update from a plugin that does not own the slot is ignored`() {
        state.show("relay", showPayload("relay"))

        val result = state.update("maps", JSONObject().put("title", "Hijacked"))

        assertTrue(result is PhoneNoticeUpdateResult.Ignored)
        assertEquals("relay", state.ownerPluginId())
    }

    @Test
    fun `a glasses-side close for the wrong surface is ignored`() {
        state.show("relay", showPayload("relay"))

        val wrong = state.closedByGlasses("maps:notice", NoticeCloseReason.USER)
        assertTrue(wrong is PhoneNoticeClearResult.Ignored)

        val right = state.closedByGlasses("relay:notice", NoticeCloseReason.USER)
        assertEquals(NoticeCloseReason.USER, (right as PhoneNoticeClearResult.Cleared).reason)
        assertNull(state.ownerPluginId())
    }

    @Test
    fun `losing access closes the notice with the disconnect reason`() {
        state.show("relay", showPayload("relay"))

        val cleared = state.ownerLostAccess("relay")

        assertEquals(NoticeCloseReason.DISCONNECT, (cleared as PhoneNoticeClearResult.Cleared).reason)
        assertEquals("relay", cleared.ownerPluginId)
    }

    @Test
    fun `hide from a plugin that is not the owner changes nothing`() {
        state.show("relay", showPayload("relay"))

        assertTrue(state.hide("maps") is PhoneNoticeClearResult.Ignored)
        assertEquals("relay", state.ownerPluginId())
    }

    @Test
    fun `an action only reaches the plugin whose visible notice offers it`() {
        state.show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeAnswer(noticeId, "reply"),
        )
    }

    @Test
    fun `a pick for another notice or another action is not current`() {
        state.show("relay", showPayload("relay").put("actions", actions()))

        // A pick that raced a replacement, an id this band never offered, and a
        // blank one all go nowhere rather than to whoever holds the slot now.
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer("maps:notice", "reply"))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, "later"))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, ""))
    }

    @Test
    fun `a notice takes exactly one answer`() {
        state.show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply"))
        // The duplicate temple tap. Told apart from not_current so logcat can
        // say which of the two refusals happened.
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeAnswer(noticeId, "reply"))
    }

    @Test
    fun `a notice with no actions answers no pick at all`() {
        state.show("relay", showPayload("relay"))

        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeAnswer(noticeId, "reply"))
    }

    @Test
    fun `a new show reopens the question`() {
        state.show("relay", showPayload("relay").put("actions", actions()))
        state.takeAnswer(noticeId, "reply")

        state.show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply"))
    }

    @Test
    fun `an update carrying actions reopens the question and one without does not`() {
        state.show("relay", showPayload("relay").put("actions", actions()))
        state.takeAnswer(noticeId, "reply")

        state.update("relay", JSONObject().put("body", "Sending"))
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply"),
        )

        state.update("relay", JSONObject().put("actions", actions()))
        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply"))
    }

    @Test
    fun `a plain interactive notice takes exactly one answer too`() {
        state.show("relay", showPayload("relay").put("interactive", true))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeInputAnswer(noticeId))
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId))
    }

    @Test
    fun `input for another notice or one that asked nothing is not current`() {
        state.show("relay", showPayload("relay"))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeInputAnswer(noticeId))

        state.show("relay", showPayload("relay").put("interactive", true))
        assertEquals(PhoneNoticeActionResult.NotCurrent, state.takeInputAnswer("maps:notice"))
    }

    @Test
    fun `an action and an input are the same one answer`() {
        state.show("relay", showPayload("relay").put("actions", actions()))

        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeAnswer(noticeId, "reply"))
        // Whichever kind arrives first spends the band's one answer.
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId))
    }

    @Test
    fun `show and update reopen a plain interactive question`() {
        state.show("relay", showPayload("relay").put("interactive", true))
        state.takeInputAnswer(noticeId)

        state.update("relay", JSONObject().put("body", "Still here"))
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId))

        state.update("relay", JSONObject().put("interactive", true))
        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeInputAnswer(noticeId))

        state.takeInputAnswer(noticeId)
        state.show("relay", showPayload("relay").put("interactive", true))
        assertEquals(PhoneNoticeActionResult.Owner("relay"), state.takeInputAnswer(noticeId))
    }

    /**
     * The forwarded update is the owner's patch, stamped -- not a
     * re-serialisation of canonical state. Only the patch form can say "clear
     * this", because on the receiving side an absent key means "leave it".
     */
    @Test
    fun `an update forwards the owner's patch, stamped with the hub's fields`() {
        state.show("relay", showPayload("relay").put("interactive", true))

        val accepted = state.update("relay", JSONObject().put("body", "Five out"))

        val payload = (accepted as PhoneNoticeUpdateResult.Accepted).notice.payload
        assertEquals(
            setOf("surfaceId", "body", "localSurfaceId", "ownerPluginId", "seq"),
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
        state.show("relay", showPayload("relay"))

        val accepted = state.update(
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
            setOf("surfaceId", "lines", "localSurfaceId", "ownerPluginId", "seq"),
            accepted.notice.payload.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `a cleared field travels as a cleared field`() {
        state.show(
            "relay",
            showPayload("relay").put("footer", "tap to reply").put("interactive", true),
        )

        val accepted = state.update(
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
        state.show("relay", showPayload("relay").put("actions", actions()))

        val accepted = state.update("relay", JSONObject().put("actions", JSONArray()))

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
        state.show(
            "relay",
            showPayload("relay").put("interactive", true).put("actions", actions()),
        )
        state.takeAnswer(noticeId, "reply")

        val answered = state.update("relay", JSONObject().put("body", "Sending"))

        val payload = (answered as PhoneNoticeUpdateResult.Accepted).notice.payload
        assertFalse(payload.has("actions"))
        assertFalse(payload.has("interactive"))
        assertEquals("Sending", payload.getString("body"))
        // The canonical content keeps both, so a duplicate reply of either kind
        // is still recognised as a real one rather than an unknown one.
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeAnswer(noticeId, "reply"),
        )
        assertEquals(PhoneNoticeActionResult.AlreadyAnswered, state.takeInputAnswer(noticeId))
    }

    @Test
    fun `a notice text field submits once only to its canonical owner`() {
        state.show(
            "relay",
            showPayload("relay").put(
                "textInput",
                JSONObject()
                    .put("id", "question")
                    .put("hint", "Ask Assistant"),
            ),
        )

        assertEquals(
            PhoneNoticeActionResult.NotCurrent,
            state.takeTextSubmission(noticeId, "wrong"),
        )
        assertEquals(
            PhoneNoticeActionResult.Owner("relay"),
            state.takeTextSubmission(noticeId, "question"),
        )
        assertEquals(
            PhoneNoticeActionResult.AlreadyAnswered,
            state.takeTextSubmission(noticeId, "question"),
        )
    }

    @Test
    fun `a text input patch cannot inherit an action row`() {
        state.show("relay", showPayload("relay").put("actions", actions()))

        val rejected = state.update(
            "relay",
            JSONObject().put(
                "textInput",
                JSONObject().put("id", "question").put("hint", "Ask Assistant"),
            ),
        )

        assertTrue(rejected is PhoneNoticeUpdateResult.Rejected)
    }

    @Test
    fun `an invalid patch is rejected before anything is forwarded`() {
        state.show("relay", showPayload("relay"))
        val before = state.ownerPluginId()

        val rejected = state.update(
            "relay",
            JSONObject().put("footer", "x".repeat(NoticeSurfaceContract.MAX_FOOTER_CHARS + 1)),
        )

        assertTrue(rejected is PhoneNoticeUpdateResult.Rejected)
        assertEquals(before, state.ownerPluginId())
        // Emptying the band of all its text is refused for the same reason: the
        // patch never becomes something the glasses are asked to apply.
        assertTrue(
            state.update("relay", JSONObject().put("title", "").put("body", ""))
                is PhoneNoticeUpdateResult.Rejected,
        )
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
