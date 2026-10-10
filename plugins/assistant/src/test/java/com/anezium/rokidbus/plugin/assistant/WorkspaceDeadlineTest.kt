package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceDeadlineTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `selection returns check failed while roots or relationship ignore cancellation`() = runBlocking {
        for (blockRoots in listOf(false, true)) {
            val store = WorkspaceStore(temporary.newFolder())
            val gateway = BlockingGateway(blockRoots)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 200)
            try {
                val started = System.nanoTime()
                assertEquals(WorkspaceFolderResult.CHECK_FAILED, controller.chooseFolder(BLOCKED, 1, true))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
                assertEquals(0L, gateway.entered.count)
                assertEquals(1L, gateway.release.count)
                withTimeout(1_000) { while (gateway.cancelled.get() == 0) delay(5) }
                assertEquals(WorkspaceProblem.CHECK_FAILED, controller.state.value.workspace.problem)
                assertFalse(controller.state.value.checking)
                assertTrue(gateway.delegate.persistedFlags.isEmpty())
                assertEquals(WorkspaceFolderResult.SELECTED, controller.chooseFolder(HEALTHY, 1, true))
                assertEquals(HEALTHY, store.snapshot().state.settings.treeUri)
                assertEquals(1L, gateway.release.count)
            } finally { scope.cancel(); gateway.close() }
        }
    }

    @Test
    fun `stalled indexing releases the check mutex and a later selection can index`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(BLOCKED, "Blocked", true)
        val gateway = BlockingGateway()
        gateway.delegate.put("notice", "notice.txt", "Notice is two months.")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 200)
        try {
            controller.attach(Any())
            withTimeout(1_000) { while (gateway.entered.count != 0L) delay(5) }
            assertTrue(controller.state.value.checking)
            withTimeout(1_000) { while (controller.state.value.checking) delay(5) }
            assertEquals(WorkspaceProblem.CHECK_LIMIT, controller.state.value.workspace.problem)
            assertEquals(1L, gateway.release.count)
            assertEquals(WorkspaceFolderResult.SELECTED, controller.chooseFolder(HEALTHY, 1, true))
            withTimeout(1_000) {
                while (!controller.state.value.workspace.validated || controller.state.value.checking) delay(5)
            }
            assertEquals(HEALTHY, store.snapshot().state.settings.treeUri)
            assertEquals(1, store.snapshot().state.index!!.chunkCount)
            assertEquals(1L, gateway.release.count)
            gateway.release.countDown()
            withTimeout(1_000) { while (gateway.returned.count != 0L) delay(5) }
            assertEquals(HEALTHY, store.snapshot().state.settings.treeUri)
            assertEquals(1, store.snapshot().state.index!!.chunkCount)
        } finally { scope.cancel(); gateway.close() }
    }

    @Test
    fun `new selection cancels the old check and drops its delayed local result`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        val gateway = BlockingGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope, checkTimeoutMs = 2_000)
        try {
            val old = async { controller.chooseFolder(BLOCKED, 1, true) }
            withTimeout(1_000) { while (gateway.entered.count != 0L) delay(5) }
            assertEquals(WorkspaceFolderResult.SELECTED, controller.chooseFolder(HEALTHY, 1, true))
            try { old.await(); error("Expected the old selection to be cancelled") }
            catch (_: CancellationException) { }
            gateway.release.countDown()
            withTimeout(1_000) { while (gateway.returned.count != 0L) delay(5) }
            assertEquals(HEALTHY, store.snapshot().state.settings.treeUri)
            assertEquals(listOf(HEALTHY to 1), gateway.delegate.persistedFlags)
            assertEquals(1, gateway.delegate.rootCalls)
        } finally { scope.cancel(); gateway.close() }
    }

    @Test
    fun `selection cannot commit after the workspace generation changes`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        val gateway = FakeWorkspaceGateway().apply { rootDelayMs = 200 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope)
        try {
            val selection = async { controller.chooseFolder(HEALTHY, 1, true) }
            withTimeout(1_000) { while (gateway.rootCalls == 0) delay(5) }
            store.setEnabled(false)
            assertEquals(WorkspaceFolderResult.CHECK_FAILED, selection.await())
            assertFalse(store.snapshot().state.settings.enabled)
            assertEquals("", store.snapshot().state.settings.treeUri)
            assertEquals(listOf(HEALTHY), gateway.released)
        } finally { scope.cancel() }
    }

    @Test
    fun `selection cannot commit after the indexed revision changes`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(HEALTHY, "Documents", true)
        val gateway = FakeWorkspaceGateway().apply { rootDelayMs = 200 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = WorkspaceController(store, gateway, scope)
        val replacement = HEALTHY + "2"
        try {
            val selection = async { controller.chooseFolder(replacement, 1, true) }
            withTimeout(1_000) { while (gateway.rootCalls == 0) delay(5) }
            store.publish(WorkspaceIndex(store.snapshot().state.settings.generation, emptyList(), 1_000))
            assertEquals(WorkspaceFolderResult.CHECK_FAILED, selection.await())
            assertEquals(HEALTHY, store.snapshot().state.settings.treeUri)
            assertTrue(store.snapshot().state.validated)
            assertEquals(listOf(replacement), gateway.released)
        } finally { scope.cancel() }
    }

    private class BlockingGateway(
        private val blockRoots: Boolean = false,
        val delegate: FakeWorkspaceGateway = FakeWorkspaceGateway(),
    ) : WorkspaceDocumentGateway by delegate, AutoCloseable {
        private val calls = WorkspaceProviderCalls(5_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val cancelled = AtomicInteger()

        override suspend fun isLocalTree(treeUri: String): Boolean {
            if (treeUri != BLOCKED) return delegate.isLocalTree(treeUri)
            return calls.await(treeUri, cancel = { cancelled.incrementAndGet() }) {
                workspaceFolderIsLocal(treeUri, "child", readRoots = {
                    if (blockRoots) stall()
                    listOf(WorkspaceProviderRoot("local", "device", 2), WorkspaceProviderRoot("remote", "cloud", 0))
                }, isChild = { stall(); true })
            }
        }

        override suspend fun root(treeUri: String): WorkspaceEntry = calls.await(treeUri) {
            runBlocking { delegate.root(treeUri) }
        }

        private fun stall() {
            entered.countDown()
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { }
            }
            returned.countDown()
        }

        override fun close() { release.countDown(); calls.close() }
    }

    companion object {
        private const val BLOCKED = "content://mixed.local.provider/tree/child"
        private const val HEALTHY = WorkspaceStoreTest.TREE
    }
}
