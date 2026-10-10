package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import com.anezium.rokidbus.shared.skills.SkillCatalog
import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import com.anezium.rokidbus.shared.skills.SkillLimits
import com.anezium.rokidbus.shared.skills.SkillProviderChoice
import com.anezium.rokidbus.shared.skills.SkillProviderInput
import com.anezium.rokidbus.shared.skills.SkillProviderReference
import com.anezium.rokidbus.shared.skills.SkillProviderResult
import com.anezium.rokidbus.shared.skills.SkillResultEnvelope
import com.anezium.rokidbus.shared.skills.SkillStatus
import com.anezium.rokidbus.shared.skills.SkillsContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for real ComponentName equality inside principals.
@RunWith(RobolectricTestRunner::class)
class SkillsCoordinatorTest {
    private val catalogJson = """
        {"version":1,"operations":[
          {"id":"search_stops","version":1,"label":"Search stops","description":"Find stops.",
           "effect":"read","cancellable":true,"deduplicates":true,"data":["place_names"],
           "input":{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":120}},"required":["query"]},
           "output":{"type":"object","properties":{"stops":{"type":"array","maxItems":8,"items":{"type":"object",
             "properties":{"stop":{"type":"string","nexusRef":"stop"},"name":{"type":"string","maxLength":80}}}}}}},
          {"id":"get_departures","version":1,"label":"Departures","description":"Departures at a stop.",
           "effect":"read","cancellable":true,"deduplicates":true,"data":["schedules"],
           "input":{"type":"object","properties":{"stop":{"type":"string","nexusRef":"stop"}},"required":["stop"]},
           "output":{"type":"object","properties":{"count":{"type":"integer","minimum":0,"maximum":12}}}},
          {"id":"start_journey","version":1,"label":"Start journey","description":"Guide a journey.",
           "effect":"action","cancellable":false,"deduplicates":true,"requires":["surfaces"],"data":["itinerary"],
           "input":{"type":"object","properties":{}},
           "output":{"type":"object","properties":{"journey":{"type":"string","nexusRef":"journey"}}}}
        ]}
    """.trimIndent()

    private val catalog: SkillCatalog =
        (SkillCatalogParser.parse(catalogJson) as SkillCatalogParseResult.Valid).catalog

