package com.anezium.rokidbus.plugin.assistant

/**
 * What indexing knows about one page's text. [LEGACY_TEXT] is text an older index retained without
 * recording how it was read or what the page shows; [LEGACY_UNKNOWN] is a known page an older index
 * never recorded. Neither claims a complete or empty extraction.
 */
internal enum class WorkspaceTextState {
    UNATTEMPTED, NATIVE, OCR, EMPTY, FAILED, TRUNCATED, LEGACY_TEXT, LEGACY_UNKNOWN;

    val hasText: Boolean get() = this == NATIVE || this == OCR || this == LEGACY_TEXT || this == TRUNCATED

    /** A pass reached the page and it needs no further automatic visit. */
    val terminal: Boolean get() = this != UNATTEMPTED && this != LEGACY_UNKNOWN
}

internal enum class WorkspaceVisualState { UNKNOWN, PRESENT, ABSENT }

internal enum class WorkspaceRenderState { UNKNOWN, AVAILABLE, UNAVAILABLE }

internal data class WorkspacePageState(
    val text: WorkspaceTextState,
    val visual: WorkspaceVisualState = WorkspaceVisualState.UNKNOWN,
    val render: WorkspaceRenderState = WorkspaceRenderState.UNKNOWN,
) {
    companion object {
        val UNATTEMPTED = WorkspacePageState(WorkspaceTextState.UNATTEMPTED)
        val LEGACY_TEXT = WorkspacePageState(WorkspaceTextState.LEGACY_TEXT)
        val LEGACY_UNKNOWN = WorkspacePageState(WorkspaceTextState.LEGACY_UNKNOWN)
    }
}

/** Pages [start]..[end] (1-based, inclusive) share [state]. */
internal data class WorkspacePageRun(val start: Int, val end: Int, val state: WorkspacePageState)

/**
 * Sorted, non-overlapping, merged page runs. A page no run covers is UNATTEMPTED, so a catalog
 * stores only what was observed and never allocates per reported page.
 */
internal object WorkspacePageCatalog {
    fun stateOf(runs: List<WorkspacePageRun>, page: Int): WorkspacePageState {
        var low = 0
        var high = runs.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val run = runs[middle]
            when {
                page < run.start -> high = middle - 1
                page > run.end -> low = middle + 1
                else -> return run.state
            }
        }
        return WorkspacePageState.UNATTEMPTED
    }

    /** Applies [updates] (page to state) over [runs]; explicit pages are bounded by the page cap. */
    fun with(runs: List<WorkspacePageRun>, updates: Map<Int, WorkspacePageState>): List<WorkspacePageRun> {
        if (updates.isEmpty()) return runs
        val pages = sortedMapOf<Int, WorkspacePageState>()
        runs.forEach { run -> for (page in run.start..run.end) pages[page] = run.state }
        updates.forEach { (page, state) ->
            require(page in 1..WorkspaceLimits.MAX_PDF_PAGES)
            pages[page] = state
        }
        return compress(pages)
    }

    fun compress(pages: Map<Int, WorkspacePageState>): List<WorkspacePageRun> {
        val runs = mutableListOf<WorkspacePageRun>()
        for ((page, state) in pages.toSortedMap()) {
            if (state == WorkspacePageState.UNATTEMPTED) continue
            val last = runs.lastOrNull()
            if (last != null && last.end == page - 1 && last.state == state) {
                runs[runs.lastIndex] = last.copy(end = page)
            } else {
                runs += WorkspacePageRun(page, page, state)
            }
        }
        return runs
    }

    fun isValid(runs: List<WorkspacePageRun>, pageCount: Int?): Boolean {
        if (runs.size > WorkspaceLimits.MAX_PAGE_RUNS_PER_DOCUMENT) return false
        val limit = minOf(pageCount ?: WorkspaceLimits.MAX_PDF_PAGES, WorkspaceLimits.MAX_PDF_PAGES)
        var previous: WorkspacePageRun? = null
        for (run in runs) {
            if (run.start < 1 || run.end < run.start || run.end > limit) return false
            if (run.state == WorkspacePageState.UNATTEMPTED) return false
            if (previous != null && (run.start <= previous.end ||
                    run.start == previous.end + 1 && run.state == previous.state)) return false
            previous = run
        }
        return true
    }

    /** Counts pages within 1..min(pageCount, cap) by text state; the implicit tail is UNATTEMPTED. */
    fun counts(document: WorkspaceDocument): Map<WorkspaceTextState, Int> {
        val counts = mutableMapOf<WorkspaceTextState, Int>()
        var explicit = 0
        document.pageRuns.forEach { run ->
            val size = run.end - run.start + 1
            explicit += size
            counts[run.state.text] = (counts[run.state.text] ?: 0) + size
        }
        val known = document.pageCount?.let { minOf(it, WorkspaceLimits.MAX_PDF_PAGES) }
        if (known != null && known > explicit) {
            counts[WorkspaceTextState.UNATTEMPTED] = (counts[WorkspaceTextState.UNATTEMPTED] ?: 0) + known - explicit
        }
        return counts
    }

    /** "1-4,6" for the pages in [pages], capped to [maxChars] with a trailing count of the rest. */
    fun ranges(pages: Collection<Int>, maxChars: Int = 80): String {
        val sorted = pages.toSortedSet().toList()
        if (sorted.isEmpty()) return "none"
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var end = start
        for (page in sorted.drop(1) + Int.MIN_VALUE) {
            if (page == end + 1) {
                end = page
                continue
            }
            parts += if (start == end) "$start" else "$start-$end"
            start = page
            end = page
        }
        val output = StringBuilder()
        for ((index, part) in parts.withIndex()) {
            val separator = if (output.isEmpty()) "" else ","
            if (output.length + separator.length + part.length > maxChars) {
                output.append(" (+${parts.size - index} more)")
                break
            }
            output.append(separator).append(part)
        }
        return output.toString()
    }

    fun pagesIn(document: WorkspaceDocument, predicate: (WorkspaceTextState) -> Boolean): List<Int> {
        val pages = mutableListOf<Int>()
        document.pageRuns.forEach { run -> if (predicate(run.state.text)) pages += run.start..run.end }
        if (predicate(WorkspaceTextState.UNATTEMPTED)) {
            val known = document.pageCount?.let { minOf(it, WorkspaceLimits.MAX_PDF_PAGES) } ?: 0
            for (page in 1..known) if (document.pageRuns.none { page in it.start..it.end }) pages += page
        }
        return pages.sorted()
    }
}
