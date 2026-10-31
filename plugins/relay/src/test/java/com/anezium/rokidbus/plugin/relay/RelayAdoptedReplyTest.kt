package com.anezium.rokidbus.plugin.relay

import android.app.Application
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Typing a reply from a Relay notice on an idle HUD, through the real listener, band, plugin
 * service and SDK client against a hub that adopts the reply card with a real PLUGIN_OPEN.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class RelayAdoptedReplyTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private lateinit var listener: ServiceController<RelayNotificationListener>
    private lateinit var service: ServiceController<RelayPluginService>

    @Before
    fun setUp() {
        RelayHubFixture.install(app, hub)
        listener = Robolectric.buildService(RelayNotificationListener::class.java).create()
        listener.get().onListenerConnected()
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.destroy()
        listener.destroy()
        ReplyRepository.clear()
        RelayHubFixture.idle()
    }

    /** A notice is up, the wearer chose Type, and the reply card went to the hub. */
    private fun typingFromANotice(): Int {
        listener.get().onNotificationPosted(RelayHubFixture.chat(app))
        RelayHubFixture.idle()
        // The band's hold binds the plugin service; Android creates it and its one client connects.
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idle()
        val shown = hub.sentTo(BusPaths.NOTICE_SHOW).single()
        hub.deliver(
            BusPaths.NOTICE_ACTION,
            JSONObject()
                .put("noticeId", "relay:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
                .put("id", RelayNoticeRuntime.ACTION_TYPE)
                .put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, shown.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)),
        )
        RelayHubFixture.idle()
        assertTrue(hub.sentTo(BusPaths.SURFACE_SHOW).single().toString().contains(RelayNoticeRuntime.TYPING_SURFACE_ID))
        return hub.sent.size
    }

    private fun inboxDrawn(since: Int) = hub.sentSince(since).any { (path, payload) ->
        path == BusPaths.SURFACE_SHOW && payload.toString().contains("relay-inbox")
    }

    @Test
    fun `an adopted reply card stays the band's, with no inbox, no lost reply and its session service stopped`() {
        val mark = typingFromANotice()
        // An idle HUD adopts the card's sender with a real PLUGIN_OPEN.
        hub.deliver(BusPaths.PLUGIN_OPEN, JSONObject().put("type", PluginOpenTypes.ADOPTED))
        RelayHubFixture.idle()

        assertFalse(NotificationControl.inboxOpen)
        assertFalse(inboxDrawn(mark))
        val afterOpen = hub.sentSince(mark).map { it.first }
        assertFalse(afterOpen.toString(), BusPaths.NOTICE_HIDE in afterOpen || BusPaths.SURFACE_HIDE in afterOpen)
        // The SDK promotes its session service on every open; for the band's card it is stopped again.
        assertTrue(shadowOf(service.get()).isForegroundStopped)

        // The wearer's typed reply still lands in the band, ready to send.
        hub.deliver(
            BusPaths.SURFACE_TEXT_COMMITTED,
            JSONObject().put("surfaceId", "relay:${RelayNoticeRuntime.TYPING_SURFACE_ID}").put("text", "On my way").put("cancelled", false),
        )
        RelayHubFixture.idleFor(1_000)
        assertTrue(hub.sentTo(BusPaths.NOTICE_UPDATE).any { it.optString("body") == "On my way" })

        // The card closing ends the adoption without closing the inbox or the band.
        val beforeClose = hub.sent.size
        hub.deliver(BusPaths.PLUGIN_CLOSE, JSONObject())
        RelayHubFixture.idle()
        assertFalse(hub.sentSince(beforeClose).any { it.first == BusPaths.NOTICE_HIDE })
        assertFalse(inboxDrawn(mark))

        // An explicit open from the launcher still opens the inbox, on the same one client.
        hub.deliver(BusPaths.PLUGIN_OPEN, JSONObject().put("type", PluginOpenTypes.OPEN))
        RelayHubFixture.idle()
        assertTrue(NotificationControl.inboxOpen)
        assertTrue(inboxDrawn(mark))
        assertEquals(1, hub.registrations)
        hub.deliver(BusPaths.PLUGIN_CLOSE, JSONObject())
        RelayHubFixture.idle()
        assertFalse(NotificationControl.inboxOpen)
    }
}
