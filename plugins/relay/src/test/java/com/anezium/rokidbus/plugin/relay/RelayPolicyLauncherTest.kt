package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Relay 1.3's per-app policies and read-only alerts meeting the launcher's thread pages: real
 * listener, band, plugin service and SDK client against a hub running the launcher bundle.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class RelayPolicyLauncherTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private val settings = RelaySettings(app)
    private lateinit var listener: ServiceController<RelayNotificationListener>
    private lateinit var service: ServiceController<RelayPluginService>
    private lateinit var chat: StatusBarNotification

    private fun start() {
        hub.pageVersion = PageSurfaceContract.VERSION
        hub.launcherVersion = LauncherInteractiveContract.VERSION
        hub.features = hub.features or BusCapabilityBits.LAUNCHER_INTERACTIVE
        RelayHubFixture.install(app, hub)
        settings.setEnabled(true)
        settings.setClearAfterReply(false)
        listener = Robolectric.buildService(RelayNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        chat = RelayHubFixture.chat(app)
        listener.get().onNotificationPosted(chat)
        RelayHubFixture.idle()
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idle()
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.destroy()
        if (::listener.isInitialized) listener.destroy()
        ReplyRepository.clear()
        settings.resetAppPolicies()
        settings.setEnabled(true)
        settings.setClearAfterReply(RelaySettings.DEFAULT_CLEAR_AFTER_REPLY)
        RelayHubFixture.idle()
    }

    private fun idOf(sbn: StatusBarNotification) = ReplyRepository.stableId(sbn.key)

    /** The same app posting a notification nobody can reply to. */
    private fun alert(id: Int, text: String): StatusBarNotification {
        val notification = Notification.Builder(app, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Build")
            .setContentText(text)
            .build()
        return StatusBarNotification(
            chat.packageName, chat.opPkg, id, null, chat.uid, 0, 0, notification, chat.user, System.currentTimeMillis(),
        )
    }

    private fun request(requestId: String, pageId: String) =
        PageSurfaceContract.correlationPayload(PageCorrelation(requestId, 1, 1, pageId))
            .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a")

    private fun openThread(threadId: String): String {
        val thread = RelayPages.threadPageId(threadId)
        hub.deliver(BusPaths.PAGE_REQUEST, request("r-$threadId", thread))
        hub.deliver(
            BusPaths.PAGE_VISIBILITY,
            JSONObject().put("pageId", thread).put("visible", true).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a")
                .put("leaseUntilMs", 999_999L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000L),
        )
        RelayHubFixture.idle()
        return thread
    }

    private fun answersFor(pageId: String) = hub.sentTo(BusPaths.PAGE_RESPONSE).filter { it.optString("pageId") == pageId }

    private fun withdrawals() = hub.sentTo(BusPaths.NOTICE_WITHDRAW)

    @Test
    fun `a read-only alert admitted under All is a thread page without Send and no conversation event`() {
        start()
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.ALL)
        val alert = alert(11, "Build 412 passed")
        listener.get().onNotificationPosted(alert)
        RelayHubFixture.idle()

        val entry = ReplyRepository.threadEntries().single { it.id == idOf(alert) }
        assertEquals(RelayReplyAvailability.READ_ONLY, entry.availability)
        assertFalse(ReplyRepository.hasReplyAuthority(idOf(alert)))
        val thread = openThread(idOf(alert))
        val composer = answersFor(thread).last().getJSONObject("body").optJSONObject("composer")
        assertTrue(composer == null || !composer.getBoolean("replyable"))
        // The band stood down for the chat first; whatever shows the alert never calls it a message awaiting reply.
        hub.sentTo(BusPaths.NOTICE_SHOW).filter { it.optString(NoticeSurfaceContract.FIELD_EVENT_KEY) == idOf(alert) }
            .forEach { assertFalse(it.optBoolean(NoticeSurfaceContract.FIELD_CONVERSATION)) }
    }

    @Test
    fun `turning an app Off empties its open page and asks the glasses to forget its events`() {
        start()
        val thread = openThread(idOf(chat))
        assertTrue(answersFor(thread).last().toString().contains("on est devant"))
        assertEquals(idOf(chat), hub.sentTo(BusPaths.NOTICE_SHOW).single().getString(NoticeSurfaceContract.FIELD_EVENT_KEY))

        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)

        assertFalse(answersFor(thread).last().toString().contains("on est devant"))
        val withdraw = withdrawals().single()
        assertEquals(idOf(chat), withdraw.getString(NoticeSurfaceContract.FIELD_EVENT_KEY))
        assertTrue(withdraw.getBoolean(NoticeSurfaceContract.FIELD_FORGET))
    }

    @Test
    fun `switching Relay off asks the glasses to forget every event it announced`() {
        start()
        settings.setEnabled(false)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)

        val withdraw = withdrawals().single()
        assertEquals(idOf(chat), withdraw.getString(NoticeSurfaceContract.FIELD_EVENT_KEY))
        assertTrue(withdraw.getBoolean(NoticeSurfaceContract.FIELD_FORGET))
    }

    @Test
    fun `an ordinary removal still only withdraws, keeping the text readable`() {
        start()
        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idleFor(1_000)
        assertFalse(withdrawals().single().has(NoticeSurfaceContract.FIELD_FORGET))
    }

    @Test
    fun `under All our reply echoed back without Reply stays a handed-off thread, not a new alert`() {
        start()
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.ALL)
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(app, idOf(chat), "j'arrive"))
        val echo = StatusBarNotification(
            chat.packageName, chat.opPkg, chat.id, chat.tag, chat.uid, 0, 0,
            Notification.Builder(app, "chat").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Mika").setContentText("on est devant").build(),
            chat.user, System.currentTimeMillis(),
        )
        val shows = hub.sentTo(BusPaths.NOTICE_SHOW).size
        listener.get().onNotificationPosted(echo)
        RelayHubFixture.idleFor(1_000)

        assertEquals(shows, hub.sentTo(BusPaths.NOTICE_SHOW).size)
        assertTrue(ReplyRepository.inboxEntries().none { it.id == idOf(chat) })
        val thread = ReplyRepository.threadEntries().single { it.id == idOf(chat) }
        assertEquals(listOf("j'arrive"), thread.snapshot.handoffs.map { it.text })
    }

    @Test
    fun `a policy change during a send refuses it at the final boundary`() {
        start()
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        assertEquals(ReplySendResult.Missing, ReplyRepository.sendReply(app, idOf(chat), "trop tard"))
        assertNull(ReplyRepository.threadEntries().single { it.id == idOf(chat) }.snapshot.handoffs.firstOrNull())
    }
}
