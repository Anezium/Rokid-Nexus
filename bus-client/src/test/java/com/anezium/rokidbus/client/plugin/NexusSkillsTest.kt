package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.skills.SkillCatalogEntry
import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import com.anezium.rokidbus.shared.skills.SkillLimits
import com.anezium.rokidbus.shared.skills.SkillProviderInvocation
import com.anezium.rokidbus.shared.skills.SkillResultEnvelope
import com.anezium.rokidbus.shared.skills.SkillStatus
import com.anezium.rokidbus.shared.skills.SkillsContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusSkillsTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Pair<String, JSONObject>>()
        val sendIds = mutableListOf<String>()
        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            sendIds += id
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun capabilities(): Int = 0
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private class Callbacks : NexusPluginCallbacks {
        val invocations = mutableListOf<NexusSkillInvocation>()
        val cancelled = mutableListOf<String>()
        val results = mutableListOf<SkillResultEnvelope>()
        val catalogs = mutableListOf<Pair<List<SkillCatalogEntry>, String?>>()
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onSkillInvoked(invocation: NexusSkillInvocation) {
            invocations += invocation
        }
        override fun onSkillCancelled(invocation: NexusSkillInvocation) {
            cancelled += invocation.invocationId
        }
        override fun onSkillResult(result: SkillResultEnvelope) {
            results += result
        }
        override fun onSkillCatalog(entries: List<SkillCatalogEntry>, errorCode: String?) {
            catalogs += entries to errorCode
        }
    }

    private var eventCounter = 0

    private fun fixture(
        capabilities: String,
        skillsVersion: Int? = SkillsContract.VERSION,
    ): Triple<NexusPluginClient, FakeTransport, Callbacks> {
        val transport = FakeTransport()
        val callbacks = Callbacks()
        val client = NexusPluginClient("transit", callbacks, transport) { 1_000L }
        client.connect()
        val registration = JSONObject()
            .put("pluginId", "transit")
            .put("result", PluginRegistrationResult.APPROVED)
            .put("capabilities", capabilities)
        skillsVersion?.let { registration.put(SkillsContract.REGISTRATION_FIELD, it) }
        deliver(transport, BusPaths.PLUGIN_REGISTRATION, registration)
        return Triple(client, transport, callbacks)
    }

    private fun deliver(transport: FakeTransport, path: String, payload: JSONObject) {
        transport.listener.onMessage(path, "event-${eventCounter++}", payload)
    }

    private fun invoke(transport: FakeTransport, invocationId: String = "inv_00000001", pluginId: String = "transit") {
        deliver(
            transport,
            BusPaths.SKILLS_PROVIDER_INVOKE,
            SkillsContract.providerInvoke(
                pluginId,
                SkillProviderInvocation(invocationId, "search_stops", 1, JSONObject().put("query", "x"), 9_000L),
            ),
        )
    }

    private val request = SkillInvokeRequest("session-01", "turn-1:call_1", "sk_transit__search_stops", JSONObject())

    @Test
    fun `skills calls stay local on a hub that does not announce skills`() {
        val (client, transport, _) = fixture("surfaces,skills_client", skillsVersion = null)

        assertFalse(client.supportsSkills)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.invokeSkill(request))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.requestSkillCatalog())
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun `a caller without the grant is refused before sending`() {
        val (client, transport, _) = fixture("surfaces")

        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, client.invokeSkill(request))
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun `invoke sends the request and a route rejection becomes a failed result`() {
        val (client, transport, callbacks) = fixture("skills_client")

        assertEquals(NexusSdkResult.SENT, client.invokeSkill(request))
        assertEquals(BusPaths.SKILLS_INVOKE, transport.sends.single().first)
        assertEquals("turn-1:call_1", transport.sends.single().second.getString("requestKey"))

        transport.listener.onMessage(
            BusPaths.ERROR,
            "error-1",
            JSONObject().put("code", "CAPABILITY_REQUIRED_SKILLS_CLIENT").put("forId", transport.sendIds.single()),
        )

        val result = callbacks.results.single()
        assertEquals(SkillStatus.FAILED, result.status)
        assertEquals(SkillErrorCodes.PERMISSION_REQUIRED, result.error?.code)
        assertEquals(SkillDispatch.NONE, result.error?.dispatch)
        assertEquals("turn-1:call_1", result.requestKey)
    }

    @Test
    fun `oversized arguments are refused locally`() {
        val (client, transport, _) = fixture("skills_client")
        val big = JSONObject().put("text", "x".repeat(SkillLimits.MAX_ARGUMENTS_BYTES))

        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.invokeSkill(request.copy(arguments = big)))
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun `a provider answers each invocation exactly once`() {
        val (_, transport, callbacks) = fixture("skills_provider")
        invoke(transport)

        val invocation = callbacks.invocations.single()
        assertEquals(9_000L, invocation.remainingMs)
        assertEquals(NexusSdkResult.SENT, invocation.complete(JSONObject().put("count", 0)))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, invocation.fail("busy"))

        val (path, payload) = transport.sends.single()
        assertEquals(BusPaths.SKILLS_PROVIDER_RESULT, path)
        assertEquals("completed", payload.getString("status"))
        assertEquals("inv_00000001", payload.getString("invocationId"))
    }

    @Test
    fun `an answer that breaks the declared output is refused and can be corrected`() {
        val (client, transport, callbacks) = fixture("skills_provider")
        client.ownSkillCatalog = (
            SkillCatalogParser.parse(
                """{"version":1,"operations":[{"id":"search_stops","version":1,"label":"Search",
                   "description":"Find stops.","effect":"read","cancellable":true,"deduplicates":true,
                   "data":["place_names"],"input":{"type":"object"},
                   "output":{"type":"object","properties":{"count":{"type":"integer","minimum":0,"maximum":8}},
                   "required":["count"]}}]}""",
            ) as SkillCatalogParseResult.Valid
            ).catalog
        invoke(transport)
        val invocation = callbacks.invocations.single()

        assertEquals(NexusSdkResult.INVALID_PAYLOAD, invocation.complete(JSONObject().put("count", 99)))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, invocation.complete(JSONObject().put("stops", "x")))
        assertEquals(NexusSdkResult.SENT, invocation.complete(JSONObject().put("count", 3)))
        assertEquals(1, transport.sends.size)
    }

    @Test
    fun `cancellation reaches the live invocation and a plugin without the grant hears nothing`() {
        val (_, transport, callbacks) = fixture("skills_provider")
        invoke(transport)
        deliver(
            transport,
            BusPaths.SKILLS_PROVIDER_CANCEL,
            SkillsContract.providerCancel("transit", "inv_00000001", "caller_closed"),
        )
        assertTrue(callbacks.invocations.single().isCancelled)
        assertEquals(listOf("inv_00000001"), callbacks.cancelled)

        val (_, otherTransport, otherCallbacks) = fixture("surfaces")
        invoke(otherTransport)
        assertTrue(otherCallbacks.invocations.isEmpty())
    }

    @Test
    fun `deliveries stamped for another plugin are ignored`() {
        val (_, transport, callbacks) = fixture("skills_provider")

        invoke(transport, pluginId = "media")

        assertTrue(callbacks.invocations.isEmpty())
    }

    @Test
    fun `losing approval cancels live invocations`() {
        val (_, transport, callbacks) = fixture("skills_provider")
        invoke(transport)

        transport.listener.onRegistrationState(PluginRegistrationResult.DENIED)

        assertTrue(callbacks.invocations.single().isCancelled)
        assertEquals(NexusSdkResult.NOT_REGISTERED, callbacks.invocations.single().complete(JSONObject()))
    }

    @Test
    fun `results and catalogs reach the caller`() {
        val (client, transport, callbacks) = fixture("skills_client")
        assertEquals(NexusSdkResult.SENT, client.requestSkillCatalog())
        deliver(transport, BusPaths.SKILLS_CATALOG_REPLY, SkillsContract.catalogReply("transit", emptyList()))
        assertEquals(listOf(emptyList<SkillCatalogEntry>() to null), callbacks.catalogs)

        val envelope = SkillResultEnvelope(
            session = "session-01",
            requestKey = "k",
            invocationId = "inv_00000001",
            providerId = "transit",
            operationId = "search_stops",
            alias = "sk_transit__search_stops",
            contractVersion = 1,
            status = SkillStatus.COMPLETED,
            data = JSONObject(),
            observedAtMs = 1L,
        )
        deliver(transport, BusPaths.SKILLS_RESULT, SkillsContract.resultPayload("transit", envelope))
        assertEquals("inv_00000001", callbacks.results.single().invocationId)
    }
}
