package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject
import java.io.File

/**
 * [revision] moves on every published change. [epoch] moves only when a publication withdraws or alters
 * a source a turn may already have used, so background indexing that only adds text or coverage never
 * invalidates an answer in flight.
 */
internal data class WorkspaceSnapshot(val state: WorkspaceState, val retriever: WorkspaceRetriever? = null,
    val revision: Long = 0, val epoch: Long = 0)

internal class WorkspaceStore(
    private val directory: File,
    private val fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val settingsFile = File(directory, "workspace-settings.json")
    private val indexFile = File(directory, "workspace-index.json")
    private val lock = Any()
    @Volatile private var current = load()

    fun snapshot(): WorkspaceSnapshot = current

    fun isCurrent(generation: Long): Boolean = current.state.settings.let {
        it.enabled && it.generation == generation
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        if (enabled && current.state.settings.enabled) return@synchronized
        updateSettings(current.state.settings.copy(enabled = enabled,
            generation = current.state.settings.generation + 1, verificationRequested = false))
    }

    fun selectTree(uri: String, name: String, enable: Boolean = current.state.settings.enabled) = synchronized(lock) {
        require(isWorkspaceTreeUri(uri))
        updateSettings(WorkspaceSettings(enable, uri, workspaceLabel(name, 96),
            current.state.settings.generation + 1))
    }

    fun folderUnavailable(generation: Long) = synchronized(lock) {
        if (!isCurrent(generation)) return@synchronized
        val settings = current.state.settings.copy(generation = generation + 1, verificationRequested = false)
        current = WorkspaceSnapshot(WorkspaceState(settings, problem = WorkspaceProblem.FOLDER_UNAVAILABLE))
        clearIndexFiles()
        persistSettings(settings)
    }

    fun failed(generation: Long, problem: WorkspaceProblem) = synchronized(lock) {
        if (!isCurrent(generation)) return@synchronized
        current = current.copy(state = current.state.copy(problem = problem, validated = false))
    }

    fun selectionFailed(generation: Long, revision: Long) = synchronized(lock) {
        if (current.state.settings.generation != generation || current.revision != revision) return@synchronized
        current = current.copy(state = current.state.copy(problem = WorkspaceProblem.CHECK_FAILED, validated = false))
    }

    fun requestVerification(generation: Long, requested: Boolean) = synchronized(lock) {
        if (!isCurrent(generation) || current.state.settings.verificationRequested == requested) return@synchronized
        val settings = current.state.settings.copy(verificationRequested = requested)
        persistSettings(settings)
        current = current.copy(state = current.state.copy(settings = settings))
    }

    /**
     * Commits one immutable snapshot: the catalog, text, and the optional posting cache are written
     * atomically before any of them becomes visible. A failed write keeps the previous file and
     * the previous in-memory snapshot.
     */
    fun publish(index: WorkspaceIndex): Boolean = synchronized(lock) {
        if (!isCurrent(index.generation)) return@synchronized false
        val changed = current.state.index?.documents != index.documents
        val retriever = if (changed || current.retriever == null) WorkspaceRetriever(index.documents) else current.retriever!!
        val encoded = WorkspaceIndexJson.encode(index, retriever.lexical)
        val revision = current.revision + if (changed) 1 else 0
        val retracted = current.state.index?.let { workspaceRetracts(it, index) } == true
        val epoch = current.epoch + if (retracted) 1 else 0
        writeAssistantJsonAtomically(indexFile, encoded.text, fileOperations)
        current = WorkspaceSnapshot(WorkspaceState(current.state.settings, index, validated = true), retriever,
            revision, epoch)
        WorkspaceDiagnostics.event("index_published", "bytes" to encoded.bytes,
            "lexical_persisted" to encoded.lexicalPersisted, "documents" to index.documents.size,
            "chunks" to index.chunkCount, "retracted" to retracted)
        true
    }

    private fun updateSettings(settings: WorkspaceSettings) {
        current = WorkspaceSnapshot(WorkspaceState(settings))
        clearIndexFiles()
        persistSettings(settings)
    }

    private fun clearIndexFiles() {
        listOf(indexFile, File(directory, ".${indexFile.name}.tmp")).forEach { file ->
            check(!file.exists() || file.delete()) { "Could not clear the workspace cache." }
        }
    }

    private fun persistSettings(settings: WorkspaceSettings) {
        val text = JSONObject().put("version", 1).put("enabled", settings.enabled)
            .put("treeUri", settings.treeUri).put("folderName", settings.folderName)
            .put("generation", settings.generation).put("verificationRequested", settings.verificationRequested).toString()
        writeAssistantJsonAtomically(settingsFile, text, fileOperations)
    }

    private fun load(): WorkspaceSnapshot = runCatching {
        val settings = if (settingsFile.isFile) {
            val root = JSONObject(readBounded(settingsFile, 4_096))
            require(root.getInt("version") == 1)
            WorkspaceSettings(root.getBoolean("enabled"), root.getString("treeUri"),
                root.getString("folderName"), root.getLong("generation"),
                root.optBoolean("verificationRequested", false)).also {
                require(it.generation >= 0 && it.folderName.length <= 96)
                require(it.treeUri.isEmpty() || isWorkspaceTreeUri(it.treeUri))
            }
        } else WorkspaceSettings()
        if (!settings.enabled) {
            clearIndexFiles()
            return@runCatching WorkspaceSnapshot(WorkspaceState(settings))
        }
        val started = elapsed()
        val hadIndex = indexFile.isFile
        val stored = if (hadIndex) runCatching {
            WorkspaceIndexJson.decode(readBounded(indexFile, WorkspaceLimits.MAX_INDEX_BYTES))
                .also { require(it.index.generation == settings.generation) }
        }.getOrNull() else null
        if (stored == null) clearIndexFiles()
        val retriever = stored?.let { WorkspaceRetriever(it.index.documents, it.lexical) }
        if (hadIndex) {
            WorkspaceDiagnostics.event("index_loaded", "ok" to (stored != null), "ms" to elapsed() - started,
                "schema" to (stored?.schemaVersion ?: 0), "cache_used" to (stored?.lexical != null),
                "chunks" to (stored?.index?.chunkCount ?: 0))
        }
        WorkspaceSnapshot(WorkspaceState(settings, stored?.index,
            problem = if (hadIndex && stored == null) WorkspaceProblem.INVALID_INDEX else null), retriever)
    }.getOrElse {
        runCatching { clearIndexFiles() }
        WorkspaceSnapshot(WorkspaceState(WorkspaceSettings(), problem = WorkspaceProblem.INVALID_INDEX))
    }

    private fun readBounded(file: File, maxBytes: Int): String = file.inputStream().use {
        require(file.length() <= maxBytes)
        readWorkspaceBytes(it, maxBytes).data!!.toString(Charsets.UTF_8)
    }
}

