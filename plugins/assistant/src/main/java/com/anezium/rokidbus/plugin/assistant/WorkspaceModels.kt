package com.anezium.rokidbus.plugin.assistant

internal object WorkspaceLimits {
    const val MAX_FILES = 100
    const val MAX_ENTRIES = 1_000
    const val MAX_DIRECTORIES = 100
    const val MAX_PROVIDER_ROOTS = 64
    const val MAX_DEPTH = 4
    const val MAX_FILE_CHARS = 100_000
    const val MAX_TOTAL_CHARS = 1_000_000
    const val MAX_CHUNKS = 2_500
    const val MAX_INDEX_BYTES = 8 * 1_024 * 1_024
    const val MAX_TEXT_BYTES = 2 * 1_024 * 1_024
    const val MAX_DOCX_BYTES = 4 * 1_024 * 1_024
    const val MAX_PDF_BYTES = 32 * 1_024 * 1_024
    const val MAX_PDF_PAGES = 500
    const val MAX_IMAGE_BYTES = 24 * 1_024 * 1_024
    const val MAX_ZIP_ENTRIES = 128
    const val MAX_EXCERPT_CHARS = 2_500
    const val MAX_QUERY_CHARS = 240
    const val MAX_CHUNK_CHARS = 800
    const val TARGET_CHUNK_CHARS = 600
    const val CHECK_TIMEOUT_MS = 15_000L
    const val ACCESS_TIMEOUT_MS = 150L
    const val MAX_PERSONAL_CONTEXT_CHARS = 10_002
}

internal enum class WorkspaceFileType {
    TEXT, MARKDOWN, DOCX, PDF, IMAGE;

    /** Read page by page through [WorkspacePageReader], within a time budget and resumable across passes. */
    val paged: Boolean get() = this == PDF || this == IMAGE

    companion object {
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif")

        fun fromName(name: String): WorkspaceFileType? = when {
            name.endsWith(".txt", ignoreCase = true) -> TEXT
            name.endsWith(".md", ignoreCase = true) -> MARKDOWN
            name.endsWith(".docx", ignoreCase = true) -> DOCX
            name.endsWith(".pdf", ignoreCase = true) -> PDF
            IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } -> IMAGE
            else -> null
        }
    }
}

internal data class WorkspaceEntry(
    val documentId: String,
    val name: String,
    val relativePath: String = name,
    val modifiedAtMs: Long? = null,
    val sizeBytes: Long? = null,
    val directory: Boolean = false,
    val virtual: Boolean = false,
    val type: WorkspaceFileType? = WorkspaceFileType.fromName(name),
) {
    fun hasReliableMetadata(): Boolean = modifiedAtMs != null && modifiedAtMs > 0L &&
        sizeBytes != null && sizeBytes >= 0L

    fun hasSameContent(other: WorkspaceEntry): Boolean = documentId == other.documentId &&
        hasReliableMetadata() && other.hasReliableMetadata() &&
        modifiedAtMs == other.modifiedAtMs && sizeBytes == other.sizeBytes
}

internal data class WorkspaceChunk(
    val ordinal: Int,
    val text: String,
    val headingPath: String = "",
    val paragraph: Int = 0,
    val page: Int = 0,
    // The page also shows a chart, table, drawing, or picture its text does not carry.
    val visual: Boolean = false,
)

internal enum class WorkspaceDocumentStatus {
    INDEXED, TRUNCATED, METADATA_UNAVAILABLE, UNREADABLE, TOO_LARGE, INVALID_TEXT,
    PROTECTED, NO_TEXT,
    // Left for the next pass of the same check so one run stays within its time limit.
    PENDING;
}

internal data class WorkspaceDocument(
    val entry: WorkspaceEntry,
    val chunks: List<WorkspaceChunk>,
    val status: WorkspaceDocumentStatus = WorkspaceDocumentStatus.INDEXED,
    // Pages already read while the document is PENDING; the next pass resumes after them.
    val pagesRead: Int = 0,
)

internal data class WorkspaceIndex(
    val generation: Long,
    val documents: List<WorkspaceDocument>,
    val indexedAtMs: Long,
    val skippedFiles: Int = 0,
) {
    val fileCount: Int get() = documents.count { it.chunks.isNotEmpty() }
    val chunkCount: Int get() = documents.sumOf { it.chunks.size }
    val characterCount: Int get() = documents.sumOf { document -> document.chunks.sumOf { it.text.length } }
    val truncatedFiles: Int get() = documents.count { it.status == WorkspaceDocumentStatus.TRUNCATED }
}

internal data class WorkspaceSettings(
    val enabled: Boolean = false,
    val treeUri: String = "",
    val folderName: String = "",
    val generation: Long = 0L,
)

internal enum class WorkspaceProblem(val label: String) {
    FOLDER_UNAVAILABLE("Folder unavailable"),
    CHECK_FAILED("Folder check failed. Try Re-index now."),
    CHECK_LIMIT("Folder check incomplete: tree or time limit reached."),
    INVALID_INDEX("Index unavailable. Try Re-index now."),
    STORE_FAILED("Could not save the workspace index."),
}

internal data class WorkspaceState(
    val settings: WorkspaceSettings,
    val index: WorkspaceIndex? = null,
    val problem: WorkspaceProblem? = null,
    val validated: Boolean = false,
)

internal fun workspaceWordPrefix(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    val value = text.trim()
    if (value.length <= maxChars) return value
    if (value[maxChars].isWhitespace()) return value.take(maxChars).trimEnd()
    val candidate = value.take(maxChars)
    val boundary = candidate.indexOfLast(Char::isWhitespace)
    return if (boundary >= 0) candidate.take(boundary).trimEnd() else ""
}

internal fun workspaceLabel(text: String, maxChars: Int = 160): String =
    text.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ").trim().let { value ->
            workspaceWordPrefix(value, maxChars).ifEmpty { value.take(maxChars) }
        }

internal fun workspacePathLabel(parent: String, name: String, discriminator: Int = 0): String {
    val suffix = if (discriminator == 0) name else "[$discriminator]/$name"
    val directory = workspaceLabel(parent, (160 - suffix.length - 1).coerceAtLeast(0))
    return if (directory.isEmpty()) suffix else "$directory/$suffix"
}

internal fun workspacePromptBudget(existingContext: String): Int = minOf(
    WorkspaceLimits.MAX_EXCERPT_CHARS,
    (WorkspaceLimits.MAX_PERSONAL_CONTEXT_CHARS - existingContext.length -
        if (existingContext.isEmpty()) 0 else 2).coerceAtLeast(0),
)
