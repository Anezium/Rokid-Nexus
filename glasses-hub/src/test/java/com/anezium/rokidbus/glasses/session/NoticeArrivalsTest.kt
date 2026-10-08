package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.NexusNoticeSurface
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeArrivalsTest {
    private val reducer = SessionReducer()
    private var model = SessionModel()
    private val arrivals = NoticeArrivals()

    @Test
    fun `two shows from one plugin while the session hides the band are two arrivals`() {
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        send(SessionEvent.Step(1, 2_000))
        val root = model.state as SessionState.Root

        // Relay shows every notice under one surface id; each show is a new instance.
        val first = relayNotice(seq = 1, instance = "i-1", body = "Ana: message A")
        val second = relayNotice(seq = 2, instance = "i-2", body = "Ana: message B")
        arrive(first)
        // A redraw of the same notice, and the band hidden and shown again, are not arrivals.
        assertNull(arrivals.onNotice(first.copy(seq = 3, selectedActionIndex = 1)))
        assertNull(arrivals.onNotice(first))
        arrive(second)

        assertEquals("Ana: message B", model.preview?.text)
        assertEquals(2, model.notificationCount)
        val after = model.state as SessionState.Root
        assertEquals(root.stops.map(RootStop::id), after.stops.map(RootStop::id))
        assertEquals(root.selected, after.selected)
        assertEquals(2, after.stops.single { it.kind == RootStopKind.NOTIFICATIONS }.count)
    }

    @Test
    fun `a notice going away is not an arrival and the next one is`() {
        arrive(relayNotice(seq = 1, instance = "i-1", body = "Ana: hi"))
        assertNull(arrivals.onNotice(null))
        arrive(relayNotice(seq = 2, instance = "i-2", body = "Bo: on my way"))

        assertEquals(2, model.notificationCount)
        assertEquals("Bo: on my way", model.preview?.text)
    }

    private fun arrive(notice: NexusNoticeSurface) {
        val event = arrivals.onNotice(notice)
        assertEquals(RootStops.NOTIFICATIONS_PAGE_ID, event?.preview?.pageId)
        send(event!!)
    }

    private fun send(event: SessionEvent) {
        model = reducer.reduce(model, event).model
    }

    private fun relayNotice(seq: Long, instance: String, body: String) = NexusNoticeSurface(
        surfaceId = "relay:local",
        seq = seq,
        content = NoticeSurfaceContent(title = null, body = body, footer = null),
        expiresAtMs = 60_000,
        hardExpiresAtMs = 120_000,
        ownerPluginId = "relay",
        interactionIdentity = NoticeInteractionIdentity(instance, "q-$instance"),
    )
}
