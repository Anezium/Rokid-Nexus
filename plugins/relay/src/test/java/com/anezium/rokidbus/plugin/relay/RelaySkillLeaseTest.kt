package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.skills.SkillsContract
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
 * Relay bound only for a skill call, through its real client: the hub would refuse notice and page
 * traffic then, so nothing may be sent meanwhile, the inbox stays shut, and what was held goes
 * once the call has settled.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class RelaySkillLeaseTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private val settings = RelaySettings(app)
    private lateinit var listener: ServiceController<RelayNotificationListener>
    private lateinit var service: ServiceController<RelayPluginService>
    private var nextInvocation = 0

    private fun start() {
        hub.pageVersion = PageSurfaceContract.VERSION
        hub.launcherVersion = LauncherInteractiveContract.VERSION
        hub.features = hub.features or BusCapabilityBits.LAUNCHER_INTERACTIVE
        hub.granted = "${FakeHub.CAPABILITIES},skills_provider"
        RelayHubFixture.install(app, hub)
        settings.setEnabled(true)
        settings.setClearAfterReply(false)
        listener = Robolectric.buildService(RelayNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idle()
        hub.deliver(
            BusPaths.PLUGIN_REGISTRATION,
            JSONObject()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "${FakeHub.CAPABILITIES},skills_provider")
                .put("noticeInteractionVersion", 1)
                .put(SkillsContract.REGISTRATION_FIELD, SkillsContract.VERSION),
        )
        RelayHubFixture.idle()
    }

    @After
    fun tearDown() {
        // Lets every call settle: the count is process-wide, like the service it stands in for.
        RelayHubFixture.idleFor(2_000)
        service.destroy()
        listener.destroy()
        ReplyRepository.clear()
        app.getSharedPreferences("relay_history", 0).edit().clear().commit()
        settings.resetAppPolicies()
        settings.setClearAfterReply(RelaySettings.DEFAULT_CLEAR_AFTER_REPLY)
        RelayHubFixture.idle()
    }

    /** Delivers the call without running the main-thread work it queues. */
    private fun deliverInvocation(operation: String, arguments: JSONObject = JSONObject()): String {
        val id = "inv_%010x".format(nextInvocation++)
        hub.deliver(
            BusPaths.SKILLS_PROVIDER_INVOKE,
            JSONObject()
                .put("version", 1)
                .put("invocationId", id)
                .put("operation", operation)
                .put("contractVersion", 1)
                .put("arguments", arguments)
                .put("deadlineMs", 14_000),
        )
        return id
    }

    private fun invoke(operation: String, arguments: JSONObject = JSONObject()): JSONObject {
        val id = deliverInvocation(operation, arguments)
        RelayHubFixture.idle()
        return hub.sentTo(BusPaths.SKILLS_PROVIDER_RESULT).last { it.optString("invocationId") == id }
    }

    private fun post(sbn: StatusBarNotification = RelayHubFixture.chat(app)) {
        listener.get().onNotificationPosted(sbn)
        RelayHubFixture.idle()
    }

    private fun noticeTraffic(mark: Int): List<String> = hub.sentSince(mark).map { it.first }
        .filter { it.startsWith("/notice") || it.startsWith("/page") || it.startsWith("/surface") }

    @Test
    fun `a call leaves the inbox shut and answers without touching the hud`() {
        start()
        val mark = hub.sent.size

        val result = invoke(RelaySkills.LIST_REPLY_THREADS)

        assertEquals("completed", result.getString("status"))
        assertFalse(NotificationControl.inboxOpen)
        assertTrue(noticeTraffic(mark).isEmpty())
    }

    @Test
    fun `a full send leaves the inbox shut and sends no page or notice traffic while the call holds`() {
        start()
        post()
        RelayHubFixture.idleFor(2_000)
        val thread = ReplyRepository.stableId(RelayHubFixture.chat(app).key)
        val prepared = invoke(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", thread).put("text", "We meet at 3 pm"))
        val draft = prepared.getJSONObject("data").getString("draft")
        val mark = hub.sent.size

        val sent = invoke(RelaySkills.SEND_REPLY, JSONObject().put("draft", draft))

        assertEquals("accepted", sent.getString("status"))
        assertFalse(NotificationControl.inboxOpen)
        assertTrue(noticeTraffic(mark).isEmpty())
    }

    @Test
    fun `a notification arriving during a call gets its band only once the call has settled`() {
        start()
        RelayHubFixture.idleFor(2_000)
        invoke(RelaySkills.LIST_REPLY_THREADS)
        val mark = hub.sent.size

        post()
        assertTrue(noticeTraffic(mark).isEmpty())

        RelayHubFixture.idleFor(1_100)
        assertTrue(noticeTraffic(mark).contains(BusPaths.NOTICE_SHOW))
    }

    @Test
    fun `a withdrawal owed during a call stays owed until the call has settled`() {
        start()
        val chat = RelayHubFixture.chat(app)
        post(chat)
        RelayHubFixture.idleFor(2_000)
        assertTrue(hub.sentTo(BusPaths.NOTICE_SHOW).isNotEmpty())
        hub.sentTo(BusPaths.NOTICE_SHOW).last().optString("eventKey").let { assertTrue(it.isNotBlank()) }
        invoke(RelaySkills.LIST_REPLY_THREADS)
        val mark = hub.sent.size

        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idle()
        assertFalse(noticeTraffic(mark).contains(BusPaths.NOTICE_WITHDRAW))

        RelayHubFixture.idleFor(1_100)
        assertTrue(noticeTraffic(mark).contains(BusPaths.NOTICE_WITHDRAW))
    }

    @Test
    fun `a message arriving while a draft waits holds its band until the send, and it does not replace the confirmation`() {
        start()
        post()
        RelayHubFixture.idleFor(2_000)
        val thread = ReplyRepository.stableId(RelayHubFixture.chat(app).key)
        val draft = invoke(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", thread).put("text", "On my way"))
            .getJSONObject("data").getString("draft")
        RelayHubFixture.idleFor(2_000)
        val mark = hub.sent.size

        post(RelayHubFixture.chat(app, text = "a newer message"))
        RelayHubFixture.idleFor(2_000)
        assertFalse(noticeTraffic(mark).contains(BusPaths.NOTICE_SHOW))

        invoke(RelaySkills.SEND_REPLY, JSONObject().put("draft", draft))
        RelayHubFixture.idleFor(3_000)
        assertFalse(noticeTraffic(mark).contains(BusPaths.NOTICE_SHOW))
        RelayHubFixture.idleFor(1_100)
        assertTrue(noticeTraffic(mark).contains(BusPaths.NOTICE_SHOW))
    }

    @Test
    fun `a draft nobody sends stops holding bands by itself`() {
        start()
        post()
        RelayHubFixture.idleFor(2_000)
        val thread = ReplyRepository.stableId(RelayHubFixture.chat(app).key)
        invoke(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", thread).put("text", "On my way"))
        RelayHubFixture.idleFor(2_000)
        val mark = hub.sent.size

        post(RelayHubFixture.chat(app, text = "a newer message"))
        assertFalse(noticeTraffic(mark).contains(BusPaths.NOTICE_SHOW))

        RelayHubFixture.idleFor(60_000)
        assertTrue(noticeTraffic(mark).contains(BusPaths.NOTICE_SHOW))
    }

    @Test
    fun `an operation Relay does not know is refused, with nothing dispatched`() {
        start()
        val result = invoke("delete_everything")
        assertEquals("failed", result.getString("status"))
        assertEquals("none", result.getJSONObject("error").getString("dispatch"))
    }
}
