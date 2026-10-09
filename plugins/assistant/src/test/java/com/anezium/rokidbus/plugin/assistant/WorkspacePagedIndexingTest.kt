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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePagedIndexingTest {
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
    fun `pages showing a visual are marked in their citation`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("pdf", "sales.pdf", pages("Quarterly sales summary.", "Sales by region chart ${FakePageReader.VISUAL}"))
        indexer.refresh()
        val chunks = store.snapshot().state.index!!.documents.single().chunks
        assertEquals(listOf(false, true), chunks.map { it.visual })
        assertEquals("Sales by region chart", chunks.last().text)
        val excerpts = store.snapshot().retriever!!.search("sales region").excerpts
        assertTrue(excerpts.contains("sales.pdf › page 2${WorkspaceRetriever.VISUAL_MARK}\n"))
        assertFalse(excerpts.contains("page 1${WorkspaceRetriever.VISUAL_MARK}"))
        val restored = WorkspaceIndexJson.parse(WorkspaceIndexJson.render(store.snapshot().state.index!!))
        assertEquals(listOf(false, true), restored.documents.single().chunks.map { it.visual })
    }

    @Test
    fun `an index stored before pages existed still loads`() {
        val legacy = """{"version":1,"generation":0,"indexedAtMs":1000,"skippedFiles":0,"documents":[{"id":"a",""" +
            """"name":"notice.txt","path":"notice.txt","modifiedAtMs":10,"sizeBytes":5,"type":"TEXT",""" +
            """"status":"INDEXED","chunks":[{"ordinal":0,"text":"Notice","heading":"","paragraph":0}]}]}"""
        val document = WorkspaceIndexJson.parse(legacy).documents.single()
        assertEquals(0, document.chunks.single().page)
        assertEquals(0, document.pagesRead)
        val withPdf = legacy.replace("]}]}", """]},{"id":"b","name":"chart.pdf","path":"chart.pdf",""" +
            """"modifiedAtMs":10,"sizeBytes":5,"type":"PDF","status":"INDEXED",""" +
            """"chunks":[{"ordinal":0,"text":"Chart","heading":"","paragraph":0,"page":1}]}]}""")
        assertEquals(listOf("notice.txt"), WorkspaceIndexJson.parse(withPdf).documents.map { it.entry.name })
    }

    @Test
    fun `images are recognized into searchable text cited by file name`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("receipt", "receipt.JPG", "Hotel Bellecour total 184 euros")
        gateway.put("blank", "sunset.png", "")
        indexer.refresh()
        val statuses = store.snapshot().state.index!!.documents.associate { it.entry.name to it.status }
        assertEquals(WorkspaceDocumentStatus.INDEXED, statuses["receipt.JPG"])
        assertEquals(WorkspaceDocumentStatus.NO_TEXT, statuses["sunset.png"])
        val excerpts = store.snapshot().retriever!!.search("hotel total").excerpts
        assertTrue(excerpts.contains("[1] receipt.JPG${WorkspaceRetriever.VISUAL_MARK}\n"))
    }

    @Test
    fun `protected and textless files are reported without chunks`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("locked", "locked.pdf", FakePageReader.LOCKED)
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
    fun `control characters from recognized text never reach an excerpt`() = runBlocking {
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
    fun `slow files are deferred to a later pass instead of failing the check`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePageReader { now += 400 }) { now }
        gateway.put("a", "a.pdf", pages("Alpha notice."))
        gateway.put("b", "b.pdf", pages("Bravo notice."))
        gateway.put("c", "c.png", "Charlie notice.")
        gateway.put("d", "d.txt", "Delta notice.")
        indexer.refresh()
        val first = store.snapshot().state.index!!.documents.associate { it.entry.name to it.status }
        assertEquals(WorkspaceDocumentStatus.INDEXED, first["a.pdf"])
        assertEquals(WorkspaceDocumentStatus.INDEXED, first["b.pdf"])
        assertEquals(WorkspaceDocumentStatus.PENDING, first["c.png"])
        assertEquals(WorkspaceDocumentStatus.INDEXED, first["d.txt"])
        indexer.refresh()
        assertTrue(store.snapshot().state.index!!.documents.all { it.status == WorkspaceDocumentStatus.INDEXED })
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1, "d" to 1), gateway.opens)
    }

    @Test
    fun `a long scan resumes after its last read page and stays searchable meanwhile`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePageReader { now += 600 }) { now }
        gateway.put("scan", "minutes.pdf", pages("Budget approved.", "Venue is Lyon.", "Next meeting in May."))
        indexer.refresh()
        var document = store.snapshot().state.index!!.documents.single()
        assertEquals(WorkspaceDocumentStatus.PENDING, document.status)
        assertEquals(1, document.pagesRead)
        assertTrue(store.snapshot().retriever!!.search("budget").excerpts.contains("minutes.pdf › page 1"))
        val epoch = store.snapshot().epoch
        indexer.refresh()
        indexer.refresh()
        document = store.snapshot().state.index!!.documents.single()
        assertEquals(WorkspaceDocumentStatus.INDEXED, document.status)
        assertEquals(listOf(1, 2, 3), document.chunks.map { it.page })
        assertEquals(listOf(0, 1, 2), document.chunks.map { it.ordinal })
        assertEquals(epoch, store.snapshot().epoch)
        assertEquals(3, gateway.opens["scan"])
    }

    @Test
    fun `a file whose opening outlasts the pass budget still reads a page each pass`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePageReader(beforeLoad = { now += 900 }, beforePage = { now += 10 })) { now }
        gateway.put("slow", "a-slow.pdf", pages("Alpha.", "Bravo."))
        gateway.put("next", "b-next.pdf", pages("Charlie."))
        indexer.refresh()
        val first = store.snapshot().state.index!!.documents.associateBy { it.entry.name }
        assertEquals(WorkspaceDocumentStatus.PENDING, first.getValue("a-slow.pdf").status)
        assertEquals(1, first.getValue("a-slow.pdf").pagesRead)
        assertEquals(WorkspaceDocumentStatus.PENDING, first.getValue("b-next.pdf").status)
        indexer.refresh()
        indexer.refresh()
        assertTrue(store.snapshot().state.index!!.documents.all { it.status == WorkspaceDocumentStatus.INDEXED })
    }

    @Test
    fun `a later file running past the pass deadline keeps the pages it read`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePageReader { now += 100 }) { now }
        gateway.put("a", "a.pdf", pages("Alpha one.", "Alpha two.", "Alpha three."))
        gateway.put("b", "b.pdf", pages("Bravo one.", "Bravo two.", "Bravo three."))
        indexer.refresh()
        val b = store.snapshot().state.index!!.documents.single { it.entry.name == "b.pdf" }
        assertEquals(WorkspaceDocumentStatus.PENDING, b.status)
        assertEquals(listOf(1, 2), b.chunks.map { it.page })
        indexer.refresh()
        val done = store.snapshot().state.index!!.documents.single { it.entry.name == "b.pdf" }
        assertEquals(WorkspaceDocumentStatus.INDEXED, done.status)
        assertEquals(listOf(1, 2, 3), done.chunks.map { it.page })
        assertEquals(1, gateway.opens["a"])
    }

    @Test
    fun `a changed pending file restarts from its first page and invalidates answers`() = runBlocking {
        var now = 0L
        val (store, gateway, indexer) = fixture(FakePageReader { now += 600 }) { now }
        gateway.put("scan", "minutes.pdf", pages("Budget approved.", "Venue is Lyon."))
        indexer.refresh()
        val epoch = store.snapshot().epoch
        gateway.put("scan", "minutes.pdf", pages("Budget rejected.", "Venue is Lyon."), modified = 20)
        indexer.refresh()
        val document = store.snapshot().state.index!!.documents.single()
        assertEquals(listOf("Budget rejected."), document.chunks.map { it.text })
        assertNotEquals(epoch, store.snapshot().epoch)
    }

    @Test
    fun `only withdrawn or altered excerpts move the answer epoch`() {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val generation = store.snapshot().state.settings.generation
        fun document(id: String, vararg text: String) = WorkspaceDocument(
            WorkspaceEntry(id, "$id.txt", modifiedAtMs = 10, sizeBytes = 10),
            text.mapIndexed { ordinal, value -> WorkspaceChunk(ordinal, value) })
        fun publish(vararg documents: WorkspaceDocument) =
            store.publish(WorkspaceIndex(generation, documents.toList(), 1_000))

        publish(document("a", "Alpha."))
        val start = store.snapshot()
        publish(document("a", "Alpha.", "More alpha."), document("b", "Bravo."))
        assertEquals(start.epoch, store.snapshot().epoch)
        assertNotEquals(start.revision, store.snapshot().revision)
        publish(document("a", "Alpha.", "More alpha."))
        assertEquals(start.epoch + 1, store.snapshot().epoch)
        publish(document("a", "Changed alpha."))
        assertEquals(start.epoch + 2, store.snapshot().epoch)
    }

    @Test
    fun `the controller keeps reading until no file is pending`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("a", "a.pdf", pages("Notice a.", "Notice a two.", "Notice a three."))
        gateway.put("b", "b.pdf", pages("Notice b."))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 2_000,
            pageReader = FakePageReader { Thread.sleep(600) })
        try {
            controller.attach(Any())
            withTimeout(15_000) {
                while (store.snapshot().state.index?.documents?.all { it.status == WorkspaceDocumentStatus.INDEXED } != true) {
                    delay(20)
                }
            }
            withTimeout(1_000) { while (controller.state.value.checking) delay(10) }
            assertEquals(4, store.snapshot().state.index!!.chunkCount)
            assertFalse(controller.state.value.checking)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `without a page reader pdfs and images are unreadable rather than opened`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("pdf", "contract.pdf", pages("Notice."))
        gateway.put("image", "photo.webp", "Notice.")
        WorkspaceIndexer(store, gateway, clock = { 1_000 }).refresh()
        assertTrue(store.snapshot().state.index!!.documents.all { it.status == WorkspaceDocumentStatus.UNREADABLE })
        assertTrue(gateway.opens.isEmpty())
    }

    private fun pages(vararg text: String) = text.joinToString(FakePageReader.PAGE_BREAK)

    private fun fixture(
        reader: WorkspacePageReader = FakePageReader(),
        elapsed: () -> Long = { 0L },
    ): Triple<WorkspaceStore, FakeWorkspaceGateway, WorkspaceIndexer> {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        return Triple(store, gateway, WorkspaceIndexer(store, gateway, clock = { 1_000 }, checkTimeoutMs = 1_000,
            pageReader = reader, elapsed = elapsed))
    }
}

