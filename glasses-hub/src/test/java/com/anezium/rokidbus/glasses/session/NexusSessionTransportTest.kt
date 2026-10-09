package com.anezium.rokidbus.glasses.session

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.glasses.CxrBusBridge
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.NexusSession
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The session runtime behind GlassesHub's own link and capability handling: SPP lost while CXR
 * stays, and the pair's page version changing while the link stays up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NexusSessionTransportTest {
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private lateinit var service: OverlayService

    /** CXR's own connection flag; the bridge sets it when the vendor link comes up. */
    private fun cxrConnected(up: Boolean) {
        CxrBusBridge::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(null, up)
        GlassesHub.onCxrState(up)
    }

    private fun phoneAnnounces(pageVersion: Int) {
        val capabilities = PhoneHubCapabilitiesContract.create(features = 0, cameraConsumerName = null, pageSessionVersion = pageVersion)
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.HUB_CAPABILITIES, payload = PhoneHubCapabilitiesContract.toJson(capabilities)))
        idle()
    }

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
        cxrConnected(true)
        GlassesHub.onSppConnected(true)
        phoneAnnounces(PageSurfaceContract.VERSION)
        assertTrue(GlassesHub.pageSessionSupported())
    }

    @After
    fun tearDown() {
        NexusSession.dispatch(SessionEvent.Abort)
        NexusSession.dispatch(SessionEvent.ActivityEnded("relay:local"))
        GlassesHub.onSppConnected(false)
        cxrConnected(false)
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

    private fun frames(): List<Frame> = (NexusSession.state as SessionState.InPage).frames

    private fun top(): Frame = frames().last()

    private fun rows(frame: Frame): List<PageRow> {
        val page = (frame.status as FrameStatus.Shown).snapshot.page
        return ((PageTemplateContract.decode(page, null) as PageSurfaceValidationResult.Valid).value as PageBody.SelectableList).items
    }

    /** The phone's stamped answer to the request the top frame is waiting on. */
    private fun answer(rows: List<PageRow>) {
        val frame = top()
        val requestId = (frame.status as FrameStatus.Loading).requestId
        val payload = PageSurfaceContract.correlationPayload(PageCorrelation(requestId, NexusSession.generation, frames().size, frame.pageId))
            .put("revision", 1).put("template", "selectableList").put("title", "Relay")
            .put("body", PageTemplateContract.encode(PageBody.SelectableList(rows)))
            .put("actions", JSONArray()).put("live", true)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "e1")
            .put(PageSurfaceContract.FIELD_PLUGIN_ID, "relay")
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.PAGE_RESPONSE, payload = payload))
        idle()
        assertTrue("${top().status}", top().status is FrameStatus.Shown)
    }

    @Test
    fun `losing SPP while CXR stays leaves the big page inert and the small one working`() {
        send(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:main", "Mika: hi", pinned = true)))
        openRoot()
        send(SessionEvent.Enter(now()))
        // Too big for one CXR message: it came over SPP.
        answer(listOf(PageRow("small", "Open the short one", target = PageTarget.Page("relay:small"))) + (1..40).map { PageRow("r$it", "Thread $it · " + "x".repeat(90)) })
        send(SessionEvent.Enter(now()))
        answer(listOf(PageRow("a", "Mika")))

        GlassesHub.onSppConnected(false)
        idle()
        assertTrue(GlassesHub.isPhoneConnected())
        val big = frames().first().status
        assertTrue("$big", big is FrameStatus.Unavailable && big.reason == SessionReducer.UNAVAILABLE_DATA_PLANE)
        assertTrue(top().status is FrameStatus.Shown)

        // Back on the big page: readable, inert, and nothing is asked again.
        send(SessionEvent.Back(now()))
        assertEquals(1, frames().size)
        assertTrue(top().status is FrameStatus.Unavailable)
    }

    @Test
    fun `a page version change with the link up reaches Applications and leaves provider pages inert`() {
        openRoot()
        send(SessionEvent.Step(1, now()))
        send(SessionEvent.Enter(now()))
        assertEquals(RootStops.APPLICATIONS_PAGE_ID, top().pageId)
        fun relayRow() = rows(top()).single { it.label == "Relay" }
        assertTrue(relayRow().target is PageTarget.Page)

        // The phone stops negotiating pages: the visible catalog follows without a wearer action.
        phoneAnnounces(0)
        assertTrue(relayRow().target is PageTarget.Action)
        phoneAnnounces(PageSurfaceContract.VERSION)
        assertTrue(relayRow().target is PageTarget.Page)

        // A provider page open when support goes stays inert, and support coming back replays nothing.
        repeat(rows(top()).indexOfFirst { it.label == "Relay" }) { send(SessionEvent.Step(1, now())) }
        send(SessionEvent.Enter(now()))
        assertEquals("relay:main", top().pageId)
        answer(listOf(PageRow("a", "Mika")))
        phoneAnnounces(0)
        assertTrue("${top().status}", top().status is FrameStatus.Unavailable)
        phoneAnnounces(PageSurfaceContract.VERSION)
        assertTrue(top().status is FrameStatus.Unavailable)
        assertTrue(frames().none { it.status is FrameStatus.Loading })
    }
}
