package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePageStatesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `failed empty and dense pages are recorded and the cursor moves past them`() = runBlocking {
        val (store, gateway, reader) = fixture()
        gateway.put("mixed", "mixed.pdf", pages("Alpha one.", FakePageReader.DENSE, "Gamma three.", "",
            FakePageReader.NO_RENDER, FakePageReader.OCR_FAIL))
        indexer(store, gateway, reader).refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(6, document.pageCount)
        assertEquals(listOf(WorkspaceTextState.NATIVE, WorkspaceTextState.TRUNCATED, WorkspaceTextState.NATIVE,
            WorkspaceTextState.EMPTY, WorkspaceTextState.FAILED, WorkspaceTextState.FAILED),
            (1..6).map { document.pageState(it).text })
        assertEquals(WorkspaceRenderState.AVAILABLE, document.pageState(4).render)
        assertEquals(WorkspaceRenderState.UNAVAILABLE, document.pageState(5).render)
        assertEquals(WorkspaceRenderState.AVAILABLE, document.pageState(6).render)
        assertEquals(listOf(1, 3), document.chunks.map { it.page })
        assertEquals(WorkspaceDocumentStatus.TRUNCATED, document.status)
        assertNull(document.nextPage)
        val coverage = WorkspaceCoverage.catalog(document)
        assertTrue(coverage, coverage.contains("empty OCR 4"))
        assertTrue(coverage, coverage.contains("not read (failed) 5-6"))
        assertTrue(coverage, coverage.contains("text cut on 2"))
        val extractions = reader.extractions
        indexer(store, gateway, reader).refresh()
        assertEquals(extractions, reader.extractions)
        assertEquals(1, gateway.opens["mixed"])
    }

    @Test
    fun `a dense page in a long scan does not stall resumed passes`() = runBlocking {
        var now = 0L
        val (store, gateway, _) = fixture()
        val reader = FakePageReader { now += 600 }
        gateway.put("scan", "scan.pdf", pages("One.", FakePageReader.DENSE, "Three.", "Four.", "Five."))
        val indexer = WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 1_000, pageReader = reader,
            elapsed = { now })
        repeat(5) { indexer.refresh() }
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(listOf(1, 3, 4, 5), document.chunks.map { it.page })
        assertEquals(WorkspaceTextState.TRUNCATED, document.pageState(2).text)
        assertEquals(5, reader.extractions)
    }

    @Test
    fun `the controller stops once failing pages have been passed`() = runBlocking {
        val (store, gateway, _) = fixture()
        gateway.put("bad", "bad.pdf", pages(FakePageReader.OCR_FAIL, FakePageReader.OCR_FAIL, FakePageReader.NO_RENDER))
        val reader = FakePageReader { Thread.sleep(150) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 400, pageReader = reader)
        try {
            controller.attach(Any())
            withTimeout(10_000) {
                while (store.snapshot().state.index?.documents?.singleOrNull()?.status != WorkspaceDocumentStatus.NO_TEXT) {
                    delay(20)
                }
            }
            withTimeout(2_000) { while (controller.state.value.checking) delay(10) }
            val extractions = reader.extractions
            delay(400)
            assertEquals(3, extractions)
            assertEquals(extractions, reader.extractions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an explicit re-index retries each failed page once and ordinary passes never do`() = runBlocking {
        val (store, gateway, reader) = fixture()
        gateway.put("bad", "bad.pdf", pages("Fine.", FakePageReader.OCR_FAIL))
        indexer(store, gateway, reader).refresh()
        indexer(store, gateway, reader).refresh()
        assertEquals(listOf(1, 2), reader.extractedPages)
        val documents = store.snapshot().state.index!!.documents.map { it.entry.documentId }
        val verification = WorkspaceVerification(documents)
        indexer(store, gateway, reader, verification).refresh()
        assertEquals(listOf(1, 2, 2), reader.extractedPages)
        assertEquals(0, verification.size)
        indexer(store, gateway, reader, verification).refresh()
        assertEquals(listOf(1, 2, 2), reader.extractedPages)
        assertEquals(2, gateway.opens["bad"])
    }

    @Test
    fun `explicit verification finds a changed digest under unchanged metadata`() = runBlocking {
        val (store, gateway, reader) = fixture()
        gateway.put("notes", "notes.txt", "Notice is two months.")
        indexer(store, gateway, reader).refresh()
        val epoch = store.snapshot().epoch
        gateway.contents["notes"] = "Notice is ten months.".toByteArray()
        indexer(store, gateway, reader).refresh()
        assertTrue(store.snapshot().retriever!!.search("notice").excerpts.contains("two months"))
        indexer(store, gateway, reader, WorkspaceVerification(listOf("notes"))).refresh()
        assertTrue(store.snapshot().retriever!!.search("notice").excerpts.contains("ten months"))
        assertEquals(epoch + 1, store.snapshot().epoch)
    }

    @Test
    fun `a pdf beyond the page cap keeps its true count and an implicit unattempted tail`() = runBlocking {
        val (store, gateway, reader) = fixture()
        gateway.put("big", "big.pdf", (1..501).joinToString(FakePageReader.PAGE_BREAK) { "Page $it text." })
        WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 60_000, pageReader = reader).refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(501, document.pageCount)
        assertEquals(WorkspaceDocumentStatus.TRUNCATED, document.status)
        assertEquals(500, document.chunks.maxOf { it.page })
        assertEquals(1, document.pageRuns.size)
        assertFalse(document.coverageKnown)
        assertTrue(WorkspaceCoverage.catalog(document).contains("501 known pages, only the first 500"))
        assertEquals(500, reader.extractions)
    }

    @Test
    fun `an image is catalog page one and blank recognition is empty, not failed`() = runBlocking {
        val (store, gateway, reader) = fixture()
        gateway.put("blank", "sky.png", "")
        gateway.put("receipt", "receipt.jpg", "Hotel total 184 euros")
        indexer(store, gateway, reader).refresh()
        val documents = store.snapshot().state.index!!.documents.associateBy { it.entry.name }
        val blank = documents.getValue("sky.png")
        assertEquals(1, blank.pageCount)
        assertEquals(WorkspacePageState(WorkspaceTextState.EMPTY, render = WorkspaceRenderState.AVAILABLE), blank.pageState(1))
        assertEquals(WorkspaceDocumentStatus.NO_TEXT, blank.status)
        val receipt = documents.getValue("receipt.jpg")
        assertEquals(0, receipt.chunks.single().page)
        assertEquals(1, receipt.catalogPage(receipt.chunks.single()))
        assertTrue(receipt.coverageKnown)
    }

    @Test
    fun `a legacy pdf with text on every page gains digest and count without any page extraction`() = runBlocking {
        val (directory, gateway, reader) = legacyFixture(status = "INDEXED", pagesRead = 0, textPages = listOf(1, 2, 3),
            pageText = listOf("Projet Orion.", "Code 3912.", "Annexe Bellecour."))
        val store = WorkspaceStore(directory)
        val before = store.snapshot()
        indexer(store, gateway, reader).refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(0, reader.extractions)
        assertEquals(1, reader.inspections)
        assertEquals(3, document.pageCount)
        assertEquals(WorkspaceIndexer.sha256(gateway.contents.getValue("orion")), document.sourceDigest)
        assertEquals(listOf(WorkspacePageRun(1, 3, WorkspacePageState.LEGACY_TEXT)), document.pageRuns)
        assertEquals(before.state.index!!.documents.single().chunks, document.chunks)
        assertEquals(before.epoch, store.snapshot().epoch)
        assertTrue(document.lookupSafe)
        assertFalse(document.coverageKnown)
        indexer(store, gateway, reader).refresh()
        assertEquals(1, gateway.opens["orion"])
        assertEquals(0, reader.extractions)
    }

    @Test
    fun `a legacy pending pdf resumes after its last read page and backfills only chunk-less pages`() = runBlocking {
        val (directory, gateway, reader) = legacyFixture(status = "PENDING", pagesRead = 2, textPages = listOf(2),
            pageText = listOf("Scanned cover.", "Second page text.", "Third.", "Fourth.", "Fifth."))
        val store = WorkspaceStore(directory)
        val before = store.snapshot()
        indexer(store, gateway, reader).refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(listOf(3, 4, 5, 1), reader.extractedPages)
        assertEquals(WorkspaceTextState.LEGACY_TEXT, document.pageState(2).text)
        assertEquals(WorkspaceTextState.NATIVE, document.pageState(1).text)
        assertEquals(5, document.pageCount)
        assertEquals(listOf(2, 3, 4, 5, 1), document.chunks.map { it.page })
        assertEquals(before.state.index!!.documents.single().chunks, document.chunks.take(1))
        assertEquals(before.epoch, store.snapshot().epoch)
        assertFalse(document.coverageKnown)
        indexer(store, gateway, reader).refresh()
        assertEquals(listOf(3, 4, 5, 1), reader.extractedPages)
    }

    @Test
    fun `a legacy cited page stays viewable before its digest is known and broad scope waits for it`() = runBlocking {
        val (directory, gateway, reader) = legacyFixture(status = "INDEXED", pagesRead = 0, textPages = listOf(3),
            pageText = listOf("", "Code 3912.", "Annexe Bellecour salle."))
        val store = WorkspaceStore(directory)
        store.publish(store.snapshot().state.index!!)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controller = WorkspaceController(store, gateway, scope, pageReader = reader)
            val general = controller.contextForQuestion("annexe bellecour", "").turn!!
            assertTrue(general.viewPage("orion.pdf", 3) is WorkspaceToolOutcome.Image)
            assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), general.viewPage("orion.pdf", 1))
            val unverified = controller.contextForQuestion("dans orion pdf que montre la page 1", "")
            assertEquals(WorkspaceResolutionState.UNAVAILABLE, unverified.turn!!.resolution.state)
            assertTrue(unverified.excerpts.contains("cannot be verified yet"))
            assertFalse(unverified.excerpts.contains("Bellecour"))
            assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), unverified.turn!!.viewPage("orion.pdf", 1))
            indexer(store, gateway, reader).refresh()
            val document = store.snapshot().state.index!!.documents.single()
            assertEquals(3, document.pageCount)
            assertTrue(document.lookupSafe)
            val named = controller.contextForQuestion("dans orion pdf que montre la page 1", "").turn!!
            assertEquals(WorkspaceResolutionState.RESOLVED, named.resolution.state)
            assertTrue(named.viewPage("orion.pdf", 1) is WorkspaceToolOutcome.Image)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `explicit verification honors the pass deadline and resumes in the next pass`() = runBlocking {
        var now = 0L
        val (store, gateway, reader) = fixture()
        listOf("a", "b", "c").forEach { gateway.put(it, "$it.txt", "Notice $it.") }
        WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 1_000, pageReader = reader,
            elapsed = { now }).refresh()
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), gateway.opens)
        gateway.onOpen = { now += 600 }
        val verification = WorkspaceVerification(listOf("a", "b", "c"))
        val sizes = mutableListOf<Int>()
        repeat(3) {
            WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 1_000, pageReader = reader,
                elapsed = { now }, verification = verification).refresh()
            sizes += verification.size
        }
        assertEquals(listOf(2, 1, 0), sizes)
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 2), gateway.opens)
    }

    private fun pages(vararg text: String) = text.joinToString(FakePageReader.PAGE_BREAK)

    private fun fixture(): Triple<WorkspaceStore, FakeWorkspaceGateway, FakePageReader> {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        return Triple(store, FakeWorkspaceGateway(), FakePageReader())
    }

    private fun indexer(store: WorkspaceStore, gateway: FakeWorkspaceGateway, reader: FakePageReader,
        verification: WorkspaceVerification? = null) = WorkspaceIndexer(store, gateway, clock = { 1_000 },
        checkTimeoutMs = 10_000, pageReader = reader, elapsed = { 0L }, verification = verification)

    /** Writes a version 2 index for one PDF whose metadata matches the gateway's file. */
    private fun legacyFixture(status: String, pagesRead: Int, textPages: List<Int>,
        pageText: List<String>): Triple<File, FakeWorkspaceGateway, FakePageReader> {
        val directory = temporary.newFolder()
        WorkspaceStore(directory).selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("orion", "orion.pdf", pages(*pageText.toTypedArray()))
        val size = gateway.entries.getValue("orion").sizeBytes
        val chunks = textPages.mapIndexed { ordinal, page ->
            """{"ordinal":$ordinal,"text":"${pageText[page - 1]}","heading":"","paragraph":$ordinal,"page":$page,"visual":false}"""
        }.joinToString(",")
        File(directory, "workspace-index.json").writeText(
            """{"version":2,"generation":1,"indexedAtMs":1000,"skippedFiles":0,"documents":[{"id":"orion",""" +
                """"name":"orion.pdf","path":"orion.pdf","modifiedAtMs":10,"sizeBytes":$size,"type":"PDF",""" +
                """"status":"$status","pagesRead":$pagesRead,"chunks":[$chunks]}]}""")
        return Triple(directory, gateway, FakePageReader())
    }
}
