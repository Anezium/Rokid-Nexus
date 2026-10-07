package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceControllerTest {
    @get:Rule val temporary = TemporaryFolder()

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
            val context = fixture.controller.contextForQuestion("notice", "Memory stays unchanged.")
            assertTrue(context.excerpts.contains("two months"))
            assertTrue(fixture.controller.isCurrent(context))
            assertTrue(fixture.gateway.opens.isEmpty())
            fixture.gateway.granted = false
            assertNull(fixture.controller.search("notice"))
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
                assertNull(failure, fixture.controller.search("notice"))
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
