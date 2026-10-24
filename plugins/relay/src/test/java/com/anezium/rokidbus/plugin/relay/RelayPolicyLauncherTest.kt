package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.client.PluginRegistrationResult
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
import org.robolectric.Shadows.shadowOf

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
        app.getSharedPreferences("relay_history", 0).edit().clear().commit()
        settings.resetAppPolicies()
        settings.setEnabled(true)
        settings.setClearAfterReply(RelaySettings.DEFAULT_CLEAR_AFTER_REPLY)
        settings.setPauseWhilePhoneScreenOn(RelaySettings.DEFAULT_PAUSE_SCREEN_ON)
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

    private fun forgets() = hub.sentTo(BusPaths.NOTICE_FORGET)

    @Test
    fun `turning an app Off empties its open page and asks the glasses to forget Relay's notifications`() {
        start()
        val thread = openThread(idOf(chat))
        assertTrue(answersFor(thread).last().toString().contains("on est devant"))

        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)

        assertFalse(answersFor(thread).last().toString().contains("on est devant"))
        assertEquals(1, forgets().size)
    }

    @Test
    fun `an event already withdrawn is still forgotten when its app is turned Off`() {
        start()
        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idleFor(1_000)
        assertEquals(1, withdrawals().size)

        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        assertEquals(1, forgets().size)
    }

    @Test
    fun `many announced events need one forget, not one each`() {
        start()
        for (id in 20..27) listener.get().onNotificationPosted(RelayHubFixture.chat(app, id = id, sender = "S$id"))
        RelayHubFixture.idleFor(2_000)
        settings.setEnabled(false)
        listener.get().refreshFromSettings()
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        assertTrue(forgets().size in 1..2)
        assertTrue(withdrawals().isEmpty())
    }

    @Test
    fun `an app turned Off while the listener is detached is still forgotten`() {
        start()
        // Destroyed with its access kept: the teardown itself asks for nothing.
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(app, RelayNotificationListener::class.java), true)
        listener.destroy()
        RelayHubFixture.idle()
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        assertEquals(1, forgets().size)
    }

    /** The system unbinds the listener while the wearer keeps Relay's notification access. */
    private fun disconnectKeepingAccess() {
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(app, RelayNotificationListener::class.java), true)
        listener.get().onListenerDisconnected()
        RelayHubFixture.idleFor(1_000)
        assertTrue("a mere rebind keeps the glasses' history", forgets().isEmpty())
    }

    private fun owed() = app.getSharedPreferences("relay_history", 0).getBoolean("forget_owed", false)

    @Test
    fun `an app turned Off after a real disconnect, with access kept, is still forgotten`() {
        start()
        disconnectKeepingAccess()

        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)

        assertEquals(1, forgets().size)
        assertFalse(owed())
    }

    @Test
    fun `Relay switched off after a real disconnect, with an empty inbox, is still forgotten`() {
        start()
        disconnectKeepingAccess()

        settings.setEnabled(false)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)

        assertEquals(1, forgets().size)
    }

    @Test
    fun `a wider policy forgets nothing`() {
        start()
        disconnectKeepingAccess()

        settings.setAppPolicy(chat.packageName, RelayAppPolicy.ALL)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)

        assertTrue(forgets().isEmpty())
    }

    @Test
    fun `a forget the hub never took stays owed and goes again with the next client`() {
        start()
        hub.acceptsForgets = false
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        assertEquals(1, forgets().size)
        assertTrue("sent is not taken", owed())

        hub.acceptsForgets = true
        service.destroy()
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idleFor(1_000)

        assertTrue(forgets().size >= 2)
        assertFalse(owed())
    }

    @Test
    fun `an unaccepted forget goes again when the same client registers again`() {
        start()
        hub.acceptsForgets = false
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        assertEquals(1, forgets().size)

        // The hub restarted before taking it; the same client registers with the new one, a new
        // SDK registration generation, as its transport reports after a reconnect.
        hub.acceptsForgets = true
        val client = checkNotNull(RelayBus.client)
        val generation = client.registrationGeneration
        client.onRegistrationState(PluginRegistrationResult.APPROVED)
        assertEquals(generation + 1, client.registrationGeneration)
        RelayHubFixture.idleFor(1_000)

        assertEquals(2, forgets().size)
        assertFalse(owed())
    }

    @Test
    fun `a late acceptance of an older forget does not settle a newer one`() {
        start()
        hub.acceptsForgets = false
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.ALL)
        NotificationControl.refreshFromSettings(app)
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.MESSAGES)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        val older = hub.forgetIds.single()

        settings.setEnabled(false)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        val newer = hub.forgetIds.last()
        assertTrue(newer != older)

        hub.acceptForget(older)
        RelayHubFixture.idleFor(1_000)
        assertTrue("the newer request may follow content the older one never covered", owed())
        hub.acceptForget(newer)
        RelayHubFixture.idleFor(1_000)
        assertFalse(owed())
    }

    @Test
    fun `the repeated approval report of one registration does not send the same forget twice`() {
        start()
        hub.acceptsForgets = false
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(app)
        RelayHubFixture.idleFor(1_000)
        assertEquals(1, forgets().size)

        // The metadata report of the registration already in place: no new SDK generation.
        hub.deliver(
            BusPaths.PLUGIN_REGISTRATION,
            JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", FakeHub.CAPABILITIES)
                .put("noticeInteractionVersion", 1),
        )
        RelayHubFixture.idleFor(1_000)

        assertEquals(1, forgets().size)
        assertTrue(owed())
    }

    @Test
    fun `a filtered repost while paused still ends a read-only alert, so it cannot be announced later`() {
        start()
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.ALL)
        val alert = alert(12, "Build 413 passed")
        listener.get().onNotificationPosted(alert)
        RelayHubFixture.idle()
        assertTrue(ReplyRepository.isActive(idOf(alert)))

        settings.setPauseWhilePhoneScreenOn(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val ongoing = Notification.Builder(app, "chat").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Build").setContentText("Build 413 passed").setOngoing(true).build()
        listener.get().onNotificationPosted(
            StatusBarNotification(chat.packageName, chat.opPkg, 12, null, chat.uid, 0, 0, ongoing, chat.user, System.currentTimeMillis()),
        )
        RelayHubFixture.idle()

        assertFalse(ReplyRepository.isActive(idOf(alert)))
    }

    @Test
    fun `switching Relay off asks the glasses to forget Relay's notifications`() {
        start()
        settings.setEnabled(false)
        listener.get().refreshFromSettings()
        RelayHubFixture.idleFor(1_000)
        assertTrue(forgets().isNotEmpty())
    }

    @Test
    fun `an ordinary removal only withdraws, keeping the text readable`() {
        start()
        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idleFor(1_000)
        assertEquals(idOf(chat), withdrawals().single().getString(NoticeSurfaceContract.FIELD_EVENT_KEY))
        assertTrue(forgets().isEmpty())
    }

    @Test
    fun `a read-only alert reposted as ongoing stops being active and is never announced late`() {
        start()
        settings.setAppPolicy(chat.packageName, RelayAppPolicy.ALL)
        val alert = alert(11, "Build 412 passed")
        listener.get().onNotificationPosted(alert)
        RelayHubFixture.idle()
        assertTrue(ReplyRepository.isActive(idOf(alert)))
        val ongoing = Notification.Builder(app, "chat").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Build").setContentText("Build 412 passed").setOngoing(true).build()
        listener.get().onNotificationPosted(
            StatusBarNotification(chat.packageName, chat.opPkg, 11, null, chat.uid, 0, 0, ongoing, chat.user, System.currentTimeMillis()),
        )
        RelayHubFixture.idle()
        assertFalse(ReplyRepository.isActive(idOf(alert)))
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
