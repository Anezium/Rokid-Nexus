package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Relay's classic inbox, as a hub without the launcher bundle sees it: a thread answered from
 * Relay leaves it, as it always did, until the app posts the thread again.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class RelayLegacyInboxTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private lateinit var listener: ServiceController<RelayNotificationListener>
    private lateinit var service: ServiceController<RelayPluginService>
    private lateinit var chat: StatusBarNotification

    private fun start(clearAfterReply: Boolean) {
        hub.pageVersion = PageSurfaceContract.VERSION
        RelayHubFixture.install(app, hub)
        RelaySettings(app).setEnabled(true)
        RelaySettings(app).setClearAfterReply(clearAfterReply)
        listener = Robolectric.buildService(RelayNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        chat = RelayHubFixture.chat(app)
        listener.get().onNotificationPosted(chat)
        RelayHubFixture.idle()
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idle()
        assertTrue(inboxIds().contains(threadId))
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.destroy()
        if (::listener.isInitialized) listener.destroy()
        ReplyRepository.clear()
        RelaySettings(app).setClearAfterReply(RelaySettings.DEFAULT_CLEAR_AFTER_REPLY)
        RelayHubFixture.idle()
    }

    private val threadId: String
        get() = ReplyRepository.stableId(chat.key)

    private fun inboxIds() = ReplyRepository.inboxEntries().map { it.id }

    /** The wearer chose Type on the band, committed [text], and let the countdown send it. */
    private fun typeOnTheBandAndSend(text: String) {
        val shown = hub.sentTo(BusPaths.NOTICE_SHOW).single()
        hub.deliver(
            BusPaths.NOTICE_ACTION,
            JSONObject()
                .put("noticeId", "relay:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
                .put("id", RelayNoticeRuntime.ACTION_TYPE)
                .put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, shown.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)),
        )
        RelayHubFixture.idle()
        hub.deliver(
            BusPaths.SURFACE_TEXT_COMMITTED,
            JSONObject().put("surfaceId", "relay:${RelayNoticeRuntime.TYPING_SURFACE_ID}").put("text", text).put("cancelled", false),
        )
        RelayHubFixture.idleFor(10_000)
    }

    @Test
    fun `a reply sent from the band leaves the classic inbox, notification kept`() {
        start(clearAfterReply = false)
        typeOnTheBandAndSend("On my way")
        assertFalse(inboxIds().contains(threadId))
    }

    @Test
    fun `a reply sent from the band leaves the classic inbox, notification cleared`() {
        start(clearAfterReply = true)
        typeOnTheBandAndSend("On my way")
        assertFalse(inboxIds().contains(threadId))
    }

    @Test
    fun `a reply sent from the full app leaves the classic inbox, with or without clearing`() {
        start(clearAfterReply = false)
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(app, threadId, "On my way"))
        assertFalse(inboxIds().contains(threadId))
        // The full app opened again lists what is left: not the answered thread.
        service.get().onNexusOpen()
        RelayHubFixture.idle()
        assertFalse(inboxIds().contains(threadId))

        RelaySettings(app).setClearAfterReply(true)
        val other = RelayHubFixture.chat(app, id = 8, sender = "Alan", text = "tu viens ?")
        listener.get().onNotificationPosted(other)
        RelayHubFixture.idle()
        val otherId = ReplyRepository.stableId(other.key)
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(app, otherId, "oui"))
        assertFalse(inboxIds().contains(otherId))
    }

    @Test
    fun `the app posting the thread again brings it back`() {
        start(clearAfterReply = false)
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(app, threadId, "On my way"))
        assertFalse(inboxIds().contains(threadId))

        listener.get().onNotificationPosted(RelayHubFixture.chat(app, text = "super, à tout de suite"))
        RelayHubFixture.idle()
        assertTrue(inboxIds().contains(threadId))
    }

    @Test
    fun `a failed send keeps the thread in the classic inbox`() {
        start(clearAfterReply = false)
        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idle()
        // The reply authority went with the notification: nothing is sent, the readable thread stays.
        assertFalse(ReplyRepository.sendReply(app, threadId, "On my way") == ReplySendResult.Sent)
        assertTrue(inboxIds().contains(threadId))
    }
}
