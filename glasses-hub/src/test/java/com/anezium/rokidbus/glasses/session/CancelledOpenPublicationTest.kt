package com.anezium.rokidbus.glasses.session

import android.app.Application
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rule 16 at the window: the real surface controller announces a late surface, the session
 * closes it from inside that announcement, and nothing of it may be displayed afterwards. With
 * no accessibility service connected, a displayed surface falls back to `SurfaceActivity`, so a
 * started activity is the display this test watches for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CancelledOpenPublicationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val requests = mutableListOf<SessionEffect.RequestPage>()
    private val closes = mutableListOf<SessionEffect.CloseSurface>()
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
    private var unsubscribe: (() -> Unit)? = null

    @After
    fun tearDown() {
        unsubscribe?.invoke()
        SurfaceController.activeSurface()?.let { SurfaceController.closeUnseen(it.surfaceId) }
    }

    @Test
    fun `CloseSurface is never followed by a display of that surface`() {
        cancelOpen("maps")
        unsubscribe = SurfaceController.observe { surface ->
            if (surface != null) runner.dispatch(SessionEvent.SurfaceShown(surface.surfaceId, surface.ownerPluginId, now))
        }

        show("maps:surface", "maps", seq = 1)

        assertEquals(listOf(SessionEffect.CloseSurface("maps:surface", SessionReducer.SURFACE_OPEN_CANCELLED)), closes)
        assertNull(SurfaceController.activeSurface())
        assertTrue(displayedSurfaces().isEmpty())

        // The same path displays a surface nobody cancelled.
        show("lens:surface", "lens", seq = 2)
        assertEquals("lens:surface", SurfaceController.activeSurface()?.surfaceId)
        assertEquals(listOf("lens:surface"), displayedSurfaces())
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

    private fun show(surfaceId: String, ownerPluginId: String, seq: Long) {
        val payload = JSONObject()
            .put("surfaceId", surfaceId)
            .put("ownerPluginId", ownerPluginId)
            .put("seq", seq)
            .put("kind", "card")
            .put("title", ownerPluginId)
        SurfaceController.handleSurfaceEnvelope(app, BusEnvelope(path = BusPaths.SURFACE_SHOW, payload = payload))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun displayedSurfaces(): List<String> {
        val shadow = shadowOf(app)
        return generateSequence { shadow.nextStartedActivity }
            .filter { it.component?.className == SurfaceActivity::class.java.name }
            .map { it.getStringExtra("surfaceId").orEmpty() }
            .toList()
    }
}
