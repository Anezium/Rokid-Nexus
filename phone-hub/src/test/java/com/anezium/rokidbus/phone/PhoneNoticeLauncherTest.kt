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
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val router = PhoneNoticeRouter(
        state = PhoneNoticeState(nowMs = { nowMs }, initialSequence = 0L),
        sink = sink,
        launcherVersion = { launcherVersion },
        schedule = { delayMs, block -> scheduled += delayMs to block },
    )

    /** Advances the clock past every scheduled task and runs them, as the hub's handler would. */
    private fun runScheduled() {
        val due = scheduled.toList().also { scheduled.clear() }
        nowMs += due.maxOfOrNull { it.first } ?: 0L
        due.forEach { it.second() }
    }

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
        // The gate runs on the plugin's own envelope, before the hub stamps the owner.
        val fromSdk = BusEnvelope(path = BusPaths.NOTICE_WITHDRAW, payload = NoticeSurfaceContract.withdrawPayload("9f2c"))
        assertNull(router.invalidLocalReason(fromSdk))
        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            router.invalidLocalReason(fromSdk.copy(binary = byteArrayOf(1))),
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
    fun `a newer notice for the same event drops the withdrawal queued before it`() {
        sink.linkUp = false
        router.handleLocal(withdraw("relay", "9f2c"), hudSender("relay"))

        sink.linkUp = true
        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        router.onGlassesAnnounced()

        assertEquals(listOf(BusPaths.NOTICE_SHOW), sink.remote.map { it.path })
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
    fun `a plugin's forget purges only its own history, whatever the payload claims`() {
        router.handleLocal(forget("relay").also { it.payload.put("ownerPluginId", "assistant") }, hudSender("relay"))

        val purge = sink.remote.single()
        assertEquals(BusPaths.CORE_NOTICE_PURGE, purge.path)
        assertEquals("relay", NoticeSurfaceContract.parsePurge(purge.payload))
        assertTrue(sink.errors.isEmpty())
    }

    @Test
    fun `a forget past the notice budget goes once the window passes, on a link that stays up`() {
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) { index ->
            router.handleLocal(withdraw("relay", "key-$index"), hudSender("relay"))
        }
        router.handleLocal(forget("relay"), hudSender("relay"))
        router.handleLocal(forget("relay"), hudSender("relay"))
        assertTrue(sink.remote.none { it.path == BusPaths.CORE_NOTICE_PURGE })
        assertEquals("one drain per owner, however many forgets", 1, scheduled.size)

        runScheduled()

        assertEquals("relay", NoticeSurfaceContract.parsePurge(sink.remote.single { it.path == BusPaths.CORE_NOTICE_PURGE }.payload))
        router.onGlassesAnnounced()
        assertEquals("nothing is owed twice", 1, sink.remote.count { it.path == BusPaths.CORE_NOTICE_PURGE })
    }

    @Test
    fun `a deferred forget goes before the owner's next show, never after it`() {
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) { index ->
            router.handleLocal(withdraw("relay", "key-$index"), hudSender("relay"))
        }
        router.handleLocal(forget("relay"), hudSender("relay"))
        nowMs += 1_000L

        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        runScheduled()

        val paths = sink.remote.map { it.path }.filter { it == BusPaths.CORE_NOTICE_PURGE || it == BusPaths.NOTICE_SHOW }
        assertEquals(listOf(BusPaths.CORE_NOTICE_PURGE, BusPaths.NOTICE_SHOW), paths)
    }

    @Test
    fun `the sender hears that this hub holds its forget, sent or deferred`() {
        sink.linkUp = false
        val request = forget("relay")
        router.handleLocal(request, hudSender("relay"))

        val accepted = sink.local.single()
        assertEquals(BusPaths.NOTICE_FORGET_ACCEPTED, accepted.path)
        assertEquals("relay", accepted.payload.getString("pluginId"))
        assertEquals(request.id, accepted.payload.getString("forId"))
    }

    @Test
    fun `a forget while the glasses are away is kept for them`() {
        sink.linkUp = false
        router.handleLocal(forget("relay"), hudSender("relay"))
        assertTrue(sink.remote.isEmpty())

        sink.linkUp = true
        router.onGlassesAnnounced()
        assertEquals(BusPaths.CORE_NOTICE_PURGE, sink.remote.single().path)
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
    fun `a grant or package change purges exactly the owners that lost surfaces`() {
        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        router.handleLocal(conversationShow("assistant"), hudSender("assistant"))
        sink.remote.clear()
        val surfaceOwners = mutableSetOf("relay", "assistant")

        // An approval or a capability added elsewhere: nobody lost access, nothing is purged.
        router.purgeOwnersWithoutAccess({ it in surfaceOwners }, "authorizationChanged", "relay")
        assertTrue(sink.remote.isEmpty())

        // Revoke, disable, uninstall or a new signer: relay is no longer an approved surfaces owner.
        surfaceOwners -= "relay"
        router.purgeOwnersWithoutAccess({ it in surfaceOwners }, "ownerUnavailable")

        assertEquals(listOf("relay"), sink.remote.map { NoticeSurfaceContract.parsePurge(it.payload) })
        assertEquals(setOf("assistant"), router.historyOwnerIds())
    }

    @Test
    fun `the changed owner is purged even when this hub never saw it notify`() {
        router.purgeOwnersWithoutAccess({ false }, "authorizationChanged", "media")

        assertEquals(BusPaths.CORE_NOTICE_PURGE, sink.remote.single().path)
        assertEquals("media", NoticeSurfaceContract.parsePurge(sink.remote.single().payload))
    }

    /** A ledger that outlives the router, as the hub's preferences outlive its process. */
    private class MemoryLedger : PhoneNoticeOwnerLedger {
        var saved = PhoneNoticeOwners()

        override fun load() = saved

        override fun save(owners: PhoneNoticeOwners) {
            saved = owners
        }
    }

    /** A new phone hub process: fresh router state over the same ledger. */
    private fun restartedRouter(ledger: PhoneNoticeOwnerLedger, sink: RecordingPhoneHudRouteSink = this.sink) = PhoneNoticeRouter(
        state = PhoneNoticeState(nowMs = { nowMs }, initialSequence = 0L),
        sink = sink,
        launcherVersion = { launcherVersion },
    ).also { it.restore(ledger) }

    @Test
    fun `an owner uninstalled after a hub restart is still purged by the package change`() {
        val ledger = MemoryLedger()
        router.restore(ledger)
        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        sink.remote.clear()

        val restarted = restartedRouter(ledger)
        // reconcilePluginPackage names no changed owner: the restored inventory must.
        restarted.purgeOwnersWithoutAccess({ false }, "ownerUnavailable")

        assertEquals(listOf("relay"), sink.remote.map { NoticeSurfaceContract.parsePurge(it.payload) })
        assertTrue(ledger.saved.historyOwners.isEmpty())
        assertTrue(ledger.saved.pendingPurges.isEmpty())
    }

    @Test
    fun `a purge owed while disconnected survives a hub restart and goes at the next announce`() {
        val ledger = MemoryLedger()
        router.restore(ledger)
        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        sink.remote.clear()
        sink.linkUp = false
        router.purgeOwnersWithoutAccess({ false }, "authorizationChanged", "relay")
        assertTrue(sink.remote.isEmpty())
        assertEquals(setOf("relay"), ledger.saved.pendingPurges)

        val linked = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.NOTICE_SURFACE, linkUp = true)
        restartedRouter(ledger, linked).onGlassesAnnounced()

        assertEquals(listOf(BusPaths.CORE_NOTICE_PURGE), linked.remote.map { it.path })
        assertEquals("relay", NoticeSurfaceContract.parsePurge(linked.remote.single().payload))
        assertTrue(ledger.saved.pendingPurges.isEmpty())
    }

    @Test
    fun `the startup reconcile purges an owner that lost access while the hub was not running`() {
        val ledger = MemoryLedger()
        router.restore(ledger)
        router.handleLocal(conversationShow("relay"), hudSender("relay"))
        router.handleLocal(conversationShow("assistant"), hudSender("assistant"))
        sink.remote.clear()

        // The new process has not heard the glasses yet: the purge waits, durably.
        launcherVersion = null
        val restarted = restartedRouter(ledger)
        restarted.purgeOwnersWithoutAccess({ it == "assistant" }, "startup")
        assertTrue(sink.remote.isEmpty())
        assertEquals(setOf("relay"), ledger.saved.pendingPurges)
        assertEquals(setOf("assistant"), ledger.saved.historyOwners)

        launcherVersion = 1
        restarted.onGlassesAnnounced()
        assertEquals(listOf("relay"), sink.remote.map { NoticeSurfaceContract.parsePurge(it.payload) })
    }

    @Test
    fun `the ledger keeps owner ids and nothing a notice said`() {
        val ledger = MemoryLedger()
        router.restore(ledger)
        router.handleLocal(conversationShow("relay"), hudSender("relay"))

        assertEquals(PhoneNoticeOwners(historyOwners = setOf("relay")), ledger.saved)
        assertFalse(ledger.saved.toString().contains("Marie"))
        assertFalse(ledger.saved.toString().contains("On my way"))
        assertFalse(ledger.saved.toString().contains("9f2c"))
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
            .put("destination", JSONObject().put("pageId", "$pluginId:thread:9f2c"))
            .put("eventKey", "9f2c")
            .put("conversation", true),
    )

    /** A withdraw as the hub stamps it for [pluginId]; the router itself trusts only the sender. */
    private fun forget(pluginId: String) = BusEnvelope(
        path = BusPaths.NOTICE_FORGET,
        payload = PluginRoutePolicy.injectSurfaceOwner(pluginId, NoticeSurfaceContract.forgetPayload())!!,
    )

    private fun withdraw(pluginId: String, eventKey: String) = BusEnvelope(
        path = BusPaths.NOTICE_WITHDRAW,
        payload = PluginRoutePolicy.injectSurfaceOwner(pluginId, NoticeSurfaceContract.withdrawPayload(eventKey))!!,
    )
}
