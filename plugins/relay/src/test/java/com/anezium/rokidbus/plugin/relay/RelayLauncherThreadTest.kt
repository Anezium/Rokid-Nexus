package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PageCorrelation
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
 * A Relay thread as the launcher sees it, and the band's reply around it: real listener, band,
 * plugin service and SDK client against a hub that may or may not run the launcher bundle.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class RelayLauncherThreadTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private lateinit var listener: ServiceController<RelayNotificationListener>
    private lateinit var service: ServiceController<RelayPluginService>
    private lateinit var chat: StatusBarNotification

    /** One chat notification captured and announced, with the plugin service and its one client up. */
    private fun start(launcher: Boolean) {
        hub.pageVersion = PageSurfaceContract.VERSION
        if (launcher) {
            hub.launcherVersion = LauncherInteractiveContract.VERSION
            hub.features = hub.features or BusCapabilityBits.LAUNCHER_INTERACTIVE
        }
        RelayHubFixture.install(app, hub)
        RelaySettings(app).setEnabled(true)
        // Cancelling the app's notification is the listener's job on a device, not this test's.
        RelaySettings(app).setClearAfterReply(false)
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
        RelaySettings(app).setClearAfterReply(RelaySettings.DEFAULT_CLEAR_AFTER_REPLY)
        RelayHubFixture.idle()
    }

    private val threadId: String
        get() = ReplyRepository.stableId(chat.key)

    private fun handoffs() = ReplyRepository.inboxEntries().single { it.id == threadId }.snapshot.handoffs

    private fun request(requestId: String, pageId: String) =
        PageSurfaceContract.correlationPayload(PageCorrelation(requestId, 1, 1, pageId))
            .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a")

    private fun answersFor(pageId: String) = hub.sentTo(BusPaths.PAGE_RESPONSE).filter { it.optString("pageId") == pageId }

    /** The thread's page requested and visible under a lease, as the glasses hold it. */
    private fun openThread(): String {
        val thread = RelayPages.threadPageId(threadId)
        hub.deliver(BusPaths.PAGE_REQUEST, request("r1", thread))
        hub.deliver(
            BusPaths.PAGE_VISIBILITY,
            JSONObject().put("pageId", thread).put("visible", true).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a")
                .put("leaseUntilMs", 999_999L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000L),
        )
        RelayHubFixture.idle()
        return thread
    }

    /** The hub composer's Send on the revision the glasses show. */
    private fun composerSend(thread: String, invocationId: String, text: String): JSONObject {
        val revision = answersFor(thread).last().getLong("revision")
        hub.deliver(
            BusPaths.PAGE_ACTION,
            JSONObject().put("invocationId", invocationId).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put("pageId", thread).put("revision", revision).put("actionId", RelayPages.SEND_ACTION_ID)
                .put("mode", PageSurfaceContract.MODE_COMPOSE).put("text", text)
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        RelayHubFixture.idleFor(1_000)
        return hub.sentTo(BusPaths.PAGE_RESULT).single { it.getString("invocationId") == invocationId }
    }

    private fun composer(answer: JSONObject) = answer.getJSONObject("body").getJSONObject("composer")

    private fun messages(answer: JSONObject): List<JSONObject> {
        val array = answer.getJSONObject("body").getJSONArray("messages")
        return (0 until array.length()).map(array::getJSONObject)
    }

    /** The wearer chose Type on the band and committed [text]; its send countdown is running. */
    private fun typeOnTheBand(text: String) {
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
        RelayHubFixture.idleFor(500)
        assertTrue(hub.sentTo(BusPaths.NOTICE_UPDATE).any { it.optString("body") == text })
    }

    @Test
    fun `with the launcher bundle the notice names its thread and the thread is a conversation`() {
        start(launcher = true)
        val shown = hub.sentTo(BusPaths.NOTICE_SHOW).single()
        assertEquals(threadId, shown.getString(NoticeSurfaceContract.FIELD_EVENT_KEY))
        assertEquals(
            RelayPages.threadPageId(threadId),
            shown.getJSONObject(NoticeSurfaceContract.FIELD_DESTINATION).getString("pageId"),
        )
        assertTrue(shown.getBoolean(NoticeSurfaceContract.FIELD_CONVERSATION))

        val answer = answersFor(openThread()).single()
        assertEquals(PageSurfaceContract.TEMPLATE_CONVERSATION, answer.getString("template"))
        // The notice's event key is the conversation's thread key.
        assertEquals(threadId, answer.getJSONObject("body").getString("threadKey"))
        assertTrue(answer.getJSONObject("body").getString("caption").startsWith("Messages · captured "))
        assertTrue(composer(answer).getBoolean("replyable"))
        assertEquals(listOf("on est devant"), messages(answer).map { it.getString("text") })
    }

    @Test
    fun `without the launcher bundle the notice has no launcher fields and the thread is a document`() {
        start(launcher = false)
        val shown = hub.sentTo(BusPaths.NOTICE_SHOW).single()
        assertFalse(shown.has(NoticeSurfaceContract.FIELD_EVENT_KEY))
        assertFalse(shown.has(NoticeSurfaceContract.FIELD_DESTINATION))
        assertFalse(shown.has(NoticeSurfaceContract.FIELD_CONVERSATION))
        val answer = answersFor(openThread()).single()
        assertEquals(PageSurfaceContract.TEMPLATE_DOCUMENT, answer.getString("template"))
        assertEquals(0, answer.getJSONArray("actions").length())
    }

    @Test
    fun `a composer send is handed to the app and the thread keeps it as ours in the next revision`() {
        start(launcher = true)
        val thread = openThread()
        val before = answersFor(thread).last().getLong("revision")

        val result = composerSend(thread, "i1", "On my way")
        assertEquals("done", result.getString("status"))
        assertEquals(RelayComposeHandler.HANDED_OFF_MESSAGE, result.getString("message"))

        val revised = answersFor(thread).last()
        assertTrue(revised.getLong("revision") > before)
        val ours = messages(revised).last()
        assertEquals("ours", ours.getString("role"))
        assertEquals("On my way", ours.getString("text"))
        assertEquals("handed_off", ours.getString("status"))
        assertEquals("on est devant", messages(revised).first().getString("text"))
        // Still the same thread, still replyable: a reply does not end the conversation.
        assertTrue(composer(revised).getBoolean("replyable"))
        assertEquals(listOf("On my way"), handoffs().map { it.text })
    }

    @Test
    fun `the composer's send is revalidated just before it goes`() {
        start(launcher = true)
        val thread = openThread()
        // The reply action goes before the page hears of it: the glasses still offer Send.
        ReplyRepository.forget(chat)

        val result = composerSend(thread, "i1", "On my way")
        assertEquals("rejected", result.getString("status"))
        assertEquals(PageSurfaceContract.CODE_AUTHORITY_LOST, result.getString("code"))
        assertTrue(handoffs().isEmpty())
        // The page follows, with the text still there.
        assertFalse(composer(answersFor(thread).last()).getBoolean("replyable"))
    }

    @Test
    fun `a removed notification is withdrawn and its page keeps the text without Send`() {
        start(launcher = true)
        val thread = openThread()
        val before = answersFor(thread).last().getLong("revision")

        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idleFor(1_000)

        assertEquals(listOf(threadId), hub.sentTo(BusPaths.NOTICE_WITHDRAW).map { it.getString(NoticeSurfaceContract.FIELD_EVENT_KEY) })
        val revised = answersFor(thread).last()
        assertTrue(revised.getLong("revision") > before)
        assertFalse(composer(revised).getBoolean("replyable"))
        assertEquals(RelayPages.UNAVAILABLE_TEXT, composer(revised).getString("unavailableText"))
        assertEquals(listOf("on est devant"), messages(revised).map { it.getString("text") })

        // A Send that still reaches Relay is lost authority, and nothing goes to the app.
        val result = composerSend(thread, "i1", "trop tard")
        assertEquals(PageSurfaceContract.CODE_AUTHORITY_LOST, result.getString("code"))
        assertTrue(handoffs().isEmpty())
    }

    @Test
    fun `without the launcher bundle a removal withdraws nothing`() {
        start(launcher = false)
        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idleFor(1_000)
        assertTrue(hub.sentTo(BusPaths.NOTICE_WITHDRAW).isEmpty())
    }

    @Test
    fun `a countdown the glasses show sends on its own, and the thread stays`() {
        start(launcher = true)
        typeOnTheBand("On my way")
        RelayHubFixture.idleFor(10_000)
        assertEquals(listOf(RelayHandoffStatus.HANDED_OFF), handoffs().map { it.status })
        assertEquals(listOf("On my way"), handoffs().map { it.text })
    }

    @Test
    fun `a removal while the reply waits keeps the words on the band and never sends them`() {
        start(launcher = true)
        typeOnTheBand("On my way")

        listener.get().onNotificationRemoved(chat)
        RelayHubFixture.idleFor(10_000)

        assertTrue(handoffs().isEmpty())
        val last = hub.sentTo(BusPaths.NOTICE_UPDATE).last()
        assertEquals("On my way", last.optString("body"))
        assertEquals(RelayNoticeRuntime.REPLY_UNAVAILABLE_FOOTER, last.optString("footer"))
        val actions = last.getJSONArray("actions")
        assertEquals(listOf(RelayNoticeRuntime.ACTION_UNAVAILABLE), (0 until actions.length()).map { actions.getJSONObject(it).getString("id") })
    }

    @Test
    fun `a countdown never sends once the data link is lost`() {
        start(launcher = true)
        typeOnTheBand("On my way")

        hub.link = LinkStateBits.CXR_CONTROL_UP
        hub.callback!!.onLinkState(hub.link)
        RelayHubFixture.idleFor(10_000)

        assertTrue(handoffs().isEmpty())
    }

    @Test
    fun `an update without its reply action takes the reply authority away`() {
        start(launcher = true)
        assertTrue(ReplyRepository.hasReplyAuthority(threadId))

        val read = android.app.Notification.Builder(app, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mika")
            .setContentText("on est devant")
            .build()
        val updated = StatusBarNotification(
            chat.packageName, chat.opPkg, chat.id, chat.tag, chat.uid, 0, 0, read, chat.user, System.currentTimeMillis(),
        )
        listener.get().onNotificationPosted(updated)
        RelayHubFixture.idle()

        assertFalse(ReplyRepository.hasReplyAuthority(threadId))
        // The thread itself stays readable; only Send goes.
        assertTrue(ReplyRepository.inboxEntries().any { it.id == threadId })
    }

    @Test
    fun `a repost without its reply action stops the band countdown for good`() {
        start(launcher = true)
        typeOnTheBand("On my way")
        val read = android.app.Notification.Builder(app, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mika")
            .setContentText("on est devant")
            .build()
        listener.get().onNotificationPosted(
            StatusBarNotification(chat.packageName, chat.opPkg, chat.id, chat.tag, chat.uid, 0, 0, read, chat.user, System.currentTimeMillis()),
        )
        // Updates already queued before the repost drain first.
        RelayHubFixture.idleFor(500)
        val mark = hub.sentTo(BusPaths.NOTICE_UPDATE).size
        RelayHubFixture.idleFor(10_000)

        assertTrue(handoffs().isEmpty())
        // No countdown label ticks on after the reply is gone.
        val later = hub.sentTo(BusPaths.NOTICE_UPDATE).drop(mark)
        assertTrue(later.none { it.toString().contains("Sending") })
    }

    @Test
    fun `a countdown never sends after a band update failed`() {
        start(launcher = true)
        typeOnTheBand("On my way")

        // The surfaces grant goes: the next update of the band cannot reach the glasses.
        hub.granted = "stt,tts"
        hub.deliverToEveryClient(BusPaths.PLUGIN_REGISTRATION) {
            JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "stt,tts").put("noticeInteractionVersion", 1)
        }
        RelayHubFixture.idleFor(10_000)

        assertTrue(handoffs().isEmpty())
    }
}
