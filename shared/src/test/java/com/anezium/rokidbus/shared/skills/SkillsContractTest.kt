package com.anezium.rokidbus.shared.skills

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsContractTest {
    private val invocationId = "inv_0123456789"

    @Test
    fun `invoke requests round-trip and reject malformed identity`() {
        val request = SkillInvokeRequest("session-01", "turn-1:call_1", "sk_transit__search_stops", JSONObject().put("query", "x"))
        assertEquals(request.toString(), SkillsContract.parseInvokeRequest(SkillsContract.invokeRequest(request)).toString())

        assertNull(SkillsContract.parseInvokeRequest(SkillsContract.invokeRequest(request.copy(session = "short"))))
        assertNull(SkillsContract.parseInvokeRequest(SkillsContract.invokeRequest(request.copy(alias = "Bad Alias"))))
        assertNull(SkillsContract.parseInvokeRequest(SkillsContract.invokeRequest(request).put("version", 2)))
    }

    @Test
    fun `provider results must match their status`() {
        fun parse(result: SkillProviderResult) = SkillsContract.parseProviderResult(SkillsContract.providerResult(result))

        assertNotNull(parse(SkillProviderResult(invocationId, SkillStatus.COMPLETED, data = JSONObject())))
        assertNull(parse(SkillProviderResult(invocationId, SkillStatus.COMPLETED)))
        assertNull(
            parse(
                SkillProviderResult(
                    invocationId,
                    SkillStatus.COMPLETED,
                    data = JSONObject(),
                    error = SkillError("busy", SkillDispatch.NONE),
                ),
            ),
        )
        assertNotNull(parse(SkillProviderResult(invocationId, SkillStatus.FAILED, error = SkillError("no_route", SkillDispatch.NONE))))
        assertNull(parse(SkillProviderResult(invocationId, SkillStatus.FAILED)))
        assertNull(parse(SkillProviderResult(invocationId, SkillStatus.FAILED, error = SkillError("Bad Code", SkillDispatch.NONE))))
        assertNull(
            parse(
                SkillProviderResult(invocationId, SkillStatus.UNKNOWN, error = SkillError("lost", SkillDispatch.NONE)),
            ),
        )
        assertNotNull(parse(SkillProviderResult(invocationId, SkillStatus.UNKNOWN)))
    }

    @Test
    fun `needs input carries bounded choices with provider references`() {
        val input = SkillProviderInput(
            reason = "ambiguous_stop",
            prompt = "Which stop?",
            choices = listOf(SkillProviderChoice("Central", "Paris", SkillProviderReference("stop", "id-1"))),
        )
        val parsed = SkillsContract.parseProviderResult(
            SkillsContract.providerResult(SkillProviderResult(invocationId, SkillStatus.NEEDS_INPUT, input = input)),
        )
        assertEquals(input, parsed?.input)

        val tooMany = input.copy(
            choices = (0..SkillLimits.MAX_CHOICES).map { SkillProviderChoice("c$it", null, SkillProviderReference("stop", "$it")) },
        )
        assertNull(
            SkillsContract.parseProviderResult(
                SkillsContract.providerResult(SkillProviderResult(invocationId, SkillStatus.NEEDS_INPUT, input = tooMany)),
            ),
        )
    }

    @Test
    fun `results to the caller carry only hub handles as choice references`() {
        val envelope = SkillResultEnvelope(
            session = "session-01",
            requestKey = "k1",
            invocationId = invocationId,
            providerId = "transit",
            operationId = "search_stops",
            alias = "sk_transit__search_stops",
            contractVersion = 1,
            status = SkillStatus.NEEDS_INPUT,
            input = SkillInputRequest("ambiguous_stop", null, listOf(SkillChoice("A", null, "r_" + "a".repeat(22)))),
            observedAtMs = 5L,
        )
        assertEquals(envelope, SkillsContract.parseResult(SkillsContract.resultPayload("assistant", envelope)))

        val forged = SkillsContract.resultPayload("assistant", envelope)
        forged.getJSONObject("input").getJSONArray("choices").getJSONObject(0).put("ref", "stop-id-1")
        assertNull(SkillsContract.parseResult(forged))
    }

    @Test
    fun `aliases are generated from identities and never collide`() {
        assertEquals("sk_transit__search_stops", SkillAliases.generate("transit", "search_stops", emptySet()))
        val dotted = SkillAliases.generate("my.transit", "search_stops", emptySet())
        val underscored = SkillAliases.generate("my_transit", "search_stops", setOf(dotted))
        assertTrue(dotted != underscored)
        assertTrue(underscored.startsWith(SkillsContract.ALIAS_PREFIX))
        val long = SkillAliases.generate("a".repeat(60), "b".repeat(40), emptySet())
        assertTrue(long.length <= 64 && SkillsContract.ALIAS.matches(long))
    }

    @Test
    fun `skills routes are capability gated and deliveries are hub-only direct replies`() {
        assertEquals(PluginCapability.SKILLS_CLIENT, PathRules.requiredCapability(BusPaths.SKILLS_INVOKE))
        assertEquals(PluginCapability.SKILLS_CLIENT, PathRules.requiredCapability(BusPaths.SKILLS_CATALOG_REQUEST))
        assertEquals(PluginCapability.SKILLS_PROVIDER, PathRules.requiredCapability(BusPaths.SKILLS_PROVIDER_RESULT))
        listOf(
            BusPaths.SKILLS_RESULT,
            BusPaths.SKILLS_CATALOG_REPLY,
            BusPaths.SKILLS_PROVIDER_INVOKE,
            BusPaths.SKILLS_PROVIDER_CANCEL,
        ).forEach { path ->
            assertTrue(path, PathRules.isHubOnly(path))
            assertTrue(path, PathRules.isDirectReply(path))
            assertTrue(path, PathRules.isOwnerScoped(path))
        }
        assertTrue(PathRules.isReserved("/skills/anything"))
        assertFalse(PathRules.isAllowedReceivePrefix("/skills", "transit", setOf(PluginCapability.SKILLS_PROVIDER)))
    }
}
