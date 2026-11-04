package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.shared.skills.SkillAvailability
import com.anezium.rokidbus.shared.skills.SkillCatalogEntry
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillEffect
import com.anezium.rokidbus.shared.skills.SkillError
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import com.anezium.rokidbus.shared.skills.SkillResultEnvelope
import com.anezium.rokidbus.shared.skills.SkillStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantConfirmationTest {
    private val prepareInput =
        """{"type":"object","properties":{"thread":{"type":"string","nexusRef":"relay_thread"},"text":{"type":"string","maxLength":300}},"required":["thread","text"]}"""
    private val prepare = relayEntry("prepare_reply", prepareInput)
    private val commit = relayEntry("send_reply", effect = SkillEffect.ACTION)
    private val threads = relayEntry("list_reply_threads")

    private val transport = FakeSkillTransport(entries = listOf(threads, prepare, commit))
    private val gateway = AssistantSkillGateway(transport).also { transport.gateway = it }
    private val gate = AssistantConfirmationGate(tokens = { "tok${tokenCounter++}" })
    private var tokenCounter = 0
    private val ui = FakeConfirmationUi()
    private val spoken = mutableListOf<String>()
    private var speechStops = 0
    private var bandBusy = false
    private val recorded = mutableListOf<Pair<String, String>>()
    private val conversation = AssistantConversationContext("thread-0001", emptyList(), keepConversation = true)

    private fun relayEntry(
        operation: String,
        input: String = """{"type":"object"}""",
        effect: SkillEffect = SkillEffect.READ,
        availability: SkillAvailability = SkillAvailability.READY,
    ): SkillCatalogEntry = skillEntry(operation, input, effect, availability).copy(
        alias = "sk_relay__$operation",
        providerId = "relay",
        providerName = "Relay",
    )

    private fun turn(id: String = "turn-1", effectsAllowed: () -> Boolean = { true }) =
        ConfirmationTurn(id, "session-0001", conversation, effectsAllowed)

    private fun gatedCommit() = GatedCommit(GatedSkillOperations.prepareOf(prepare)!!, commit)

    private fun tool(staging: ConfirmationStaging? = ConfirmationStaging(gate, gatedCommit(), turn())) = PluginSkillTool(
        prepare,
        gateway,
        { "session-0001" },
        { "turn-1" },
        AssistantSkillMemory(),
        staging,
    )

    private fun draftData(
        text: String = "We meet at 3 pm",
        recipient: String = "Coralie",
    ): JSONObject = JSONObject()
        .put("draft", "r_DRAFTDRAFTDRAFTDRAFTDR")
        .put("recipient", recipient)
        .put("via", "WhatsApp")
        .put("text", text)

    private fun TestScope.flow(): AssistantConfirmationFlow = AssistantConfirmationFlow(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        gate = gate,
        gateway = gateway,
        ui = ui,
        speak = { spoken += it },
        stopSpeech = { speechStops += 1 },
        bandBusy = { bandBusy },
        record = { _, user, assistant -> recorded += user to assistant },
    )

    private suspend fun stage(data: JSONObject = draftData()): String {
        transport.handler = { skillResult(it, data = data) }
        return (
            tool().execute(
                AssistantToolCall("call_1", prepare.alias, "{}"),
                JSONObject(),
            ) as AssistantToolResult.Json
        ).text
    }

    private fun AssistantConfirmationFlow.tapSend(token: String = "tok0") =
        onAction("${AssistantConfirmationGate.SEND_PREFIX}$token")

    private fun AssistantConfirmationFlow.tapCancel(token: String = "tok0") =
        onAction("${AssistantConfirmationGate.CANCEL_PREFIX}$token")

    private fun commits(): List<SkillInvokeRequest> = transport.invocations.filter { it.alias == commit.alias }

    @Test
    fun `the model is never offered a commit and registry execution rejects its alias`() = runTest {
        val offer = GatedSkillOperations.modelOffer(listOf(threads, prepare, commit), confirmable = true)
        assertEquals(
            listOf("list_reply_threads", "prepare_reply"),
            offer.map { it.first.operationId },
        )

        val tools = offer.map { (entry, gated) ->
            val staging = gated?.let { ConfirmationStaging(gate, it, turn()) }
            PluginSkillTool(entry, gateway, { "session-0001" }, { "turn-1" }, AssistantSkillMemory(), staging)
        }
        val registry = AssistantToolRegistry(emptyList(), dynamicDefinitions = { tools })
        val features = AssistantProviderFeatures(supportsTools = true, supportsVision = false)
        assertFalse(registry.availableDefinitions(features).any { it.name == commit.alias })

        val result = registry.newExecutionPhase(features).execute(AssistantToolCall("call_1", commit.alias, """{"draft":"x"}"""))
        assertEquals(TOOL_ERROR_INVALID_CALL, (result as AssistantToolResult.Error).code)
        assertTrue(transport.invocations.isEmpty())
    }

    @Test
    fun `a prepare is offered only with its commit approved and ready and a band to ask on`() {
        val offered: (List<SkillCatalogEntry>, Boolean) -> List<String> = { entries, confirmable ->
            GatedSkillOperations.modelOffer(entries, confirmable).map { it.first.operationId }
        }
        assertEquals(listOf("list_reply_threads"), offered(listOf(threads, prepare), true))
        assertEquals(
            listOf("list_reply_threads"),
            offered(listOf(threads, prepare, commit.copy(availability = SkillAvailability.SETUP_REQUIRED)), true),
        )
        assertEquals(listOf("list_reply_threads"), offered(listOf(threads, prepare, commit), false))
        // A commit alone is still not the model's.
        assertTrue(offered(listOf(commit), true).isEmpty())
    }

    @Test
    fun `an operation another provider happens to name send_reply is not gated`() {
        val other = skillEntry("send_reply")
        val offer = GatedSkillOperations.modelOffer(listOf(other), confirmable = true)
        assertEquals(listOf(other to null), offer)
    }

    @Test
    fun `a completed prepare tells the model only that the wearer decides`() = runTest {
        val modelText = JSONObject(stage())

        assertEquals(ConfirmationStaging.AWAITING_WEARER, modelText.getString("status"))
        assertTrue(modelText.getString("note").startsWith("Nothing has been sent."))
        assertFalse(modelText.has("data"))
        assertFalse(modelText.toString().contains("r_DRAFT"))
        assertFalse(modelText.toString().contains("We meet at 3 pm"))
        assertNotNull(gate.stagedFor("turn-1"))
    }

    @Test
    fun `a turn stages one draft and a further prepare is refused without reaching the provider`() = runTest {
        stage()
        val calls = transport.invocations.size

        val second = tool().execute(AssistantToolCall("call_2", prepare.alias, "{}"), JSONObject())

        assertEquals(TOOL_ERROR_ALREADY_USED, (second as AssistantToolResult.Error).code)
        assertEquals(calls, transport.invocations.size)
    }

    @Test
    fun `a failed prepare stages nothing and the model reads the failure`() = runTest {
        transport.handler = {
            skillResult(it, SkillStatus.FAILED, error = SkillError(SkillErrorCodes.STALE_REFERENCE, SkillDispatch.NONE))
        }
        val result = tool().execute(AssistantToolCall("call_1", prepare.alias, "{}"), JSONObject())

        val json = JSONObject((result as AssistantToolResult.Json).text)
        assertEquals("failed", json.getString("status"))
        assertEquals(SkillErrorCodes.STALE_REFERENCE, json.getString("code"))
        assertNull(gate.stagedFor("turn-1"))
    }

    @Test
    fun `a prepare answered as accepted or unknown can never read as sent`() = runTest {
        for (status in listOf(SkillStatus.ACCEPTED, SkillStatus.UNKNOWN)) {
            transport.handler = { skillResult(it, status, data = null) }
            val result = tool().execute(AssistantToolCall("call_1", prepare.alias, "{}"), JSONObject())

            val json = JSONObject((result as AssistantToolResult.Json).text)
            assertEquals("failed", json.getString("status"))
            assertEquals("Nothing was sent.", json.getString("note"))
            assertNull(gate.stagedFor("turn-1"))
        }
    }

    @Test
    fun `a preview that is missing a field or too long for the band is refused not clipped`() = runTest {
        for (data in listOf(
            draftData().apply { remove("recipient") },
            draftData().apply { remove("draft") },
            draftData(text = "x".repeat(ConfirmationPreview.MAX_TEXT_CHARS + 1)),
        )) {
            val json = JSONObject(stage(data))
            assertEquals(ConfirmationStaging.PREVIEW_REFUSED, json.getString("code"))
            assertNull(gate.stagedFor("turn-1"))
        }
    }

    @Test
    fun `the band and the voice read the provider's preview and a send carries only the draft`() = runTest {
        stage(draftData(text = "The provider's words"))
        val flow = flow()

        assertTrue(flow.present(gate.stagedFor("turn-1")!!))

        assertEquals(listOf("To Coralie · WhatsApp", "The provider's words"), ui.confirmationLines)
        assertEquals(listOf("To Coralie, WhatsApp. The provider's words"), spoken)

        transport.handler = { skillResult(it, SkillStatus.ACCEPTED, data = null) }
        flow.tapSend()
        advanceUntilIdle()

        val send = commits().single()
        assertEquals("session-0001", send.session)
        assertEquals("confirm:tok0", send.requestKey)
        assertEquals("""{"draft":"r_DRAFTDRAFTDRAFTDRAFTDR"}""", send.arguments.toString())
    }

    @Test
    fun `a short draft gets Send and Cancel and a long one gets Send alone so it can page`() = runTest {
        stage(draftData(text = "x".repeat(200)))
        flow().present(gate.stagedFor("turn-1")!!)
        assertEquals(listOf("Send", "Cancel"), ui.confirmationActions.map { it.label })
        assertNull(ui.confirmationFooter)

        gate.drop()
        stage(draftData(text = "x".repeat(201)))
        flow().present(gate.stagedFor("turn-1")!!)
        assertEquals(listOf("Send"), ui.confirmationActions.map { it.label })
        assertEquals(AssistantConfirmationGate.LONG_DRAFT_FOOTER, ui.confirmationFooter)

        gate.drop()
        stage(draftData(text = "x".repeat(401)))
        flow().present(gate.stagedFor("turn-1")!!)
        assertEquals(listOf("Send"), ui.confirmationActions.map { it.label })
        assertEquals(AssistantConfirmationGate.PAGED_DRAFT_FOOTER, ui.confirmationFooter)
    }

    @Test
    fun `wide characters count double when deciding what fits the band`() {
        val parse = { text: String -> ConfirmationPreview.parse(draftData(text = text))!! }
        assertTrue(parse("한".repeat(100)).fitsTwoChipBand)
        assertFalse(parse("한".repeat(101)).fitsTwoChipBand)
    }

    @Test
    fun `the voice never gets more than speech accepts`() {
        val preview = ConfirmationPreview.parse(draftData(text = "x".repeat(ConfirmationPreview.MAX_TEXT_CHARS)))!!
        assertTrue(preview.spoken.length <= 1_024)
    }

    @Test
    fun `control characters that could reverse a name are stripped from the preview`() = runTest {
        stage(draftData(recipient = "Cora‮lie"))
        flow().present(gate.stagedFor("turn-1")!!)
        assertEquals("To Coralie · WhatsApp", ui.confirmationLines.first())
    }

    @Test
    fun `chips only answer once the band presents the draft`() = runTest {
        stage()
        val flow = flow()

        assertTrue(flow.tapSend())
        advanceUntilIdle()
        assertTrue(commits().isEmpty())
    }

    @Test
    fun `a double tap and a replay send once`() = runTest {
        stage()
        val flow = flow()
        flow.present(gate.stagedFor("turn-1")!!)
        transport.handler = { skillResult(it, SkillStatus.ACCEPTED, data = null) }

        flow.tapSend()
        flow.tapSend()
        advanceUntilIdle()
        flow.tapSend()
        advanceUntilIdle()

        assertEquals(1, commits().size)
    }

    @Test
    fun `send after cancel and a stale token send nothing`() = runTest {
        stage()
        val flow = flow()
        flow.present(gate.stagedFor("turn-1")!!)

        flow.tapSend("not-the-token")
        flow.tapCancel()
        flow.tapSend()
        advanceUntilIdle()

        assertTrue(commits().isEmpty())
        assertEquals(listOf(AssistantConfirmationFlow.CANCELLED), ui.notices)
        assertEquals(listOf(AssistantConfirmationFlow.TAPPED_CANCEL to AssistantConfirmationFlow.CANCELLED), recorded)
    }

    @Test
    fun `the band leaving for any reason drops the draft and silences its reading`() = runTest {
        stage()
        val flow = flow()
        flow.present(gate.stagedFor("turn-1")!!)

        flow.onBandLost()
        flow.tapSend()
        advanceUntilIdle()

        assertTrue(commits().isEmpty())
        assertEquals(1, speechStops)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a turn that never reached the band takes its draft with it`() = runTest {
        stage()

        gate.dropUnpresented("turn-1")

        assertNull(gate.stagedFor("turn-1"))
        assertFalse(gate.hasStaged("turn-1"))
    }

    @Test
    fun `a band that could not be raised drops the draft and says nothing was sent`() = runTest {
        stage()
        val flow = flow()
        ui.showConfirmationResult = false

        assertFalse(flow.present(gate.stagedFor("turn-1")!!))

        assertEquals(listOf(AssistantConfirmationFlow.COULD_NOT_ASK), ui.notices)
        assertFalse(gate.hasStaged("turn-1"))
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `a withdrawn workspace source stops the send at the tap`() = runTest {
        transport.handler = { skillResult(it, data = draftData()) }
        var allowed = true
        val staging = ConfirmationStaging(gate, gatedCommit(), turn { allowed })
        tool(staging).execute(AssistantToolCall("call_1", prepare.alias, "{}"), JSONObject())
        val flow = flow()
        flow.present(gate.stagedFor("turn-1")!!)

        allowed = false
        flow.tapSend()
        advanceUntilIdle()

        assertTrue(commits().isEmpty())
        assertEquals(listOf(AssistantConfirmationFlow.SOURCE_WITHDRAWN), ui.notices)
    }

    @Test
    fun `the outcome is written from the status and never claims more`() {
        val pending = PendingConfirmation(
            "tok0",
            turn(),
            gatedCommit(),
            ConfirmationPreview.parse(draftData())!!,
        )
        val sms = pending.copy(
            commit = GatedCommit(
                GatedSkillOperations.prepareOf(skillEntry("prepare_sms").copy(providerId = "sms"))!!,
                commit,
            ),
        )
        val request = SkillInvokeRequest("session-0001", "confirm:tok0", commit.alias, JSONObject())
        fun failed(code: String, dispatch: SkillDispatch) =
            skillResult(request, SkillStatus.FAILED, error = SkillError(code, dispatch))

        val expected = listOf(
            confirmationOutcome(pending, skillResult(request, SkillStatus.ACCEPTED, data = null)) to
                "Handed to WhatsApp for Coralie. Arrival not confirmed.",
            confirmationOutcome(pending, skillResult(request, SkillStatus.COMPLETED)) to
                "Handed to WhatsApp for Coralie. Arrival not confirmed.",
            confirmationOutcome(sms, skillResult(request, SkillStatus.ACCEPTED, data = null)) to
                "Handed to the mobile network for Coralie. Arrival not confirmed.",
            confirmationOutcome(pending, failed("authority_lost", SkillDispatch.NONE)) to
                "Not sent: that conversation can no longer be answered.",
            confirmationOutcome(pending, failed("recipient_changed", SkillDispatch.NONE)) to
                "Not sent: that contact changed.",
            confirmationOutcome(pending, failed(SkillErrorCodes.STALE_REFERENCE, SkillDispatch.NONE)) to
                "Not sent: the draft expired.",
            confirmationOutcome(pending, failed("send_failed", SkillDispatch.UNKNOWN)) to MAY_HAVE_GONE,
            confirmationOutcome(pending, failed("send_failed", SkillDispatch.DISPATCHED)) to MAY_HAVE_GONE,
            confirmationOutcome(pending, skillResult(request, SkillStatus.UNKNOWN, data = null)) to MAY_HAVE_GONE,
        )

        expected.forEach { (actual, wanted) ->
            assertEquals(wanted, actual)
            assertFalse(actual.contains("deliver", ignoreCase = true))
        }
    }

    @Test
    fun `a failed or unknown send is shown once and never retried`() = runTest {
        for (result in listOf<(SkillInvokeRequest) -> SkillResultEnvelope>(
            { skillResult(it, SkillStatus.UNKNOWN, data = null) },
            { skillResult(it, SkillStatus.FAILED, error = SkillError("send_failed", SkillDispatch.UNKNOWN)) },
        )) {
            transport.invocations.clear()
            ui.results.clear()
            recorded.clear()
            gate.drop()
            stage()
            val flow = flow()
            val token = gate.stagedFor("turn-1")!!.token
            flow.present(gate.stagedFor("turn-1")!!)
            transport.handler = result

            flow.tapSend(token)
            advanceUntilIdle()

            assertEquals(1, commits().size)
            assertEquals(listOf(MAY_HAVE_GONE), ui.results)
            assertEquals(listOf(AssistantConfirmationFlow.TAPPED_SEND to MAY_HAVE_GONE), recorded)
        }
    }

    @Test
    fun `no answer from the hub reads as uncertain`() = runTest(UnconfinedTestDispatcher()) {
        stage()
        val shortGateway = AssistantSkillGateway(transport, resultTimeoutMs = 10L).also { transport.gateway = it }
        val flow = AssistantConfirmationFlow(
            scope = this,
            gate = gate,
            gateway = shortGateway,
            ui = ui,
            speak = {},
            stopSpeech = {},
            bandBusy = { false },
            record = { _, _, _ -> },
        )
        flow.present(gate.stagedFor("turn-1")!!)
        transport.handler = { null }

        flow.tapSend()
        advanceUntilIdle()

        assertEquals(listOf(MAY_HAVE_GONE), ui.results)
        assertEquals(1, transport.cancels.size)
    }

    @Test
    fun `a send in flight survives the band leaving and a newer question keeps the band`() = runTest {
        stage()
        val flow = flow()
        flow.present(gate.stagedFor("turn-1")!!)
        var answer: (() -> Unit)? = null
        transport.handler = { request ->
            answer = { gateway.onResult(skillResult(request, SkillStatus.ACCEPTED, data = null)) }
            null
        }

        flow.tapSend()
        assertEquals(listOf(AssistantConfirmationFlow.SENDING), ui.progress)
        assertTrue(flow.commitInFlight)

        flow.onBandLost()
        bandBusy = true
        answer!!.invoke()
        advanceUntilIdle()

        assertFalse(flow.commitInFlight)
        assertTrue(transport.cancels.isEmpty())
        assertTrue(ui.results.isEmpty())
        assertEquals(1, recorded.size)
        assertEquals(AssistantConfirmationFlow.TAPPED_SEND, recorded.single().first)
    }

    @Test
    fun `a conversation that is not kept records nothing`() = runTest {
        transport.handler = { skillResult(it, data = draftData()) }
        val staging = ConfirmationStaging(gate, gatedCommit(), turn().copy(conversation = null))
        tool(staging).execute(AssistantToolCall("call_1", prepare.alias, "{}"), JSONObject())
        val flow = flow()
        flow.present(gate.stagedFor("turn-1")!!)
        transport.handler = { skillResult(it, SkillStatus.ACCEPTED, data = null) }

        flow.tapSend()
        advanceUntilIdle()

        assertEquals(1, ui.results.size)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `the note the conversation keeps does not read as sent`() {
        val pending = PendingConfirmation("tok0", turn(), gatedCommit(), ConfirmationPreview.parse(draftData())!!)
        assertTrue(pending.conversationNote.endsWith("Not sent unless a later message says so."))
    }

    @Test
    fun `the policy tells the model it cannot send only when a prepare is offered`() {
        val tools = listOf("sk_relay__prepare_reply")
        assertTrue(
            NexusAgentPolicy.buildSystemPrompt(availableToolNames = tools, confirmedSendAvailable = true)
                .contains("you cannot send one"),
        )
        assertFalse(
            NexusAgentPolicy.buildSystemPrompt(availableToolNames = tools).contains("you cannot send one"),
        )
    }

    @Test
    fun `the policy has the model pick the recipient from the unfiltered list`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            availableToolNames = listOf("sk_relay__prepare_reply"),
            confirmedSendAvailable = true,
        )
        assertTrue(prompt.contains("with no name filter"))
        assertTrue(prompt.contains("closest in sound or spelling"))
    }

    private class FakeConfirmationUi : AssistantConfirmationUi {
        var showConfirmationResult = true
        var confirmationLines: List<String> = emptyList()
        var confirmationActions: List<NexusNoticeAction> = emptyList()
        var confirmationFooter: String? = null
        val notices = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val results = mutableListOf<String>()

        override fun showConfirmation(lines: List<String>, actions: List<NexusNoticeAction>, footer: String?): Boolean {
            confirmationLines = lines
            confirmationActions = actions
            confirmationFooter = footer
            return showConfirmationResult
        }

        override fun showConfirmationProgress(body: String) {
            progress += body
        }

        override fun showConfirmationResult(body: String) {
            results += body
        }

        override fun showConfirmationNotice(body: String) {
            notices += body
        }
    }
}
