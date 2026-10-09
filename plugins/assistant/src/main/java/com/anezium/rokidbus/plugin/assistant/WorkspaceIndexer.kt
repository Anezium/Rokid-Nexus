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
    suspend fun isLocalTree(treeUri: String): Boolean
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
    private val checkTimeoutMs: Long = WorkspaceLimits.CHECK_TIMEOUT_MS,
    private val pageReader: WorkspacePageReader? = null,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun refresh() {
        val before = store.snapshot().state
        val settings = before.settings
        if (!settings.enabled || settings.treeUri.isEmpty()) return
        var rootChecked = false
        // PDFs and images are slow to read, so a pass reads their pages only during the first half of
        // the check and leaves the rest PENDING for the next pass, which resumes after the last page
        // read. The first paged file of a pass always reads a page, so every pass progresses.
        val pagedDeadline = elapsed() + checkTimeoutMs / 2
        var pagedStarted = false
        try {
            if (!gateway.hasReadGrant(settings.treeUri)) {
                store.folderUnavailable(settings.generation)
                return
            }
            withTimeout(checkTimeoutMs) {
                if (!gateway.isLocalTree(settings.treeUri)) throw SecurityException()
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
                    val unchanged = previous != null && entry.hasSameContent(previous.entry)
                    val resume = previous?.takeIf { unchanged && it.status == WorkspaceDocumentStatus.PENDING }
                    val extracted = when {
                        !entry.hasReliableMetadata() -> WorkspaceDocument(entry, emptyList(),
                            WorkspaceDocumentStatus.METADATA_UNAVAILABLE)
                        unchanged && resume == null -> previous!!.copy(entry = entry)
                        entry.type?.paged != true -> readDocument(settings.treeUri, entry)
                        pagedStarted && elapsed() >= pagedDeadline -> resume?.copy(entry = entry)
                            ?: WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.PENDING)
                        else -> {
                            val guaranteed = !pagedStarted
                            val stopAt = if (guaranteed) maxOf(pagedDeadline, elapsed() + checkTimeoutMs / 4)
                            else pagedDeadline
                            pagedStarted = true
                            readDocument(settings.treeUri, entry, resume, stopAt, guaranteed)
                        }
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

    private suspend fun readDocument(
        treeUri: String,
        entry: WorkspaceEntry,
        resume: WorkspaceDocument? = null,
        stopAt: Long = Long.MAX_VALUE,
        guaranteed: Boolean = true,
    ): WorkspaceDocument {
        val type = entry.type!!
        val maxBytes = when (type) {
            WorkspaceFileType.DOCX -> WorkspaceLimits.MAX_DOCX_BYTES
            WorkspaceFileType.PDF -> WorkspaceLimits.MAX_PDF_BYTES
            WorkspaceFileType.IMAGE -> WorkspaceLimits.MAX_IMAGE_BYTES
            else -> WorkspaceLimits.MAX_TEXT_BYTES
        }
        if (entry.sizeBytes!! > maxBytes) return WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.TOO_LARGE)
        if (type.paged && pageReader == null) {
            return WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.UNREADABLE)
        }
        val firstPage = (resume?.pagesRead ?: 0) + 1
        val chunks = resume?.chunks.orEmpty().toMutableList()
        var remaining = WorkspaceLimits.MAX_FILE_CHARS - chunks.sumOf { it.text.length }
        return try {
            val stream = gateway.open(treeUri, entry.documentId)
            var timedOut = false
            val content = stream.use {
                runInterruptible(Dispatchers.IO) {
                    if (type.paged) {
                        val bytes = readWorkspaceBytes(it, maxBytes).data!!
                        var asked = 0
                        pageReader!!.read(type, bytes, firstPage) { characters ->
                            // The pass's first file reads a page even when opening it spent the budget,
                            // so a slow file still progresses; the check's hard timeout still applies.
                            val opening = asked++ == 0
                            if (!(guaranteed && opening) && elapsed() >= stopAt) timedOut = true
                            timedOut || characters >= remaining
                        }
                    } else {
                        WorkspacePagedText(listOf(WorkspaceDocumentExtractor.extract(it, type)), complete = true)
                    }
                }
            }
            val after = gateway.metadata(treeUri, entry.documentId)
            if (!entry.hasSameContent(after)) throw WorkspaceCheckLimitException()
            var lostText = !content.complete && !timedOut
            var paragraphs = (chunks.maxOfOrNull { it.paragraph } ?: -1) + 1
            for ((position, page) in content.pages.withIndex()) {
                val text = if (type.paged) page.replace(PAGED_CONTROL, " ") else page
                val capped = workspaceWordPrefix(text, remaining)
                if (capped.length < text.trim().length) lostText = true
                val pageChunks = WorkspaceChunker.chunk(capped, entry.type == WorkspaceFileType.MARKDOWN,
                    onTruncated = { lostText = true })
                val first = chunks.size
                pageChunks.mapTo(chunks) { chunk ->
                    chunk.copy(ordinal = first + chunk.ordinal, paragraph = paragraphs + chunk.paragraph,
                        page = if (type == WorkspaceFileType.PDF) firstPage + position else 0,
                        visual = position in content.visualPages)
                }
                paragraphs += (pageChunks.maxOfOrNull { it.paragraph } ?: -1) + 1
                remaining -= pageChunks.sumOf { it.text.length }
                if (capped.length < text.trim().length) break
            }
            val status = when {
                lostText -> WorkspaceDocumentStatus.TRUNCATED
                timedOut -> WorkspaceDocumentStatus.PENDING
                chunks.isEmpty() && type.paged -> WorkspaceDocumentStatus.NO_TEXT
                else -> WorkspaceDocumentStatus.INDEXED
            }
            WorkspaceDocument(entry, chunks, status,
                pagesRead = if (status == WorkspaceDocumentStatus.PENDING) firstPage - 1 + content.pages.size else 0)
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

    private companion object {
        val PAGED_CONTROL = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]")
    }
}