    private fun principal(
        id: String,
        capabilities: Set<PluginCapability>,
        catalog: SkillCatalog? = null,
        revision: Long = 1L,
    ) = PhonePluginPrincipal(
        packageName = "dev.$id",
        serviceComponent = ComponentName("dev.$id", "dev.$id.Service"),
        uid = 10_000 + id.hashCode().and(0xfff),
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id = id,
            displayName = id.replaceFirstChar(Char::uppercase),
            apiVersion = 3,
            requestedCapabilities = capabilities,
            receivePrefixes = listOf("/plugin/$id"),
            settingsActivity = null,
            launchable = true,
        ),
        skills = catalog?.let(PluginSkillsState::Valid) ?: PluginSkillsState.Absent,
        packageRevision = revision,
    )

    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private data class Delivery(val pluginId: String, val path: String, val payload: JSONObject)

    private inner class FakeHost : SkillsHost {
        var principals = mutableListOf<PhonePluginPrincipal>()
        val granted = mutableMapOf<PluginGrantKey, Set<PluginCapability>>()
        val registered = mutableSetOf<PluginGrantKey>()
        val displaySessions = mutableSetOf<PluginGrantKey>()
        val deliveries = mutableListOf<Delivery>()
        val errors = mutableListOf<String>()
        val binds = mutableListOf<String>()
        val unbinds = mutableListOf<String>()
        val journal = mutableListOf<SkillJournalEntry>()
        var bindSucceeds = true
        var now = 0L
        private var token = 0
        private val timers = linkedMapOf<String, Pair<Long, () -> Unit>>()

        override fun installedPrincipals() = principals.toList()
        override fun grantedCapabilities(principal: PhonePluginPrincipal) = granted[principal.grantKey()]
        override fun isRegistered(principal: PhonePluginPrincipal) = principal.grantKey() in registered
        override fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean {
            if (principal.grantKey() !in registered) return false
            deliveries += Delivery(principal.descriptor.id, path, JSONObject(payload.toString()))
            return true
        }
        override fun deliverError(principal: PhonePluginPrincipal, forId: String, code: String) {
            errors += code
        }
        override fun bindLease(principal: PhonePluginPrincipal): Boolean {
            binds += principal.descriptor.id
            return bindSucceeds
        }
        override fun unbindLease(principal: PhonePluginPrincipal) {
            unbinds += principal.descriptor.id
        }
        override fun holdsDisplaySession(principal: PhonePluginPrincipal) = principal.grantKey() in displaySessions
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) {
            timers[key] = (now + delayMs) to action
        }
        override fun cancel(key: String) {
            timers.remove(key)
        }
        override fun elapsedMs() = now
        override fun wallMs() = 1_790_000_000_000L + now
        override fun randomToken(length: Int): String = (token++).toString().padStart(length, 'a')
        override fun record(entry: SkillJournalEntry) {
            journal += entry
        }

        fun advance(ms: Long) {
            now += ms
            timers.entries.filter { it.value.first <= now }.map { it.key to it.value.second }.forEach { (key, action) ->
                timers.remove(key)
                action()
            }
        }

        fun resultsFor(pluginId: String) = deliveries
            .filter { it.pluginId == pluginId && it.path == BusPaths.SKILLS_RESULT }
            .mapNotNull { SkillsContract.parseResult(it.payload) }

        fun providerInvokes(pluginId: String) = deliveries
            .filter { it.pluginId == pluginId && it.path == BusPaths.SKILLS_PROVIDER_INVOKE }
            .map { it.payload }
    }

    private val host = FakeHost()
    private val grantStore = SkillGrantStore(MemoryStorage())
    private val coordinator = SkillsCoordinator(host, grantStore)
    private val assistant = principal("assistant", setOf(PluginCapability.SKILLS_CLIENT))
    private val transit = principal("transit", setOf(PluginCapability.SURFACES, PluginCapability.SKILLS_PROVIDER), catalog)

    private fun setUp(
        providerGrants: Set<PluginCapability> = setOf(PluginCapability.SURFACES, PluginCapability.SKILLS_PROVIDER),
        approve: List<String> = listOf("search_stops", "get_departures", "start_journey"),
        providerRegistered: Boolean = true,
    ) {
        host.principals += listOf(assistant, transit)
        host.granted[assistant.grantKey()] = setOf(PluginCapability.SKILLS_CLIENT)
        host.granted[transit.grantKey()] = providerGrants
        host.registered += assistant.grantKey()
        if (providerRegistered) host.registered += transit.grantKey()
        approve.forEach { id -> grantStore.setApproved(assistant, transit, catalog.operation(id)!!, true) }
    }

    private fun invoke(
        alias: String,
        arguments: JSONObject,
        requestKey: String = "turn-1:call_1",
        session: String = "session-01",
    ) = coordinator.invoke(
        assistant,
        "env-$requestKey",
        SkillsContract.invokeRequest(SkillInvokeRequest(session, requestKey, alias, arguments)),
    )

    private fun answer(result: SkillProviderResult, from: PhonePluginPrincipal = transit) =
        coordinator.providerResult(from, SkillsContract.providerResult(result))

    private fun lastInvocationId(): String = host.providerInvokes("transit").last().getString("invocationId")

    private fun searchAndGetHandle(requestKey: String = "turn-1:call_1"): String {
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"), requestKey = requestKey)
        answer(
            SkillProviderResult(
                lastInvocationId(),
                SkillStatus.COMPLETED,
                data = JSONObject().put(
                    "stops",
                    JSONArray().put(JSONObject().put("stop", "stop-id-42").put("name", "Central")),
                ),
            ),
        )
        return host.resultsFor("assistant").last().data!!
            .getJSONArray("stops").getJSONObject(0).getString("stop")
    }

    @Test
    fun `the catalog lists only operations approved for the caller`() {
        setUp(approve = listOf("search_stops"), providerGrants = setOf(PluginCapability.SKILLS_PROVIDER))
        grantStore.setApproved(assistant, transit, catalog.operation("start_journey")!!, true)

        coordinator.catalogRequest(assistant, "req-1")

        val reply = host.deliveries.single { it.path == BusPaths.SKILLS_CATALOG_REPLY }
        val entries = SkillsContract.parseCatalogReply(reply.payload)!!
        assertEquals(listOf("search_stops", "start_journey"), entries.map { it.operationId })
        assertEquals("sk_transit__search_stops", entries[0].alias)
        assertEquals("setup_required", entries[1].availability.wireValue)
    }

    @Test
    fun `a provider without the provider grant exposes nothing`() {
        setUp(providerGrants = setOf(PluginCapability.SURFACES))

        coordinator.catalogRequest(assistant, "req-1")

        val reply = host.deliveries.single { it.path == BusPaths.SKILLS_CATALOG_REPLY }
        assertTrue(SkillsContract.parseCatalogReply(reply.payload)!!.isEmpty())
    }

    @Test
    fun `a cold provider is bound by the lease, never opened, and released after answering`() {
        setUp(providerRegistered = false)

        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))

        assertEquals(listOf("transit"), host.binds)
        assertTrue(host.deliveries.none { it.pluginId == "transit" })

        host.registered += transit.grantKey()
        coordinator.onRegistered(transit)

        val delivered = host.providerInvokes("transit").single()
        assertEquals("search_stops", delivered.getString("operation"))
        assertTrue(delivered.getLong("deadlineMs") <= SkillLimits.INVOCATION_DEADLINE_MS)
        assertTrue(host.deliveries.none { it.path == BusPaths.PLUGIN_OPEN })

        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject()))

        assertEquals(SkillStatus.COMPLETED, host.resultsFor("assistant").single().status)
        assertEquals(listOf("transit"), host.unbinds)
        assertEquals(0, coordinator.liveInvocationCount())
    }

    @Test
    fun `provider identifiers become opaque handles and resolve back only for the same session`() {
        setUp()
        val handle = searchAndGetHandle()
        assertTrue(SkillsContract.HANDLE.matches(handle))
        assertNotEquals("stop-id-42", handle)

        invoke("sk_transit__get_departures", JSONObject().put("stop", handle), requestKey = "turn-2:call_1")
        assertEquals("stop-id-42", host.providerInvokes("transit").last().getJSONObject("arguments").getString("stop"))
        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject().put("count", 3)))

        val invokesBefore = host.providerInvokes("transit").size
        invoke("sk_transit__get_departures", JSONObject().put("stop", handle), requestKey = "k3", session = "session-02")
        invoke("sk_transit__get_departures", JSONObject().put("stop", "stop-id-42"), requestKey = "k4")
        invoke("sk_transit__get_departures", JSONObject().put("stop", "r_" + "z".repeat(22)), requestKey = "k5")

        assertEquals(invokesBefore, host.providerInvokes("transit").size)
        val stale = host.resultsFor("assistant").takeLast(3)
        stale.forEach { result ->
            assertEquals(SkillErrorCodes.STALE_REFERENCE, result.error?.code)
            assertEquals(SkillDispatch.NONE, result.error?.dispatch)
        }
    }

    @Test
    fun `a handle of one entity type cannot stand in for another`() {
        setUp()
        invoke("sk_transit__start_journey", JSONObject())
        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject().put("journey", "j-1")))
        val journey = host.resultsFor("assistant").last().data!!.getString("journey")

        invoke("sk_transit__get_departures", JSONObject().put("stop", journey), requestKey = "k2")

        assertEquals(SkillErrorCodes.STALE_REFERENCE, host.resultsFor("assistant").last().error?.code)
    }

    @Test
    fun `references expire after their idle lifetime and on package change`() {
        setUp()
        val handle = searchAndGetHandle()

        host.advance(SkillLimits.REFERENCE_IDLE_MS + 1)
        invoke("sk_transit__get_departures", JSONObject().put("stop", handle), requestKey = "k2")
        assertEquals(SkillErrorCodes.STALE_REFERENCE, host.resultsFor("assistant").last().error?.code)

        val fresh = searchAndGetHandle(requestKey = "k3").also { assertNotEquals(handle, it) }
        coordinator.onPackageChanged(transit.packageName)
        invoke("sk_transit__get_departures", JSONObject().put("stop", fresh), requestKey = "k4")
        assertEquals(SkillErrorCodes.STALE_REFERENCE, host.resultsFor("assistant").last().error?.code)
    }

    @Test
    fun `unknown arguments, unapproved aliases, and malformed requests fail closed`() {
        setUp(approve = listOf("search_stops"))

        invoke("sk_transit__search_stops", JSONObject().put("query", "x").put("route", "/core/x"))
        assertEquals(SkillErrorCodes.INVALID_ARGUMENTS, host.resultsFor("assistant").last().error?.code)

        invoke("sk_transit__get_departures", JSONObject(), requestKey = "k2")
        assertEquals(SkillErrorCodes.UNSUPPORTED_OPERATION, host.resultsFor("assistant").last().error?.code)

        coordinator.invoke(assistant, "env-bad", JSONObject().put("version", 1).put("alias", "x"))
        assertEquals(listOf(SkillsCoordinator.ERROR_INVALID_REQUEST), host.errors)
        assertTrue(host.providerInvokes("transit").isEmpty())
    }

    @Test
    fun `answers from another plugin or after the call ended change nothing`() {
        setUp()
        val intruder = principal("intruder", setOf(PluginCapability.SKILLS_PROVIDER))
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        val id = lastInvocationId()

        answer(SkillProviderResult(id, SkillStatus.COMPLETED, data = JSONObject()), from = intruder)
        assertTrue(host.resultsFor("assistant").isEmpty())

        host.advance(SkillLimits.INVOCATION_DEADLINE_MS)
        val expired = host.resultsFor("assistant").single()
        assertEquals(SkillErrorCodes.DEADLINE_EXCEEDED, expired.error?.code)
        assertEquals(SkillDispatch.UNKNOWN, expired.error?.dispatch)
        assertTrue(host.deliveries.any { it.path == BusPaths.SKILLS_PROVIDER_CANCEL })
        assertEquals(listOf("transit"), host.unbinds)

        answer(SkillProviderResult(id, SkillStatus.COMPLETED, data = JSONObject()))
        assertEquals(1, host.resultsFor("assistant").size)
    }

    @Test
    fun `a provider that never registers fails as unavailable without dispatch`() {
        setUp(providerRegistered = false)

        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        host.advance(SkillLimits.REGISTRATION_TIMEOUT_MS)

        val result = host.resultsFor("assistant").single()
        assertEquals(SkillErrorCodes.UNAVAILABLE, result.error?.code)
        assertEquals(SkillDispatch.NONE, result.error?.dispatch)
        assertEquals(listOf("transit"), host.unbinds)
    }

    @Test
    fun `a refused bind is unavailable`() {
        setUp(providerRegistered = false)
        host.bindSucceeds = false

        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))

        assertEquals(SkillErrorCodes.UNAVAILABLE, host.resultsFor("assistant").single().error?.code)
        assertTrue(host.unbinds.isEmpty())
    }

    @Test
    fun `excess work is refused as busy rather than queued`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "a"), requestKey = "k1")
        invoke("sk_transit__search_stops", JSONObject().put("query", "b"), requestKey = "k2")
        assertEquals(SkillErrorCodes.BUSY, host.resultsFor("assistant").single().error?.code)

        (2..SkillLimits.MAX_LIVE_INVOCATIONS).forEach { index ->
            invoke("sk_transit__search_stops", JSONObject().put("query", "q"), requestKey = "k", session = "session-0$index")
        }
        val helper = principal("helper", setOf(PluginCapability.SKILLS_CLIENT))
        host.principals += helper
        host.granted[helper.grantKey()] = setOf(PluginCapability.SKILLS_CLIENT)
        host.registered += helper.grantKey()
        grantStore.setApproved(helper, transit, catalog.operation("search_stops")!!, true)
        coordinator.invoke(
            helper,
            "env-helper",
            SkillsContract.invokeRequest(
                SkillInvokeRequest("session-09", "k", "sk_transit__search_stops", JSONObject().put("query", "q")),
            ),
        )
        assertEquals(SkillErrorCodes.BUSY, host.resultsFor("helper").single().error?.code)
        assertEquals(SkillLimits.MAX_LIVE_INVOCATIONS, coordinator.liveInvocationCount())
    }

    @Test
    fun `an exact duplicate reuses the outcome and a changed one is a conflict`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject()))

        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        invoke("sk_transit__search_stops", JSONObject().put("query", "Other"))

        assertEquals(1, host.providerInvokes("transit").size)
        val results = host.resultsFor("assistant")
        assertEquals(results[0].toString(), results[1].toString())
        assertEquals(SkillErrorCodes.OPERATION_CONFLICT, results[2].error?.code)
    }

    @Test
    fun `a duplicate of a running call hears the same single outcome`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        assertTrue(host.resultsFor("assistant").isEmpty())

        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject()))

        assertEquals(1, host.providerInvokes("transit").size)
        assertEquals(2, host.resultsFor("assistant").size)
    }

    @Test
    fun `results outside the declared output are invalid, and an action becomes unknown`() {
        setUp()
        invoke("sk_transit__get_departures", JSONObject().put("stop", searchAndGetHandle()), requestKey = "k2")
        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject().put("count", 99)))
        val read = host.resultsFor("assistant").last()
        assertEquals(SkillStatus.FAILED, read.status)
        assertEquals(SkillErrorCodes.INVALID_RESULT, read.error?.code)

        invoke("sk_transit__start_journey", JSONObject(), requestKey = "k3")
        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject().put("extra", 1)))
        assertEquals(SkillStatus.UNKNOWN, host.resultsFor("assistant").last().status)
    }

    @Test
    fun `choices carry handles and never the provider's identifiers`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        answer(
            SkillProviderResult(
                lastInvocationId(),
                SkillStatus.NEEDS_INPUT,
                input = SkillProviderInput(
                    "ambiguous_stop",
                    choices = listOf(
                        SkillProviderChoice("Central North", null, SkillProviderReference("stop", "id-n")),
                        SkillProviderChoice("Central South", "Bus", SkillProviderReference("stop", "id-s")),
                    ),
                ),
            ),
        )
        val choices = host.resultsFor("assistant").last().input!!.choices
        assertEquals(listOf("Central North", "Central South"), choices.map { it.label })
        assertTrue(choices.all { SkillsContract.HANDLE.matches(it.reference) })

        invoke("sk_transit__get_departures", JSONObject().put("stop", choices[1].reference), requestKey = "k2")
        assertEquals("id-s", host.providerInvokes("transit").last().getJSONObject("arguments").getString("stop"))
    }

    @Test
    fun `a choice of an entity type the provider never declared is refused`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        answer(
            SkillProviderResult(
                lastInvocationId(),
                SkillStatus.NEEDS_INPUT,
                input = SkillProviderInput(
                    "pick",
                    choices = listOf(SkillProviderChoice("Mail", null, SkillProviderReference("email", "x"))),
                ),
            ),
        )
        assertEquals(SkillErrorCodes.INVALID_RESULT, host.resultsFor("assistant").last().error?.code)
    }

    @Test
    fun `revoking an operation cancels its live call and blocks the next`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        grantStore.setApproved(assistant, transit, catalog.operation("search_stops")!!, false)
        coordinator.onSkillGrantsChanged()

        val revoked = host.resultsFor("assistant").single()
        assertEquals(SkillErrorCodes.PERMISSION_REQUIRED, revoked.error?.code)
        assertTrue(host.deliveries.any { it.path == BusPaths.SKILLS_PROVIDER_CANCEL })

        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"), requestKey = "k2")
        assertEquals(SkillErrorCodes.UNSUPPORTED_OPERATION, host.resultsFor("assistant").last().error?.code)
    }

    @Test
    fun `a revoked caller gets no result and the provider is cancelled`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        host.granted.remove(assistant.grantKey())

        coordinator.onAuthorizationChanged(assistant.grantKey())

        assertTrue(host.resultsFor("assistant").isEmpty())
        assertTrue(host.deliveries.any { it.path == BusPaths.SKILLS_PROVIDER_CANCEL })
        assertEquals(0, coordinator.liveInvocationCount())
    }

    @Test
    fun `a changed operation contract needs renewed approval`() {
        setUp()
        val changed = (
            SkillCatalogParser.parse(catalogJson.replace("Find stops.", "Find stops by name.")) as SkillCatalogParseResult.Valid
            ).catalog
        host.principals.removeAll { it.descriptor.id == "transit" }
        host.principals += transit.copy(skills = PluginSkillsState.Valid(changed), packageRevision = 2L)

        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))

        assertEquals(SkillErrorCodes.UNSUPPORTED_OPERATION, host.resultsFor("assistant").single().error?.code)
    }

    @Test
    fun `the caller closing cancels its work without a late delivery`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        val id = lastInvocationId()
        host.registered -= assistant.grantKey()

        coordinator.onRegistrationRemoved(assistant)
        host.registered += assistant.grantKey()
        answer(SkillProviderResult(id, SkillStatus.COMPLETED, data = JSONObject()))

        assertTrue(host.resultsFor("assistant").isEmpty())
        assertTrue(host.deliveries.any { it.path == BusPaths.SKILLS_PROVIDER_CANCEL })
    }

    @Test
    fun `a provider dying mid-call reports unavailable with unknown dispatch`() {
        setUp()
        invoke("sk_transit__start_journey", JSONObject())

        coordinator.onRegistrationRemoved(transit)

        val result = host.resultsFor("assistant").single()
        assertEquals(SkillErrorCodes.UNAVAILABLE, result.error?.code)
        assertEquals(SkillDispatch.UNKNOWN, result.error?.dispatch)
    }

    @Test
    fun `cancel answers with the known dispatch state`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))

        coordinator.cancel(assistant, SkillsContract.cancelRequest("session-01", "turn-1:call_1"))

        val result = host.resultsFor("assistant").single()
        assertEquals(SkillErrorCodes.CANCELLED, result.error?.code)
        assertEquals(SkillDispatch.UNKNOWN, result.error?.dispatch)
    }

    @Test
    fun `a lease-started provider cannot draw, and may drive an activity only for an operation that needs it`() {
        setUp(providerRegistered = false)
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        host.registered += transit.grantKey()
        coordinator.onRegistered(transit)

        assertEquals(SkillsCoordinator.ERROR_LEASE_DENIED, coordinator.leaseRestriction(transit, BusPaths.SURFACE_SHOW))
        assertEquals(SkillsCoordinator.ERROR_LEASE_DENIED, coordinator.leaseRestriction(transit, BusPaths.NOTICE_SHOW))
        assertEquals(SkillsCoordinator.ERROR_LEASE_DENIED, coordinator.leaseRestriction(transit, "/audio/lease/acquire"))
        assertEquals(SkillsCoordinator.ERROR_LEASE_DENIED, coordinator.leaseRestriction(transit, BusPaths.ACTIVITY_START))
        assertNull(coordinator.leaseRestriction(transit, "/plugin/transit/x"))

        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject()))
        assertNull(coordinator.leaseRestriction(transit, BusPaths.SURFACE_SHOW))
    }

    @Test
    fun `a journey operation may start its activity during a lease`() {
        setUp(providerRegistered = false)
        invoke("sk_transit__start_journey", JSONObject())
        host.registered += transit.grantKey()
        coordinator.onRegistered(transit)

        assertNull(coordinator.leaseRestriction(transit, BusPaths.ACTIVITY_START))
        assertEquals(SkillsCoordinator.ERROR_LEASE_DENIED, coordinator.leaseRestriction(transit, BusPaths.PIN_SHOW))
    }

    @Test
    fun `a provider already running or open keeps its ordinary rights`() {
        setUp(providerRegistered = true)
        invoke("sk_transit__search_stops", JSONObject().put("query", "Central"))
        assertNull(coordinator.leaseRestriction(transit, BusPaths.SURFACE_SHOW))

        val coldHost = FakeHost()
        val cold = SkillsCoordinator(coldHost, grantStore)
        coldHost.principals += listOf(assistant, transit)
        coldHost.granted.putAll(host.granted)
        coldHost.registered += assistant.grantKey()
        coldHost.displaySessions += transit.grantKey()
        cold.invoke(
            assistant,
            "env",
            SkillsContract.invokeRequest(
                SkillInvokeRequest("session-01", "k", "sk_transit__search_stops", JSONObject().put("query", "x")),
            ),
        )
        assertNull(cold.leaseRestriction(transit, BusPaths.SURFACE_SHOW))
    }

    @Test
    fun `the journal records identity, status, and dispatch only`() {
        setUp()
        invoke("sk_transit__search_stops", JSONObject().put("query", "Secret Street 12"))
        answer(SkillProviderResult(lastInvocationId(), SkillStatus.COMPLETED, data = JSONObject()))

        val entry = host.journal.last()
        assertEquals("transit", entry.providerId)
        assertEquals("search_stops", entry.operationId)
        assertEquals(SkillStatus.COMPLETED, entry.status)
        assertFalse(entry.toString().contains("Secret"))
    }
}