/**
 * Whether [after] withdraws or alters something a turn built on [before] may have used: a source's
 * identity or reliable metadata, its retained text, a known digest or page count, or a page known to
 * render. Adding text, coverage, a first digest or page count, or rebuilding the derivable posting
 * cache retracts nothing; neither does a document that carried no text, digest, or page count, since
 * no turn could have read or viewed it. A textless document with known pages does retract.
 */
internal fun workspaceRetracts(before: WorkspaceIndex, after: WorkspaceIndex): Boolean {
    val documents = after.documents.associateBy { it.entry.documentId }
    return before.documents.any { old ->
        if (old.chunks.isEmpty() && old.sourceDigest == null && old.pageCount == null) return@any false
        val new = documents[old.entry.documentId] ?: return@any true
        new.entry != old.entry ||
            new.chunks.size < old.chunks.size || new.chunks.subList(0, old.chunks.size) != old.chunks ||
            old.sourceDigest != null && new.sourceDigest != old.sourceDigest ||
            old.pageCount != null && new.pageCount != old.pageCount ||
            old.status in VIEWABLE_STATUSES && new.status !in VIEWABLE_STATUSES ||
            old.pageRuns.any { run -> (run.start..run.end).any { page -> pageRetracts(run.state, new.pageState(page)) } }
    }
}

private fun pageRetracts(old: WorkspacePageState, new: WorkspacePageState): Boolean =
    old.text.hasText && old.text != new.text && !(old.text == WorkspaceTextState.LEGACY_TEXT && new.text.hasText) ||
        old.render == WorkspaceRenderState.AVAILABLE && new.render == WorkspaceRenderState.UNAVAILABLE

internal val VIEWABLE_STATUSES = setOf(WorkspaceDocumentStatus.INDEXED, WorkspaceDocumentStatus.TRUNCATED,
    WorkspaceDocumentStatus.PENDING, WorkspaceDocumentStatus.NO_TEXT)
