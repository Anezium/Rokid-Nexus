package com.anezium.rokidbus.plugin.nav

import android.Manifest
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Location is asked for when the wearer taps Start, and only that tap starts a journey. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NavJourneyPanelTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `granting location from the permission row does not start a selected journey`() = withPanel { panel ->
        val label = findText(panel, "Precise location")!!
        assertTrue((label.parent.parent as View).performClick())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        panel.permissionResult()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `granting location after Start resumes that explicit journey request`() = withPanel { panel ->
        assertTrue(findText(panel, "Start walking")!!.performClick())
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        panel.permissionResult()
        val started = shadowOf(app).nextStartedService!!
        assertEquals(NavJourneyService.ACTION_START, started.action)
        assertEquals("Selected place", started.getStringExtra(NavJourneyService.EXTRA_NAME))
    }

    private fun withPanel(check: (NavJourneyPanel) -> Unit) {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val controller = Robolectric.buildActivity(NavSettingsActivity::class.java).create()
        try {
            val panel = ReflectionHelpers.getField<NavJourneyPanel>(controller.get(), "journeyPanel")
            ReflectionHelpers.setField(panel, "destination", NavDestination("Selected place", GeoPoint(48.001, 2.0)))
            panel.refresh()
            check(panel)
        } finally {
            controller.destroy()
        }
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }
}
