package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch

class WorkspaceLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a change notification during a running pass is coalesced into another pass`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("notes", "notes.txt", "Notice is two months.")
        val opened = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var first = true
        gateway.onOpen = {
            if (first) {
                first = false
                opened.complete(Unit)
                release.await()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, debounceMs = 20)
        try {
            controller.attach(Any())
            withTimeout(2_000) { opened.await() }
            gateway.put("notes", "notes.txt", "Notice is three months.", modified = 20)
            gateway.changed!!.invoke()
            assertNull(controller.contextForQuestion("notice", "").turn)
            delay(150)
            release.countDown()
            withTimeout(5_000) {
                while (store.snapshot().retriever?.search("notice")?.excerpts?.contains("three months") != true ||
                    controller.state.value.checking) delay(20)
            }
            assertNull(store.snapshot().state.problem)
            assertNotNull(controller.contextForQuestion("notice", "").turn)
            val roots = gateway.rootCalls
            delay(400)
            assertEquals(roots, gateway.rootCalls)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `leaving every owner pauses indexing and the next attach resumes from the committed cursor`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("scan", "scan.pdf", (1..8).joinToString(FakePageReader.PAGE_BREAK) { "Page $it." })
        val reader = FakePageReader { Thread.sleep(120) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 400, pageReader = reader)
        val owner = Any()
        try {
            controller.attach(owner)
            withTimeout(5_000) {
                while ((store.snapshot().state.index?.documents?.singleOrNull()?.pagesRead ?: 0) < 2) delay(10)
            }
            controller.detach(owner)
            assertEquals(0, gateway.activeObservers)
            delay(500)
            val paused = reader.extractedPages.size
            val committed = store.snapshot().state.index!!.documents.single()
            assertEquals(WorkspaceDocumentStatus.PENDING, committed.status)
            delay(400)
            assertEquals(paused, reader.extractedPages.size)
            controller.attach(owner)
            withTimeout(10_000) {
                while (store.snapshot().state.index?.documents?.single()?.status != WorkspaceDocumentStatus.INDEXED) delay(20)
            }
            assertEquals(committed.nextPage, synchronized(reader.extractedPages) { reader.extractedPages[paused] })
            assertEquals((1..8).toList(), store.snapshot().state.index!!.documents.single().chunks.map { it.page })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `codex and compat replies carry runtime image provenance next to the pixels`() = runTest {
        val caption = "Workspace page image supplied by Nexus: \"orion.pdf\", page 3 of 3."
        val image = AssistantToolResult.Image("image/jpeg", "AAAA", caption)
        val call = AssistantToolCall("view", VIEW_WORKSPACE_PAGE_TOOL_NAME, "{}")
        val output = functionCallOutput(call, image).getJSONArray("output")
        assertEquals("input_text", output.getJSONObject(0).getString("type"))
        assertEquals(caption, output.getJSONObject(0).getString("text"))
        assertEquals("input_image", output.getJSONObject(1).getString("type"))
        val photo = functionCallOutput(call, image.copy(caption = null)).getJSONArray("output")
        assertEquals(1, photo.length())

        val tool = TestAssistantTool(VIEW_WORKSPACE_PAGE_TOOL_NAME, executor = { _, _ -> image })
        val client = object : OpenAiCompatChatClient {
            val requests = mutableListOf<OpenAiCompatChatRequest>()
            override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
                requests += request
                if (requests.size == 1) emit(OpenAiChatSseEvent.Delta(toolCalls = listOf(OpenAiChatToolCallDelta(
                    index = 0, id = "view", nameFragment = tool.name, argumentsFragment = "{}"))))
                else emit(OpenAiChatSseEvent.Delta(content = "Page 3 shows the venue."))
            }
            override fun cancel(requestId: String) = Unit
        }
        val provider = OpenAiCompatProvider(ProviderCatalog.openAi, client, { true }, AssistantToolRegistry(listOf(tool)),
            supportsVision = { true })
        val events = provider.streamEvents(ChatRequest(userText = "Look at page 3")).toList()
        assertTrue(events.any { it is AiProviderEvent.MessageDone })
        val replay = client.requests[1].messages
        val messages = (0 until replay.length()).map { replay.getJSONObject(it) }
        val toolMessage = JSONObject(messages.single { it.optString("role") == "tool" }.getString("content"))
        assertEquals(caption, toolMessage.getString("source"))
        val attached = messages.last { it.optString("role") == "user" }.get("content") as JSONArray
        assertEquals(caption, attached.getJSONObject(0).getString("text"))
    }

    @Test
    fun `an unrelated answer continues after Workspace access is withdrawn without evidence`() = runTest {
        val turn = FakeWorkspaceTurn()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val tool = TestAssistantTool(LIST_NOTES_TOOL_NAME, executor = { _, _ ->
            started.complete(Unit)
            finish.await()
            AssistantToolResult.Json("{\"ok\":true}")
        })
        val client = object : OpenAiCompatChatClient {
            val requests = mutableListOf<OpenAiCompatChatRequest>()
            override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
                requests += request
                if (requests.size == 1) emit(OpenAiChatSseEvent.Delta(toolCalls = listOf(OpenAiChatToolCallDelta(
                    index = 0, id = "notes", nameFragment = tool.name, argumentsFragment = "{}"))))
                else emit(OpenAiChatSseEvent.Delta(content = "You have no notes."))
            }
            override fun cancel(requestId: String) = Unit
        }
        val provider = OpenAiCompatProvider(ProviderCatalog.openAi, client, { true },
            AssistantToolRegistry(listOf(tool, SearchWorkspaceTool())), supportsVision = { false })
        val request = ChatRequest(userText = "List my notes", systemPrompt = "Prompt without Workspace text.",
            workspaceVersion = turn.version, workspaceTurn = turn).forCurrentWorkspace(false) { error("unused") }
        val response = async { provider.streamEvents(request).toList() }
        started.await()
        turn.withdraw(WORKSPACE_SOURCE_CHANGED)
        finish.complete(Unit)
        val events = response.await()
        assertEquals(2, client.requests.size)
        assertTrue(events.none { it is AiProviderEvent.Failed })
        assertEquals("You have no notes.", events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content)
        assertTrue(turn.finalEffectsAllowed())
        assertFalse(turn.evidence)
    }

    @Test
    fun `manual verification survives owner detach and process restart`() = runBlocking {
        for (restart in listOf(false, true)) {
            val directory = temporary.newFolder()
            val store = WorkspaceStore(directory)
            store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
            val gateway = FakeWorkspaceGateway().apply { put("notice", "notice.txt", "Notice is two months.") }
            WorkspaceIndexer(store, gateway).refresh()
            val before = store.snapshot()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller = WorkspaceController(store, gateway, scope)
            val owner = Any()
            val opened = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            try {
                controller.reindexNow()
                gateway.onRoot = { opened.complete(Unit); blocked.await() }
                controller.attach(owner)
                withTimeout(2_000) { opened.await() }
                controller.detach(owner)
                assertTrue(store.snapshot().state.settings.verificationRequested)
                assertEquals(before.epoch, store.snapshot().epoch)
                gateway.onRoot = null
                gateway.put("notice", "notice.txt", "Notice is six months.")
                val resumedStore = if (restart) WorkspaceStore(directory) else store
                val resumed = if (restart) WorkspaceController(resumedStore, gateway, scope) else controller
                resumed.attach(owner)
                withTimeout(5_000) {
                    while (resumedStore.snapshot().state.settings.verificationRequested ||
                        resumedStore.snapshot().retriever?.search("notice")?.excerpts?.contains("six months") != true) delay(20)
                }
                assertEquals(WorkspaceStoreTest.TREE, resumedStore.snapshot().state.settings.treeUri)
                resumed.detach(owner)
                assertFalse(WorkspaceStore(directory).snapshot().state.settings.verificationRequested)
            } finally { scope.cancel() }
        }
    }

    @Test
    fun `the Workspace card reports catalog, text, and page progress truthfully`() {
        fun pdf(id: String, status: WorkspaceDocumentStatus, runs: List<WorkspacePageRun>, count: Int?,
            chunks: List<WorkspaceChunk> = emptyList(), next: Int? = null, known: Boolean = false) = WorkspaceDocument(
            WorkspaceEntry(id, "$id.pdf", modifiedAtMs = 10, sizeBytes = 10), chunks, status, nextPage = next,
            pageCount = count, pageRuns = runs, sourceDigest = "a".repeat(64), lookupSafe = true, coverageKnown = known)
        val native = WorkspacePageState(WorkspaceTextState.NATIVE, WorkspaceVisualState.ABSENT)
        val empty = WorkspacePageState(WorkspaceTextState.EMPTY, render = WorkspaceRenderState.AVAILABLE)
        val ready = WorkspaceIndex(0, listOf(pdf("a", WorkspaceDocumentStatus.INDEXED, listOf(WorkspacePageRun(1, 2, native)),
            2, listOf(WorkspaceChunk(0, "Text.", page = 1)), known = true)), 1_000)
        assertEquals("Ready", WorkspaceStatusText.state(ready, checking = false))
        val pending = WorkspaceIndex(0, listOf(pdf("b", WorkspaceDocumentStatus.PENDING, listOf(WorkspacePageRun(1, 12, native)),
            40, listOf(WorkspaceChunk(0, "Text.", page = 1)), next = 13)), 1_000)
        assertEquals("Indexing 12/40 pages", WorkspaceStatusText.state(pending, checking = true))
        assertEquals("Partially indexed", WorkspaceStatusText.state(pending, checking = false))
        val textless = WorkspaceIndex(0, listOf(pdf("c", WorkspaceDocumentStatus.NO_TEXT, listOf(WorkspacePageRun(1, 3, empty)),
            3, known = true)), 1_000)
        assertEquals("No readable text; pages can be viewed", WorkspaceStatusText.state(textless, checking = false))
        assertEquals("1 files cataloged · 0 with searchable text · pages 3/3 processed, 3 without text",
            WorkspaceStatusText.summary(textless))
        assertEquals("Checking folder", WorkspaceStatusText.state(null, checking = true))
        assertEquals("Partially indexed", WorkspaceStatusText.state(ready.copy(omittedFiles = 4), checking = false))
    }
}
