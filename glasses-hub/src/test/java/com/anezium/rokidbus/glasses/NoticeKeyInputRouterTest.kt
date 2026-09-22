package com.anezium.rokidbus.glasses

import android.view.KeyEvent
import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeKeyInputRouterTest {
    private val state = NoticeStateMachine()
    private val answers = mutableListOf<NoticeAnswer>()
    private var editable = false
    private var sequence = 0L
    private var underlyingCalls = 0
    private val router = NoticeKeyInputRouter(
        editableSurfaceActive = { editable },
        dismiss = { state.close(NoticeCloseReason.USER) is NoticeStateDecision.Closed },
        claimsDirection = { state.activeNotice()?.claimsDirection == true },
        moveDirection = { state.moveSelection(it) },
        confirm = { keyCode ->
            val result = state.answer(keyCode)
            if (result is NoticeStateDecision.Answered) {
                answers += result.answer
                true
            } else {
                false
            }
        },
        claimsAllInput = { state.activeNotice()?.content?.backdrop == true },
    )

    @Test
    fun `direct window swipe pair selects notice action before underlying Ink or launcher`() {
        show()

        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, time = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, action = KeyEvent.ACTION_UP, time = 1_010L, down = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_DOWN, time = 1_050L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_DOWN, action = KeyEvent.ACTION_UP, time = 1_060L, down = 1_050L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_CENTER, time = 1_300L))

        assertEquals(listOf("cancel"), actionIds())
        assertEquals(0, underlyingCalls)
    }

    @Test
    fun `duplicate confirm repeat and release stay claimed after answer and replacement`() {
        show()
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L))
        show()

        // The same press may reach a local host as well as the accessibility path.
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_050L, down = 1_000L, repeat = 1))
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, action = KeyEvent.ACTION_UP, time = 1_100L, down = 1_000L))

        assertEquals(listOf("reply"), actionIds())
        assertTrue(state.activeNotice()!!.expectsInput)
        assertEquals(0, underlyingCalls)

        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_300L))
        assertEquals(listOf("reply", "reply"), actionIds())
    }

    @Test
    fun `duplicate direction does not step twice even beyond the swipe pair window`() {
        show()
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, time = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, time = 1_400L, down = 1_000L))

        assertEquals(1, state.activeNotice()!!.selectedActionIndex)
        assertEquals(0, underlyingCalls)
    }

    @Test
    fun `back dismisses only the notice and retains the press through its disappearance`() {
        show()
        assertTrue(dispatch(KeyEvent.KEYCODE_BACK, time = 1_000L))
        assertNull(state.activeNotice())
        show()

        assertTrue(dispatch(KeyEvent.KEYCODE_BACK, time = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_BACK, action = KeyEvent.ACTION_UP, time = 1_010L, down = 1_000L))

        assertNotNull(state.activeNotice())
        assertEquals(0, underlyingCalls)
        assertTrue(dispatch(KeyEvent.KEYCODE_BACK, time = 1_200L))
        assertNull(state.activeNotice())
    }

    @Test
    fun `editable field keeps confirm and direction while back still dismisses a notice`() {
        show(backdrop = true)
        editable = true

        assertFalse(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L))
        assertFalse(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, time = 1_200L))
        assertTrue(dispatch(KeyEvent.KEYCODE_BACK, time = 1_400L))

        assertTrue(answers.isEmpty())
        assertEquals(2, underlyingCalls)
        assertNull(state.activeNotice())
    }

    @Test
    fun `passive notice leaves underlying controls usable`() {
        show(actions = emptyList())

        assertFalse(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L))
        assertFalse(dispatch(KeyEvent.KEYCODE_DPAD_DOWN, time = 1_200L))
        assertFalse(dispatch(TripleTapDetector.KEYCODE_NOTIFICATION, time = 1_400L))

        assertTrue(answers.isEmpty())
        assertEquals(3, underlyingCalls)
    }

    @Test
    fun `answered backdrop swallows further classifications but never treats raw contact as answer`() {
        show(backdrop = true)
        assertFalse(dispatch(TripleTapDetector.KEYCODE_NOTIFICATION, time = 900L))
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, action = KeyEvent.ACTION_UP, time = 1_010L, down = 1_000L))

        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_300L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, time = 1_500L))
        assertFalse(dispatch(KeyEvent.KEYCODE_SPACE, time = 1_700L))

        assertEquals(listOf("reply"), actionIds())
        assertEquals(2, underlyingCalls)
    }

    @Test
    fun `answered non-backdrop lets the next separate press pass through`() {
        show()
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, action = KeyEvent.ACTION_UP, time = 1_010L, down = 1_000L))

        assertFalse(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_300L))
        assertEquals(listOf("reply"), actionIds())
        assertEquals(1, underlyingCalls)
    }

    @Test
    fun `unrelated key release cannot consume or clear the notice press`() {
        show()
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, time = 1_000L, device = 7))

        assertFalse(dispatch(KeyEvent.KEYCODE_ENTER, action = KeyEvent.ACTION_UP, time = 1_010L, down = 1_000L, device = 8))
        assertFalse(dispatch(KeyEvent.KEYCODE_ENTER, action = KeyEvent.ACTION_UP, time = 1_020L, down = 900L, device = 7))
        assertTrue(dispatch(KeyEvent.KEYCODE_ENTER, action = KeyEvent.ACTION_UP, time = 1_030L, down = 1_000L, device = 7))
    }

    @Test
    fun `service teardown clears pending presses and swipe dedupe`() {
        show()
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, time = 1_000L))
        router.reset()

        assertFalse(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, action = KeyEvent.ACTION_UP, time = 1_010L, down = 1_000L))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_DOWN, time = 1_050L))
        assertEquals(0, state.activeNotice()!!.selectedActionIndex)
    }

    private fun show(
        actions: List<NoticeAction> = listOf(
            NoticeAction("reply", "reply", "Reply"),
            NoticeAction("cancel", "close", "Cancel"),
        ),
        backdrop: Boolean = false,
    ) {
        sequence += 1
        state.show(
            surfaceId = "relay:notice",
            seq = sequence,
            content = NoticeSurfaceContent(
                title = "Message", body = null, footer = null,
                actions = actions, backdrop = backdrop,
            ),
            nowMs = 0L,
            interactionIdentity = NoticeInteractionIdentity("instance-$sequence", "question-$sequence"),
        )
    }

    private fun dispatch(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        time: Long,
        down: Long = time,
        repeat: Int = 0,
        device: Int = 0,
    ): Boolean {
        if (router.handleKey(keyCode, action, repeat, time, down, device)) return true
        underlyingCalls += 1
        return false
    }

    private fun actionIds() = answers.map { (it as NoticeAnswer.Action).action.id }
}
