package com.anezium.rokidbus.phone

import android.app.Activity
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class StreamingEditTextTest {
    private val screen = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val editor = StreamingEditText(screen.get()).apply {
        inputType = InputType.TYPE_CLASS_TEXT
    }
    private val operations = mutableListOf<LocalInputOperation>()
    private val connection = editor.onCreateInputConnection(EditorInfo())!!

    init {
        screen.get().setContentView(editor)
        editor.requestFocus()
        editor.onInputOperation = { operations += it }
    }

    @Test
    fun `numeric key down and up commit once to local and remote editors`() {
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_7)))
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_7)))

        assertEquals("7", editor.text.toString())
        assertEquals(listOf(LocalInputOperation.CommitText("7")), operations)
    }

    @Test
    fun `printable key events finish the existing word before appending a digit`() {
        connection.setComposingText("word", 1)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2))

        assertEquals("word2", editor.text.toString())
        assertEquals(
            listOf(
                LocalInputOperation.SetComposingText("word"),
                LocalInputOperation.FinishComposing,
                LocalInputOperation.CommitText("2"),
            ),
            operations,
        )
    }

    @Test
    fun `ordinary composing and commit calls are each forwarded once`() {
        connection.setComposingText("wor", 1)
        connection.commitText("word", 1)

        assertEquals("word", editor.text.toString())
        assertEquals(
            listOf(LocalInputOperation.SetComposingText("wor"), LocalInputOperation.CommitText("word")),
            operations,
        )
    }

    @Test
    fun `shifted printable key preserves its unicode character`() {
        connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_SHIFT_ON))

        assertEquals("A", editor.text.toString())
        assertEquals(listOf(LocalInputOperation.CommitText("A")), operations)
    }

    @Test
    fun `backspace key forwards full utf16 length of preceding supplementary character`() {
        editor.setText("x\uD83D\uDE00")
        editor.setSelection(editor.length())
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))

        assertEquals("x", editor.text.toString())
        assertEquals(listOf(LocalInputOperation.DeleteSurrounding(2, 0)), operations)
    }
}
