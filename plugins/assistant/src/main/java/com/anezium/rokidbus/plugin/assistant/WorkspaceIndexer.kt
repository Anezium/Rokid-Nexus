package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

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

/**
 * Documents an explicit Re-index asked to verify: their bytes are hashed again even though their
 * metadata did not change, and a FAILED page gets one more attempt. Ordinary and observer passes
 * never do either. Passes of the same active session work through it under their own deadlines.
 */
internal class WorkspaceVerification(documentIds: Collection<String>) {
    private val remaining = LinkedHashSet(documentIds)
    private val hashed = mutableSetOf<String>()
    private val retried = mutableMapOf<String, Set<Int>>()

    @Synchronized fun contains(documentId: String): Boolean = documentId in remaining
    @Synchronized fun done(documentId: String) { remaining.remove(documentId) }
    @Synchronized fun retain(documentIds: Set<String>) { remaining.retainAll(documentIds) }
    @Synchronized fun hashed(documentId: String): Boolean = documentId in hashed
    @Synchronized fun markHashed(documentId: String) { hashed += documentId }
    @Synchronized fun retried(documentId: String): Set<Int> = retried[documentId].orEmpty()
    @Synchronized fun markRetried(documentId: String, pages: Collection<Int>) {
        retried[documentId] = retried[documentId].orEmpty() + pages
    }
    val size: Int @Synchronized get() = remaining.size
}

