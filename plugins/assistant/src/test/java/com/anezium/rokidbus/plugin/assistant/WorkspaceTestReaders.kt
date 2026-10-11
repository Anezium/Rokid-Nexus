package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException

/**
 * Reads the fixture format: a PDF's pages separated by form feeds, an image's recognized text as is,
 * and [LOCKED] standing for a password. A page marked [NO_RENDER] has no renderer, [OCR_FAIL]
 * renders but recognition fails, [DENSE] exceeds the glyph budget, and a blank page is empty OCR.
 * Every counter records real work, so a test can prove a question or migration did none.
 */
internal class FakePageReader(
    private val beforeLoad: () -> Unit = {},
    private val beforePage: () -> Unit = {},
) : WorkspacePageReader {
    @Volatile var loads = 0
    @Volatile var extractions = 0
    @Volatile var inspections = 0
    @Volatile var renders = 0
    val extractedPages = mutableListOf<Int>()

    override fun read(
        type: WorkspaceFileType,
        bytes: ByteArray,
        firstPage: Int,
        shouldStop: (characters: Int) -> Boolean,
        skip: (page: Int) -> Boolean,
    ): WorkspacePagedText {
        val text = bytes.toString(Charsets.UTF_8)
        if (text == LOCKED) throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        loads++
        beforeLoad()
        val all = pages(type, text)
        val last = minOf(all.size, WorkspaceLimits.MAX_PDF_PAGES)
        val pages = mutableListOf<WorkspaceReadPage>()
        var characters = 0
        for (page in firstPage..last) {
            if (skip(page)) continue
            if (shouldStop(characters)) return WorkspacePagedText(pages, all.size, complete = false)
            beforePage()
            extractions++
            synchronized(extractedPages) { extractedPages += page }
            val raw = all[page - 1]
            val read = when {
                NO_RENDER in raw -> WorkspaceReadPage(page, "",
                    WorkspacePageState(WorkspaceTextState.FAILED, render = WorkspaceRenderState.UNAVAILABLE))
                OCR_FAIL in raw -> WorkspaceReadPage(page, "",
                    WorkspacePageState(WorkspaceTextState.FAILED, render = WorkspaceRenderState.AVAILABLE))
                DENSE in raw -> WorkspaceReadPage(page, "", WorkspacePageState(WorkspaceTextState.TRUNCATED))
                raw.isBlank() -> WorkspaceReadPage(page, "",
                    WorkspacePageState(WorkspaceTextState.EMPTY, render = WorkspaceRenderState.AVAILABLE))
                type == WorkspaceFileType.IMAGE -> WorkspaceReadPage(page, raw, WorkspacePageState(
                    WorkspaceTextState.OCR, WorkspaceVisualState.PRESENT, WorkspaceRenderState.AVAILABLE))
                else -> WorkspaceReadPage(page, raw.replace(VISUAL, ""), WorkspacePageState(WorkspaceTextState.NATIVE,
                    if (VISUAL in raw) WorkspaceVisualState.PRESENT else WorkspaceVisualState.ABSENT))
            }
            pages += read
            characters += read.text.length
        }
        return WorkspacePagedText(pages, all.size, complete = true)
    }

    override fun inspect(type: WorkspaceFileType, bytes: ByteArray): WorkspacePageInfo {
        val text = bytes.toString(Charsets.UTF_8)
        if (text == LOCKED) throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        inspections++
        return WorkspacePageInfo(pages(type, text).size)
    }

    override fun render(type: WorkspaceFileType, bytes: ByteArray, page: Int): ByteArray? {
        renders++
        val text = bytes.toString(Charsets.UTF_8)
        val all = pages(type, text)
        val raw = all.getOrNull(page - 1) ?: return null
        if (NO_RENDER in raw) return null
        return "jpeg:$type:$page:$raw".toByteArray()
    }

    private fun pages(type: WorkspaceFileType, text: String) =
        if (type == WorkspaceFileType.IMAGE) listOf(text) else text.split(PAGE_BREAK)

    companion object {
        const val PAGE_BREAK = "\u000C"
        const val LOCKED = "locked"
        const val VISUAL = "[visual]"
        const val NO_RENDER = "[norender]"
        const val OCR_FAIL = "[ocrfail]"
        const val DENSE = "[dense]"
    }
}

/**
 * A turn double for tool, provider, and guard tests. Its version is captured at creation; moving
 * [generation] or [revision], clearing [available], or withdrawing it makes it unusable, as a real
 * turn becomes. Identical searches return the first result, as the real turn does.
 */
internal class FakeWorkspaceTurn : WorkspaceTurnAccess() {
    var available = true
    var executions = 0
    var result = WorkspaceSearchResult()
    var invalidateDuringSearch = false
    var cancel = false
    var revision = 0L
    var generation = 0L
    var changeVersionDuringSearch = false
    var searchable = true
    var viewable = false
    var image: WorkspaceToolOutcome = WorkspaceToolOutcome.Failure("workspace_page_unavailable")
    var evidence = false
    var withdrawnReason: String? = null
    val files = mutableListOf<String?>()
    private val cache = mutableMapOf<String, WorkspaceToolOutcome>()

    override val version: Pair<Long, Long> = 0L to 0L
    override fun isUsable() = available && withdrawnReason == null && (generation to revision) == version
    override fun hasSearchableText() = searchable
    override fun hasViewablePages() = viewable

    override suspend fun search(query: String, file: String?): WorkspaceToolOutcome {
        if (!isUsable()) return WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED)
        val key = "$query\u0000$file"
        cache[key]?.let { return it }
        executions++
        files += file
        if (cancel) throw CancellationException("fixture cancelled")
        if (invalidateDuringSearch) available = false
        if (changeVersionDuringSearch) revision++
        if (!isUsable()) return WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED)
        if (result.matchCount > 0) evidence = true
        return WorkspaceToolOutcome.Text(WorkspaceToolJson.render(if (result.matchCount > 0) "ok" else "no_match",
            result.matchCount, result.excerpts, emptyList(), "fixture coverage", null)).also { cache[key] = it }
    }

    override suspend fun viewPage(file: String, page: Int?): WorkspaceToolOutcome {
        if (!isUsable()) return WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED)
        if (image is WorkspaceToolOutcome.Image) evidence = true
        return image
    }

    override fun beforeSend(promptCarriesEvidence: Boolean) {
        if (isUsable()) {
            if (promptCarriesEvidence) evidence = true
            return
        }
        if (withdrawnReason == null) withdrawnReason = "changed"
        if (evidence || promptCarriesEvidence) throw IllegalStateException(WORKSPACE_CHANGED_MESSAGE)
    }

    override fun finalEffectsAllowed() = !evidence || isUsable()
    override fun withdraw(reason: String) {
        if (withdrawnReason == null) withdrawnReason = reason
    }
}
