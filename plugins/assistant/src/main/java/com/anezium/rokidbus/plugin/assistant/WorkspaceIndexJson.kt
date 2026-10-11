package com.anezium.rokidbus.plugin.assistant

import org.json.JSONArray
import org.json.JSONObject

/** A committed snapshot as stored: the catalog and text, plus the posting cache when it was persisted. */
internal data class WorkspaceStoredIndex(
    val index: WorkspaceIndex,
    val lexical: WorkspaceLexicalIndex?,
    // The schema the file was written with; 1 and 2 are migrated on load.
    val schemaVersion: Int,
)

internal data class WorkspaceEncodedIndex(val text: String, val bytes: Int, val lexicalPersisted: Boolean)

internal object WorkspaceIndexJson {
    const val VERSION = 3

    fun render(index: WorkspaceIndex): String = encode(index, null).text

    fun parse(text: String): WorkspaceIndex = decode(text).index

    /**
     * Writes one snapshot. The posting cache is included only when it is within its structural
     * ceilings and the whole file still fits 8 MiB; otherwise it is omitted and rebuilt on load.
     * Retained text is never dropped to make room for it. Text plus catalog beyond the cap, or a
     * catalog beyond its 2 MiB reservation, fails the publication and leaves the committed file.
     */
    fun encode(
        index: WorkspaceIndex,
        lexical: WorkspaceLexicalIndex?,
        maxBytes: Int = WorkspaceLimits.MAX_INDEX_BYTES,
    ): WorkspaceEncodedIndex {
        validate(index)
        val writer = JsonWriter()
        writer.base(index)
        val baseBytes = utf8Length(writer.out)
        require(baseBytes + CLOSING_WITHOUT_CACHE.length <= maxBytes) { "index_too_large" }
        require(baseBytes - writer.textBytes <= WorkspaceLimits.MAX_CATALOG_BYTES) { "catalog_too_large" }
        val cache = lexical?.takeIf { it.fitsCache && it.chunkCount == index.chunkCount }?.let(writer::cache)
        val withCache = cache?.takeIf { baseBytes + utf8Length(it) <= maxBytes }
        val text = writer.out.append(withCache ?: CLOSING_WITHOUT_CACHE).toString()
        val bytes = text.toByteArray(Charsets.UTF_8).size
        check(bytes <= maxBytes)
        return WorkspaceEncodedIndex(text, bytes, withCache != null)
    }

    /** UTF-8 bytes of the catalog and framing alone, without chunk text or the posting cache. */
    fun catalogBytes(index: WorkspaceIndex): Int {
        val writer = JsonWriter()
        writer.base(index)
        return utf8Length(writer.out) - writer.textBytes + CLOSING_WITHOUT_CACHE.length
    }

    fun decode(text: String): WorkspaceStoredIndex {
        require(text.toByteArray(Charsets.UTF_8).size <= WorkspaceLimits.MAX_INDEX_BYTES)
        val root = JSONObject(text)
        return when (val version = root.getInt("version")) {
            1, 2 -> WorkspaceStoredIndex(migrate(root, version), null, version)
            VERSION -> decodeCurrent(root)
            else -> throw IllegalArgumentException("Unknown workspace index schema.")
        }
    }

