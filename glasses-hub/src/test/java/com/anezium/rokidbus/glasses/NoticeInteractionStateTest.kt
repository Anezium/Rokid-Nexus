package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeField
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfacePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeInteractionStateTest {
    private val first = NoticeInteractionIdentity("instance-a", "question-a")
    private val nextQuestion = first.copy(questionId = "question-b")
    private val replacement = NoticeInteractionIdentity("instance-b", "question-c")
    private val reply = NoticeAction("reply", "reply", "Reply")
    private val content = NoticeSurfaceContent(
        title = "Message",
        body = "Read this",
        footer = "Choose a reply",
        actions = listOf(reply),
        ttlMs = 8_000L,
    )

    @Test
    fun `delayed confirmation and back cannot target a replacement from the same plugin`() {
        val state = NoticeStateMachine()
        val captured = show(state, first, seq = 1)
        show(state, replacement, seq = 2)

        assertEquals(NoticeStateDecision.Ignored, state.answer(66, captured))
        assertEquals(NoticeStateDecision.Ignored, state.close(NoticeCloseReason.USER, captured))
        assertEquals(replacement, state.activeNotice()?.interactionIdentity)
        assertTrue(state.activeNotice()!!.expectsInput)
    }

    @Test
    fun `a body refresh keeps the captured question answerable`() {
        val state = NoticeStateMachine()
        val captured = show(state, first, seq = 1)

        val refreshed = state.update(
            "relay:notice", 2,
            NoticeSurfacePatch(body = NoticeField("More text")),
            nowMs = 100L,
            interactionIdentity = first,
        ) as NoticeStateDecision.Updated

        assertEquals(first, refreshed.notice.interactionIdentity)
        assertTrue(state.answer(66, captured) is NoticeStateDecision.Answered)
    }

    @Test
    fun `a rearmed question rejects the earlier ring tap and accepts a new tap`() {
        val state = NoticeStateMachine()
        val captured = show(state, first, seq = 1)
        rearm(state)

        assertFalse(state.isCurrentInteraction(captured))
        assertEquals(NoticeStateDecision.Ignored, state.answer(66, captured))
        val answered = state.answer(66) as NoticeStateDecision.Answered
        assertEquals(nextQuestion, answered.notice.interactionIdentity)
    }

    @Test
    fun `a captured tap keeps the action the wearer selected`() {
        val state = NoticeStateMachine()
        val captured = (state.show(
            "relay:notice", 1,
            content.copy(actions = listOf(reply, NoticeAction("cancel", "close", "Cancel"))),
            nowMs = 0L,
            interactionIdentity = first,
        ) as NoticeStateDecision.Shown).notice
        state.moveSelection(1)

        val answered = state.answer(66, captured) as NoticeStateDecision.Answered

        assertEquals("reply", (answered.answer as NoticeAnswer.Action).action.id)
    }

    @Test
    fun `a cosmetic action label change cannot rearm an answered question`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        state.answer(66)

        val refreshed = state.update(
            "relay:notice", 2,
            NoticeSurfacePatch(
                actions = NoticeField(listOf(reply.copy(label = "Reply now"))),
                rearm = false,
            ),
            nowMs = 100L,
            interactionIdentity = first,
        ) as NoticeStateDecision.Updated

        assertEquals(first, refreshed.notice.interactionIdentity)
        assertTrue(refreshed.notice.answered)
        assertEquals(NoticeStateDecision.Ignored, state.answer(66))
    }

    @Test
    fun `a cosmetic patch cannot substitute a different action`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)

        val result = state.update(
            "relay:notice", 2,
            NoticeSurfacePatch(
                actions = NoticeField(listOf(NoticeAction("send", "send", "Send"))),
                rearm = false,
            ),
            nowMs = 100L,
            interactionIdentity = first,
        )

        assertEquals(NoticeStateDecision.Ignored, result)
        assertEquals(listOf(reply), state.activeNotice()?.liveActions)
    }

    @Test
    fun `an update cannot install a different instance when its show was not received`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)

        val result = state.update(
            "relay:notice", 3,
            NoticeSurfacePatch(body = NoticeField("Replacement text")),
            nowMs = 100L,
            interactionIdentity = replacement,
        )

        assertEquals(NoticeStateDecision.Ignored, result)
        assertEquals("Read this", state.activeNotice()?.content?.body)
        // The missing show may still arrive on the other transport.
        assertTrue(show(state, replacement, seq = 2).interactionIdentity == replacement)
    }

    @Test
    fun `a body patch cannot change the question or omit its identity`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        val patch = NoticeSurfacePatch(body = NoticeField("Changed"))

        assertEquals(
            NoticeStateDecision.Ignored,
            state.update("relay:notice", 2, patch, 100L, nextQuestion),
        )
        assertEquals(NoticeStateDecision.Ignored, state.update("relay:notice", 3, patch, 100L))
        assertEquals(first, state.activeNotice()?.interactionIdentity)
    }

    @Test
    fun `an action patch cannot reuse the question it is rearming`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        state.answer(66)

        val result = state.update(
            "relay:notice", 2,
            NoticeSurfacePatch(actions = NoticeField(listOf(reply))),
            nowMs = 100L,
            interactionIdentity = first,
        )

        assertEquals(NoticeStateDecision.Ignored, result)
        assertTrue(state.activeNotice()!!.answered)
    }

    @Test
    fun `a late decoded show cannot replace the current identity`() {
        val state = NoticeStateMachine()
        show(state, replacement, seq = 2)

        val late = state.show("relay:notice", 1, content, 100L, interactionIdentity = first)

        assertEquals(NoticeStateDecision.DroppedStale, late)
        assertEquals(replacement, state.activeNotice()?.interactionIdentity)
    }

    @Test
    fun `back ttl and owner hide all retain the identity being closed`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        assertEquals(first, (state.close(NoticeCloseReason.USER) as NoticeStateDecision.Closed).interactionIdentity)

        show(state, nextQuestion, seq = 2)
        assertEquals(
            nextQuestion,
            (state.expire(8_000L, 2) as NoticeStateDecision.Closed).interactionIdentity,
        )

        show(state, replacement, seq = 3)
        assertEquals(
            replacement,
            (state.hide(4, NoticeCloseReason.OWNER, replacement) as NoticeStateDecision.Closed).interactionIdentity,
        )
    }

    @Test
    fun `an owner hide cannot close a different notice instance`() {
        val state = NoticeStateMachine()
        show(state, replacement, seq = 1)

        assertEquals(NoticeStateDecision.Ignored, state.hide(2, NoticeCloseReason.OWNER, first))
        assertEquals(replacement, state.activeNotice()?.interactionIdentity)
    }

    @Test
    fun `the owner can hide the instance even when its latest question was not received`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)

        val closed = state.hide(3, NoticeCloseReason.OWNER, nextQuestion) as NoticeStateDecision.Closed

        assertEquals(first, closed.interactionIdentity)
        assertEquals(null, state.activeNotice())
    }

    @Test
    fun `failed delivery stays spent displays uncertainty and preserves the ttl`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        val attempted = state.answer(66) as NoticeStateDecision.Answered

        val failed = state.deliveryFailed(attempted.notice) as NoticeStateDecision.Updated

        assertTrue(failed.notice.answered)
        assertFalse(failed.notice.expectsInput)
        assertTrue(failed.notice.liveActions.isEmpty())
        assertEquals("Delivery not confirmed", noticeFooterText(failed.notice))
        assertEquals(attempted.notice.expiresAtMs, failed.notice.expiresAtMs)
        assertEquals(NoticeStateDecision.Ignored, state.answer(66))
    }

    @Test
    fun `a delayed delivery failure cannot mark a replacement or rearmed question`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        val attempted = state.answer(66) as NoticeStateDecision.Answered
        rearm(state)

        assertEquals(NoticeStateDecision.Ignored, state.deliveryFailed(attempted.notice))
        assertFalse(state.activeNotice()!!.deliveryUnconfirmed)
        show(state, replacement, seq = 3)
        assertEquals(NoticeStateDecision.Ignored, state.deliveryFailed(attempted.notice))
        assertEquals("Choose a reply", noticeFooterText(state.activeNotice()!!))
    }

    @Test
    fun `a new question clears delivery uncertainty without reopening the old one`() {
        val state = NoticeStateMachine()
        show(state, first, seq = 1)
        val attempted = state.answer(66) as NoticeStateDecision.Answered
        state.deliveryFailed(attempted.notice)
        state.update(
            "relay:notice", 2, NoticeSurfacePatch(body = NoticeField("Updated text")),
            nowMs = 100L, interactionIdentity = first,
        )
        assertTrue(state.activeNotice()!!.deliveryUnconfirmed)

        rearm(state, seq = 3)

        assertFalse(state.activeNotice()!!.deliveryUnconfirmed)
        assertTrue(state.activeNotice()!!.expectsInput)
        assertEquals(NoticeStateDecision.Ignored, state.answer(66, attempted.notice))
    }

    private fun show(
        state: NoticeStateMachine,
        identity: NoticeInteractionIdentity,
        seq: Long,
    ): NexusNoticeSurface = (state.show(
        "relay:notice", seq, content, nowMs = 0L, interactionIdentity = identity,
    ) as NoticeStateDecision.Shown).notice

    private fun rearm(state: NoticeStateMachine, seq: Long = 2) = state.update(
        "relay:notice", seq,
        NoticeSurfacePatch(actions = NoticeField(listOf(reply))),
        nowMs = 100L,
        interactionIdentity = nextQuestion,
    ) as NoticeStateDecision.Updated
}
