package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.EditableSurfaceField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeComposeMirrorTest {
    @Test
    fun anInNoticeFieldIsDrawnOnlyInItsOwnPluginsBand() {
        assertTrue(editableDrawsInNotice(true, "relay", "relay"))
        assertFalse(editableDrawsInNotice(true, "relay", "assistant"))
        assertFalse(editableDrawsInNotice(true, "relay", null))
        assertFalse(editableDrawsInNotice(false, "relay", "relay"))
        assertFalse(editableDrawsInNotice(true, "", ""))
    }

    @Test
    fun aBareCardStepsAsideOnlyForItsOwnPluginsBand() {
        val holder = card(title = "Assistant")

        assertTrue(cardHoldsUnderBand(holder, "assistant"))
        assertFalse(cardHoldsUnderBand(holder, "relay"))
        assertFalse(cardHoldsUnderBand(holder, null))
        assertFalse(cardHoldsUnderBand(holder.copy(ownerPluginId = ""), ""))
    }

    @Test
    fun aCardWithAnythingToShowIsNeverBare() {
        assertTrue(card(title = "Assistant").isBareCard())
        assertTrue(card(title = "Assistant", rows = listOf(SurfaceRow(text = " "))).isBareCard())
        assertFalse(card(title = "Assistant", rows = listOf(SurfaceRow(text = "Ask out loud."))).isBareCard())
        assertFalse(card(title = "Assistant", rows = listOf(SurfaceRow(text = "", badge = "3"))).isBareCard())
        assertFalse(card(title = "Assistant", footer = "tap to ask again").isBareCard())
        assertFalse(card(title = "Assistant", subtitle = "Listening").isBareCard())
        assertFalse(card(title = "Assistant", editable = EditableSurfaceField(placeholder = "Ask")).isBareCard())
        assertFalse(card(title = "Assistant").copy(kind = NexusSurface.KIND_READER).isBareCard())
    }

    @Test
    fun anEmptyFieldLeadsThePlaceholderWithTheCaret() {
        val render = noticeComposeRender(line(text = "", cursor = 0, placeholder = "Type your reply…"))

        assertEquals(" Type your reply…", render.text)
        assertEquals(0, render.caretStart)
        assertEquals(1, render.caretEnd)
        assertEquals(1, render.placeholderStart)
    }

    @Test
    fun theCaretAtTheEndSitsOnAnAddedSpace() {
        val render = noticeComposeRender(line(text = "Salut", cursor = 5))

        assertEquals("Salut ", render.text)
        assertEquals(5, render.caretStart)
        assertEquals(6, render.caretEnd)
        assertEquals(render.text.length, render.placeholderStart)
    }

    @Test
    fun theCaretInsideTheTextCoversOneWholeCharacter() {
        val emoji = "a😀b"
        val render = noticeComposeRender(line(text = emoji, cursor = 1))

        assertEquals(emoji, render.text)
        assertEquals(1, render.caretStart)
        assertEquals(3, render.caretEnd)
    }

    @Test
    fun anOutOfRangeCursorIsClampedToTheText() {
        val render = noticeComposeRender(line(text = "ok", cursor = 9))

        assertEquals(2, render.caretStart)
    }

    @Test
    fun clearingIgnoresAnotherPluginsLine() {
        NoticeComposeMirror.publish(line(text = "hi", cursor = 2))
        NoticeComposeMirror.clear("assistant")
        assertEquals("hi", NoticeComposeMirror.current?.text)

        NoticeComposeMirror.clear("relay")
        assertNull(NoticeComposeMirror.current)
    }

    private fun line(text: String, cursor: Int, placeholder: String = "") =
        NoticeComposeMirror.Line("relay", text, cursor, placeholder)

    private fun card(
        title: String,
        subtitle: String = "",
        footer: String = "",
        rows: List<SurfaceRow> = emptyList(),
        editable: EditableSurfaceField? = null,
    ) = NexusSurface(
        surfaceId = "assistant:main",
        seq = 1L,
        kind = NexusSurface.KIND_CARD,
        contentKey = "holder",
        title = title,
        subtitle = subtitle,
        footer = footer,
        rows = rows,
        timedLines = emptyList(),
        anchor = null,
        handlesBack = true,
        ownerPluginId = "assistant",
        editable = editable,
    )
}