/**
 * Reads the fixture format: a PDF's pages separated by form feeds, an image's recognized text as is,
 * and [LOCKED] standing for a password.
 */
internal class FakePageReader(
    private val beforeLoad: () -> Unit = {},
    private val beforePage: () -> Unit = {},
) : WorkspacePageReader {
    override fun read(
        type: WorkspaceFileType,
        bytes: ByteArray,
        firstPage: Int,
        shouldStop: (characters: Int) -> Boolean,
    ): WorkspacePagedText {
        val text = bytes.toString(Charsets.UTF_8)
        if (text == LOCKED) throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        beforeLoad()
        val all = if (type == WorkspaceFileType.IMAGE) listOf(text) else text.split(PAGE_BREAK)
        val pages = mutableListOf<String>()
        val visual = mutableSetOf<Int>()
        var characters = 0
        for (page in all.drop(firstPage - 1)) {
            if (shouldStop(characters)) return WorkspacePagedText(pages, complete = false, visual)
            beforePage()
            if (type == WorkspaceFileType.IMAGE || VISUAL in page) visual += pages.size
            pages += page.replace(VISUAL, "")
            characters += page.length
        }
        return WorkspacePagedText(pages, complete = true, visual)
    }

    override fun render(type: WorkspaceFileType, bytes: ByteArray, page: Int): ByteArray? {
        val text = bytes.toString(Charsets.UTF_8)
        val all = if (type == WorkspaceFileType.IMAGE) listOf(text) else text.split(PAGE_BREAK)
        return all.getOrNull(page - 1)?.let { "jpeg:$type:$page:$it".toByteArray() }
    }

    companion object {
        const val PAGE_BREAK = "\u000C"
        const val LOCKED = "locked"
        const val VISUAL = "[visual]"
    }
}
