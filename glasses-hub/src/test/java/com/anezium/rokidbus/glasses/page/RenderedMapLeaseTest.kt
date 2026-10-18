package com.anezium.rokidbus.glasses.page

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.anezium.rokidbus.glasses.session.MapSize
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RouteGeometry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w480dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RenderedMapLeaseTest {
    private val events = mutableListOf<JSONObject>()
    private var supported = true
    private var now = 0L
    private val context = RenderedMapContext("ambient", "nav:activity", 7, seq = 10)
    private val geometry = RouteGeometry(400.0, emptyList(), attribution = "© OpenStreetMap")
    private val factory: (View) -> RenderedMapLease = { view ->
        RenderedMapLease(view, { supported }, { events += JSONObject(it.toString()); true }, { now })
    }

    private fun attach(controller: ActivityController<Activity>, view: View) {
        VisibleMapTestWindow.attach(controller, view)
    }

    private fun draw(view: View) {
        shadowOf(Looper.getMainLooper()).idle()
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun `binding alone is not demand and hide detach or unknown authority cancels a real map draw`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()
        val view = RouteMapView(activity, factory)
        view.bind(MapInput(geometry, MapForm.MINIMAP, renderContext = context))
        assertTrue(events.isEmpty())
        attach(controller, view)
        draw(view)
        assertTrue(events.last().getBoolean("visible"))
        assertEquals("follow", events.last().getString("view"))
        val firstToken = events.last().getString("token")
        VisibleMapTestWindow.setAppVisible(view, false)
        assertEquals(View.GONE, view.windowVisibility)
        assertFalse(events.last().getBoolean("visible"))
        VisibleMapTestWindow.setAppVisible(view, true)
        draw(view)
        assertTrue(events.last().getBoolean("visible"))
        view.visibility = View.GONE
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(events.last().getBoolean("visible"))
        view.visibility = View.VISIBLE
        draw(view)
        assertTrue(events.last().getBoolean("visible"))
        view.bind(MapInput(geometry, MapForm.MINIMAP))
        assertFalse(events.last().getBoolean("visible"))
        draw(view)
        assertFalse(events.last().getBoolean("visible"))
        view.bind(MapInput(geometry, MapForm.MINIMAP, renderContext = context))
        draw(view)
        assertTrue(events.last().getBoolean("visible"))
        assertTrue(events.last().getString("token") != firstToken)
        activity.setContentView(View(activity))
        assertFalse(events.last().getBoolean("visible"))
        controller.pause().stop().destroy()
    }

    @Test
    fun `full map reports only its selected view and Commands clears it without a new visual design`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()
        val view = SessionScreenView(activity, leaseFactory = factory)
        val authority = RenderedMapContext("page", "nav:main", 4, sessionGeneration = 1, frameIndex = 1, requestId = "r1")
        fun screen(mapView: MapView) = SessionScreen(null,
            listOf(ScreenBlock.Map("map", geometry, size = MapSize.FULL, view = mapView, renderContext = authority)),
            null, chromeless = true)
        view.show(screen(MapView.FOLLOW))
        attach(controller, view)
        draw(view)
        assertEquals("follow", events.last().getString("view"))
        view.show(screen(MapView.OVERVIEW))
        draw(view)
        assertEquals("overview", events.last().getString("view"))
        assertTrue(events.last().getBoolean("visible"))
        view.show(SessionScreen(null, emptyList(), null))
        assertFalse(events.last().getBoolean("visible"))
        val count = events.size
        draw(view)
        assertEquals(count, events.size)
        supported = false
        view.show(screen(MapView.FOLLOW))
        draw(view)
        assertEquals(count, events.size)
        controller.pause().stop().destroy()
    }

    @Test
    fun `content revisions retain the attached map and replace its acknowledgment without a hide`() {
        for (size in listOf(MapSize.COMPACT, MapSize.FULL)) {
            events.clear()
            val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
            val view = SessionScreenView(controller.get(), leaseFactory = factory)
            val authority = RenderedMapContext("page", "nav:main", 4, sessionGeneration = 1, frameIndex = 1, requestId = "r1")
            fun screen(revision: Long) = SessionScreen(null, listOf(ScreenBlock.Map("map", geometry,
                size = size, renderContext = authority.copy(revision = revision))), null, chromeless = size == MapSize.FULL)
            view.show(screen(4))
            attach(controller, view)
            draw(view)
            val child = view.blockViews.getValue("map")
            val token = events.last().getString("token")
            view.show(screen(5))
            assertTrue(events.none { !it.getBoolean("visible") })
            if (size == MapSize.COMPACT) assertSame(child, view.blockViews.getValue("map"))
            draw(view)
            assertEquals(5L, events.last().getLong("revision"))
            assertEquals(token, events.last().getString("token"))
            repeat(3) {
                now += 2_000
                draw(view)
                assertEquals(5L, events.last().getLong("revision"))
                assertTrue(events.last().getBoolean("visible"))
            }
            view.show(SessionScreen(null, emptyList(), null))
            assertFalse(events.last().getBoolean("visible"))
            controller.pause().stop().destroy()
        }
    }
}
