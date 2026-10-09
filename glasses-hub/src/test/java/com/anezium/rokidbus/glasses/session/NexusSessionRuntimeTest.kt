package com.anezium.rokidbus.glasses.session

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.NexusSession
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * The real session runtime behind a connected service window, with no phone: local pages answer
 * in-process, Applications follows the launcher catalog, unchanged plugins sit behind the
 * labelled launch step, and nothing from the phone can author a hub page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NexusSessionRuntimeTest {
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private lateinit var service: OverlayService

    @Before
    fun setUp() {
        service = Robolectric.setupService(OverlayService::class.java)
        NexusSession.onServiceConnected(service)
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(
                path = BusPaths.LAUNCHER_LIST,
                payload = JSONObject().put(
                    "plugins",
                    JSONArray()
                        .put(JSONObject().put("id", "lens").put("displayName", "Lens"))
                        .put(JSONObject().put("id", "relay").put("displayName", "Relay").put("pageSessionVersion", 1)),
                ),
            ),
        )
        idle()
    }

    @After
    fun tearDown() {
        NexusSession.dispatch(SessionEvent.Abort)
        // The runtime is a process singleton: forget what a test fed it.
        NexusSession.dispatch(SessionEvent.ActivityEnded("relay:local"))
        NexusSession.onServiceDestroyed()
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.LAUNCHER_LIST, payload = JSONObject().put("plugins", JSONArray())))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun now() = SystemClock.uptimeMillis()

    private fun send(event: SessionEvent) {
        NexusSession.dispatch(event)
        idle()
    }

    private fun openRoot() {
        send(SessionEvent.TripleTap(now()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PageSurfaceContract.GATE_MS + 50))
        assertTrue("${NexusSession.state}", NexusSession.state is SessionState.Root)
    }

    private fun top(): Frame = (NexusSession.state as SessionState.InPage).frames.last()

    private fun body(frame: Frame): PageBody {
        val page = (frame.status as FrameStatus.Shown).snapshot.page
        return (PageTemplateContract.decode(page, null) as PageSurfaceValidationResult.Valid).value
    }

    @Test
    fun `local pages answer in-process without a phone`() {
        openRoot()
        val root = NexusSession.state as SessionState.Root
        assertEquals(listOf(RootStopKind.NOTIFICATIONS, RootStopKind.APPLICATIONS), root.stops.map(RootStop::kind))
        assertEquals(0, root.stops.first().count)
        send(SessionEvent.Enter(now()))
        assertEquals(RootStops.NOTIFICATIONS_PAGE_ID, top().pageId)
        assertTrue(top().status is FrameStatus.Shown)
        assertEquals(0, (body(top()) as PageBody.SelectableList).items.size)
    }

    @Test
    fun `Applications lists the catalog and keeps unchanged plugins behind the labelled launch step`() {
        openRoot()
        send(SessionEvent.Step(1, now()))
        send(SessionEvent.Enter(now()))
        assertEquals(RootStops.APPLICATIONS_PAGE_ID, top().pageId)
        val rows = (body(top()) as PageBody.SelectableList).items
        // Without a negotiated pair even a page provider is offered as its full app.
        assertEquals(listOf("Lens", "Relay"), rows.map { it.label })
        assertTrue(rows.all { it.sub == "Open the full app · exclusive surface" })

        send(SessionEvent.Enter(now()))
        val panel = top().panel as FramePanel.Launch
        assertEquals("lens", panel.pluginId)
        assertEquals(FramePanel.OPEN, panel.selected)
        // BACK leaves the panel without opening anything.
        send(SessionEvent.Back(now()))
        assertNull(top().panel)
        assertTrue(NexusSession.state is SessionState.InPage)

        // Open, with no phone to carry it, fails back to Applications with nothing launched.
        send(SessionEvent.Enter(now()))
        send(SessionEvent.Enter(now()))
        assertTrue("${NexusSession.state}", NexusSession.state is SessionState.InPage)
        assertEquals(RootStops.APPLICATIONS_PAGE_ID, top().pageId)
    }

    @Test
    fun `the phone can neither author a hub page nor answer for another owner`() {
        openRoot()
        send(SessionEvent.Enter(now()))
        val before = top()
        val shown = before.status as FrameStatus.Shown
        val forged = PageSurfaceContract.correlationPayload(PageCorrelation(shown.requestId, NexusSession.generation, 1, RootStops.NOTIFICATIONS_PAGE_ID))
            .put("revision", shown.revision + 1).put("template", "selectableList").put("title", "Forged")
            .put("body", JSONObject().put("items", JSONArray().put(JSONObject().put("id", "x").put("label", "Forged"))))
            .put("actions", JSONArray()).put("live", true).put(PageSurfaceContract.FIELD_PLUGIN_ID, "relay")
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.PAGE_RESPONSE, payload = forged))
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.PAGE_REQUEST, payload = forged))
        idle()
        assertEquals(before, top())
    }

    @Test
    fun `a provider page with no phone says so and never becomes a launch`() {
        send(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:main", "Ana: hi", pinned = true)))
        openRoot()
        send(SessionEvent.Enter(now()))
        assertEquals(SessionReducer.UNAVAILABLE_LINK_LOST, (top().status as FrameStatus.Unavailable).reason)
        assertTrue(NexusSession.state is SessionState.InPage)
        // Retry stays local while the phone is gone; still no launch.
        send(SessionEvent.Enter(now()))
        assertTrue(NexusSession.state is SessionState.InPage)
    }
}
