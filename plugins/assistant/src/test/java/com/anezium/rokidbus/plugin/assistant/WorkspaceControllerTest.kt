package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException

class WorkspaceControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a failed verification request keeps the index and reports the storage error`() = runBlocking {
        val directory = temporary.newFolder()
        var fail = false
        val operations = object : AssistantAtomicFileOperations {
            override fun atomicReplace(source: java.io.File, target: java.io.File) {
                if (fail) throw java.io.IOException("fixture write failure")
                NioAssistantAtomicFileOperations.atomicReplace(source, target)
            }
            override fun replace(source: java.io.File, target: java.io.File) = error("Unexpected fallback")
        }
        val store = WorkspaceStore(directory, operations)
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway().apply { put("notice", "notice.txt", "Notice is two months.") }
        WorkspaceIndexer(store, gateway).refresh()
        val before = store.snapshot().state.index
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fail = true
            val controller = WorkspaceController(store, gateway, scope)
            controller.reindexNow()
            assertEquals(before, store.snapshot().state.index)
            assertEquals(WorkspaceProblem.STORE_FAILED, controller.state.value.workspace.problem)
            assertFalse(store.snapshot().state.settings.verificationRequested)
            assertEquals(WorkspaceStoreTest.TREE, WorkspaceStore(directory).snapshot().state.settings.treeUri)
        } finally { scope.cancel() }
    }

    @Test
    fun `folder selection persists only read flags and replacement releases the old grant`() = runBlocking {
        val fixture = fixture()
        try {
            val result = fixture.controller.chooseFolder(WorkspaceStoreTest.TREE, 3, enable = true)
            assertEquals(WorkspaceFolderResult.SELECTED, result)
            assertEquals(listOf(WorkspaceStoreTest.TREE to 1), fixture.gateway.persistedFlags)
            assertTrue(fixture.store.snapshot().state.settings.enabled)
            fixture.controller.chooseFolder(WorkspaceStoreTest.TREE + "2", 1, enable = true)
            assertEquals(listOf(WorkspaceStoreTest.TREE), fixture.gateway.released)
            assertEquals(WorkspaceStoreTest.TREE + "2", fixture.store.snapshot().state.settings.treeUri)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `unverified provider and missing read grant never persist or read documents`() = runBlocking {
        val fixture = fixture()
        try {
            assertEquals(WorkspaceFolderResult.LOCAL_FOLDER_REQUIRED,
                fixture.controller.chooseFolder("content://cloud.example/tree/a", 1, enable = true))
            assertEquals(WorkspaceFolderResult.NO_READ_GRANT,
                fixture.controller.chooseFolder(WorkspaceStoreTest.TREE, 2, enable = true))
            assertTrue(fixture.gateway.persistedFlags.isEmpty())
            assertEquals(0, fixture.gateway.rootCalls)
            assertEquals(1, fixture.gateway.localRootQueries)
            assertFalse(fixture.store.snapshot().state.settings.enabled)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `denied persisted access leaves the previous folder and index available`() = runBlocking {
        val fixture = fixture(seed = true)
        val before = fixture.store.snapshot()
        try {
            fixture.gateway.persistFailure = SecurityException("fixture denied grant")
            assertEquals(WorkspaceFolderResult.NO_READ_GRANT,
                fixture.controller.chooseFolder(WorkspaceStoreTest.TREE + "2", 1, enable = true))
            assertEquals(before, fixture.store.snapshot())
            assertTrue(fixture.gateway.persistedFlags.isEmpty())
            assertTrue(fixture.gateway.released.isEmpty())
            assertEquals(0, fixture.gateway.rootCalls)
            assertTrue(fixture.gateway.opens.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `failed replacement root reports access or missing folder and releases only its new grant`() = runBlocking {
        for ((error, expected) in listOf(
            SecurityException("fixture denied root") to WorkspaceFolderResult.NO_READ_GRANT,
            FileNotFoundException("fixture missing root") to WorkspaceFolderResult.UNAVAILABLE,
        )) {
            val fixture = fixture(seed = true)
            val before = fixture.store.snapshot()
            val replacement = WorkspaceStoreTest.TREE + "2"
            try {
                fixture.gateway.rootFailure = error
                assertEquals(expected, fixture.controller.chooseFolder(replacement, 1, enable = true))
                assertEquals(before, fixture.store.snapshot())
                assertEquals(listOf(replacement to 1), fixture.gateway.persistedFlags)
                assertEquals(listOf(replacement), fixture.gateway.released)
                assertTrue(fixture.gateway.opens.isEmpty())
            } finally { fixture.scope.cancel() }
        }
    }

    @Test
    fun `reselecting the current folder supersedes an automatic check already in progress`() = runBlocking {
        val fixture = fixture(seed = true)
        val checkStarted = CompletableDeferred<Unit>()
        val finishCheck = CompletableDeferred<Unit>()
        fixture.gateway.put("notice", "notice.txt", "Notice is two months.")
        fixture.gateway.onRoot = {
            when (fixture.gateway.rootCalls) {
                1 -> {
                    checkStarted.complete(Unit)
                    finishCheck.await()
                }
                2 -> {
                    finishCheck.complete(Unit)
                    fixture.controller.state.first { !it.checking }
                }
            }
        }
        try {
            fixture.controller.attach(Any())
            withTimeout(2_000) { checkStarted.await() }
            assertEquals(WorkspaceFolderResult.SELECTED,
                withTimeout(2_000) { fixture.controller.chooseFolder(WorkspaceStoreTest.TREE, 1, enable = true) })
            withTimeout(2_000) { fixture.controller.state.first { it.workspace.validated } }
            assertEquals(WorkspaceStoreTest.TREE, fixture.store.snapshot().state.settings.treeUri)
            assertEquals(1, fixture.store.snapshot().state.index?.chunkCount)
            assertTrue(fixture.gateway.released.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `third-party local roots are selected while missing flags and failed root queries keep the old folder`() = runBlocking {
        val fixture = fixture()
        val tree = "content://local.example.documents/tree/local"
        try {
            fixture.gateway.providerRoots[tree] = listOf(WorkspaceProviderRoot("device", "local", 2))
            assertEquals(WorkspaceFolderResult.SELECTED, fixture.controller.chooseFolder(tree, 3, enable = true))
            assertEquals(listOf(tree to 1), fixture.gateway.persistedFlags)
            assertEquals(tree, fixture.store.snapshot().state.settings.treeUri)
            for (failQuery in listOf(false, true)) {
                fixture.gateway.providerRoots[tree] = listOf(WorkspaceProviderRoot("device", "local", null))
                fixture.gateway.providerRootFailure = failQuery
                assertEquals(WorkspaceFolderResult.LOCAL_FOLDER_REQUIRED,
                    fixture.controller.chooseFolder(tree, 1, enable = true))
                assertEquals(tree, fixture.store.snapshot().state.settings.treeUri)
                assertEquals(listOf(tree to 1), fixture.gateway.persistedFlags)
                assertEquals(1, fixture.gateway.rootCalls)
                assertTrue(fixture.gateway.opens.isEmpty())
            }
            assertEquals(3, fixture.gateway.localRootQueries)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `search validates root and grants without opening source content`() = runBlocking {
        val fixture = fixture(seed = true)
        try {
            val context = fixture.controller.contextForQuestion("how many months", "Memory stays unchanged.")
            assertTrue(context.excerpts.contains("two months"))
            assertTrue(fixture.controller.isCurrent(context))
            assertTrue(fixture.gateway.opens.isEmpty())
            fixture.gateway.granted = false
            assertTrue(context.turn!!.search("notice", null) is WorkspaceToolOutcome.Failure)
            assertNull(fixture.controller.contextForQuestion("notice", "").turn)
            assertNull(fixture.store.snapshot().state.index)
            assertEquals(WorkspaceProblem.FOLDER_UNAVAILABLE, fixture.controller.state.value.workspace.problem)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `removed root slow root and Off fail closed for an already prepared request`() = runBlocking {
        for (failure in listOf("removed", "slow", "off")) {
            val fixture = fixture(seed = true)
            try {
                val context = fixture.controller.contextForQuestion("notice", "")
                when (failure) {
                    "removed" -> fixture.gateway.rootExists = false
                    "slow" -> fixture.gateway.rootDelayMs = 250
                    else -> fixture.controller.setEnabled(false)
                }
                assertTrue(failure, context.turn!!.search("notice", null) is WorkspaceToolOutcome.Failure)
                assertFalse(failure, fixture.controller.isCurrent(context))
                if (failure == "slow") {
                    assertEquals(WorkspaceProblem.CHECK_FAILED, fixture.store.snapshot().state.problem)
                } else assertNull(fixture.store.snapshot().state.index)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test
    fun `active owners alone keep observation and index work alive`() = runBlocking {
        val fixture = fixture(seed = true)
        try {
            val first = Any()
            val second = Any()
            fixture.controller.attach(first)
            fixture.controller.attach(second)
            assertEquals(1, fixture.gateway.activeObservers)
            fixture.controller.detach(first)
            assertEquals(1, fixture.gateway.activeObservers)
            fixture.controller.detach(second)
            assertEquals(0, fixture.gateway.activeObservers)
            fixture.gateway.changed?.invoke()
            delay(300)
            assertTrue(fixture.gateway.opens.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `changed index invalidates prepared excerpts but an unchanged check keeps them valid`() = runBlocking {
        val fixture = fixture(seed = true)
        try {
            val context = fixture.controller.contextForQuestion("notice", "")
            val previous = fixture.store.snapshot().state.index!!
            fixture.store.publish(previous.copy(indexedAtMs = 2_000))
            assertTrue(fixture.controller.isCurrent(context))
            fixture.store.publish(previous.copy(documents = emptyList(), indexedAtMs = 3_000))
            assertFalse(fixture.controller.isCurrent(context))
        } finally { fixture.scope.cancel() }
    }

    private data class Fixture(val store: WorkspaceStore, val gateway: FakeWorkspaceGateway,
        val scope: CoroutineScope, val controller: WorkspaceController)

    private fun fixture(seed: Boolean = false): Fixture {
        val store = WorkspaceStore(temporary.newFolder())
        val gateway = FakeWorkspaceGateway()
        if (seed) {
            store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
            store.publish(WorkspaceIndex(store.snapshot().state.settings.generation,
                listOf(WorkspaceDocument(WorkspaceEntry("notice", "notice.txt", modifiedAtMs = 10, sizeBytes = 20),
                    listOf(WorkspaceChunk(0, "Notice is two months.")))), 1_000))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return Fixture(store, gateway, scope, WorkspaceController(store, gateway, scope))
    }
}
