package com.anezium.rokidbus.plugin.assistant

internal data class WorkspaceIndexCounts(
    val catalogFiles: Int,
    val searchableFiles: Int,
    val knownPages: Int,
    val processedPages: Int,
    val noTextPages: Int,
    val unreadPages: Int,
    val pendingFiles: Int,
    val partialFiles: Int,
)

/** The Workspace card's concise status. "Ready" means work within the declared limits finished. */
internal object WorkspaceStatusText {
    fun counts(index: WorkspaceIndex): WorkspaceIndexCounts {
        var known = 0
        var processed = 0
        var noText = 0
        var unread = 0
        index.documents.filter { it.entry.type?.paged == true }.forEach { document ->
            val counts = WorkspacePageCatalog.counts(document)
            known += document.pageCount?.let { minOf(it, WorkspaceLimits.MAX_PDF_PAGES) } ?: 0
            processed += counts.filterKeys { it.terminal }.values.sum()
            noText += counts[WorkspaceTextState.EMPTY] ?: 0
            unread += (counts[WorkspaceTextState.FAILED] ?: 0) + (counts[WorkspaceTextState.TRUNCATED] ?: 0)
        }
        return WorkspaceIndexCounts(
            catalogFiles = index.documents.size,
            searchableFiles = index.fileCount,
            knownPages = known,
            processedPages = processed,
            noTextPages = noText,
            unreadPages = unread,
            pendingFiles = index.documents.count { it.status == WorkspaceDocumentStatus.PENDING },
            partialFiles = index.documents.count {
                it.status == WorkspaceDocumentStatus.TRUNCATED || it.entry.type?.paged == true && !it.coverageKnown &&
                    it.status in VIEWABLE_STATUSES
            } + index.omittedFiles,
        )
    }

    fun state(index: WorkspaceIndex?, checking: Boolean): String {
        if (index == null) return if (checking) "Checking folder" else "Not indexed yet"
        val counts = counts(index)
        return when {
            checking && counts.pendingFiles > 0 && counts.knownPages > 0 ->
                "Indexing ${counts.processedPages}/${counts.knownPages} pages"
            checking -> "Checking folder"
            counts.searchableFiles == 0 && index.documents.any {
                it.entry.type?.paged == true && it.status in VIEWABLE_STATUSES
            } -> "No readable text; pages can be viewed"
            counts.pendingFiles > 0 || counts.partialFiles > 0 -> "Partially indexed"
            else -> "Ready"
        }
    }

    fun summary(index: WorkspaceIndex): String {
        val counts = counts(index)
        val pages = if (counts.knownPages > 0) {
            " · pages ${counts.processedPages}/${counts.knownPages} processed" +
                (if (counts.noTextPages > 0) ", ${counts.noTextPages} without text" else "") +
                (if (counts.unreadPages > 0) ", ${counts.unreadPages} not fully read" else "")
        } else ""
        val omitted = if (index.omittedFiles > 0) " · ${index.omittedFiles} files beyond the 100-file limit" else ""
        return "${counts.catalogFiles} files cataloged · ${counts.searchableFiles} with searchable text$pages$omitted"
    }
}
