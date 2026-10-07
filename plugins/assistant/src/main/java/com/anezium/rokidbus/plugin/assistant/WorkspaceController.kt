package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException

internal data class WorkspaceUiState(val workspace: WorkspaceState, val checking: Boolean = false,
    val promptSpaceEmpty: Boolean = false)

internal enum class WorkspaceFolderResult {
    SELECTED, LOCAL_FOLDER_REQUIRED, NO_READ_GRANT, UNAVAILABLE, STORE_FAILED;
}

internal data class WorkspacePromptContext(val generation: Long, val enabled: Boolean, val excerpts: String = "",
    val revision: Long = -1)

internal interface WorkspaceSearchAccess {
    fun isSearchAvailable(): Boolean
    fun searchVersion(): Pair<Long, Long> = 0L to 0L
    suspend fun search(query: String): WorkspaceSearchResult?
}

internal class WorkspaceController(
    private val store: WorkspaceStore,
    private val gateway: WorkspaceDocumentGateway,
    private val scope: CoroutineScope,
) : WorkspaceSearchAccess {
    private val lock = Any()
    private val owners = mutableSetOf<Any>()
    private val checkMutex = Mutex()
    private var checkJob: Job? = null
    private var changeJob: Job? = null
    private var observer: AutoCloseable? = null
    @Volatile private var suppressed = false
    private val mutableState = MutableStateFlow(WorkspaceUiState(store.snapshot().state))
    val state: StateFlow<WorkspaceUiState> = mutableState

    fun attach(owner: Any) = synchronized(lock) {
        if (owners.add(owner)) {
            restartObserver()
            refresh()
        }
    }

    fun detach(owner: Any) = synchronized(lock) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            cancelWorkers()
            observer?.close()
            observer = null
        }
    }

    fun refresh() = synchronized(lock) {
        val settings = store.snapshot().state.settings
        if (owners.isEmpty() || !settings.enabled || settings.treeUri.isEmpty() || checkJob?.isActive == true) return@synchronized
        checkJob = scope.launch {
            checkMutex.withLock {
                emit(checking = true)
                try {
                    WorkspaceIndexer(store, gateway).refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    store.failed(settings.generation, WorkspaceProblem.STORE_FAILED)
                } finally {
                    emit(checking = false)
                }
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) suppressed = true
        withContext(Dispatchers.IO) {
            synchronized(lock) { cancelWorkers() }
            try {
                store.setEnabled(enabled)
            } catch (error: Exception) {
                if (!enabled) gateway.releaseReadGrant(store.snapshot().state.settings.treeUri)
                throw error
            } finally {
                suppressed = !store.snapshot().state.settings.enabled
                synchronized(lock) { restartObserver() }
                emit(checking = false)
            }
            if (enabled) refresh()
        }
    }

    suspend fun chooseFolder(uri: String, returnedFlags: Int, enable: Boolean): WorkspaceFolderResult {
        if (!isLocalWorkspaceTree(uri)) return WorkspaceFolderResult.LOCAL_FOLDER_REQUIRED
        if (returnedFlags and READ_GRANT == 0) return WorkspaceFolderResult.NO_READ_GRANT
        return withContext(Dispatchers.IO) {
            val previous = store.snapshot().state.settings
            var persisted = false
            try {
                gateway.persistReadGrant(uri, READ_GRANT)
                persisted = true
                val root = withTimeoutOrNull(WorkspaceLimits.CHECK_TIMEOUT_MS) { gateway.root(uri) }
                if (root?.directory != true) {
                    if (uri != previous.treeUri) gateway.releaseReadGrant(uri)
                    return@withContext WorkspaceFolderResult.UNAVAILABLE
                }
                synchronized(lock) { cancelWorkers() }
                store.selectTree(uri, root.name, enable)
                if (previous.treeUri.isNotEmpty() && previous.treeUri != uri) gateway.releaseReadGrant(previous.treeUri)
                suppressed = !enable
                synchronized(lock) { restartObserver() }
                emit(checking = false)
                refresh()
                WorkspaceFolderResult.SELECTED
            } catch (cancelled: CancellationException) {
                if (persisted && uri != previous.treeUri) gateway.releaseReadGrant(uri)
                throw cancelled
            } catch (_: Exception) {
                if (persisted && uri != previous.treeUri) gateway.releaseReadGrant(uri)
                emit(checking = false)
                WorkspaceFolderResult.STORE_FAILED
            }
        }
    }

    override fun isSearchAvailable(): Boolean {
        val snapshot = store.snapshot().state
        return !suppressed && snapshot.settings.enabled && snapshot.validated &&
            (snapshot.index?.chunkCount ?: 0) > 0 && runCatching {
                gateway.hasReadGrant(snapshot.settings.treeUri)
            }.getOrDefault(false)
    }

    override fun searchVersion(): Pair<Long, Long> = store.snapshot().let { it.state.settings.generation to it.revision }

    fun isCurrent(context: WorkspacePromptContext): Boolean = !suppressed &&
        store.isCurrent(context.generation) && store.snapshot().revision == context.revision && isSearchAvailable()

    suspend fun contextForQuestion(query: String, existingContext: String): WorkspacePromptContext {
        val budget = workspacePromptBudget(existingContext)
        mutableState.value = mutableState.value.copy(promptSpaceEmpty = budget <= WorkspaceRetriever.SOURCE_RULE.length + 40)
        val snapshot = validatedSnapshot()
        val settings = store.snapshot().state.settings
        val excerpts = if (snapshot != null && budget > 0) snapshot.retriever?.search(query, budget)?.excerpts.orEmpty() else ""
        return WorkspacePromptContext(snapshot?.state?.settings?.generation ?: settings.generation,
            settings.enabled && !suppressed, excerpts, snapshot?.revision ?: -1)
    }

    override suspend fun search(query: String): WorkspaceSearchResult? {
        val snapshot = validatedSnapshot() ?: return null
        val result = snapshot.retriever?.search(query) ?: return null
        return result.takeIf { store.isCurrent(snapshot.state.settings.generation) &&
            store.snapshot().revision == snapshot.revision && !suppressed }
    }

    private suspend fun validatedSnapshot(): WorkspaceSnapshot? {
        val snapshot = store.snapshot()
        val settings = snapshot.state.settings
        if (suppressed || !settings.enabled || settings.treeUri.isEmpty()) return null
        if (!runCatching { gateway.hasReadGrant(settings.treeUri) }.getOrDefault(false)) {
            withContext(Dispatchers.IO) { store.folderUnavailable(settings.generation) }
            emit()
            return null
        }
        if (!snapshot.state.validated || snapshot.retriever == null) return null
        val access = scope.async { gateway.root(settings.treeUri) }
        try {
            val root = withTimeoutOrNull(WorkspaceLimits.ACCESS_TIMEOUT_MS) { access.await() }
            if (root == null) {
                store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
                emit()
                return null
            }
            if (!root.directory) throw FileNotFoundException()
            return store.snapshot().takeIf { !suppressed && store.isCurrent(settings.generation) &&
                store.snapshot().state.validated && gateway.hasReadGrant(settings.treeUri) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            withContext(Dispatchers.IO) { store.folderUnavailable(settings.generation) }
            emit()
            return null
        } catch (_: FileNotFoundException) {
            withContext(Dispatchers.IO) { store.folderUnavailable(settings.generation) }
            emit()
            return null
        } catch (_: Exception) {
            store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
            emit()
            return null
        } finally {
            access.cancel()
        }
    }

    private fun cancelWorkers() {
        checkJob?.cancel()
        changeJob?.cancel()
        checkJob = null
        changeJob = null
    }

    private fun restartObserver() {
        observer?.close()
        observer = null
        val settings = store.snapshot().state.settings
        if (owners.isNotEmpty() && settings.enabled && settings.treeUri.isNotEmpty()) {
            observer = gateway.observe(settings.treeUri) {
                synchronized(lock) {
                    if (owners.isNotEmpty() && store.isCurrent(settings.generation)) {
                        changeJob?.cancel()
                        changeJob = scope.launch {
                            delay(250)
                            refresh()
                        }
                    }
                }
            }
        }
    }

    private fun emit(checking: Boolean = mutableState.value.checking) {
        mutableState.value = mutableState.value.copy(workspace = store.snapshot().state, checking = checking)
    }

    companion object {
        const val READ_GRANT = 1
    }
}
