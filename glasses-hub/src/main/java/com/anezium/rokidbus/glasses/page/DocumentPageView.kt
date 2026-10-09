package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.anezium.rokidbus.glasses.session.DocumentAnchor
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind

/**
 * One page of a document at a time, measured with Android's own text layout at the size this
 * view is given. Source order, paragraphs, explicit line breaks and Unicode are kept; a word too
 * long for a line is broken inside it rather than clipped. It owns no input: it reports where its
 * pages start, and shows the page holding the anchor it is given.
 */
internal class DocumentPageView(context: Context) : View(context) {
    /** One measured line: which segment, where it starts in the text, and its height. */
    private data class Line(val segment: Int, val layoutLine: Int, val start: Int, val height: Int, val gapBefore: Int)

    var onPaged: ((starts: List<DocumentAnchor>, anchorPage: Int) -> Unit)? = null

    private var segments: List<PageSegment> = emptyList()
    private var anchor: DocumentAnchor? = null
    private var dimmed = false
    private var layouts: List<StaticLayout> = emptyList()
    private var pages: List<List<Line>> = emptyList()
    private var laidOutFor: Pair<Int, Int>? = null
    private var reported: Pair<List<DocumentAnchor>, Int>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Page starts of the current layout, as last measured. */
    var pageStarts: List<DocumentAnchor> = emptyList()
        private set

    /** The page currently drawn. */
    var page = 0
        private set

    fun bind(segments: List<PageSegment>, anchor: DocumentAnchor?, dimmed: Boolean) {
        val contentChanged = segments != this.segments || dimmed != this.dimmed
        this.segments = segments
        this.anchor = anchor
        this.dimmed = dimmed
        if (contentChanged) laidOutFor = null
        paginate()
        invalidate()
    }

    /** The text drawn on the current page, in order; for tests and accessibility. */
    fun visibleText(): String = pages.getOrNull(page).orEmpty().joinToString("\n") { line ->
        val layout = layouts[line.segment]
        layout.text.subSequence(layout.getLineStart(line.layoutLine), layout.getLineEnd(line.layoutLine)).toString().trimEnd('\n')
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paginate()
    }

    private fun paginate() {
        val width = width - paddingLeft - paddingRight
        val height = height - paddingTop - paddingBottom
        if (width <= 0 || height <= 0) return
        if (laidOutFor != width to height) {
            layouts = segments.map { layoutFor(it, width) }
            pages = split(height)
            laidOutFor = width to height
        }
        pageStarts = pages.map { page -> page.first().let { DocumentAnchor(segments[it.segment].id, it.start) } }
        page = pageOf(anchor)
        val report = pageStarts to page
        if (report != reported && pageStarts.isNotEmpty()) {
            reported = report
            // Reported after this pass, on the main looper whether or not the view is
            // attached yet: the state it updates redraws this view again.
            main.post { onPaged?.invoke(report.first, report.second) }
        }
    }

    private fun split(height: Int): List<List<Line>> {
        val lines = segments.indices.flatMap { index ->
            val layout = layouts[index]
            (0 until layout.lineCount).map { line ->
                Line(
                    segment = index,
                    layoutLine = line,
                    start = layout.getLineStart(line),
                    height = layout.getLineBottom(line) - layout.getLineTop(line),
                    gapBefore = if (line == 0 && index > 0) PageStyle.SEGMENT_GAP_PX else 0,
                )
            }
        }
        val result = mutableListOf<MutableList<Line>>()
        var used = 0
        lines.forEach { line ->
            val current = result.lastOrNull()
            val needed = (if (current.isNullOrEmpty()) 0 else line.gapBefore) + line.height
            if (current == null || (current.isNotEmpty() && used + needed > height)) {
                result += mutableListOf(line)
                used = line.height
            } else {
                current += line
                used += needed
            }
        }
        return result
    }

    /** The page holding [anchor]; a segment that disappeared falls back to the page before it. */
    private fun pageOf(anchor: DocumentAnchor?): Int {
        if (anchor == null || pages.isEmpty()) return 0
        val segment = segments.indexOfFirst { it.id == anchor.segmentId }
        if (segment < 0) return 0
        var found = 0
        pages.forEachIndexed { index, lines ->
            val first = lines.first()
            if (first.segment < segment || (first.segment == segment && first.start <= anchor.offset)) found = index
        }
        return found
    }

    override fun onDraw(canvas: Canvas) {
        val lines = pages.getOrNull(page) ?: return
        var y = paddingTop.toFloat()
        lines.forEachIndexed { index, line ->
            if (index > 0) y += line.gapBefore
            val layout = layouts[line.segment]
            val top = layout.getLineTop(line.layoutLine)
            canvas.save()
            canvas.clipRect(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y + line.height)
            canvas.translate(paddingLeft.toFloat(), y - top)
            layout.draw(canvas)
            canvas.restore()
            y += line.height
        }
    }

    private fun layoutFor(segment: PageSegment, width: Int): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            when (segment.kind) {
                PageSegmentKind.HEADER -> {
                    textSize = PageStyle.CAPTION_PX
                    typeface = PageStyle.mono
                    color = PageStyle.muted
                }
                PageSegmentKind.PROSE -> {
                    textSize = PageStyle.BODY_PX
                    typeface = PageStyle.sans
                    color = if (dimmed) PageStyle.muted else PageStyle.ink
                }
                PageSegmentKind.ASIDE -> {
                    textSize = PageStyle.ASIDE_PX
                    typeface = PageStyle.sans
                    color = PageStyle.muted
                }
            }
        }
        return StaticLayout.Builder.obtain(segment.text, 0, segment.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()
    }

    /** The text size used for [kind], in logical pixels. */
    fun sizeOf(kind: PageSegmentKind): Float = when (kind) {
        PageSegmentKind.HEADER -> PageStyle.CAPTION_PX
        PageSegmentKind.PROSE -> PageStyle.BODY_PX
        PageSegmentKind.ASIDE -> PageStyle.ASIDE_PX
    }
}
