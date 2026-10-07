package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class WorkspaceIndexerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `unchanged files never reopen while changed added renamed and removed files use the diff`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("a", "notice.txt", "The notice is two months.")
        gateway.put("b", "leave.txt", "Paid leave is 25 days.")
        indexer.refresh()
        indexer.refresh()
        assertEquals(mapOf("a" to 1, "b" to 1), gateway.opens)
        gateway.put("a", "notice.txt", "The notice is three months.", modified = 20)
        gateway.entries["b"] = gateway.entries.getValue("b").copy(name = "renamed.txt")
        gateway.put("c", "new.md", "# Policy\nAdditional rules.")
        indexer.refresh()
        assertEquals(mapOf("a" to 2, "b" to 1, "c" to 1), gateway.opens)
        assertTrue(store.snapshot().retriever!!.search("notice").excerpts.contains("three months"))
        assertEquals("renamed.txt", store.snapshot().state.index!!.documents.first { it.entry.documentId == "b" }.entry.name)
        gateway.entries.remove("a")
        indexer.refresh()
        assertEquals("", store.snapshot().retriever!!.search("notice").excerpts)
        assertEquals(2, store.snapshot().state.index!!.fileCount)
    }

    @Test
    fun `unknown metadata unsupported types and virtual documents are not read`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("unknown", "unknown.txt", "Notice unavailable.")
        gateway.entries["unknown"] = gateway.entries.getValue("unknown").copy(modifiedAtMs = null)
        gateway.put("pdf", "contract.pdf", "Should never be read.")
        gateway.put("virtual", "virtual.txt", "Should never be read.")
        gateway.entries["virtual"] = gateway.entries.getValue("virtual").copy(virtual = true)
        indexer.refresh()
        indexer.refresh()
        assertTrue(gateway.opens.isEmpty())
        assertEquals(0, store.snapshot().state.index!!.chunkCount)
        assertEquals(3, store.snapshot().state.index!!.skippedFiles)
    }

    @Test
    fun `revoked permission and removed root clear the cache without throwing`() = runBlocking {
        for (revoke in listOf(true, false)) {
            val (store, gateway, indexer) = fixture()
            gateway.put("a", "notice.txt", "Notice is two months.")
            indexer.refresh()
            if (revoke) gateway.granted = false else gateway.rootExists = false
            indexer.refresh()
            assertNull(store.snapshot().state.index)
            assertNull(store.snapshot().retriever)
            assertEquals(WorkspaceProblem.FOLDER_UNAVAILABLE, store.snapshot().state.problem)
        }
    }

    @Test
    fun `Off during extraction cannot republish the cleared cache`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("a", "notice.txt", "Notice is two months.")
        gateway.onOpen = { store.setEnabled(false) }
        indexer.refresh()
        assertFalse(store.snapshot().state.settings.enabled)
        assertNull(store.snapshot().state.index)
    }

    @Test
    fun `partial enumeration preserves prior bytes but makes them unavailable for retrieval`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("a", "notice.txt", "Notice is two months.")
        indexer.refresh()
        val before = store.snapshot().state.index
        gateway.listFailure = true
        indexer.refresh()
        assertEquals(before, store.snapshot().state.index)
        assertFalse(store.snapshot().state.validated)
        assertEquals(WorkspaceProblem.CHECK_FAILED, store.snapshot().state.problem)
    }

    @Test
    fun `changed invalid contents remove old excerpts and unchanged failures are not retried`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("a", "notice.txt", "Notice is two months.")
        indexer.refresh()
        gateway.put("a", "notice.txt", "invalid\u0000binary", modified = 20)
        indexer.refresh()
        indexer.refresh()
        assertEquals(2, gateway.opens["a"])
        assertEquals("", store.snapshot().retriever!!.search("notice").excerpts)
        assertEquals(WorkspaceDocumentStatus.INVALID_TEXT, store.snapshot().state.index!!.documents.single().status)
    }

    @Test
    fun `tree and file caps stop unbounded scans and deterministically select files`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        repeat(110) { gateway.put("$it", "file${it.toString().padStart(3, '0')}.txt", "Notice number $it.") }
        indexer.refresh()
        assertEquals(100, store.snapshot().state.index!!.fileCount)
        assertEquals(10, store.snapshot().state.index!!.skippedFiles)
        assertEquals(100, gateway.opens.size)
        repeat(1_001) { gateway.put("overflow$it", "ignore$it.pdf", "unsupported") }
        indexer.refresh()
        assertEquals(WorkspaceProblem.CHECK_LIMIT, store.snapshot().state.problem)
        assertFalse(store.snapshot().state.validated)
    }

    @Test
    fun `a document changed during extraction cannot commit a misleading timestamp`() = runBlocking {
        val (store, gateway, indexer) = fixture()
        gateway.put("a", "notice.txt", "Notice is two months.")
        gateway.onOpen = { gateway.entries["a"] = gateway.entries.getValue("a").copy(modifiedAtMs = 30) }
        indexer.refresh()
        assertNull(store.snapshot().state.index)
        assertEquals(WorkspaceProblem.CHECK_LIMIT, store.snapshot().state.problem)
    }

    private fun fixture(): Triple<WorkspaceStore, FakeWorkspaceGateway, WorkspaceIndexer> {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        return Triple(store, gateway, WorkspaceIndexer(store, gateway, clock = { 1_000 }))
    }
}

internal class FakeWorkspaceGateway : WorkspaceDocumentGateway {
    var granted = true
    var rootExists = true
    var listFailure = false
    var onOpen: (() -> Unit)? = null
    val entries = linkedMapOf<String, WorkspaceEntry>()
    val contents = mutableMapOf<String, ByteArray>()
    val opens = mutableMapOf<String, Int>()
    val persistedFlags = mutableListOf<Pair<String, Int>>()
    val released = mutableListOf<String>()
    override fun hasReadGrant(treeUri: String) = granted
    override fun persistReadGrant(treeUri: String, returnedFlags: Int) { persistedFlags += treeUri to returnedFlags }
    override fun releaseReadGrant(treeUri: String) { released += treeUri }
    override suspend fun root(treeUri: String): WorkspaceEntry {
        if (!rootExists) throw FileNotFoundException()
        return WorkspaceEntry("root", "Documents", directory = true)
    }
    override suspend fun children(treeUri: String, documentId: String): List<WorkspaceEntry> {
        if (listFailure) error("fixture list failure")
        return if (documentId == "root") entries.values.toList() else emptyList()
    }
    override suspend fun metadata(treeUri: String, documentId: String) = entries.getValue(documentId)
    override suspend fun open(treeUri: String, documentId: String): InputStream {
        opens[documentId] = (opens[documentId] ?: 0) + 1
        onOpen?.invoke()
        return ByteArrayInputStream(contents.getValue(documentId))
    }
    fun put(id: String, name: String, text: String, modified: Long = 10) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        entries[id] = WorkspaceEntry(id, name, modifiedAtMs = modified, sizeBytes = bytes.size.toLong())
        contents[id] = bytes
    }
}
