package com.anezium.rokidbus.phone

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.window.OnBackInvokedCallback
import com.anezium.rokidbus.shared.ComposeTypeStart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * System Back on the phone composer. On a phone running Android 16 with targetSdk 36, Back is
 * delivered to the window's OnBackInvokedCallback, not to the deprecated hook, and without one
 * the screen closed while the glasses kept showing "typing" with the edited text. These drive
 * the callback the real dispatcher resolves as the top one, the legacy hook on an older
 * Android, and the backstop for a screen finished by any other route: each ends the capture
 * uncommitted exactly once, so the previous draft stays, and never commits.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeKeyboardActivityBackTest {
    private val app = RuntimeEnvironment.getApplication()
    private val request = ComposeTypeStart("cap-back", "Unsent UI check.", 1000)

    private class RecordingSink : ComposeKeyboardSink {
        val texts = mutableListOf<String>()
        val done = mutableListOf<Triple<String, Boolean, String>>()
        override fun onText(captureId: String, text: String) { texts += text }
        override fun onDone(captureId: String, commit: Boolean, text: String) { done += Triple(captureId, commit, text) }
    }

    private val sink = RecordingSink()

    @Before
    fun setUp() {
        ComposeKeyboardPrompt.sink = sink
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(ComposeKeyboardPrompt.open(app, request))
    }

    @After
    fun tearDown() {
        ComposeKeyboardPrompt.close(app, request.captureId)
        ComposeKeyboardPrompt.sink = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun open(): ActivityController<ComposeKeyboardActivity> =
        Robolectric.buildActivity(ComposeKeyboardActivity::class.java).setup()

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())

    private fun editor(activity: Activity): EditText =
        views(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().single()

    private fun button(activity: Activity, label: String): Button =
        views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.text.toString() == label }

    /** The callback the window's dispatcher would invoke for a system Back right now. */
    private fun topBackCallback(activity: Activity): OnBackInvokedCallback? {
        val dispatcher = activity.onBackInvokedDispatcher
        return dispatcher.javaClass.getMethod("getTopCallback").invoke(dispatcher) as OnBackInvokedCallback?
    }

    @Test
    @Config(sdk = [34])
    fun `the Back the dispatcher routes ends the capture uncommitted with the edited text`() {
        val screen = open()
        val activity = screen.get()
        editor(activity).append(" Temporary change.")
        assertEquals(listOf("Unsent UI check. Temporary change."), sink.texts)

        val back = topBackCallback(activity)
        assertNotNull("the screen owns the top Back callback", back)
        back!!.onBackInvoked()

        assertEquals(listOf(Triple("cap-back", false, "Unsent UI check. Temporary change.")), sink.done)
        assertTrue(activity.isFinishing)
        // The prompt has forgotten the capture: later text for it goes nowhere.
        ComposeKeyboardPrompt.onText(request.captureId, "late")
        assertEquals(1, sink.texts.size)

        screen.pause().stop().destroy()
        assertEquals("destroying the screen after a handled Back reports nothing more", 1, sink.done.size)
    }

    @Test
    @Config(sdk = [34])
    fun `Keep draft commits, and the destroyed screen leaves no Back callback behind`() {
        val screen = open()
        val activity = screen.get()
        button(activity, "Keep draft").performClick()
        assertEquals(listOf(Triple("cap-back", true, "Unsent UI check.")), sink.done)
        assertTrue(activity.isFinishing)

        screen.pause().stop().destroy()
        assertNull(topBackCallback(activity))
        assertEquals(1, sink.done.size)
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the legacy Back hook ends the capture uncommitted`() {
        val screen = open()
        val activity = screen.get()
        editor(activity).append(" Temporary change.")
        @Suppress("DEPRECATION") activity.onBackPressed()
        assertEquals(listOf(Triple("cap-back", false, "Unsent UI check. Temporary change.")), sink.done)
        assertTrue(activity.isFinishing)
        screen.pause().stop().destroy()
        assertEquals(1, sink.done.size)
    }

    @Test
    @Config(sdk = [34])
    fun `a screen finished by any other route still ends the capture uncommitted`() {
        val screen = open()
        val activity = screen.get()
        editor(activity).append(" Temporary change.")
        activity.finish()
        screen.pause().stop().destroy()
        assertEquals(listOf(Triple("cap-back", false, "Unsent UI check. Temporary change.")), sink.done)
    }

    @Test
    @Config(sdk = [34])
    fun `a screen whose capture the glasses already ended reports nothing when it closes`() {
        val screen = open()
        val activity = screen.get()
        ComposeKeyboardPrompt.close(app, request.captureId)
        assertTrue("the screen closes with the capture", activity.isFinishing)
        screen.pause().stop().destroy()
        assertTrue(sink.done.isEmpty())
    }
}