internal class WorkspaceIndexer(
    private val store: WorkspaceStore,
    private val gateway: WorkspaceDocumentGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val checkTimeoutMs: Long = WorkspaceLimits.CHECK_TIMEOUT_MS,
    private val pageReader: WorkspacePageReader? = null,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val gate: WorkspaceHeavyGate = WorkspaceHeavyGate(),
    private val verification: WorkspaceVerification? = null,
    // Called once this pass has committed (or found no need for) its metadata reconciliation.
    private val onReconciled: () -> Unit = {},
) {
    /**
     * PDFs and images are slow to read, so a pass spends only the first half of its check on them
     * and leaves the rest for the next pass. The first heavy operation of a pass always runs, so
     * every pass progresses.
     */
    private inner class PassBudget {
        val deadline = elapsed() + checkTimeoutMs / 2
        var started = false

        fun available(): Boolean = !started || elapsed() < deadline

        /** The stop time for one heavy operation, and whether it may always read one page. */
        fun begin(): Pair<Long, Boolean> {
            val guaranteed = !started
            started = true
            return (if (guaranteed) maxOf(deadline, elapsed() + checkTimeoutMs / 4) else deadline) to guaranteed
        }
    }

    private var opens = 0
    private var pagesRead = 0
    private var inspections = 0

    suspend fun refresh() {
        val before = store.snapshot().state
        val settings = before.settings
        if (!settings.enabled || settings.treeUri.isEmpty()) return
        val started = elapsed()
        var rootChecked = false
        var outcome = "published"
        val budget = PassBudget()
        try {
            if (!gateway.hasReadGrant(settings.treeUri)) {
                store.folderUnavailable(settings.generation)
                outcome = "folder_unavailable"
                return
            }
            withTimeout(checkTimeoutMs) {
                if (!gateway.isLocalTree(settings.treeUri)) throw SecurityException()
                val root = gateway.root(settings.treeUri)
                if (!root.directory) throw FileNotFoundException()
                rootChecked = true
                val walked = walk(settings.treeUri, root.documentId)
                val entries = walked.map { it.first }
                val safe = walked.associate { it.first.documentId to it.second }
                val supported = entries.filter { it.type != null && !it.directory && !it.virtual }
                    .sortedWith(compareBy<WorkspaceEntry> { it.relativePath }.thenBy { it.documentId })
                val selected = supported.take(WorkspaceLimits.MAX_FILES)
                val omitted = (supported.size - WorkspaceLimits.MAX_FILES).coerceAtLeast(0)
                val unsupported = entries.count { !it.directory && (it.type == null || it.virtual) }
                verification?.retain(selected.map { it.documentId }.toSet())
                val old = before.index?.documents?.associateBy { it.entry.documentId }.orEmpty()
                val staleExtraction = before.index?.let { it.extractionVersion != WORKSPACE_EXTRACTION_VERSION } == true

                // Metadata first: a changed or removed file stops being searchable in a committed
                // snapshot before any slow extraction of its replacement begins.
                val documents = selected.map { entry ->
                    val previous = old[entry.documentId]
                    val lookupSafe = safe[entry.documentId] == true
                    when {
                        !entry.hasReliableMetadata() -> WorkspaceDocument(entry, emptyList(),
                            WorkspaceDocumentStatus.METADATA_UNAVAILABLE, lookupSafe = lookupSafe)
                        previous != null && !staleExtraction && entry.hasSameContent(previous.entry) ->
                            previous.copy(entry = entry, lookupSafe = lookupSafe)
                        else -> pending(entry, lookupSafe)
                    }
                }.toMutableList()
                val reconciled = WorkspaceIndex(settings.generation, documents.toList(), clock(),
                    unsupported + omitted, omitted)
                if (before.index != null && workspaceRetracts(before.index, reconciled)) {
                    if (!store.publish(retain(reconciled))) return@withTimeout
                }
                onReconciled()

                // New, changed, and pending files.
                for ((position, document) in documents.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(settings.generation)) return@withTimeout
                    if (document.status != WorkspaceDocumentStatus.PENDING) continue
                    val type = document.entry.type!!
                    documents[position] = when {
                        !type.paged -> readDocument(settings.treeUri, document)
                        !budget.available() -> document
                        else -> budget.begin().let { (stopAt, guaranteed) ->
                            readDocument(settings.treeUri, document, stopAt, guaranteed)
                        }
                    }
                }
                // Digests missing from an older index, then an explicit verification, then pages an
                // older index never recorded: all after new work, all within the same pass budget.
                for ((position, document) in documents.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(settings.generation)) return@withTimeout
                    if (!budget.available()) break
                    if (needsDigest(document)) {
                        budget.begin()
                        documents[position] = establishDigest(settings.treeUri, document)
                    }
                }
                verification?.let { requested ->
                    for ((position, document) in documents.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (!store.isCurrent(settings.generation)) return@withTimeout
                        if (!requested.contains(document.entry.documentId)) continue
                        if (!budget.available()) break
                        documents[position] = verify(settings.treeUri, document, budget, requested)
                    }
                }
                for ((position, document) in documents.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!store.isCurrent(settings.generation)) return@withTimeout
                    if (!budget.available()) break
                    if (backfillPages(document).isEmpty()) continue
                    val (stopAt, guaranteed) = budget.begin()
                    documents[position] = backfill(settings.treeUri, document, stopAt, guaranteed)
                }

                if (!gateway.hasReadGrant(settings.treeUri)) throw SecurityException()
                currentCoroutineContext().ensureActive()
                store.publish(retain(WorkspaceIndex(settings.generation, documents.toList(), clock(), unsupported + omitted,
                    omitted)))
            }
        } catch (_: TimeoutCancellationException) {
            outcome = "check_limit"
            store.failed(settings.generation, WorkspaceProblem.CHECK_LIMIT)
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (_: SecurityException) {
            outcome = "access_lost"
            if (!rootChecked || !gateway.hasReadGrant(settings.treeUri)) store.folderUnavailable(settings.generation)
            else store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
        } catch (_: FileNotFoundException) {
            outcome = "root_missing"
            if (!rootChecked) store.folderUnavailable(settings.generation)
            else store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
        } catch (_: WorkspaceCheckLimitException) {
            outcome = "check_limit"
            store.failed(settings.generation, WorkspaceProblem.CHECK_LIMIT)
        } catch (_: Exception) {
            outcome = "check_failed"
            store.failed(settings.generation, WorkspaceProblem.CHECK_FAILED)
        } finally {
            WorkspaceDiagnostics.event("index_pass", "result" to outcome, "ms" to elapsed() - started,
                "opens" to opens, "pages" to pagesRead, "inspections" to inspections)
        }
    }

    private fun pending(entry: WorkspaceEntry, lookupSafe: Boolean) = WorkspaceDocument(entry, emptyList(),
        WorkspaceDocumentStatus.PENDING, nextPage = 1, lookupSafe = lookupSafe)

    /**
     * Applies the shared chunk and character limits in document order; lost text marks its pages.
     * Files left without text count as skipped, as before.
     */
    private fun retain(index: WorkspaceIndex): WorkspaceIndex {
        var totalChars = 0
        var totalChunks = 0
        val documents = index.documents.map { document ->
            val retained = document.chunks.takeWhile { chunk ->
                if (totalChunks >= WorkspaceLimits.MAX_CHUNKS ||
                    totalChars + chunk.text.length > WorkspaceLimits.MAX_TOTAL_CHARS
                ) false else {
                    totalChunks++
                    totalChars += chunk.text.length
                    true
                }
            }
            if (retained.size == document.chunks.size) return@map document
            val lost = document.chunks.drop(retained.size).map { document.catalogPage(it) }.filter { it > 0 }.toSet()
            document.copy(chunks = retained, status = WorkspaceDocumentStatus.TRUNCATED, nextPage = null,
                coverageKnown = false, pageRuns = WorkspacePageCatalog.with(document.pageRuns,
                    lost.associateWith { document.pageState(it).copy(text = WorkspaceTextState.TRUNCATED) }))
        }
        return index.copy(documents = documents, skippedFiles = index.skippedFiles + documents.count { it.chunks.isEmpty() })
    }

    private suspend fun walk(treeUri: String, rootId: String): List<Pair<WorkspaceEntry, Boolean>> {
        data class Directory(val id: String, val path: String, val depth: Int, val lossless: Boolean)
        val queue = ArrayDeque<Directory>()
        val visited = mutableSetOf(rootId)
        val labels = mutableSetOf<String>()
        val entries = mutableListOf<Pair<WorkspaceEntry, Boolean>>()
        var queries = 0
        queue.add(Directory(rootId, "", 0, true))
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
                // A label is safe for lookup only when it spells the provider's own names in full.
                var lossless = directory.lossless && name == entry.name &&
                    path == if (directory.path.isEmpty()) name else "${directory.path}/$name"
                var discriminator = 2
                while (!labels.add(path)) {
                    path = workspacePathLabel(directory.path, name, discriminator++)
                    lossless = false
                }
                entries += entry.copy(name = name, relativePath = path) to lossless
                if (entry.directory) {
                    if (directory.depth >= WorkspaceLimits.MAX_DEPTH) throw WorkspaceCheckLimitException()
                    queue.add(Directory(entry.documentId, path, directory.depth + 1, lossless))
                }
            }
        }
        return entries
    }

    private fun maxBytes(type: WorkspaceFileType) = when (type) {
        WorkspaceFileType.DOCX -> WorkspaceLimits.MAX_DOCX_BYTES
        WorkspaceFileType.PDF -> WorkspaceLimits.MAX_PDF_BYTES
        WorkspaceFileType.IMAGE -> WorkspaceLimits.MAX_IMAGE_BYTES
        else -> WorkspaceLimits.MAX_TEXT_BYTES
    }

    /** Reads the bytes once and checks the metadata did not move while they were read. */
    private suspend fun readBytes(treeUri: String, entry: WorkspaceEntry): ByteArray {
        val bytes = gateway.open(treeUri, entry.documentId).use { stream ->
            opens++
            runInterruptible(Dispatchers.IO) { readWorkspaceBytes(stream, maxBytes(entry.type!!)).data!! }
        }
        if (!entry.hasSameContent(gateway.metadata(treeUri, entry.documentId))) throw WorkspaceCheckLimitException()
        return bytes
    }

    private fun needsDigest(document: WorkspaceDocument): Boolean = document.sourceDigest == null &&
        document.status != WorkspaceDocumentStatus.PENDING && document.entry.hasReliableMetadata() &&
        (document.chunks.isNotEmpty() || document.status == WorkspaceDocumentStatus.NO_TEXT) &&
        (document.entry.type?.paged != true || pageReader != null)

    /**
     * Records the digest and page count an older index never stored. Its retained text is reused
     * on the strength of unchanged reliable metadata, as before: this first digest is a new
     * observation, not proof the old text matches historical bytes. No page is extracted here.
     */
    private suspend fun establishDigest(treeUri: String, document: WorkspaceDocument): WorkspaceDocument {
        val bytes = try {
            readBytes(treeUri, document.entry)
        } catch (limit: WorkspaceCheckLimitException) {
            throw limit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return document
        }
        val digested = document.copy(sourceDigest = sha256(bytes))
        val type = document.entry.type!!
        if (!type.paged) return digested
        val count = try {
            runInterruptible(Dispatchers.IO) { gate.runIndexing { pageReader!!.inspect(type, bytes) } }.pageCount
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The digest still records what was read, so this does not repeat; the count stays unknown.
            return digested
        } finally {
            inspections++
        }
        return withPageCount(digested, count)
    }

    /**
     * The true page count of a document whose earlier pages an older index read without recording
     * them: those without retained text become LEGACY_UNKNOWN rather than an empty extraction.
     */
    private fun withPageCount(document: WorkspaceDocument, pageCount: Int): WorkspaceDocument {
        val reached = minOf(pageCount, WorkspaceLimits.MAX_PDF_PAGES, (document.nextPage ?: Int.MAX_VALUE) - 1)
        val unknown = (1..reached).filter { document.pageState(it).text == WorkspaceTextState.UNATTEMPTED }
        val runs = WorkspacePageCatalog.with(document.pageRuns.filter { it.start <= pageCount }
            .map { it.copy(end = minOf(it.end, pageCount)) }, unknown.associateWith { WorkspacePageState.LEGACY_UNKNOWN })
        return document.copy(pageCount = pageCount, pageRuns = runs, coverageKnown = coverageKnown(document.status, pageCount, runs))
    }

    private fun coverageKnown(status: WorkspaceDocumentStatus, pageCount: Int?, runs: List<WorkspacePageRun>): Boolean {
        if (status == WorkspaceDocumentStatus.PENDING || pageCount == null || pageCount > WorkspaceLimits.MAX_PDF_PAGES) {
            return false
        }
        val explicit = runs.sumOf { it.end - it.start + 1 }
        return explicit == pageCount && runs.none {
            it.state.text == WorkspaceTextState.LEGACY_UNKNOWN || it.state.text == WorkspaceTextState.LEGACY_TEXT
        }
    }

    private fun backfillPages(document: WorkspaceDocument): List<Int> {
        if (pageReader == null || document.status == WorkspaceDocumentStatus.PENDING || document.sourceDigest == null ||
            document.pageCount == null || document.chunks.sumOf { it.text.length } >= WorkspaceLimits.MAX_FILE_CHARS
        ) return emptyList()
        return WorkspacePageCatalog.pagesIn(document) { it == WorkspaceTextState.LEGACY_UNKNOWN }
    }

    /** An explicit Re-index hashes an unchanged file again once and retries each FAILED page once. */
    private suspend fun verify(treeUri: String, document: WorkspaceDocument, budget: PassBudget,
        requested: WorkspaceVerification): WorkspaceDocument {
        val id = document.entry.documentId
        val type = document.entry.type!!
        if (!document.entry.hasReliableMetadata() || document.status == WorkspaceDocumentStatus.PENDING ||
            document.sourceDigest == null || type.paged && pageReader == null) {
            requested.done(id)
            return document
        }
        val retry = WorkspacePageCatalog.pagesIn(document) { it == WorkspaceTextState.FAILED }.toSet() -
            requested.retried(id)
        if (requested.hashed(id) && retry.isEmpty()) {
            requested.done(id)
            return document
        }
        budget.begin()
        val bytes = try {
            readBytes(treeUri, document.entry)
        } catch (limit: WorkspaceCheckLimitException) {
            throw limit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            requested.done(id)
            return document
        }
        requested.markHashed(id)
        if (sha256(bytes) != document.sourceDigest) {
            requested.done(id)
            val restarted = pending(document.entry, document.lookupSafe)
            return if (type.paged) restarted else readDocument(treeUri, restarted)
        }
        if (retry.isEmpty()) {
            requested.done(id)
            return document
        }
        if (!budget.available()) return document
        val (stopAt, guaranteed) = budget.begin()
        val (updated, reached) = extendPages(document, bytes, retry, stopAt, guaranteed)
        requested.markRetried(id, reached)
        if (retry.all { it in reached }) requested.done(id)
        return updated
    }

    private suspend fun backfill(treeUri: String, document: WorkspaceDocument, stopAt: Long,
        guaranteed: Boolean): WorkspaceDocument {
        val bytes = try {
            readBytes(treeUri, document.entry)
        } catch (limit: WorkspaceCheckLimitException) {
            throw limit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return document
        }
        if (sha256(bytes) != document.sourceDigest) return pending(document.entry, document.lookupSafe)
        return extendPages(document, bytes, backfillPages(document).toSet(), stopAt, guaranteed).first
    }

    /**
     * Reads only [pages] of an already indexed document, skipping every other page before
     * extraction, and appends their text after the retained chunks so earlier evidence is unchanged.
     * Returns the document with the pages this read reached; the others keep their state.
     */
    private suspend fun extendPages(document: WorkspaceDocument, bytes: ByteArray, pages: Set<Int>, stopAt: Long,
        guaranteed: Boolean): Pair<WorkspaceDocument, Set<Int>> {
        val type = document.entry.type!!
        if (pages.isEmpty()) return document to emptySet()
        var asked = 0
        val content = try {
            runInterruptible(Dispatchers.IO) {
                gate.runIndexing {
                    pageReader!!.read(type, bytes, pages.min(), shouldStop = { _ ->
                        val opening = asked++ == 0
                        !(guaranteed && opening) && (elapsed() >= stopAt || gate.viewWaiting)
                    }, skip = { it !in pages })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return document to emptySet()
        }
        pagesRead += content.pages.size
        val chunks = document.chunks.toMutableList()
        var remaining = WorkspaceLimits.MAX_FILE_CHARS - chunks.sumOf { it.text.length }
        val updates = mutableMapOf<Int, WorkspacePageState>()
        for (read in content.pages) {
            val added = appendPage(document.entry, read, chunks, remaining)
            remaining -= added.characters
            updates[read.page] = added.state
            if (added.capped) break
        }
        val runs = WorkspacePageCatalog.with(document.pageRuns, updates)
        val status = finalStatus(document.status == WorkspaceDocumentStatus.TRUNCATED, false, document.pageCount, runs, chunks)
        return document.copy(chunks = chunks, status = status, pageRuns = runs,
            coverageKnown = coverageKnown(status, document.pageCount, runs)) to updates.keys
    }

    private fun finalStatus(lostText: Boolean, timedOut: Boolean, pageCount: Int?, runs: List<WorkspacePageRun>,
        chunks: List<WorkspaceChunk>): WorkspaceDocumentStatus = when {
        lostText -> WorkspaceDocumentStatus.TRUNCATED
        timedOut -> WorkspaceDocumentStatus.PENDING
        (pageCount ?: 0) > WorkspaceLimits.MAX_PDF_PAGES ||
            runs.any { it.state.text == WorkspaceTextState.TRUNCATED } -> WorkspaceDocumentStatus.TRUNCATED
        chunks.isEmpty() -> WorkspaceDocumentStatus.NO_TEXT
        else -> WorkspaceDocumentStatus.INDEXED
    }

    private data class AppendedPage(val characters: Int, val state: WorkspacePageState, val capped: Boolean)

    /**
     * Chunks one page's text after [chunks] within [remaining] characters. Text the character or
     * chunk limits cut makes the page TRUNCATED; [AppendedPage.capped] means no room is left.
     */
    private fun appendPage(entry: WorkspaceEntry, read: WorkspaceReadPage, chunks: MutableList<WorkspaceChunk>,
        remaining: Int): AppendedPage {
        val text = read.text.replace(PAGED_CONTROL, " ")
        val capped = workspaceWordPrefix(text, remaining)
        val cut = capped.length < text.trim().length
        var truncated = cut
        val pageChunks = WorkspaceChunker.chunk(capped, false, onTruncated = { truncated = true })
        val paragraphs = (chunks.maxOfOrNull { it.paragraph } ?: -1) + 1
        val nextOrdinal = (chunks.maxOfOrNull { it.ordinal } ?: -1) + 1
        pageChunks.mapTo(chunks) { chunk ->
            chunk.copy(ordinal = nextOrdinal + chunk.ordinal, paragraph = paragraphs + chunk.paragraph,
                page = if (entry.type == WorkspaceFileType.PDF) read.page else 0,
                visual = read.state.visual == WorkspaceVisualState.PRESENT)
        }
        val state = if (truncated) read.state.copy(text = WorkspaceTextState.TRUNCATED) else read.state
        return AppendedPage(pageChunks.sumOf { it.text.length }, state, cut)
    }

    private suspend fun readDocument(
        treeUri: String,
        document: WorkspaceDocument,
        stopAt: Long = Long.MAX_VALUE,
        guaranteed: Boolean = true,
    ): WorkspaceDocument {
        val entry = document.entry
        val type = entry.type!!
        val lookupSafe = document.lookupSafe
        if (entry.sizeBytes!! > maxBytes(type)) {
            return WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.TOO_LARGE, lookupSafe = lookupSafe)
        }
        if (type.paged && pageReader == null) {
            return WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.UNREADABLE, lookupSafe = lookupSafe)
        }
        return try {
            val bytes = readBytes(treeUri, entry)
            val digest = sha256(bytes)
            if (!type.paged) {
                val text = runInterruptible(Dispatchers.IO) {
                    WorkspaceDocumentExtractor.extract(ByteArrayInputStream(bytes), type)
                }
                var lostText = false
                val chunks = WorkspaceChunker.chunk(workspaceWordPrefix(text, WorkspaceLimits.MAX_FILE_CHARS).also {
                    if (it.length < text.trim().length) lostText = true
                }, type == WorkspaceFileType.MARKDOWN, onTruncated = { lostText = true })
                val status = if (lostText) WorkspaceDocumentStatus.TRUNCATED else WorkspaceDocumentStatus.INDEXED
                return WorkspaceDocument(entry, chunks, status, sourceDigest = digest, lookupSafe = lookupSafe,
                    coverageKnown = !lostText)
            }
            // Bytes that differ from the digest a pending read began with restart it from page one.
            val resume = document.takeIf { it.sourceDigest == null || it.sourceDigest == digest }
                ?: pending(entry, lookupSafe)
            val firstPage = resume.nextPage ?: 1
            val chunks = resume.chunks.toMutableList()
            var remaining = WorkspaceLimits.MAX_FILE_CHARS - chunks.sumOf { it.text.length }
            var timedOut = false
            var asked = 0
            val content = runInterruptible(Dispatchers.IO) {
                gate.runIndexing {
                    pageReader!!.read(type, bytes, firstPage, shouldStop = { characters ->
                        // The pass's first file reads a page even when opening it spent the budget,
                        // so a slow file still progresses; the check's hard timeout still applies.
                        val opening = asked++ == 0
                        if (!(guaranteed && opening) && (elapsed() >= stopAt || gate.viewWaiting)) timedOut = true
                        timedOut || characters >= remaining
                    })
                }
            }
            pagesRead += content.pages.size
            var lostText = !content.complete && !timedOut
            val updates = mutableMapOf<Int, WorkspacePageState>()
            for (read in content.pages) {
                val added = appendPage(entry, read, chunks, remaining)
                remaining -= added.characters
                updates[read.page] = added.state
                if (added.capped) {
                    lostText = true
                    break
                }
            }
            val pageCount = content.pageCount ?: resume.pageCount
            var updated = resume.copy(sourceDigest = digest)
            if (pageCount != null && resume.pageCount == null) updated = withPageCount(updated, pageCount)
            val runs = WorkspacePageCatalog.with(updated.pageRuns, updates)
            val reached = (content.pages.maxOfOrNull { it.page } ?: (firstPage - 1)) + 1
            val status = finalStatus(lostText, timedOut, pageCount, runs, chunks)
            updated.copy(chunks = chunks, status = status, pageCount = pageCount, pageRuns = runs,
                nextPage = if (status == WorkspaceDocumentStatus.PENDING) reached else null,
                coverageKnown = coverageKnown(status, pageCount, runs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: WorkspaceCheckLimitException) {
            throw limit
        } catch (error: WorkspaceReadException) {
            WorkspaceDocument(entry, emptyList(), error.status, lookupSafe = lookupSafe)
        } catch (_: CharacterCodingException) {
            WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.INVALID_TEXT, lookupSafe = lookupSafe)
        } catch (_: org.xml.sax.SAXException) {
            WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.INVALID_TEXT, lookupSafe = lookupSafe)
        } catch (_: Exception) {
            WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.UNREADABLE, lookupSafe = lookupSafe)
        }
    }

    companion object {
        private val PAGED_CONTROL = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