    private fun decodeCurrent(root: JSONObject): WorkspaceStoredIndex {
        val extractionVersion = root.getInt("extractionVersion")
        require(extractionVersion in 1..WORKSPACE_EXTRACTION_VERSION)
        val documents = root.getJSONArray("documents")
        require(documents.length() <= WorkspaceLimits.MAX_FILES)
        var totalChunks = 0
        var totalRuns = 0
        val parsed = List(documents.length()) { position ->
            val document = documents.getJSONArray(position)
            require(document.length() == DOCUMENT_FIELDS)
            val chunks = document.getJSONArray(13)
            val runs = document.getJSONArray(12)
            totalChunks += chunks.length()
            totalRuns += runs.length()
            require(totalChunks <= WorkspaceLimits.MAX_CHUNKS && totalRuns <= WorkspaceLimits.MAX_PAGE_RUNS &&
                runs.length() <= WorkspaceLimits.MAX_PAGE_RUNS_PER_DOCUMENT)
            WorkspaceDocument(
                entry = WorkspaceEntry(
                    documentId = document.getString(0), name = document.getString(1),
                    relativePath = document.getString(2), modifiedAtMs = document.nullableLong(3),
                    sizeBytes = document.nullableLong(4), type = WorkspaceFileType.valueOf(document.getString(5)),
                ),
                status = WorkspaceDocumentStatus.valueOf(document.getString(6)),
                lookupSafe = document.getBoolean(7),
                sourceDigest = if (document.isNull(8)) null else document.getString(8),
                pageCount = document.nullableInt(9),
                coverageKnown = document.getBoolean(10),
                nextPage = document.nullableInt(11),
                pageRuns = List(runs.length()) { index ->
                    val run = runs.getJSONArray(index)
                    require(run.length() == 5)
                    WorkspacePageRun(run.getInt(0), run.getInt(1), WorkspacePageState(
                        WorkspaceTextState.values()[run.getInt(2)], WorkspaceVisualState.values()[run.getInt(3)],
                        WorkspaceRenderState.values()[run.getInt(4)]))
                },
                chunks = List(chunks.length()) { index ->
                    val chunk = chunks.getJSONArray(index)
                    require(chunk.length() == 6)
                    WorkspaceChunk(chunk.getInt(0), chunk.getString(1), chunk.getString(2), chunk.getInt(3),
                        chunk.getInt(4), chunk.getInt(5) == 1)
                },
            )
        }
        val index = WorkspaceIndex(
            generation = root.getLong("generation"), documents = parsed, indexedAtMs = root.getLong("indexedAtMs"),
            skippedFiles = root.getInt("skippedFiles"), omittedFiles = root.getInt("omittedFiles"),
            extractionVersion = extractionVersion,
        )
        require(root.getBoolean("inventoryComplete") == index.inventoryComplete)
        validate(index)
        val lexical = if (root.getBoolean("lexicalPersisted") &&
            root.getInt("lexicalVersion") == WorkspaceLexicalIndex.VERSION
        ) decodeCache(root, index.chunkCount) else null
        return WorkspaceStoredIndex(index, lexical, VERSION)
    }

    private fun decodeCache(root: JSONObject, chunkCount: Int): WorkspaceLexicalIndex {
        val lexicon = root.getJSONArray("lexicon")
        val postings = root.getJSONArray("postings")
        val counts = root.getJSONArray("tokenCounts")
        require(lexicon.length() <= WorkspaceLexicalIndex.MAX_TERMS && postings.length() == lexicon.length() &&
            counts.length() == chunkCount)
        var pairs = 0L
        for (index in 0 until postings.length()) pairs += postings.getJSONArray(index).length() / 2
        require(pairs <= WorkspaceLexicalIndex.MAX_PAIRS)
        return WorkspaceLexicalIndex.restore(
            Array(lexicon.length()) { lexicon.getString(it) },
            Array(postings.length()) { index ->
                val list = postings.getJSONArray(index)
                IntArray(list.length()) { list.getInt(it) }
            },
            IntArray(counts.length()) { counts.getInt(it) },
            chunkCount,
        )
    }

