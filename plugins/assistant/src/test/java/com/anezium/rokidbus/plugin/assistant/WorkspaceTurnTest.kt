package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceTurnTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Env(
        val directory: File,
        val store: WorkspaceStore,
        val gateway: FakeWorkspaceGateway,
        val reader: FakePageReader,
        val scope: CoroutineScope,
        val controller: WorkspaceController,
    ) {
        suspend fun ask(question: String, memory: String = "") = controller.contextForQuestion(question, memory)
    }

    @Test
    fun `a reloaded index answers with one root check each and no source-document work`() = env({
        put("notes", "notes.txt", "Notice is two months.")
        put("orion", "orion.pdf", ORION)
    }) {
        val store = WorkspaceStore(directory)
        val reader = FakePageReader()
        val controller = WorkspaceController(store, gateway, scope, pageReader = reader)
        store.publish(store.snapshot().state.index!!)
        gateway.rootCalls = 0
        gateway.metadataCalls = 0
        gateway.opens.clear()
        val tokenizations = WorkspaceLexicalIndex.bodyTokenizations
        val context = controller.contextForQuestion(ORION_QUESTION, "")
        assertEquals(1, gateway.rootCalls)
        val turn = context.turn!!
        assertTrue(turn.search("Orion", null) is WorkspaceToolOutcome.Text)
        assertEquals(2, gateway.rootCalls)
        assertTrue(turn.search("casier", "orion.pdf") is WorkspaceToolOutcome.Text)
        assertTrue(turn.search("casier", "orion.pdf") is WorkspaceToolOutcome.Text)
        assertEquals(3, gateway.rootCalls)
        assertEquals(0, gateway.metadataCalls)
        assertTrue(gateway.opens.isEmpty())
        assertEquals(0, reader.loads + reader.extractions + reader.inspections + reader.renders)
        assertEquals(tokenizations, WorkspaceLexicalIndex.bodyTokenizations)
    }

    @Test
    fun `the named Orion file supplies its annex page from the question and from a names-only search`() = env({
        put("orion", "orion.pdf", ORION)
        put("lyon", "lyon.txt", "La réunion de lancement Vega aura lieu à Paris, salle Opéra.")
    }) {
        val context = ask(ORION_QUESTION)
        val turn = context.turn!!
        assertEquals(WorkspaceResolutionState.RESOLVED, turn.resolution.state)
        assertTrue(context.excerpts.contains("Bellecour"))
        assertTrue(context.excerpts.contains("orion.pdf › page 3"))
        assertTrue(context.excerpts.contains("Scope: orion.pdf - 3 known pages"))
        assertFalse(context.excerpts.contains("Paris"))
        assertTrue(context.carriesEvidence)
        assertTrue(WorkspaceCitation("orion", 3) in turn.deliveredCitations)
        val followUp = JSONObject((turn.search("Orion", null) as WorkspaceToolOutcome.Text).json)
        assertTrue(followUp.getString("excerpts").contains("Bellecour"))
        assertEquals("ok", followUp.getString("status"))
        assertTrue(followUp.getString("coverage").contains("Complete source text supplied."))
        assertEquals("orion.pdf", followUp.getJSONArray("files").getJSONObject(0).getString("file"))
    }

    @Test
    fun `an annex deep in a long named file is found by scoped ranking at every position`() {
        for (annexPage in listOf(1, 3, 17)) env({
            put("orion", "orion.pdf", (1..20).joinToString(FakePageReader.PAGE_BREAK) { page ->
                if (page == annexPage) ANNEX else "Projet Orion, chapitre $page. " + "Calendrier détaillé des équipes. ".repeat(20)
            })
            put("lyon", "lyon.txt", "La réunion de lancement Vega aura lieu à Paris, salle Opéra.")
        }) {
            val context = ask(ORION_QUESTION)
            assertTrue("$annexPage", context.excerpts.contains("Bellecour"))
            assertTrue("$annexPage", context.excerpts.contains("orion.pdf › page $annexPage"))
            assertFalse(context.excerpts.contains("Paris"))
            assertTrue(context.excerpts.length <= WorkspaceLimits.MAX_EXCERPT_CHARS)
        }
    }

    @Test
    fun `every prefetch line fits the remaining personal context and zero injects nothing`() = env({
        put("orion", "orion.pdf", ORION)
    }) {
        for (memoryLength in listOf(0, 5_000, 9_500, 9_900, 10_002, 12_000)) {
            val memory = "m".repeat(memoryLength)
            val context = ask(ORION_QUESTION, memory)
            val budget = workspacePromptBudget(memory)
            assertTrue("$memoryLength", context.excerpts.length <= budget)
            assertTrue("$memoryLength", memory.length + context.excerpts.length +
                (if (memory.isNotEmpty() && context.excerpts.isNotEmpty()) 2 else 0) <= WorkspaceLimits.MAX_PERSONAL_CONTEXT_CHARS ||
                memoryLength > WorkspaceLimits.MAX_PERSONAL_CONTEXT_CHARS && context.excerpts.isEmpty())
            if (budget == 0) {
                assertEquals("", context.excerpts)
                assertFalse(context.carriesEvidence)
            }
        }
        assertTrue(ask(ORION_QUESTION, "m".repeat(5_000)).excerpts.contains("Bellecour"))
    }

    @Test
    fun `a missing or ambiguous file never falls back to another file's code`() = env({
        put("code", "code.txt", "The cabinet code is 4471.")
        put("nebuleuse", "nebuleuse.txt", "Nebuleuse launch code is VELA-5836.")
        entries["a"] = WorkspaceEntry("a", "a", directory = true)
        entries["b"] = WorkspaceEntry("b", "b", directory = true)
        put("plan-a", "plan.pdf", "Plan A details.")
        put("plan-b", "plan.pdf", "Plan B details.")
        childrenByParent["root"] = listOf("code", "nebuleuse", "a", "b")
        childrenByParent["a"] = listOf("plan-a")
        childrenByParent["b"] = listOf("plan-b")
    }) {
        val missing = ask("dans orphee pdf quel est le code")
        assertEquals(WorkspaceResolutionState.MISSING, missing.turn!!.resolution.state)
        assertTrue(missing.excerpts.contains("not in the indexed Workspace folder"))
        assertFalse(missing.excerpts.contains("4471"))
        assertFalse(missing.carriesEvidence)
        val search = JSONObject((missing.turn!!.search("code", null) as WorkspaceToolOutcome.Text).json)
        assertEquals("missing", search.getString("status"))
        assertEquals("", search.getString("excerpts"))
        val named = JSONObject((missing.turn!!.search("code", "code.txt") as WorkspaceToolOutcome.Text).json)
        assertEquals("unknown_file", named.getString("status"))
        assertEquals(WorkspaceToolOutcome.Failure(TOOL_ERROR_ALREADY_USED), missing.turn!!.search("cabinet", null))
        assertFalse(missing.turn!!.evidenceSupplied)

        val ambiguous = ask("what is in plan pdf")
        val turn = ambiguous.turn!!
        assertEquals(WorkspaceResolutionState.AMBIGUOUS, turn.resolution.state)
        assertTrue(ambiguous.excerpts.contains("\"a/plan.pdf\""))
        assertTrue(ambiguous.excerpts.contains("\"b/plan.pdf\""))
        assertFalse(ambiguous.excerpts.contains("Plan A details"))
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), turn.viewPage("a/plan.pdf", 1))
        val choice = JSONObject((turn.search("details", "a/plan.pdf") as WorkspaceToolOutcome.Text).json)
        assertEquals("unknown_file", choice.getString("status"))
        assertFalse(turn.evidenceSupplied)
    }

    @Test
    fun `unscoped private search requires the full stated name`() = env({
        put("martin", "martin.txt", "Jean Martin badge code is 1188.")
        put("dupont", "dupont.txt", "Jean et Marie Dupont share locker 77.")
    }) {
        val turn = ask("bonjour").turn!!
        val result = JSONObject((turn.search("Jean et Marie Dupont", null) as WorkspaceToolOutcome.Text).json)
        assertTrue(result.getString("excerpts").contains("locker 77"))
        assertFalse(result.getString("excerpts").contains("1188"))
        assertTrue(result.getString("coverage").contains("No match is not proof of absence."))
        val none = JSONObject((turn.search("Jean Dupont badge", null) as WorkspaceToolOutcome.Text).json)
        assertEquals("no_match", none.getString("status"))
    }

    @Test
    fun `a general hit views only its cited page while the named file views any cataloged page`() = env({
        put("report", "report.pdf", listOf("Intro.", "Context.", "Method.", "Budget table for Vega.", "", "Annex.",
            "End.").joinToString(FakePageReader.PAGE_BREAK))
    }) {
        val general = ask("budget table vega").turn!!
        assertEquals(WorkspaceResolutionState.NONE, general.resolution.state)
        assertTrue(general.viewPage("report.pdf", 4) is WorkspaceToolOutcome.Image)
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), general.viewPage("report.pdf", 5))
        val named = ask("dans report pdf que montre la page 5").turn!!
        assertTrue(named.allPagesInScope(named.resolution.document!!))
        val image = named.viewPage("report.pdf", 5) as WorkspaceToolOutcome.Image
        assertEquals("jpeg:PDF:5:", String(image.jpeg))
        assertTrue(image.caption.contains("page 5 of 7"))
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), named.viewPage("report.pdf", 8))
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), general.viewPage("report.pdf", 4))
    }

    @Test
    fun `a textless folder offers the named file's pages without advertising global search`() = env({
        put("scan", "scan.pdf", listOf("", "").joinToString(FakePageReader.PAGE_BREAK))
    }) {
        val context = ask("dans scan pdf que montre la page 2")
        val turn = context.turn!!
        assertFalse(turn.hasSearchableText())
        assertTrue(turn.hasViewablePages())
        assertTrue(context.excerpts.contains("2 known pages; empty OCR 1-2"))
        assertFalse(context.carriesEvidence)
        val registry = AssistantToolRegistry(listOf(SearchWorkspaceTool(), ViewWorkspacePageTool()))
        assertEquals(listOf(VIEW_WORKSPACE_PAGE_TOOL_NAME),
            registry.availableDefinitions(VISION, turn.version, turn).map { it.name })
        assertTrue(registry.availableDefinitions(VISION.copy(supportsVision = false), turn.version, turn).isEmpty())
        assertTrue(turn.viewPage("scan.pdf", 2) is WorkspaceToolOutcome.Image)
        assertTrue(turn.evidenceSupplied)
    }

    @Test
    fun `different bytes under the same size and time are a source change and send no image`() = env({
        put("orion", "orion.pdf", ORION)
    }) {
        val turn = ask(ORION_QUESTION).turn!!
        val original = gateway.contents.getValue("orion")
        gateway.contents["orion"] = original.copyOf().also { it[0] = 'X'.code.toByte() }
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), turn.viewPage("orion.pdf", 3))
        assertEquals(0, reader.renders)
        assertFalse(turn.isUsable())
        assertNull(ask(ORION_QUESTION).turn)
    }

    @Test
    fun `a change with no supplied Workspace content withdraws access but keeps the answer`() = env({
        put("notes", "notes.txt", "Notice is two months.")
    }) {
        val context = ask("What time is it?")
        val turn = context.turn!!
        assertFalse(context.carriesEvidence)
        turn.beforeSend(false)
        controller.sourceChanged()
        turn.beforeSend(false)
        assertTrue(turn.finalEffectsAllowed())
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), turn.search("notice", null))
        assertTrue(AssistantToolRegistry(listOf(SearchWorkspaceTool())).availableDefinitions(
            SearchWorkspaceToolTest.FEATURES, turn.version, turn).isEmpty())
        assertFalse(turn.evidenceSupplied)
    }

    @Test
    fun `a change after prefetched text suppresses the final effects and cannot be revived`() = env({
        put("notes", "notes.txt", "Notice is two months.")
    }) {
        val context = ask("notice")
        val turn = context.turn!!
        assertTrue(context.carriesEvidence)
        turn.beforeSend(true)
        controller.sourceChanged()
        assertFalse(turn.finalEffectsAllowed())
        assertTrue(runCatching { turn.beforeSend(true) }.exceptionOrNull()?.message == WORKSPACE_CHANGED_MESSAGE)
        WorkspaceIndexer(store, gateway, clock = { 2_000 }, pageReader = reader).refresh()
        assertFalse(turn.finalEffectsAllowed())
        assertFalse(turn.isUsable())
    }

    @Test
    fun `a change between a search and a view returns source changed and sends no image`() = env({
        put("sales", "sales.pdf", listOf("Summary.", "Quarterly chart.").joinToString(FakePageReader.PAGE_BREAK))
    }) {
        val turn = ask("What time is it?").turn!!
        assertTrue(JSONObject((turn.search("quarterly chart", null) as WorkspaceToolOutcome.Text).json).getInt("matchCount") > 0)
        assertTrue(turn.evidenceSupplied)
        controller.sourceChanged()
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), turn.viewPage("sales.pdf", 2))
        assertEquals(0, reader.renders)
        assertFalse(turn.finalEffectsAllowed())
    }

    @Test
    fun `a change that wins the race during a search drops its evidence`() = env({
        put("notes", "notes.txt", "Notice is two months.")
    }) {
        val turn = ask("What time is it?").turn!!
        gateway.onRoot = { controller.sourceChanged() }
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), turn.search("notice", null))
        assertFalse(turn.evidenceSupplied)
        assertTrue(turn.finalEffectsAllowed())
    }

    @Test
    fun `additive publication alone keeps supplied evidence valid`() = env({
        put("notes", "notes.txt", "Notice is two months.")
    }) {
        val turn = ask("notice").turn!!
        turn.beforeSend(true)
        val index = store.snapshot().state.index!!
        store.publish(index.copy(indexedAtMs = 5_000, documents = index.documents + WorkspaceDocument(
            WorkspaceEntry("extra", "extra.txt", modifiedAtMs = 10, sizeBytes = 5), listOf(WorkspaceChunk(0, "Extra.")),
            sourceDigest = "f".repeat(64), lookupSafe = true, coverageKnown = true)))
        assertTrue(turn.isUsable())
        assertTrue(turn.finalEffectsAllowed())
        turn.beforeSend(true)
    }

    @Test
    fun `a new question, a revoked grant, or Off end the old turn's access at the same epoch`() = env({
        put("sales", "sales.pdf", listOf("Summary.", "Quarterly chart.").joinToString(FakePageReader.PAGE_BREAK))
    }) {
        val first = ask("quarterly chart").turn!!
        val phase = AssistantToolRegistry(listOf(ViewWorkspacePageTool())).newExecutionPhase(VISION, first.version, first)
        val second = ask("summary").turn!!
        assertEquals(first.version, second.version)
        assertEquals(AssistantToolResult.Error(WORKSPACE_SOURCE_CHANGED),
            phase.execute(AssistantToolCall("v", VIEW_WORKSPACE_PAGE_TOOL_NAME, """{"file":"sales.pdf","page":1}""")))
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), second.viewPage("sales.pdf", 2))
        gateway.granted = false
        assertFalse(second.isUsable())
        gateway.granted = true
        val third = ask("summary").turn!!
        controller.setEnabled(false)
        assertFalse(third.isUsable())
        assertNull(ask("summary").turn)
    }

    @Test
    fun `search results stay within the character and byte limits with their coverage`() = env({
        put("dense", "dense.txt", (1..60).joinToString("\n\n") { "Clé \"$it\" \\ ‘€’ 𝄞 notice " + "é".repeat(500) })
    }) {
        val turn = ask("bonjour").turn!!
        val json = (turn.search("notice", null) as WorkspaceToolOutcome.Text).json
        assertTrue(json.length <= WorkspaceLimits.MAX_TOOL_RESULT_CHARS)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= WorkspaceLimits.MAX_TOOL_RESULT_BYTES)
        val result = JSONObject(json)
        assertTrue(result.getInt("matchCount") > 0)
        assertTrue(result.getString("coverage").isNotEmpty())
    }

    @Test
    fun `search cache preserves separate groups and retries the same normalized grouping`() = env({
        put("vega", "one.txt", "Vega schedule is Monday.")
        put("aurora", "two.txt", "Aurora schedule is Tuesday.")
    }) {
        val turn = ask("hello").turn!!
        val combined = JSONObject((turn.search("Vega Aurora", null) as WorkspaceToolOutcome.Text).json)
        assertEquals("no_match", combined.getString("status"))
        val grouped = (turn.search("Vega; Aurora", null) as WorkspaceToolOutcome.Text).json
        assertEquals(2, JSONObject(grouped).getInt("matchCount"))
        val roots = gateway.rootCalls
        assertEquals(grouped, (turn.search("VEGA? aurora", null) as WorkspaceToolOutcome.Text).json)
        assertEquals(roots, gateway.rootCalls)
        assertEquals(WorkspaceToolOutcome.Failure(TOOL_ERROR_ALREADY_USED), turn.search("schedule", null))
    }

    @Test
    fun `diagnostics carry counts and codes but no names, queries, ids, or text`() = env({
        put("secret-id", "confidential-plan.pdf", listOf("Secretword budget.", "Hidden chart.").joinToString(FakePageReader.PAGE_BREAK))
    }) {
        val lines = mutableListOf<String>()
        val previous = WorkspaceDiagnostics.sink
        WorkspaceDiagnostics.sink = { synchronized(lines) { lines += it } }
        try {
            val turn = ask("dans confidential-plan pdf secretword budget").turn!!
            turn.search("hidden chart", null)
            turn.viewPage("confidential-plan.pdf", 2)
            WorkspaceIndexer(store, gateway, clock = { 3_000 }, pageReader = reader).refresh()
        } finally {
            WorkspaceDiagnostics.sink = previous
        }
        val digest = store.snapshot().state.index!!.documents.single().sourceDigest!!
        assertTrue(lines.any { it.startsWith("workspace_prefetch ") })
        assertTrue(lines.any { it.startsWith("workspace_search ") })
        assertTrue(lines.any { it.startsWith("workspace_view ") })
        assertTrue(lines.any { it.startsWith("index_pass ") })
        for (line in lines) {
            for (secret in listOf("confidential", "secret", "hidden", "chart", "plan", digest.take(12))) {
                assertFalse(line, line.lowercase().contains(secret))
            }
        }
    }

    private fun env(setup: FakeWorkspaceGateway.() -> Unit, test: suspend Env.() -> Unit) = runBlocking {
        val directory = temporary.newFolder()
        val store = WorkspaceStore(directory)
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway().apply(setup)
        val reader = FakePageReader()
        WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 10_000, pageReader = reader,
            elapsed = { 0L }).refresh()
        check(store.snapshot().state.validated)
        val renders = reader.renders
        check(renders == 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            Env(directory, store, gateway, reader, scope,
                WorkspaceController(store, gateway, scope, pageReader = reader)).test()
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val ORION_QUESTION = "dans orion pdf ou a lieu la reunion de lancement"
        const val ANNEX = "Annexe. La réunion de lancement aura lieu à Lyon, salle Bellecour."
        val ORION = listOf("Projet Orion. Calendrier général.", "Le code du casier Orion-47 est 3912.", ANNEX)
            .joinToString(FakePageReader.PAGE_BREAK)
        val VISION = AssistantProviderFeatures(supportsTools = true, supportsVision = true)
    }
}
