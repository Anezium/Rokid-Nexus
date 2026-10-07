package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.CharacterCodingException

internal interface WorkspaceDocumentGateway {
    fun hasReadGrant(treeUri: String): Boolean
    fun persistReadGrant(treeUri: String, returnedFlags: Int)
    fun releaseReadGrant(treeUri: String)
    suspend fun root(treeUri: String): WorkspaceEntry
    suspend fun children(treeUri: String, documentId: String): List<WorkspaceEntry>
    suspend fun metadata(treeUri: String, documentId: String): WorkspaceEntry
    suspend fun open(treeUri: String, documentId: String): InputStream
    fun observe(treeUri: String, onChange: () -> Unit): AutoCloseable? = null
}

internal class WorkspaceCheckLimitException : Exception()

internal class WorkspaceIndexer(
    private val store: WorkspaceStore,
    private val gateway: WorkspaceDocumentGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh() {
        val before = store.snapshot().state
        val settings = before.settings
        if (!settings.enabled || settings.treeUri.isEmpty()) return
        var rootChecked = false
        try {
            if (!gateway.hasReadGrant(settings.treeUri)) {
                store.folderUnavailable(settings.generation)
                return
            }
            withTimeout(WorkspaceLimits.CHECK_TIMEOUT_MS) {
                val root = gateway.root(settings.treeUri)
                if (!root.directory) throw FileNotFoundException()
                rootChecked = true
                val entries = walk(settings.treeUri, root.documentId)
                val supported = entries.filter { it.type != null && !it.directory && !it.virtual }
                    .sortedWith(compareBy<WorkspaceEntry> { it.relativePath }.thenBy { it.documentId })
                val old = before.index?.documents?.associateBy { it.entry.documentId }.orEmpty()
                var totalChars = 0
                var totalChunks = 0
                var skipped = entries.count { !it.directory && (it.type == null || it.virtual) } +
                    (supported.size - WorkspaceLimits.MAX_FILES).coerceAtLeast(0)
                val documents = mutableListOf<WorkspaceDocument>()
                for (entry in supported.take(WorkspaceLimits.MAX_FILES)) {
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(settings.generation)) return@withTimeout
                    val previous = old[entry.documentId]
                    val extracted = when {
                        !entry.hasReliableMetadata() -> WorkspaceDocument(entry, emptyList(),
                            WorkspaceDocumentStatus.METADATA_UNAVAILABLE)
                        previous != null && entry.hasSameContent(previous.entry) -> previous.copy(entry = entry)
                        else -> readDocument(settings.treeUri, entry)
                    }
                    val retained = extracted.chunks.takeWhile { chunk ->
                        if (totalChunks >= WorkspaceLimits.MAX_CHUNKS ||
                            totalChars + chunk.text.length > WorkspaceLimits.MAX_TOTAL_CHARS
                        ) false else {
                            totalChunks++
                            totalChars += chunk.text.length
                            true
                        }
                    }
                    val status = if (retained.size < extracted.chunks.size) WorkspaceDocumentStatus.TRUNCATED
                    else extracted.status
                    documents += extracted.copy(chunks = retained, status = status)
                    if (retained.isEmpty()) skipped++
                }
                if (!gateway.hasReadGrant(settings.treeUri)) throw SecurityException()
                currentCoroutineContext().ensureActive()
                store.publish(WorkspaceIndex(settings.generation, documents.toList(), clock(), skipped))
            }
        } catch (_: TimeoutCancellationException) {
            store.failed(settings.generation, WorkspaceProblem.CHECK_LIMIT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            if (!rootChecked || !gateway.hasReadGrant(settings.treeUri)) store.folderUnavailable(settings.generation)
            else store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
        } catch (_: FileNotFoundException) {
            if (!rootChecked) store.folderUnavailable(settings.generation)
            else store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
        } catch (_: WorkspaceCheckLimitException) {
            store.failed(settings.generation, WorkspaceProblem.CHECK_LIMIT)
        } catch (_: Exception) {
            store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
        }
    }

    private suspend fun walk(treeUri: String, rootId: String): List<WorkspaceEntry> {
        data class Directory(val id: String, val path: String, val depth: Int)
        val queue = ArrayDeque<Directory>()
        val visited = mutableSetOf(rootId)
        val labels = mutableSetOf<String>()
        val entries = mutableListOf<WorkspaceEntry>()
        var queries = 0
        queue.add(Directory(rootId, "", 0))
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++queries > WorkspaceLimits.MAX_DIRECTORIES) throw WorkspaceCheckLimitException()
            val directory = queue.removeFirst()
            for (entry in gateway.children(treeUri, directory.id)
                .sortedWith(compareBy<WorkspaceEntry> { it.name }.thenBy { it.documentId })) {
                if (!visited.add(entry.documentId)) continue
                if (entry.documentId.isEmpty() || entry.documentId.length > 1_024 ||
                    entries.size >= WorkspaceLimits.MAX_ENTRIES
                ) throw WorkspaceCheckLimitException()
                val name = workspaceLabel(entry.name, 96)
                var path = workspacePathLabel(directory.path, name)
                var discriminator = 2
                while (!labels.add(path)) path = workspacePathLabel(directory.path, name, discriminator++)
                val bounded = entry.copy(name = name, relativePath = path)
                entries += bounded
                if (entry.directory) {
                    if (directory.depth >= WorkspaceLimits.MAX_DEPTH) throw WorkspaceCheckLimitException()
                    queue.add(Directory(entry.documentId, path, directory.depth + 1))
                }
            }
        }
        return entries
    }

    private suspend fun readDocument(treeUri: String, entry: WorkspaceEntry): WorkspaceDocument {
        val maxBytes = if (entry.type == WorkspaceFileType.DOCX) WorkspaceLimits.MAX_DOCX_BYTES
        else WorkspaceLimits.MAX_TEXT_BYTES
        if (entry.sizeBytes!! > maxBytes) return WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.TOO_LARGE)
        return try {
            val stream = gateway.open(treeUri, entry.documentId)
            val text = stream.use {
                runInterruptible(Dispatchers.IO) { WorkspaceDocumentExtractor.extract(it, entry.type!!) }
            }
            val after = gateway.metadata(treeUri, entry.documentId)
            if (!entry.hasSameContent(after)) throw WorkspaceCheckLimitException()
            val capped = workspaceWordPrefix(text, WorkspaceLimits.MAX_FILE_CHARS)
            var tokenOmitted = false
            val chunks = WorkspaceChunker.chunk(capped, entry.type == WorkspaceFileType.MARKDOWN,
                onTruncated = { tokenOmitted = true })
            val lostText = capped.length < text.trim().length || tokenOmitted
            WorkspaceDocument(entry, chunks, if (lostText) WorkspaceDocumentStatus.TRUNCATED else WorkspaceDocumentStatus.INDEXED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: WorkspaceCheckLimitException) {
            throw limit
        } catch (error: WorkspaceReadException) {
            WorkspaceDocument(entry, emptyList(), error.status)
        } catch (_: CharacterCodingException) {
            WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.INVALID_TEXT)
        } catch (_: org.xml.sax.SAXException) {
            WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.INVALID_TEXT)
        } catch (_: Exception) {
            WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.UNREADABLE)
        }
    }
}
