package com.anezium.rokidbus.glasses.session

import android.app.Application
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.glasses.SurfaceActivity
import com.anezium.rokidbus.glasses.SurfaceController
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Rule 16 at the window: the real surface controller presents a late surface, the session closes
 * it from its presentation gate, and nothing of it may reach a renderer afterwards. With no
 * accessibility service connected, a displayed surface falls back to `SurfaceActivity`, so a
 * started activity is one display this test watches for; an already running `SurfaceActivity`,
 * whose text views record every text they are given, is the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CancelledOpenPublicationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val requests = mutableListOf<SessionEffect.RequestPage>()
    private val closes = mutableListOf<SessionEffect.CloseSurface>()
    private val rendered = mutableListOf<String>()
    private var now = 0L
    private val runner = SessionRunner(
        reducer = SessionReducer(PageItemResolver { page -> page.actions.map { PageItem.Launch(it.id) } }),
        clock = { now },
        timer = object : SessionTimer {
            override fun schedule(atUptimeMs: Long, task: () -> Unit) = Unit
            override fun cancel() = Unit
        },
        sink = object : SessionEffectSink {
            // What the runtime sink does with these two effects.
            override fun execute(effect: SessionEffect) {
                when (effect) {
                    is SessionEffect.RequestPage -> requests += effect
                    is SessionEffect.CloseSurface -> {
                        closes += effect
                        SurfaceController.closeUnseen(effect.surfaceId)
                    }
                    else -> Unit
                }
            }

            override fun settled(model: SessionModel) = Unit
        },
    )
    private val unsubscribes = mutableListOf<() -> Unit>()
    private var activity: ActivityController<SurfaceActivity>? = null

    @After
    fun tearDown() {
        SurfaceController.setPresentationGate(null)
        unsubscribes.forEach { it() }
        SurfaceController.activeSurface()?.let { SurfaceController.closeUnseen(it.surfaceId) }
        activity?.destroy()
    }

    @Test
    fun `CloseSurface is never followed by a display of that surface`() {
        cancelOpen("maps")
        installSession()

        show("maps:surface", "maps", title = "Cancelled maps")

        assertEquals(listOf(SessionEffect.CloseSurface("maps:surface", SessionReducer.SURFACE_OPEN_CANCELLED)), closes)
        assertNull(SurfaceController.activeSurface())
        assertTrue(displayedSurfaces().isEmpty())

        // The same path displays a surface nobody cancelled.
        show("lens:surface", "lens", title = "Lens")
        assertEquals("lens:surface", SurfaceController.activeSurface()?.surfaceId)
        assertEquals(listOf("lens:surface"), displayedSurfaces())
    }

    @Test
    fun `the cancelled surface never reaches a SurfaceActivity subscribed after the session`() {
        cancelledSurfaceStaysOutOfRunningActivity(activityFirst = false)
    }

    @Test
    fun `the cancelled surface never reaches a SurfaceActivity subscribed before the session`() {
        cancelledSurfaceStaysOutOfRunningActivity(activityFirst = true)
    }

    @Test
    fun `a cancelled surface accepted while the service was away is closed when the session reconnects`() {
        installSession()
        cancelOpen("maps")
        // The accessibility service goes away at 5000: the session's observer and gate go with it.
        now = 5_000
        removeSession()

        // The hub, still running, accepts maps at 9000 and falls back to its activity.
        now = 9_000
        show("maps:surface", "maps", title = "Cancelled maps")
        assertEquals(listOf("maps:surface"), displayedSurfaces())
        assertTrue(closes.isEmpty())

        // Reconnect at 9500, before the cancellation expires at 14000.
        now = 9_500
        assertEquals(false, installSession())

        assertEquals(listOf(SessionEffect.CloseSurface("maps:surface", SessionReducer.SURFACE_OPEN_CANCELLED)), closes)
        assertNull(SurfaceController.activeSurface())
    }

    @Test
    fun `a surface nobody cancelled survives the session reconnecting`() {
        installSession()
        cancelOpen("maps")
        now = 5_000
        removeSession()
        now = 9_000
        show("lens:surface", "lens", title = "Lens")

        now = 9_500
        assertEquals(true, installSession())

        assertTrue(closes.isEmpty())
        assertEquals("lens:surface", SurfaceController.activeSurface()?.surfaceId)
    }

    @Test
    fun `an observer that closes a surface keeps it from the observers after it`() {
        show("notes:card", "notes", title = "Notes")
        // Any observer ahead of the activity that closes what it hears about, as the session's
        // own observer once did.
        unsubscribes += SurfaceController.observe { surface ->
            if (surface?.surfaceId == "maps:surface") SurfaceController.closeUnseen(surface.surfaceId)
        }
        startActivity()

        show("maps:surface", "maps", title = "Cancelled maps")

        assertTrue(rendered.none { "Cancelled maps" in it })
        assertNull(SurfaceController.activeSurface())
        show("lens:surface", "lens", title = "Lens")
        assertTrue("rendered=$rendered", "Lens" in rendered)
    }

    private fun cancelledSurfaceStaysOutOfRunningActivity(activityFirst: Boolean) {
        cancelOpen("maps")
        // A surface underneath, displayed through the activity path and its running activity.
        show("notes:card", "notes", title = "Notes")
        assertEquals(listOf("notes:card"), displayedSurfaces())
        if (activityFirst) {
            startActivity()
            installSession()
        } else {
            installSession()
            startActivity()
        }

        show("maps:surface", "maps", title = "Cancelled maps")

        assertEquals(listOf(SessionEffect.CloseSurface("maps:surface", SessionReducer.SURFACE_OPEN_CANCELLED)), closes)
        assertTrue("rendered=$rendered", rendered.none { "Cancelled maps" in it })
        assertNull(SurfaceController.activeSurface())
        assertTrue(displayedSurfaces().isEmpty())

        // The recording is live: a surface nobody cancelled reaches the same renderer.
        show("lens:surface", "lens", title = "Lens")
        assertTrue("rendered=$rendered", "Lens" in rendered)
    }

    /**
     * What NexusSession registers when the service connects: its surface observer, then the
     * presentation gate. True when the surface already active, if any, survived the gate.
     */
    private fun installSession(): Boolean {
        unsubscribes += SurfaceController.observe { }
        return SurfaceController.setPresentationGate { surface ->
            runner.dispatch(SessionEvent.SurfaceShown(surface.surfaceId, surface.ownerPluginId, now))
        }
    }

    /** What NexusSession removes when the service goes away. */
    private fun removeSession() {
        SurfaceController.setPresentationGate(null)
        unsubscribes.forEach { it() }
        unsubscribes.clear()
    }

    /** Starts a `SurfaceActivity` and records every text its views are given from then on. */
    private fun startActivity() {
        val controller = Robolectric.buildActivity(SurfaceActivity::class.java).create()
        activity = controller
        controller.get().window.decorView.textViews().forEach { view ->
            view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    rendered += s.toString()
                }
            })
        }
    }

    /** Opens maps from a page, cancels the open with BACK, then closes the session. */
    private fun cancelOpen(pluginId: String) {
        at(1_000, SessionEvent.TripleTap(1_000))
        at(1_800, SessionEvent.Tick(1_800))
        at(3_000, SessionEvent.Enter(3_000))
        val request = requests.last().request
        val page = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", 1)
            .put("template", "summary")
            .put("title", "Page")
            .put("body", JSONObject())
            .put(
                "actions",
                JSONArray().put(
                    JSONObject().put("id", pluginId).put("label", pluginId).put("kind", "immersion").put("confirm", false),
                ),
            )
            .put("live", false)
        at(3_100, SessionEvent.PageResponse(page, 3_100))
        at(4_000, SessionEvent.Enter(4_000))
        assertTrue(runner.state is SessionState.Launching)
        at(4_500, SessionEvent.Back(4_500))
        at(4_600, SessionEvent.Back(4_600))
        at(4_700, SessionEvent.Back(4_700))
        assertTrue(runner.state == SessionState.Closed)
        assertTrue(pluginId in runner.model.cancelledOpen)
        now = 9_000
    }

    private fun at(time: Long, event: SessionEvent) {
        now = time
        runner.dispatch(event)
    }

    private fun show(surfaceId: String, ownerPluginId: String, title: String) {
        val payload = JSONObject()
            .put("surfaceId", surfaceId)
            .put("ownerPluginId", ownerPluginId)
            // The surface controller is a process-wide singleton that remembers each surface's
            // last sequence number across tests, so every show here is newer than any before it.
            .put("seq", nextSeq++)
            .put("kind", "card")
            .put("title", title)
        SurfaceController.handleSurfaceEnvelope(app, BusEnvelope(path = BusPaths.SURFACE_SHOW, payload = payload))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun displayedSurfaces(): List<String> {
        val shadow = shadowOf(app)
        return generateSequence { shadow.nextStartedActivity }
            .filter { it.component?.className == SurfaceActivity::class.java.name }
            .map { it.getStringExtra("surfaceId").orEmpty() }
            .toList()
    }

    private companion object {
        var nextSeq = 1L
    }

    private fun View.textViews(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).textViews() }
        else -> emptyList()
    }
}
