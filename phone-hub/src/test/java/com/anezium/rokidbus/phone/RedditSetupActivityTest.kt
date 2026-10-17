package com.anezium.rokidbus.phone

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reddit's tutorial lives under Patcher: its own header and Back lead there, not to Glasses apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RedditSetupActivityTest {
    @After fun teardown() = RedditSetupCommands.detach()

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
    private fun texts(root: View) = views(root).filterIsInstance<TextView>().map { it.text.toString() }

    private fun open() = Robolectric.buildActivity(RedditSetupActivity::class.java,
        RedditSetupActivity.intent(RuntimeEnvironment.getApplication())).setup()

    @Test fun `the Patcher header and all three steps replace the Glasses apps back link`() {
        val screen = open()
        val shown = texts(screen.get().findViewById(android.R.id.content))
        assertTrue(shown.containsAll(listOf("Reddit on glasses", "Patcher · Glasses setup",
            "1. Official Reddit APK", "2. Patch and install", "3. Sign in and reply", "Auto-open phone keyboard")))
        assertFalse(shown.any { it.contains("Glasses apps") })
        assertFalse("Reddit needs no MicroG", shown.any { it.contains("MicroG") })
        screen.pause().stop().destroy()
    }

    @Test fun `system Back returns to the Patcher screen that opened it`() {
        val screen = open()
        assertFalse(screen.get().isFinishing)
        @Suppress("DEPRECATION") screen.get().onBackPressed()
        assertTrue(screen.get().isFinishing)
        screen.pause().stop().destroy()
    }

    @Test fun `the header arrow takes the same Back route`() {
        val screen = open()
        val back = views(screen.get().findViewById(android.R.id.content))
            .firstOrNull { it.isClickable && it.hasOnClickListeners() && it !is android.widget.Button && it !is android.widget.Switch }
        requireNotNull(back).performClick()
        assertTrue(screen.get().isFinishing)
        screen.destroy()
    }

    @Test @Config(sdk = [34]) fun `API 34 registers the platform Back callback so targetSdk 36 Back works`() {
        val screen = open()
        val field = RedditSetupActivity::class.java.getDeclaredField("backCallback").apply { isAccessible = true }
        assertNotNull(field.get(screen.get()))
        screen.pause().stop().destroy()
        assertNull(field.get(screen.get()))
    }
}
