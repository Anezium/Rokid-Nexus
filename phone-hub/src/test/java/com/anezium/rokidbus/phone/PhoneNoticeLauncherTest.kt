package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNoticeLauncherTest {
    private val sink = RecordingPhoneHudRouteSink(
        capabilityBits = BusCapabilityBits.NOTICE_SURFACE,
        linkUp = true,
    )
    private var launcherVersion: Int? = 1
    private var nowMs = 0L
    private val router = PhoneNoticeRouter(
        state = PhoneNoticeState(nowMs = { nowMs }, initialSequence = 0L),
        sink = sink,
        launcherVersion = { launcherVersion },
    )

    @Test
    fun `negotiated glasses get the destination, event key and conversation flag`() {
        router.handleLocal(conversationShow("relay"), hudSender("relay"))

        val sent = sink.remote.single().payload
        assertEquals("relay:thread:9f2c", sent.getJSONObject("destination").getString("pageId"))
        assertEquals("9f2c", sent.getString("eventKey"))
        assertTrue(sent.getBoolean("conversation"))
        assertEquals("relay", sent.getString("ownerPluginId"))
    }

    @Test
    fun `glasses without the bundle get the plain notice`() {
        launcherVersion = 0
        router.handleLocal(conversationShow("relay"), hudSender("relay"))

        val sent = sink.remote.single().payload
        assertFalse(sent.has(NoticeSurfaceContract.FIELD_DESTINATION))
        assertFalse(sent.has(NoticeSurfaceContract.FIELD_EVENT_KEY))
        assertFalse(sent.has(NoticeSurfaceContract.FIELD_CONVERSATION))
        assertEquals("On my way", sent.getString("body"))
    }

    @Test
    fun `glasses that have not announced yet get the plain notice`() {
        launcherVersion = null
        router.handleLocal(conversationShow("relay"), hudSender("relay"))

        assertFalse(sink.remote.single().payload.has(NoticeSurfaceContract.FIELD_EVENT_KEY))
    }

    @Test
    fun `a destination in another plugin's namespace is refused`() {
        val show = conversationShow("relay").also {
            it.payload.put("destination", JSONObject().put("pageId", "assistant:chat"))
        }
        router.handleLocal(show, hudSender("relay"))

        assertTrue(sink.remote.isEmpty())
        assertEquals(PluginBusJournal.Verdict.REJECTED, sink.localRoutes.single().verdict)
        assertEquals(NoticeSurfaceContract.ERROR_INVALID_NOTICE, sink.localRoutes.single().reason)
    }

    @Test
    fun `the authenticated sender decides whose destination it is, not the payload`() {
        // A payload claiming relay, sent by assistant, never reaches the state as relay's.
        val show = conversationShow("relay")
        router.handleLocal(show, hudSender("assistant"))

        assertTrue(sink.remote.isEmpty())
        assertEquals(NoticeSurfaceContract.ERROR_INVALID_NOTICE, sink.localRoutes.single().reason)
    }

    @Test
    fun `malformed event keys and conversation flags are refused by the local gate`() {
        val badKey = conversationShow("relay").copy(
            payload = localShow().put("eventKey", "has space").put("destination", thread()),
        )
        val badFlag = conversationShow("relay").copy(payload = localShow().put("conversation", "yes"))
        val orphanConversation = conversationShow("relay").copy(payload = localShow().put("conversation", true))

        assertEquals(NoticeSurfaceContract.ERROR_INVALID_NOTICE, router.invalidLocalReason(badKey))
        assertEquals(NoticeSurfaceContract.ERROR_INVALID_NOTICE, router.invalidLocalReason(badFlag))
        assertEquals(NoticeSurfaceContract.ERROR_INVALID_NOTICE, router.invalidLocalReason(orphanConversation))
    }

    @Test
    fun `launcher fields are show-only and refused on update`() {
        val update = BusEnvelope(
            path = BusPaths.NOTICE_UPDATE,
            payload = JSONObject().put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID).put("eventKey", "9f2c"),
        )

        assertEquals(NoticeSurfaceContract.ERROR_INVALID_NOTICE, router.invalidLocalReason(update))
    }

    @Test
    fun `withdraw forwards only the authenticated owner and the key`() {
        router.handleLocal(withdraw("relay", "9f2c").also { it.payload.put("ownerPluginId", "assistant") }, hudSender("relay"))

        val sent = sink.remote.single()
        assertEquals(BusPaths.NOTICE_WITHDRAW, sent.path)
        assertEquals("relay", sent.payload.getString("ownerPluginId"))
        assertEquals("9f2c", sent.payload.getString("eventKey"))
        assertEquals(setOf("ownerPluginId", "eventKey"), sent.payload.keys().asSequence().toSet())
        assertEquals(PluginBusJournal.Verdict.OK, sink.localRoutes.single().verdict)
        assertTrue(sink.errors.isEmpty())
    }

    @Test
    fun `withdraw passes the local gate only with a valid key and no binary`() {
        assertNull(router.invalidLocalReason(withdraw("relay", "9f2c")))
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            router.invalidLocalReason(withdraw("relay", "9f2c").copy(binary = byteArrayOf(1))),
        )
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            router.invalidLocalReason(
                BusEnvelope(path = BusPaths.NOTICE_WITHDRAW, payload = JSONObject().put("surfaceId", "notice")),
            ),
        )
        assertTrue(router.matches(BusPaths.NOTICE_WITHDRAW))
    }

    @Test
    fun `withdraw without the bundle is refused and never reaches the glasses`() {
        launcherVersion = 0
        router.handleLocal(withdraw("relay", "9f2c"), hudSender("relay"))

        assertTrue(sink.remote.isEmpty())
        assertEquals(NoticeSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, sink.errors.single().second)
    }

    @Test
    fun `withdraw while the link is down waits for the next announce`() {
        sink.linkUp = false
        router.handleLocal(withdraw("relay", "9f2c"), hudSender("relay"))
        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.errors.isEmpty())

        sink.linkUp = true
        router.onGlassesAnnounced()

        val sent = sink.remote.single()
        assertEquals(BusPaths.NOTICE_WITHDRAW, sent.path)
        assertEquals("9f2c", sent.payload.getString("eventKey"))
        router.onGlassesAnnounced()
        assertEquals(1, sink.remote.size)
    }

    @Test
    fun `withdraw shares the notice rate budget`() {
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) { index ->
            router.handleLocal(withdraw("relay", "key-$index"), hudSender("relay"))
        }
        router.handleLocal(withdraw("relay", "one-too-many"), hudSender("relay"))

        assertEquals(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND, sink.remote.size)
        assertEquals(NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED, sink.errors.single().second)
    }

    @Test
    fun `purge names the owner and is sent at once while the link is up`() {
        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        assertEquals(setOf("relay"), router.historyOwnerIds())

        router.purgeOwner("relay", "authorizationChanged")

        val purge = sink.remote.last()
        assertEquals(BusPaths.CORE_NOTICE_PURGE, purge.path)
        assertEquals("relay", NoticeSurfaceContract.parsePurge(purge.payload))
        assertTrue(router.historyOwnerIds().isEmpty())
    }

    @Test
    fun `purge while offline is sent on announce before any withdrawal and drops the owner's withdrawals`() {
        sink.linkUp = false
        router.handleLocal(withdraw("relay", "a1"), hudSender("relay"))
        router.handleLocal(withdraw("assistant", "b1"), hudSender("assistant"))
        router.purgeOwner("relay", "ownerUnavailable")
        assertTrue(sink.remote.isEmpty())

        sink.linkUp = true
        router.onGlassesAnnounced()

        assertEquals(listOf(BusPaths.CORE_NOTICE_PURGE, BusPaths.NOTICE_WITHDRAW), sink.remote.map { it.path })
        assertEquals("relay", sink.remote[0].payload.getString("ownerPluginId"))
        assertEquals("assistant", sink.remote[1].payload.getString("ownerPluginId"))
    }

    @Test
    fun `purge before the glasses announce waits, and is dropped if they turn out not to have the bundle`() {
        launcherVersion = null
        router.purgeOwner("relay", "authorizationChanged")
        assertTrue(sink.remote.isEmpty())

        launcherVersion = 0
        router.onGlassesAnnounced()
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `no purge goes to glasses known not to have the bundle`() {
        launcherVersion = 0
        router.purgeOwner("relay", "authorizationChanged")

        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `a show to glasses without the bundle leaves no history owner to purge`() {
        launcherVersion = 0
        router.handleLocal(conversationShow("relay"), hudSender("relay"))

        assertTrue(router.historyOwnerIds().isEmpty())
    }

    private fun thread(): JSONObject = JSONObject().put("pageId", "relay:thread:9f2c")

    private fun localShow(): JSONObject = JSONObject()
        .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .put("kind", NoticeSurfaceContract.KIND)
        .put("title", "Marie")
        .put("body", "On my way")

    /** A show as it reaches the router: owner already stamped from the payload's claimed plugin. */
    private fun conversationShow(pluginId: String) = BusEnvelope(
        path = BusPaths.NOTICE_SHOW,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("kind", NoticeSurfaceContract.KIND)
            .put("title", "Marie")
            .put("body", "On my way")
            .put("destination", thread())
            .put("eventKey", "9f2c")
            .put("conversation", true),
    )

    private fun withdraw(pluginId: String, eventKey: String) = BusEnvelope(
        path = BusPaths.NOTICE_WITHDRAW,
        payload = NoticeSurfaceContract.withdrawPayload(eventKey).also {
            // The SDK's local surfaceId lets the hub stamp the owner like a show.
            assertEquals(NoticeSurfaceContract.LOCAL_SURFACE_ID, it.getString("surfaceId"))
            assertTrue(pluginId.isNotEmpty())
        },
    )
}
