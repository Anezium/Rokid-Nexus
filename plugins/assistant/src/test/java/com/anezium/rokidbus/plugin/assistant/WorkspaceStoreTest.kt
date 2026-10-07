package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WorkspaceStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `settings URI and private chunks survive restart but need fresh access validation`() {
        val directory = temporary.newFolder()
        val store = WorkspaceStore(directory)
        store.selectTree(TREE, "Documents", enable = true)
        assertTrue(store.publish(index(store)))
        val reopened = WorkspaceStore(directory).snapshot()
        assertEquals(TREE, reopened.state.settings.treeUri)
        assertEquals(1, reopened.state.index?.fileCount)
        assertFalse(reopened.state.validated)
        assertTrue(reopened.retriever!!.search("notice").excerpts.contains("two months"))
    }

    @Test
    fun `Off removes index and temporary data and rejects the old generation`() {
        val directory = temporary.newFolder()
        val store = WorkspaceStore(directory)
        store.selectTree(TREE, "Documents", enable = true)
        val candidate = index(store)
        store.publish(candidate)
        File(directory, ".workspace-index.json.tmp").writeText("private pending data")
        store.setEnabled(false)
        assertNull(store.snapshot().state.index)
        assertNull(store.snapshot().retriever)
        assertFalse(store.publish(candidate))
        assertEquals(listOf("workspace-settings.json"), directory.list()!!.toList())
        val reopened = WorkspaceStore(directory).snapshot().state
        assertFalse(reopened.settings.enabled)
        assertEquals(TREE, reopened.settings.treeUri)
        assertNull(reopened.index)
    }

    @Test
    fun `folder replacement and revocation cannot expose the previous tree`() {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(TREE, "Documents", enable = true)
        val candidate = index(store)
        store.publish(candidate)
        store.selectTree(TREE + "2", "Other")
        assertNull(store.snapshot().state.index)
        assertFalse(store.publish(candidate))
        val generation = store.snapshot().state.settings.generation
        store.folderUnavailable(generation)
        assertEquals(WorkspaceProblem.FOLDER_UNAVAILABLE, store.snapshot().state.problem)
        assertFalse(store.isCurrent(generation))
        assertEquals(TREE + "2", store.snapshot().state.settings.treeUri)
    }

    @Test
    fun `corrupt oversized and mismatched indexes fail closed`() {
        for (content in listOf("not json", "x".repeat(WorkspaceLimits.MAX_INDEX_BYTES + 1))) {
            val directory = temporary.newFolder()
            WorkspaceStore(directory).selectTree(TREE, "Documents", enable = true)
            File(directory, "workspace-index.json").writeText(content)
            val state = WorkspaceStore(directory).snapshot().state
            assertNull(state.index)
            assertFalse(state.validated)
            assertFalse(File(directory, "workspace-index.json").exists())
        }
        val directory = temporary.newFolder()
        val store = WorkspaceStore(directory)
        store.selectTree(TREE, "Documents", enable = true)
        File(directory, "workspace-index.json").writeText(WorkspaceIndexJson.render(index(store).copy(generation = 99)))
        assertNull(WorkspaceStore(directory).snapshot().state.index)
    }

    @Test
    fun `failed atomic replacement preserves the committed index`() {
        val directory = temporary.newFolder()
        var fail = false
        val operations = object : AssistantAtomicFileOperations {
            override fun atomicReplace(source: File, target: File) {
                if (fail) throw IOException("fixture failure")
                NioAssistantAtomicFileOperations.atomicReplace(source, target)
            }
            override fun replace(source: File, target: File) = error("Unexpected non-atomic fallback")
        }
        val store = WorkspaceStore(directory, operations)
        store.selectTree(TREE, "Documents", enable = true)
        val initial = index(store)
        store.publish(initial)
        fail = true
        assertTrue(runCatching { store.publish(initial.copy(indexedAtMs = 2_000)) }.isFailure)
        assertEquals(initial, WorkspaceIndexJson.parse(File(directory, "workspace-index.json").readText()))
        assertFalse(File(directory, ".workspace-index.json.tmp").exists())
    }

    @Test
    fun `only platform local tree URIs are accepted`() {
        assertTrue(isLocalWorkspaceTree(TREE))
        for (uri in listOf("https://example.com/tree/a", "content://cloud.provider/tree/a",
            "content://com.android.externalstorage.documents/document/a", TREE + "?remote=true",
            "content://user@com.android.externalstorage.documents/tree/a")) {
            assertFalse(uri, isLocalWorkspaceTree(uri))
        }
    }

    private fun index(store: WorkspaceStore) = WorkspaceIndex(
        store.snapshot().state.settings.generation,
        listOf(WorkspaceDocument(WorkspaceEntry("file", "notice.txt", modifiedAtMs = 10, sizeBytes = 30),
            listOf(WorkspaceChunk(0, "Notice is two months.")))), 1_000,
    )

    companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    }
}
