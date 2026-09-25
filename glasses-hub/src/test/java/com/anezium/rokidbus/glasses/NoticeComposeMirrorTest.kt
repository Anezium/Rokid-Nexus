package com.anezium.rokidbus.glasses

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
}
