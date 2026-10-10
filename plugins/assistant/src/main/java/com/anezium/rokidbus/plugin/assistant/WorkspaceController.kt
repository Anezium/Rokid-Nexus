package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException

internal data class WorkspaceUiState(val workspace: WorkspaceState, val checking: Boolean = false,
    val promptSpaceEmpty: Boolean = false)

internal enum class WorkspaceFolderResult {
    SELECTED, LOCAL_FOLDER_REQUIRED, NO_READ_GRANT, UNAVAILABLE, CHECK_FAILED, STORE_FAILED;
}

/**
 * What the question's first request carries. [turn] is the one access object the whole turn uses;
 * [carriesEvidence] is true only when [excerpts] holds document text, not scope or file metadata.
 */
internal data class WorkspacePromptContext(val generation: Long, val enabled: Boolean, val excerpts: String = "",
    val revision: Long = -1, val turn: WorkspaceTurn? = null, val carriesEvidence: Boolean = false)

internal class WorkspaceController(
    private val store: WorkspaceStore,
    private val gateway: WorkspaceDocumentGateway,
    private val scope: CoroutineScope,
    private val checkTimeoutMs: Long = WorkspaceLimits.CHECK_TIMEOUT_MS,
    private val pageReader: WorkspacePageReader? = null,
    private val viewTimeoutMs: Long = VIEW_TIMEOUT_MS,
    private val debounceMs: Long = CHANGE_DEBOUNCE_MS,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) : WorkspaceTurnHost {
    private val lock = Any()
    private val owners = mutableSetOf<Any>()
    private val checkMutex = Mutex()
    private val gate = WorkspaceHeavyGate()
    private var checkJob: Job? = null
    private var changeJob: Job? = null
    private var selectionJob: Job? = null
    private var selectionRevision = 0L
    private var observer: AutoCloseable? = null
    // Set when a pass is requested while one runs, so a change notification is never lost.
    private var rerun = false
    private var verification: WorkspaceVerification? = null
    @Volatile private var suppressed = false
    // A change notification arrived and its metadata reconciliation has not committed yet.
    @Volatile private var dirty = false
    private var dirtySequence = 0L
    @Volatile private var activeTurn: WorkspaceTurn? = null
    private var turnSequence = 0L
    private val mutableState = MutableStateFlow(WorkspaceUiState(store.snapshot().state))
    val state: StateFlow<WorkspaceUiState> = mutableState

    override val canRender: Boolean get() = pageReader != null

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
            rerun = false
            // A cancelled pass may have advanced its in-memory verifier before publication.
            verification = null
        }
    }

    /** An explicit Re-index: one pass sequence that also hashes unchanged files and retries failed pages once. */
    fun reindexNow() = synchronized(lock) {
        val snapshot = store.snapshot()
        if (!snapshot.state.settings.enabled) return@synchronized
        try {
            store.requestVerification(snapshot.state.settings.generation, true)
        } catch (_: Exception) {
            store.failed(snapshot.state.settings.generation, WorkspaceProblem.STORE_FAILED)
            emit(checking = false)
            return@synchronized
        }
        val documents = snapshot.state.index?.documents.orEmpty().map { it.entry.documentId }
        verification = WorkspaceVerification(documents)
        refresh()
    }

    fun refresh() = synchronized(lock) {
        val settings = store.snapshot().state.settings
        if (owners.isEmpty() || !settings.enabled || settings.treeUri.isEmpty()) return@synchronized
        if (verification == null && settings.verificationRequested) {
            verification = WorkspaceVerification(store.snapshot().state.index?.documents.orEmpty().map { it.entry.documentId })
        }
        if (checkJob?.isActive == true) {
            rerun = true
            return@synchronized
        }
        checkJob = scope.launch {
            checkMutex.withLock {
                emit(checking = true)
                try {
                    do {
                        val sequence = synchronized(lock) {
                            rerun = false
                            dirtySequence
                        }
                        passes(settings, sequence)
                    } while (synchronized(lock) { rerun })
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

    /**
     * Each pass makes progress on the remaining work (a pending page, a missing digest, a page an
     * older index never recorded, or a requested verification), so passes continue only while that
     * work shrinks and stop once nothing is left.
     */
    private suspend fun passes(settings: WorkspaceSettings, sequence: Long) {
        var remaining = Long.MAX_VALUE
        while (true) {
            val requested = synchronized(lock) { verification }
            WorkspaceIndexer(store, gateway, checkTimeoutMs = checkTimeoutMs, pageReader = pageReader, gate = gate,
                verification = requested, onReconciled = { reconciled(sequence) }).refresh()
            val state = store.snapshot().state
            val index = state.index?.takeIf { state.problem == null && it.generation == settings.generation } ?: break
            val work = remainingWork(index) + (requested?.size ?: 0)
            if (requested != null && requested.size == 0) synchronized(lock) {
                if (verification === requested) {
                    store.requestVerification(settings.generation, false)
                    verification = null
                }
            }
            if (work == 0L || work >= remaining) break
            remaining = work
        }
    }

    private fun remainingWork(index: WorkspaceIndex): Long = index.documents.sumOf { document ->
        val pending = if (document.status == WorkspaceDocumentStatus.PENDING) {
            WorkspaceLimits.MAX_PDF_PAGES + 1L - document.pagesRead
        } else 0L
        val digest = if (document.sourceDigest == null && document.status != WorkspaceDocumentStatus.PENDING &&
            (document.chunks.isNotEmpty() || document.status == WorkspaceDocumentStatus.NO_TEXT) &&
            (document.entry.type?.paged != true || pageReader != null)) 1L else 0L
        val unknown = if (pageReader != null && document.sourceDigest != null && document.pageCount != null &&
            document.status != WorkspaceDocumentStatus.PENDING &&
            document.chunks.sumOf { it.text.length } < WorkspaceLimits.MAX_FILE_CHARS
        ) WorkspacePageCatalog.counts(document)[WorkspaceTextState.LEGACY_UNKNOWN]?.toLong() ?: 0L else 0L
        pending + digest + unknown
    }

    private fun reconciled(sequence: Long) = synchronized(lock) {
        if (dirtySequence == sequence) dirty = false
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            suppressed = true
            withdrawTurn("disabled")
        }
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                selectionJob?.cancel()
                selectionRevision++
                cancelWorkers()
                verification = null
            }
            try {
                synchronized(lock) { store.setEnabled(enabled) }
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
        if (!isWorkspaceTreeUri(uri)) return WorkspaceFolderResult.LOCAL_FOLDER_REQUIRED
        if (returnedFlags and READ_GRANT == 0) return WorkspaceFolderResult.NO_READ_GRANT
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val job = context.job
            val (selection, before) = synchronized(lock) {
                selectionJob?.cancel()
                // Returning from the picker can race the check started by onStart.
                cancelWorkers()
                selectionJob = job
                ++selectionRevision to store.snapshot()
            }
            val previous = before.state.settings
            var persisted = false
            try {
                val root = withTimeout(checkTimeoutMs) {
                    if (!gateway.isLocalTree(uri)) return@withTimeout null
                    currentCoroutineContext().ensureActive()
                    gateway.persistReadGrant(uri, READ_GRANT)
                    persisted = true
                    gateway.root(uri)
                } ?: return@withContext WorkspaceFolderResult.LOCAL_FOLDER_REQUIRED
                if (!root.directory) {
                    if (uri != previous.treeUri) gateway.releaseReadGrant(uri)
                    return@withContext WorkspaceFolderResult.UNAVAILABLE
                }
                synchronized(lock) {
                    context.ensureActive()
                    if (selection != selectionRevision || indexVersion() != (previous.generation to before.revision)) {
                        if (uri != store.snapshot().state.settings.treeUri) gateway.releaseReadGrant(uri)
                        return@withContext WorkspaceFolderResult.CHECK_FAILED
                    }
                    cancelWorkers()
                    withdrawTurn("folder_changed")
                    store.selectTree(uri, root.name, enable)
                    verification = null
                    if (previous.treeUri.isNotEmpty() && previous.treeUri != uri) gateway.releaseReadGrant(previous.treeUri)
                    suppressed = !enable
                    dirty = false
                    restartObserver()
                }
                emit(checking = false)
                refresh()
                WorkspaceFolderResult.SELECTED
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                selectionFailed(selection, before, uri, persisted)
            } catch (_: WorkspaceProviderCheckException) {
                selectionFailed(selection, before, uri, persisted)
            } catch (cancelled: CancellationException) {
                if (persisted && uri != store.snapshot().state.settings.treeUri) gateway.releaseReadGrant(uri)
                throw cancelled
            } catch (error: Exception) {
                if (persisted && uri != previous.treeUri) gateway.releaseReadGrant(uri)
                emit(checking = false)
                when (error) {
                    is SecurityException -> WorkspaceFolderResult.NO_READ_GRANT
                    is FileNotFoundException -> WorkspaceFolderResult.UNAVAILABLE
                    else -> WorkspaceFolderResult.STORE_FAILED
                }
            } finally {
                synchronized(lock) { if (selectionJob === job) selectionJob = null }
            }
        }
    }

    private fun selectionFailed(selection: Long, before: WorkspaceSnapshot, uri: String,
        persisted: Boolean): WorkspaceFolderResult = synchronized(lock) {
        if (persisted && uri != store.snapshot().state.settings.treeUri) gateway.releaseReadGrant(uri)
        if (selection == selectionRevision && indexVersion() == (before.state.settings.generation to before.revision)) {
            store.selectionFailed(before.state.settings.generation, before.revision)
            emit()
        }
        WorkspaceFolderResult.CHECK_FAILED
    }

    private fun indexVersion(): Pair<Long, Long> = store.snapshot().let { it.state.settings.generation to it.revision }

    fun isCurrent(context: WorkspacePromptContext): Boolean = context.turn?.isUsable() == true

    /**
     * Prepares the question's Workspace turn: the one root check, file resolution before the first
     * model request, and excerpts within [existingContext]'s remaining allowance. A question never
     * opens, hashes, extracts, or recognizes a document.
     */
    suspend fun contextForQuestion(query: String, existingContext: String): WorkspacePromptContext {
        val started = elapsed()
        withdrawTurn("superseded")
        val budget = workspacePromptBudget(existingContext)
        mutableState.value = mutableState.value.copy(promptSpaceEmpty = budget <= WorkspaceRetriever.SOURCE_RULE.length + 40)
        val snapshot = if (dirty) null else validatedSnapshot()
        val rootMs = elapsed() - started
        val settings = store.snapshot().state.settings
        val turn = snapshot?.takeIf { it.retriever != null && it.state.index != null }?.let {
            WorkspaceTurn(synchronized(lock) { ++turnSequence }, it,
                WorkspaceFileResolver.resolve(query, it.state.index!!.documents), this, elapsed)
        }
        if (turn != null) synchronized(lock) { if (!dirty) activeTurn = turn }
        val prefetch = turn?.takeIf { activeTurn === it }?.prefetch(query, budget) ?: WorkspacePrefetch("", false)
        WorkspaceDiagnostics.event("workspace_prefetch", "available" to (turn != null), "dirty" to dirty,
            "root_ms" to rootMs, "ms" to elapsed() - started, "budget" to budget,
            "resolution" to (turn?.resolution?.state?.name?.lowercase() ?: "none"),
            "chars" to prefetch.excerpts.length, "evidence" to prefetch.carriesEvidence,
            "searchable" to (turn?.hasSearchableText() == true), "viewable" to (turn?.hasViewablePages() == true))
        return WorkspacePromptContext(snapshot?.state?.settings?.generation ?: settings.generation,
            settings.enabled && !suppressed, prefetch.excerpts, snapshot?.epoch ?: -1,
            turn?.takeIf { activeTurn === it }, prefetch.carriesEvidence)
    }

    override fun isLive(turn: WorkspaceTurn): Boolean {
        if (suppressed || dirty || activeTurn !== turn) return false
        val snapshot = store.snapshot()
        val settings = snapshot.state.settings
        return store.isCurrent(turn.version.first) && snapshot.epoch == turn.version.second &&
            snapshot.state.validated && runCatching { gateway.hasReadGrant(settings.treeUri) }.getOrDefault(false)
    }

    override suspend fun revalidate(turn: WorkspaceTurn): Boolean = validatedSnapshot() != null && isLive(turn)

    /**
     * Reads the file once, checks its current metadata, compares a known digest with the indexed
     * one, and renders those same bytes; a mismatch marks the folder changed. An older index whose
     * digest is unknown keeps metadata-only verification for its cited pages.
     */
    override suspend fun render(turn: WorkspaceTurn, document: WorkspaceDocument, page: Int): ByteArray? {
        val reader = pageReader ?: return null
        val treeUri = turn.snapshot.state.settings.treeUri
        val type = document.entry.type!!
        val maxBytes = if (type == WorkspaceFileType.PDF) WorkspaceLimits.MAX_PDF_BYTES else WorkspaceLimits.MAX_IMAGE_BYTES
        return try {
            withTimeout(viewTimeoutMs) {
                gate.acquireForView()
                var handedOff = false
                try {
                    val bytes = gateway.open(treeUri, document.entry.documentId).use {
                        runInterruptible(Dispatchers.IO) { readWorkspaceBytes(it, maxBytes).data!! }
                    }
                    val current = gateway.metadata(treeUri, document.entry.documentId)
                    val digestChanged = document.sourceDigest != null &&
                        WorkspaceIndexer.sha256(bytes) != document.sourceDigest
                    if (!document.entry.hasSameContent(current) || digestChanged) {
                        sourceChanged()
                        return@withTimeout null
                    }
                    if (!isLive(turn)) return@withTimeout null
                    handedOff = true
                    renderDetached { reader.render(type, bytes, page) }
                } finally {
                    if (!handedOff) gate.release()
                }
            }?.takeIf { isLive(turn) }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) null else throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Renders on a thread the question never joins. A native page render can neither be interrupted
     * nor stop waiting for the platform renderer's process-wide lock, so the question stops waiting at
     * its deadline and a late page is dropped. The thread owns the heavy slot until it actually ends.
     */
    private suspend fun renderDetached(render: () -> ByteArray?): ByteArray? {
        val result = CompletableDeferred<ByteArray?>()
        try {
            Thread({
                try {
                    result.complete(render())
                } catch (_: Throwable) {
                    result.complete(null)
                } finally {
                    gate.release()
                }
            }, "workspace-view-render").apply { isDaemon = true }.start()
        } catch (error: Throwable) {
            gate.release()
            throw error
        }
        return result.await()
    }

    private suspend fun validatedSnapshot(): WorkspaceSnapshot? {
        val snapshot = store.snapshot()
        val settings = snapshot.state.settings
        if (suppressed || !settings.enabled || settings.treeUri.isEmpty()) return null
        if (!runCatching { gateway.hasReadGrant(settings.treeUri) }.getOrDefault(false)) {
            withdrawTurn("grant_lost")
            withContext(Dispatchers.IO) { store.folderUnavailable(settings.generation) }
            emit()
            return null
        }
        if (!snapshot.state.validated || snapshot.retriever == null) return null
        val access = scope.async {
            if (!gateway.isLocalTree(settings.treeUri)) throw SecurityException()
            gateway.root(settings.treeUri)
        }
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
            withdrawTurn("grant_lost")
            withContext(Dispatchers.IO) { store.folderUnavailable(settings.generation) }
            emit()
            return null
        } catch (_: FileNotFoundException) {
            withdrawTurn("folder_missing")
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

    /**
     * An unknown change: the snapshot is dirty at once, so the active turn loses Workspace before
     * the debounce, and a reconciliation pass follows. A pass already running reruns afterwards.
     */
    fun sourceChanged() = synchronized(lock) {
        dirty = true
        dirtySequence++
        withdrawTurn(WORKSPACE_SOURCE_CHANGED)
        WorkspaceDiagnostics.event("workspace_dirty", "attached" to owners.isNotEmpty())
        if (owners.isEmpty()) return@synchronized
        changeJob?.cancel()
        changeJob = scope.launch {
            delay(debounceMs)
            refresh()
        }
    }

    private fun withdrawTurn(reason: String) {
        activeTurn?.withdraw(reason)
        activeTurn = null
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
                if (store.isCurrent(settings.generation)) sourceChanged()
            }
        }
    }

    private fun emit(checking: Boolean = mutableState.value.checking) {
        mutableState.value = mutableState.value.copy(workspace = store.snapshot().state, checking = checking)
    }

    companion object {
        private const val VIEW_TIMEOUT_MS = 8_000L
        private const val CHANGE_DEBOUNCE_MS = 250L
        const val READ_GRANT = 1
    }
}
