package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
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

    private companion object {
        /** `ApplicationInfo.PRIVATE_FLAG_EXT_ONBACK_INVOKED_CALLBACK_ENABLED`, hidden in the SDK. */
        const val PRIVATE_FLAG_EXT_ONBACK_INVOKED_CALLBACK_ENABLED = 1 shl 3
    }

    private class RecordingSink : ComposeKeyboardSink {
        val texts = mutableListOf<String>()
        val done = mutableListOf<Triple<String, Boolean, String>>()
        override fun onText(captureId: String, text: String) { texts += text }
        override fun onDone(captureId: String, commit: Boolean, text: String) { done += Triple(captureId, commit, text) }
    }

    private val sink = RecordingSink()

    @Before
    fun setUp() {
        optInToPlatformBack()
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

    /**
     * Opts the app into the platform back callback, as Android 16 does by default for targetSdk
     * 36: the manifest attribute sets this ApplicationInfo flag and the window's checker reads it
     * for every application registration. Robolectric's API 34 framework has no targetSdk 36
     * rule, so without the flag it drops the screen's registration with a warning and the
     * Activity's own default callback stays on top, which finishes the screen without ending the
     * capture: the device defect, reproduced by the fixture rather than fixed by it.
     */
    private fun optInToPlatformBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val info = app.applicationInfo
        val flags = ApplicationInfo::class.java.getField("privateFlagsExt")
        flags.setInt(info, flags.getInt(info) or PRIVATE_FLAG_EXT_ONBACK_INVOKED_CALLBACK_ENABLED)
    }

    /** What the window's checker answers for [activity]: the gate every application callback passes. */
    private fun platformBackEnabled(activity: Activity): Boolean =
        Class.forName("android.window.WindowOnBackInvokedDispatcher")
            .getMethod("isOnBackInvokedCallbackEnabled", Context::class.java)
            .invoke(null, activity) as Boolean

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())

    private fun editor(activity: Activity): EditText =
        views(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().single()

    private fun button(activity: Activity, label: String): Button =
        views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.text.toString() == label }

    /**
     * The dispatcher a system Back really goes through. The Activity hands out the window's
     * proxy, which only forwards: once the decor view is attached, PhoneWindow points it at the
     * ViewRootImpl's WindowOnBackInvokedDispatcher, where the registered callbacks live and the
     * top one is resolved. That is the object asked here, as on a device; both accessors are
     * public framework methods outside the SDK stubs.
     */
    private fun windowBackDispatcher(activity: Activity): Any {
        val decor = activity.window.decorView
        val viewRoot = decor.javaClass.getMethod("getViewRootImpl").invoke(decor)
            ?: error("the decor view has no ViewRootImpl: the screen was never made visible")
        return viewRoot.javaClass.getMethod("getOnBackInvokedDispatcher").invoke(viewRoot)
            ?: error("the ViewRootImpl has no back dispatcher")
    }

    /** The callback the window's dispatcher would invoke for a system Back right now. */
    private fun topBackCallback(dispatcher: Any): OnBackInvokedCallback? =
        dispatcher.javaClass.getMethod("getTopCallback").invoke(dispatcher) as OnBackInvokedCallback?

    @Test
    @Config(sdk = [34])
    fun `the Back the dispatcher routes ends the capture uncommitted with the edited text`() {
        val screen = open()
        val activity = screen.get()
        assertTrue("the app is opted into the platform back callback", platformBackEnabled(activity))
        editor(activity).append(" Temporary change.")
        assertEquals(listOf("Unsent UI check. Temporary change."), sink.texts)

        val back = topBackCallback(windowBackDispatcher(activity))
        assertNotNull("the screen owns the window's top Back callback", back)
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
        val dispatcher = windowBackDispatcher(activity)
        assertNotNull("the screen registered on the window's dispatcher", topBackCallback(dispatcher))
        button(activity, "Keep draft").performClick()
        assertEquals(listOf(Triple("cap-back", true, "Unsent UI check.")), sink.done)
        assertTrue(activity.isFinishing)

        screen.pause().stop().destroy()
        assertNull(topBackCallback(dispatcher))
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
