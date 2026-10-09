package com.anezium.rokidbus.plugin.relay

import android.app.Application
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Visible Relay pages follow the loss of what they show: Notification Access revoked, or Relay
 * switched off. Real listener, settings, plugin service and SDK client against a page-v1 hub.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class RelayPagesAccessTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub().apply { pageVersion = PageSurfaceContract.VERSION }
    private lateinit var listener: ServiceController<RelayNotificationListener>
    private lateinit var service: ServiceController<RelayPluginService>

    @Before
    fun setUp() {
        RelayHubFixture.install(app, hub)
        RelaySettings(app).setEnabled(true)
        listener = Robolectric.buildService(RelayNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        listener.get().onNotificationPosted(RelayHubFixture.chat(app))
        // The hub binds the service cold for page demand; its one client registers.
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idle()
    }

    @After
    fun tearDown() {
        service.destroy()
        listener.destroy()
        ReplyRepository.clear()
        RelaySettings(app).setEnabled(true)
        RelaySettings(app).setHideInboxPreviews(false)
        RelayHubFixture.idle()
    }

    private fun request(requestId: String, pageId: String, frameIndex: Int, registration: String = "reg-a") =
        PageSurfaceContract.correlationPayload(PageCorrelation(requestId, 1, frameIndex, pageId))
            .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, registration)

    /** The glasses' Retry of a frame they retained at [floor], to a provider registered again. */
    private fun retry(requestId: String, floor: Long) =
        request(requestId, RelayPages.MAIN, 1, registration = "reg-b").put("reason", "retry")
            .put(PageSurfaceContract.FIELD_MINIMUM_REVISION, floor)

    /** The page-only service went away with its binding, then is bound cold again: a new provider. */
    private fun recreateService() {
        service.destroy()
        RelayHubFixture.idle()
        service = Robolectric.buildService(RelayPluginService::class.java).create()
        RelayHubFixture.idle()
    }

    /** The list the glasses were sent first, at the revision they retain. */
    private fun firstList(): Long {
        hub.deliver(BusPaths.PAGE_REQUEST, request("r1", RelayPages.MAIN, 1))
        RelayHubFixture.idle()
        val first = answersFor(RelayPages.MAIN).single()
        assertTrue(first.toString(), first.toString().contains("on est devant"))
        return first.getLong("revision")
    }

    @Test
    fun `a recreated provider answers a Retry above the retained revision, with previews now hidden`() {
        val floor = firstList()
        recreateService()
        RelaySettings(app).setHideInboxPreviews(true)
        hub.deliver(BusPaths.PAGE_REQUEST, retry("r2", floor))
        RelayHubFixture.idle()
        val answer = answersFor(RelayPages.MAIN).last()
        assertTrue("revision ${answer.getLong("revision")} floor $floor", answer.getLong("revision") > floor)
        assertFalse(answer.toString().contains("on est devant"))
        assertTrue(answer.toString().contains(RelayPrivacy.HIDDEN_BODY))
    }

    @Test
    fun `a recreated provider answers a Retry above the retained revision, with the inbox now empty`() {
        val floor = firstList()
        recreateService()
        ReplyRepository.clear()
        hub.deliver(BusPaths.PAGE_REQUEST, retry("r2", floor))
        RelayHubFixture.idle()
        val answer = answersFor(RelayPages.MAIN).last()
        assertTrue("revision ${answer.getLong("revision")} floor $floor", answer.getLong("revision") > floor)
        assertEquals(0, rows(answer))
    }

    private fun visibility(pageId: String, frameIndex: Int, visible: Boolean) =
        JSONObject().put("pageId", pageId).put("visible", visible).put("sessionGeneration", 1L).put("frameIndex", frameIndex)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a").apply {
                if (visible) put("leaseUntilMs", 999_999L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000L)
            }

    private fun answersFor(pageId: String) = hub.sentTo(BusPaths.PAGE_RESPONSE).filter { it.optString("pageId") == pageId }

    private fun rows(answer: JSONObject) = answer.getJSONObject("body").getJSONArray("items").length()

    private fun segments(answer: JSONObject): List<String> {
        val array = answer.getJSONObject("body").getJSONArray("segments")
        return (0 until array.length()).map { array.getJSONObject(it).getString("text") }
    }

    /** The list shown and covered, and the one thread open and visible above it. */
    private fun threadOpenOverTheList(): String {
        hub.deliver(BusPaths.PAGE_REQUEST, request("r1", RelayPages.MAIN, 1))
        RelayHubFixture.idle()
        assertEquals(1, rows(answersFor(RelayPages.MAIN).single()))
        val thread = RelayPages.threadPageId(ReplyRepository.inboxEntries().single().id)
        hub.deliver(BusPaths.PAGE_VISIBILITY, visibility(RelayPages.MAIN, 1, visible = false))
        hub.deliver(BusPaths.PAGE_REQUEST, request("r2", thread, 2))
        hub.deliver(BusPaths.PAGE_VISIBILITY, visibility(thread, 2, visible = true))
        RelayHubFixture.idle()
        assertTrue(segments(answersFor(thread).single()).contains("on est devant"))
        return thread
    }

    @Test
    fun `revoking Notification Access turns the visible thread missing, and only that page`() {
        val thread = threadOpenOverTheList()
        val shows = hub.sentTo(BusPaths.NOTICE_SHOW).size
        listener.get().onListenerDisconnected()
        RelayHubFixture.idleFor(1_000)

        val revised = answersFor(thread).last()
        assertTrue(revised.getLong("revision") > answersFor(thread).first().getLong("revision"))
        assertEquals(listOf(RelayPages.MISSING_TEXT), segments(revised))
        assertEquals(1, answersFor(RelayPages.MAIN).size)
        assertEquals(shows, hub.sentTo(BusPaths.NOTICE_SHOW).size)
    }

    @Test
    fun `switching Relay off empties the visible list without a notice`() {
        hub.deliver(BusPaths.PAGE_REQUEST, request("r1", RelayPages.MAIN, 1))
        hub.deliver(BusPaths.PAGE_VISIBILITY, visibility(RelayPages.MAIN, 1, visible = true))
        RelayHubFixture.idle()
        assertEquals(1, rows(answersFor(RelayPages.MAIN).last()))
        val shows = hub.sentTo(BusPaths.NOTICE_SHOW).size

        // What the settings switch does.
        RelaySettings(app).setEnabled(false)
        NotificationControl.refreshFromSettings()
        RelayHubFixture.idleFor(1_000)

        assertEquals(0, rows(answersFor(RelayPages.MAIN).last()))
        assertEquals(shows, hub.sentTo(BusPaths.NOTICE_SHOW).size)
    }
}
