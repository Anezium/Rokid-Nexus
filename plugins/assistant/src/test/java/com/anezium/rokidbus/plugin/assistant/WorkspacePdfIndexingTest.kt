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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePdfIndexingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `pdf pages become cited chunks that survive the stored index`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("pdf", "contract.pdf", pages("Cover page.", "The notice period is three months.\n\nSigned in Paris."))
        indexer.refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(WorkspaceDocumentStatus.INDEXED, document.status)
        assertEquals(listOf(1, 2), document.chunks.map { it.page })
        assertEquals(listOf(0, 1), document.chunks.map { it.ordinal })
        val excerpts = store.snapshot().retriever!!.search("notice period").excerpts
        assertTrue(excerpts.contains("[1] contract.pdf › page 2\n"))
        val restored = WorkspaceIndexJson.parse(WorkspaceIndexJson.render(store.snapshot().state.index!!))
        assertEquals(listOf(1, 2), restored.documents.single().chunks.map { it.page })
    }

    @Test
    fun `an index stored before pages existed still loads`() {
        val legacy = """{"version":1,"generation":0,"indexedAtMs":1000,"skippedFiles":0,"documents":[{"id":"a",""" +
            """"name":"notice.txt","path":"notice.txt","modifiedAtMs":10,"sizeBytes":5,"type":"TEXT",""" +
            """"status":"INDEXED","chunks":[{"ordinal":0,"text":"Notice","heading":"","paragraph":0}]}]}"""
        assertEquals(0, WorkspaceIndexJson.parse(legacy).documents.single().chunks.single().page)
    }

    @Test
    fun `protected and textless pdfs are reported without chunks`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("locked", "locked.pdf", FakePdfReader.LOCKED)
        gateway.put("scan", "scan.pdf", pages("", "  \n "))
        gateway.put("notes", "notes.txt", "Notice is two months.")
        indexer.refresh()
        val index = store.snapshot().state.index!!
        val statuses = index.documents.associate { it.entry.name to it.status }
        assertEquals(WorkspaceDocumentStatus.PROTECTED, statuses["locked.pdf"])
        assertEquals(WorkspaceDocumentStatus.NO_TEXT, statuses["scan.pdf"])
        assertEquals(1, index.fileCount)
        assertEquals(2, index.skippedFiles)
    }

    @Test
    fun `control characters from a pdf text layer never reach an excerpt`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("pdf", "odd.pdf", pages("Notice\u0000period​is two months."))
        indexer.refresh()
        val text = store.snapshot().state.index!!.documents.single().chunks.single().text
        assertEquals("Notice period is two months.", text)
    }

    @Test
    fun `a pdf past the file character cap is truncated at a page boundary`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        val page = List(WorkspaceLimits.MAX_FILE_CHARS / 2 / 10) { "notice%04d".format(it) }.joinToString(" ")
        gateway.put("pdf", "long.pdf", pages(page, page, page, page))
        indexer.refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(WorkspaceDocumentStatus.TRUNCATED, document.status)
        assertTrue(document.chunks.sumOf { it.text.length } <= WorkspaceLimits.MAX_FILE_CHARS)
        assertEquals(setOf(1, 2), document.chunks.map { it.page }.toSet())
    }

    @Test
    fun `slow pdfs are deferred to a later pass instead of failing the check`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePdfReader { now += 400 }) { now }
        gateway.put("a", "a.pdf", pages("Alpha notice."))
        gateway.put("b", "b.pdf", pages("Bravo notice."))
        gateway.put("c", "c.pdf", pages("Charlie notice."))
        gateway.put("d", "d.txt", "Delta notice.")
        indexer.refresh()
        val first = store.snapshot().state.index!!.documents.associate { it.entry.name to it.status }
        assertEquals(WorkspaceDocumentStatus.INDEXED, first["a.pdf"])
        assertEquals(WorkspaceDocumentStatus.INDEXED, first["b.pdf"])
        assertEquals(WorkspaceDocumentStatus.PENDING, first["c.pdf"])
        assertEquals(WorkspaceDocumentStatus.INDEXED, first["d.txt"])
        indexer.refresh()
        assertTrue(store.snapshot().state.index!!.documents.all { it.status == WorkspaceDocumentStatus.INDEXED })
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1, "d" to 1), gateway.opens)
    }

    @Test
    fun `a later pdf running past the pass deadline is retried while the first one is kept truncated`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePdfReader { now += 100 }) { now }
        gateway.put("a", "a.pdf", pages("Alpha one.", "Alpha two.", "Alpha three."))
        gateway.put("b", "b.pdf", pages("Bravo one.", "Bravo two.", "Bravo three."))
        indexer.refresh()
        val documents = store.snapshot().state.index!!.documents.associateBy { it.entry.name }
        assertEquals(WorkspaceDocumentStatus.INDEXED, documents.getValue("a.pdf").status)
        assertEquals(3, documents.getValue("a.pdf").chunks.size)
        assertEquals(WorkspaceDocumentStatus.PENDING, documents.getValue("b.pdf").status)
        assertTrue(documents.getValue("b.pdf").chunks.isEmpty())

        now = 0
        val (slowStore, slowGateway, slowIndexer) = fixture(FakePdfReader { now += 600 }) { now }
        slowGateway.put("a", "a.pdf", pages("Alpha one.", "Alpha two.", "Alpha three."))
        slowIndexer.refresh()
        val slow = slowStore.snapshot().state.index!!.documents.single()
        assertEquals(WorkspaceDocumentStatus.TRUNCATED, slow.status)
        assertEquals(listOf(1), slow.chunks.map { it.page })
    }

    @Test
    fun `the controller keeps checking until no pdf is pending`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        listOf("a", "b", "c").forEach { gateway.put(it, "$it.pdf", pages("Notice $it.")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 2_000,
            pdfReader = FakePdfReader { Thread.sleep(600) })
        try {
            controller.attach(Any())
            withTimeout(10_000) {
                while (store.snapshot().state.index?.documents?.all { it.status == WorkspaceDocumentStatus.INDEXED } != true) {
                    delay(20)
                }
            }
            withTimeout(1_000) { while (controller.state.value.checking) delay(10) }
            assertEquals(3, store.snapshot().state.index!!.fileCount)
            assertFalse(controller.state.value.checking)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `without a pdf reader pdfs are unreadable rather than opened`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("pdf", "contract.pdf", pages("Notice."))
        WorkspaceIndexer(store, gateway, clock = { 1_000 }).refresh()
        assertEquals(WorkspaceDocumentStatus.UNREADABLE, store.snapshot().state.index!!.documents.single().status)
        assertTrue(gateway.opens.isEmpty())
    }

    private fun pages(vararg text: String) = text.joinToString(FakePdfReader.PAGE_BREAK)

    private fun fixture(
        reader: WorkspacePdfReader = FakePdfReader(),
        elapsed: () -> Long = { 0L },
    ): Triple<WorkspaceStore, FakeWorkspaceGateway, WorkspaceIndexer> {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        return Triple(store, gateway, WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 1_000,
            pdfReader = reader, elapsed = elapsed))
    }
}

/** Reads the fixture format: pages separated by form feeds, [LOCKED] standing for a password. */
internal class FakePdfReader(private val beforePage: () -> Unit = {}) : WorkspacePdfReader {
    override fun read(bytes: ByteArray, shouldStop: (characters: Int) -> Boolean): WorkspacePagedText {
        val text = bytes.toString(Charsets.UTF_8)
        if (text == LOCKED) throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        val pages = mutableListOf<String>()
        var characters = 0
        for (page in text.split(PAGE_BREAK)) {
            if (shouldStop(characters)) return WorkspacePagedText(pages, complete = false)
            beforePage()
            pages += page
            characters += page.length
        }
        return WorkspacePagedText(pages, complete = true)
    }

    companion object {
        const val PAGE_BREAK = "\u000C"
        const val LOCKED = "locked"
    }
}
