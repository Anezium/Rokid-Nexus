package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject
import java.io.File
import java.net.URI

internal data class WorkspaceSnapshot(val state: WorkspaceState, val retriever: WorkspaceRetriever? = null,
    val revision: Long = 0)

internal fun isLocalWorkspaceTree(uri: String): Boolean = runCatching {
    val value = URI(uri)
    uri.length <= 2_048 && value.scheme == "content" &&
        value.rawAuthority == "com.android.externalstorage.documents" &&
        value.rawQuery == null && value.rawFragment == null &&
        Regex("/tree/[^/]+").matches(value.rawPath.orEmpty())
}.getOrDefault(false)

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
        require(isLocalWorkspaceTree(uri))
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

    fun publish(index: WorkspaceIndex): Boolean = synchronized(lock) {
        if (!isCurrent(index.generation)) return@synchronized false
        val text = WorkspaceIndexJson.render(index)
        val changed = current.state.index?.documents != index.documents
        val retriever = if (changed) WorkspaceRetriever(index.documents) else current.retriever
        val revision = current.revision + if (changed) 1 else 0
        writeAssistantJsonAtomically(indexFile, text, fileOperations)
        current = WorkspaceSnapshot(WorkspaceState(current.state.settings, index, validated = true), retriever, revision)
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
                require(it.treeUri.isEmpty() || isLocalWorkspaceTree(it.treeUri))
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