    /**
     * Version 1 read PDFs and images without visual marks, so they are dropped and read again while
     * text documents stay cached. Version 2 text is kept as legacy evidence: its pages carry
     * LEGACY_TEXT, total page count and digest stay unknown, and labels are unsafe for lookup until
     * the next check reconciles them. A pending file resumes after its last read page.
     */
    private fun migrate(root: JSONObject, version: Int): WorkspaceIndex {
        val documents = root.getJSONArray("documents")
        require(documents.length() <= WorkspaceLimits.MAX_FILES)
        var totalChunks = 0
        val parsed = List(documents.length()) { position ->
            val document = documents.getJSONObject(position)
            val chunks = document.getJSONArray("chunks")
            totalChunks += chunks.length()
            require(totalChunks <= WorkspaceLimits.MAX_CHUNKS)
            val type = WorkspaceFileType.valueOf(document.getString("type"))
            val status = WorkspaceDocumentStatus.valueOf(document.getString("status"))
            val pagesRead = document.optInt("pagesRead", 0)
            require(pagesRead in 0..WorkspaceLimits.MAX_PDF_PAGES)
            val chunkList = List(chunks.length()) { ordinal ->
                val chunk = chunks.getJSONObject(ordinal)
                WorkspaceChunk(chunk.getInt("ordinal"), chunk.getString("text"),
                    chunk.getString("heading"), chunk.getInt("paragraph"), chunk.optInt("page", 0),
                    chunk.optBoolean("visual", false))
            }
            val textPages = when (type) {
                WorkspaceFileType.PDF -> chunkList.map { it.page }.filter { it in 1..WorkspaceLimits.MAX_PDF_PAGES }
                WorkspaceFileType.IMAGE -> if (chunkList.isEmpty()) emptyList() else listOf(1)
                else -> emptyList()
            }
            WorkspaceDocument(
                entry = WorkspaceEntry(
                    documentId = document.getString("id"), name = document.getString("name"),
                    relativePath = document.getString("path"),
                    modifiedAtMs = document.nullableLong("modifiedAtMs"),
                    sizeBytes = document.nullableLong("sizeBytes"), type = type,
                ),
                chunks = chunkList,
                status = status,
                nextPage = if (status == WorkspaceDocumentStatus.PENDING) pagesRead + 1 else null,
                pageRuns = WorkspacePageCatalog.compress(textPages.associateWith { WorkspacePageState.LEGACY_TEXT }),
                coverageKnown = !type.paged && status == WorkspaceDocumentStatus.INDEXED,
            )
        }
        val index = WorkspaceIndex(
            generation = root.getLong("generation"), indexedAtMs = root.getLong("indexedAtMs"),
            skippedFiles = root.getInt("skippedFiles"),
            documents = if (version == 1) parsed.filterNot { it.entry.type?.paged == true } else parsed,
        )
        validate(index)
        return index
    }

    private fun validate(index: WorkspaceIndex) {
        require(index.generation >= 0 && index.indexedAtMs > 0 && index.skippedFiles in 0..WorkspaceLimits.MAX_ENTRIES)
        require(index.omittedFiles in 0..WorkspaceLimits.MAX_ENTRIES)
        require(index.documents.size <= WorkspaceLimits.MAX_FILES && index.chunkCount <= WorkspaceLimits.MAX_CHUNKS)
        require(index.characterCount <= WorkspaceLimits.MAX_TOTAL_CHARS)
        require(index.documents.map { it.entry.documentId }.distinct().size == index.documents.size)
        require(index.documents.sumOf { it.pageRuns.size } <= WorkspaceLimits.MAX_PAGE_RUNS)
        index.documents.forEach { document ->
            val entry = document.entry
            val type = entry.type
            require(entry.documentId.isNotEmpty() && entry.documentId.length <= 1_024 && type != null)
            require(entry.name.length <= 96 && entry.relativePath.length <= 160)
            require(document.chunks.sumOf { it.text.length } <= WorkspaceLimits.MAX_FILE_CHARS)
            require(document.chunks.map { it.ordinal }.distinct().size == document.chunks.size)
            require((document.status == WorkspaceDocumentStatus.PENDING) == (document.nextPage != null))
            require(document.nextPage == null || document.nextPage in 1..WorkspaceLimits.MAX_PDF_PAGES + 1)
            require(document.sourceDigest == null || DIGEST.matches(document.sourceDigest))
            require(WorkspacePageCatalog.isValid(document.pageRuns, document.pageCount))
            when (type) {
                WorkspaceFileType.PDF -> require(document.pageCount == null || document.pageCount >= 1)
                WorkspaceFileType.IMAGE -> require(document.pageCount == null || document.pageCount == 1)
                else -> require(document.pageCount == null && document.pageRuns.isEmpty())
            }
            if (document.chunks.isNotEmpty()) {
                require(entry.hasReliableMetadata())
                require(document.status in setOf(WorkspaceDocumentStatus.INDEXED, WorkspaceDocumentStatus.TRUNCATED,
                    WorkspaceDocumentStatus.PENDING))
            }
            document.chunks.forEach { chunk ->
                require(chunk.ordinal >= 0 && chunk.paragraph >= 0 && chunk.page >= 0 && chunk.headingPath.length <= 160)
                require(chunk.page <= WorkspaceLimits.MAX_PDF_PAGES)
                require(chunk.text.isNotBlank() && chunk.text.length <= WorkspaceLimits.MAX_CHUNK_CHARS)
            }
        }
    }

