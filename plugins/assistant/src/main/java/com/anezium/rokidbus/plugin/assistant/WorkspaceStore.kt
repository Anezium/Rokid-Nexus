package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject
import java.io.File

/**
 * [revision] moves on every published change. [epoch] moves only when a publication withdraws or alters
 * an excerpt that was already searchable, so background indexing that only adds text never invalidates
 * an answer in flight.
 */
internal data class WorkspaceSnapshot(val state: WorkspaceState, val retriever: WorkspaceRetriever? = null,
    val revision: Long = 0, val epoch: Long = 0)

internal class WorkspaceStore(
    private val directory: File,
    private val fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
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
            generation = current.state.settings.generation + 1))
    }

    fun selectTree(uri: String, name: String, enable: Boolean = current.state.settings.enabled) = synchronized(lock) {
        require(isWorkspaceTreeUri(uri))
        updateSettings(WorkspaceSettings(enable, uri, workspaceLabel(name, 96),
            current.state.settings.generation + 1))
    }

    fun folderUnavailable(generation: Long) = synchronized(lock) {
        if (!isCurrent(generation)) return@synchronized
        val settings = current.state.settings.copy(generation = generation + 1)
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

    fun publish(index: WorkspaceIndex): Boolean = synchronized(lock) {
        if (!isCurrent(index.generation)) return@synchronized false
        val text = WorkspaceIndexJson.render(index)
        val changed = current.state.index?.documents != index.documents
        val retriever = if (changed) WorkspaceRetriever(index.documents) else current.retriever
        val revision = current.revision + if (changed) 1 else 0
        val epoch = current.epoch + if (current.state.index?.let { workspaceRetracts(it, index) } == true) 1 else 0
        writeAssistantJsonAtomically(indexFile, text, fileOperations)
        current = WorkspaceSnapshot(WorkspaceState(current.state.settings, index, validated = true), retriever,
            revision, epoch)
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
            .put("generation", settings.generation).toString()
        writeAssistantJsonAtomically(settingsFile, text, fileOperations)
    }

    private fun load(): WorkspaceSnapshot = runCatching {
        val settings = if (settingsFile.isFile) {
            val root = JSONObject(readBounded(settingsFile, 4_096))
            require(root.getInt("version") == 1)
            WorkspaceSettings(root.getBoolean("enabled"), root.getString("treeUri"),
                root.getString("folderName"), root.getLong("generation")).also {
                require(it.generation >= 0 && it.folderName.length <= 96)
                require(it.treeUri.isEmpty() || isWorkspaceTreeUri(it.treeUri))
            }
        } else WorkspaceSettings()
        if (!settings.enabled) {
            clearIndexFiles()
            return@runCatching WorkspaceSnapshot(WorkspaceState(settings))
        }
        val hadIndex = indexFile.isFile
        val index = if (hadIndex) runCatching {
            WorkspaceIndexJson.parse(readBounded(indexFile, WorkspaceLimits.MAX_INDEX_BYTES))
                .also { require(it.generation == settings.generation) }
        }.getOrNull() else null
        if (index == null) clearIndexFiles()
        WorkspaceSnapshot(WorkspaceState(settings, index,
            problem = if (hadIndex && index == null) WorkspaceProblem.INVALID_INDEX else null),
            index?.let { WorkspaceRetriever(it.documents) })
    }.getOrElse {
        runCatching { clearIndexFiles() }
        WorkspaceSnapshot(WorkspaceState(WorkspaceSettings(), problem = WorkspaceProblem.INVALID_INDEX))
    }

    private fun readBounded(file: File, maxBytes: Int): String = file.inputStream().use {
        require(file.length() <= maxBytes)
        readWorkspaceBytes(it, maxBytes).data!!.toString(Charsets.UTF_8)
    }
}

internal fun workspaceRetracts(before: WorkspaceIndex, after: WorkspaceIndex): Boolean {
    val documents = after.documents.associateBy { it.entry.documentId }
    return before.documents.any { old ->
        val new = documents[old.entry.documentId]
        old.chunks.isNotEmpty() && (new == null || new.entry != old.entry ||
            new.chunks.size < old.chunks.size || new.chunks.subList(0, old.chunks.size) != old.chunks)
    }
}
