package com.anezium.rokidbus.plugin.nav

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The settings screen asks Android to bind the listener again only when the grant exists,
 * guidance is on and the listener is not connected: once when the screen comes up, once more
 * when the wearer returns, never on its one-second refreshes, and never with the grant missing
 * or the listener connected. Its access line says which of the three states the listener is in
 * and never claims a connection the request did not produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NavSettingsListenerRebindTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val component get() = ComponentName(app, NavNotificationListener::class.java)
    private val requests = mutableListOf<ComponentName>()

    @Before
    fun setUp() {
        NavState.listenerConnected = false
        NavSettings(app).setEnabled(true)
        NavSettingsActivity.rebindRequest = { requests += it }
    }

    @After
    fun tearDown() {
        NavState.listenerConnected = false
        NavSettingsActivity.rebindRequest = android.service.notification.NotificationListenerService::requestRebind
    }

    private fun grant(granted: Boolean) {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(component, granted)
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findText(view.getChildAt(index), text)?.let { return it }
        return null
    }

    @Test
    fun `granted but not connected asks once on entry, not on refreshes, and says so`() {
        grant(true)
        val controller = Robolectric.buildActivity(NavSettingsActivity::class.java).setup()
        try {
            assertEquals(listOf(component), requests)
            val content = controller.get().findViewById<View>(android.R.id.content)
            assertNotNull(findText(content, app.getString(R.string.nav_settings_access_connecting)))
            assertNull(findText(content, app.getString(R.string.nav_settings_access_on)))

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals("the refreshes ask nothing more", 1, requests.size)

            // The listener connected meanwhile: the line says so, without another request.
            NavState.listenerConnected = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNotNull(findText(content, app.getString(R.string.nav_settings_access_on)))
            controller.pause().resume()
            assertEquals(1, requests.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `coming back to the screen while still not connected asks once more`() {
        grant(true)
        val controller = Robolectric.buildActivity(NavSettingsActivity::class.java).setup()
        try {
            assertEquals(1, requests.size)
            controller.pause().resume()
            assertEquals(2, requests.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `without the grant nothing is asked and the line still says to allow it`() {
        grant(false)
        val controller = Robolectric.buildActivity(NavSettingsActivity::class.java).setup()
        try {
            assertEquals(0, requests.size)
            val content = controller.get().findViewById<View>(android.R.id.content)
            assertNotNull(findText(content, app.getString(R.string.nav_settings_access_off)))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertEquals(0, requests.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `with other apps' guidance switched off nothing is asked`() {
        grant(true)
        NavSettings(app).setEnabled(false)
        val controller = Robolectric.buildActivity(NavSettingsActivity::class.java).setup()
        try {
            assertEquals(0, requests.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