    /**
     * A plain writer with one escaping rule on every platform, so the size checked here is the size
     * written: only quotes, backslashes, control characters, line separators, and unpaired
     * surrogates are escaped.
     */
    private class JsonWriter {
        val out = StringBuilder()
        var textBytes = 0

        fun base(index: WorkspaceIndex) {
            out.append("{\"version\":").append(VERSION)
                .append(",\"generation\":").append(index.generation)
                .append(",\"indexedAtMs\":").append(index.indexedAtMs)
                .append(",\"skippedFiles\":").append(index.skippedFiles)
                .append(",\"omittedFiles\":").append(index.omittedFiles)
                .append(",\"inventoryComplete\":").append(index.inventoryComplete)
                .append(",\"inventoryLimitReason\":")
            index.inventoryLimit?.let { string(it.name) } ?: out.append("null")
            out.append(",\"extractionVersion\":").append(index.extractionVersion)
                .append(",\"lexicalVersion\":").append(WorkspaceLexicalIndex.VERSION)
                .append(",\"documents\":[")
            index.documents.forEachIndexed { position, document ->
                if (position > 0) out.append(',')
                val entry = document.entry
                out.append('[')
                string(entry.documentId); out.append(',')
                string(entry.name); out.append(',')
                string(entry.relativePath); out.append(',')
                out.append(entry.modifiedAtMs ?: "null").append(',')
                out.append(entry.sizeBytes ?: "null").append(',')
                string(entry.type!!.name); out.append(',')
                string(document.status.name); out.append(',')
                out.append(document.lookupSafe).append(',')
                document.sourceDigest?.let(::string) ?: out.append("null")
                out.append(',').append(document.pageCount ?: "null")
                out.append(',').append(document.coverageKnown)
                out.append(',').append(document.nextPage ?: "null")
                out.append(",[")
                document.pageRuns.forEachIndexed { index, run ->
                    if (index > 0) out.append(',')
                    out.append('[').append(run.start).append(',').append(run.end).append(',')
                        .append(run.state.text.ordinal).append(',').append(run.state.visual.ordinal).append(',')
                        .append(run.state.render.ordinal).append(']')
                }
                out.append("],[")
                document.chunks.forEachIndexed { index, chunk ->
                    if (index > 0) out.append(',')
                    out.append('[').append(chunk.ordinal).append(',')
                    val start = out.length
                    string(chunk.text)
                    textBytes += utf8Length(out, start, out.length)
                    out.append(',')
                    string(chunk.headingPath)
                    out.append(',').append(chunk.paragraph).append(',').append(chunk.page).append(',')
                        .append(if (chunk.visual) 1 else 0).append(']')
                }
                out.append("]]")
            }
            out.append(']')
        }

        fun cache(lexical: WorkspaceLexicalIndex): String {
            val saved = out.length
            out.append(",\"lexicalPersisted\":true,\"lexicon\":[")
            lexical.terms.forEachIndexed { index, term ->
                if (index > 0) out.append(',')
                string(term)
            }
            out.append("],\"postings\":[")
            lexical.postings.forEachIndexed { index, list ->
                if (index > 0) out.append(',')
                out.append('[')
                list.forEachIndexed { offset, value ->
                    if (offset > 0) out.append(',')
                    out.append(value)
                }
                out.append(']')
            }
            out.append("],\"tokenCounts\":[")
            lexical.tokenCounts.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                out.append(value)
            }
            out.append("]}")
            val section = out.substring(saved)
            out.setLength(saved)
            return section
        }

        fun string(value: String) = appendWorkspaceJsonString(out, value)
    }

    fun utf8Length(text: CharSequence, start: Int = 0, end: Int = text.length): Int {
        var bytes = 0
        var index = start
        while (index < end) {
            val c = text[index]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && index + 1 < end && Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            index++
        }
        return bytes
    }

    private const val DOCUMENT_FIELDS = 14
    private const val CLOSING_WITHOUT_CACHE = ",\"lexicalPersisted\":false}"
    private val DIGEST = Regex("[0-9a-f]{64}")

    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONArray.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun JSONArray.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
}
