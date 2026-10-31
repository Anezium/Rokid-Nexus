package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.anezium.rokidbus.glasses.session.Bubble
import com.anezium.rokidbus.glasses.session.BubbleSide
import com.anezium.rokidbus.glasses.session.BubbleStyle
import com.anezium.rokidbus.glasses.session.DocumentAnchor

/**
 * A conversation read page by page, newest nearest the footer. Each message is one outlined
 * bubble at most four fifths of the width: received on the left in the hairline, ours on the
 * right in the dim green, an unknown author across the whole width; a draft or an unconfirmed
 * message is dashed bright, a capture in progress adds a caret. Nothing is ever filled.
 *
 * Pages are filled from the newest message back, so the page the wearer lands on is full and
 * only the oldest one may be short; each page is drawn against the bottom. A message taller than
 * a page is cut between its lines, never inside one. Page starts are reported as anchors naming
 * the bubble's key and the text offset its first line on that page starts at.
 */
internal class ConversationView(context: Context) : View(context) {
    /** One drawable part of a page: the caption, or the lines [first, last) of one bubble. */
    private data class Piece(
        val bubble: Int,
        val first: Int,
        val last: Int,
        val caption: Boolean,
        val meta: Boolean,
        val height: Int,
        /** The bubble's sender line leads this piece: its first lines, of a received bubble that names one. */
        val sender: Boolean = false,
    )

    /** What a drawn bubble slice covers on the current page, for tests and accessibility. */
    data class DrawnBubble(
        val key: String,
        val side: BubbleSide,
        val style: BubbleStyle,
        val rect: RectF,
        val textColor: Int,
        val outlineColor: Int,
        /** The sender line drawn above it, a group thread's received message only. */
        val sender: String? = null,
    )

    var onPaged: ((starts: List<DocumentAnchor>, anchorPage: Int) -> Unit)? = null

    private var caption: String? = null
    private var bubbles: List<Bubble> = emptyList()
    private var anchor: DocumentAnchor? = null
    private var dimmed = false
    private var identity: Any? = null
    private var reported: Triple<Any?, List<DocumentAnchor>, Int>? = null
    private var laidOutFor: Pair<Int, Int>? = null
    private var layouts: List<StaticLayout> = emptyList()
    private var widths: List<Float> = emptyList()
    private var pages: List<List<Piece>> = emptyList()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PageStyle.BODY_PX
        typeface = PageStyle.sans
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PageStyle.CAPTION_PX
        typeface = PageStyle.mono
        color = PageStyle.muted
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PageStyle.HAIR_STROKE_PX
    }
    private val caret = Paint().apply {
        style = Paint.Style.FILL
        color = PageStyle.phosphor
    }
    private val dash = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    private val path = Path()
    private val radii = FloatArray(8)

    /** Page starts of the current layout, as last measured. */
    var pageStarts: List<DocumentAnchor> = emptyList()
        private set

    /** The page currently drawn. */
    var page = 0
        private set

    /** The bubbles drawn on the current page, top to bottom, as last drawn. */
    var drawn: List<DrawnBubble> = emptyList()
        private set

    /** Whether the caption is on the current page, as last laid out. */
    val captionShown: Boolean get() = pages.getOrNull(page).orEmpty().any { leadsWithCaption(it) }

    /**
     * [identity] names what is shown (frame, page and revision); null for a retained conversation,
     * which reports nothing and simply shows its newest page.
     */
    fun bind(caption: String?, bubbles: List<Bubble>, anchor: DocumentAnchor?, dimmed: Boolean, identity: Any?) {
        val changed = caption != this.caption || bubbles != this.bubbles || dimmed != this.dimmed
        this.caption = caption?.takeIf(String::isNotBlank)
        this.bubbles = bubbles
        this.anchor = anchor
        this.dimmed = dimmed
        this.identity = identity
        if (changed) laidOutFor = null
        paginate()
        invalidate()
    }

    /** Reports the next layout even when it equals the last one reported: its reader may have dropped it. */
    fun forgetReport() {
        reported = null
    }

    /** The keys of the bubbles on the current page, oldest first. */
    fun visibleKeys(): List<String> = pages.getOrNull(page).orEmpty().filterNot { it.caption }.map { bubbles[it.bubble].key }.distinct()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paginate()
    }

    private fun paginate() {
        val width = width - paddingLeft - paddingRight
        val height = height - paddingTop - paddingBottom
        if (width <= 0 || height <= 0) return
        if (laidOutFor != width to height) {
            layouts = bubbles.map { layoutFor(it, width) }
            widths = layouts.map { layout -> (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f }
            pages = split(units(height), height)
            laidOutFor = width to height
        }
        pageStarts = pages.map { units -> units.first { !it.caption }.let { DocumentAnchor(bubbles[it.bubble].key, layouts[it.bubble].getLineStart(it.first)) } }
        page = pageOf(anchor)
        val id = identity ?: return
        val report = Triple(id, pageStarts, page)
        if (report != reported && pageStarts.isNotEmpty()) {
            reported = report
            // Reported after this pass, as a document's pages are: the state it updates redraws
            // this view, and a report overtaken by a rebind is dropped unread.
            val callback = onPaged
            main.post { if (identity == report.first) callback?.invoke(report.second, report.third) }
        }
    }

    private fun units(height: Int): List<Piece> {
        val captionHeight = caption?.let { captionLineHeight() + CAPTION_GAP_PX } ?: 0
        val result = mutableListOf<Piece>()
        bubbles.forEachIndexed { index, bubble ->
            val layout = layouts[index]
            val metaHeight = if (bubble.meta.isNullOrBlank()) 0 else META_GAP_PX + metaLineHeight()
            val senderHeight = if (bubble.sender.isNullOrBlank()) 0 else metaLineHeight() + SENDER_GAP_PX
            var first = 0
            while (first < layout.lineCount) {
                // The caption shares the first page with the first message, so its room is kept there;
                // a sender line stays with its bubble's first line.
                val leadsSender = first == 0 && senderHeight > 0
                val budget = height - 2 * PAD_V_PX - (if (index == 0 && first == 0) captionHeight else 0) - if (leadsSender) senderHeight else 0
                var last = first + 1
                while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(first) <= budget) last++
                val ends = last == layout.lineCount
                // The meta line stays with the bubble's last line; a slice that cannot hold both gives up a line.
                if (ends && metaHeight > 0 && last - first > 1 && layout.getLineBottom(last - 1) - layout.getLineTop(first) + metaHeight > budget) last--
                val lines = layout.getLineBottom(last - 1) - layout.getLineTop(first)
                val withMeta = last == layout.lineCount && metaHeight > 0
                val leading = index == 0 && first == 0 && caption != null
                result += Piece(
                    index, first, last, caption = false, meta = withMeta,
                    height = lines + 2 * PAD_V_PX + (if (withMeta) metaHeight else 0) + (if (leading) captionHeight else 0) +
                        if (leadsSender) senderHeight else 0,
                    sender = leadsSender,
                )
                first = last
            }
        }
        if (result.isEmpty() && caption != null) result += Piece(-1, 0, 0, caption = true, meta = false, height = captionHeight)
        return result
    }

    /** Newest first, each page as full as it can be; then oldest first again. */
    private fun split(units: List<Piece>, height: Int): List<List<Piece>> {
        val pages = mutableListOf<MutableList<Piece>>()
        var used = 0
        units.asReversed().forEach { unit ->
            val current = pages.lastOrNull()
            val needed = (if (current.isNullOrEmpty()) 0 else GAP_PX) + unit.height
            if (current == null || used + needed > height) {
                pages.add(mutableListOf(unit))
                used = unit.height
            } else {
                current.add(0, unit)
                used += needed
            }
        }
        return pages.asReversed().map { it.toList() }.filter { page -> page.any { !it.caption } }
    }

    /** The page holding [anchor]; with none, or one that is gone, the newest page. */
    private fun pageOf(anchor: DocumentAnchor?): Int {
        if (pages.isEmpty()) return 0
        val bubble = anchor?.let { a -> bubbles.indexOfFirst { it.key == a.segmentId } } ?: -1
        if (bubble < 0) return pages.lastIndex
        val offset = anchor?.offset ?: 0
        var found = 0
        pages.forEachIndexed { index, units ->
            val start = units.first { !it.caption }
            val at = layouts[start.bubble].getLineStart(start.first)
            if (start.bubble < bubble || (start.bubble == bubble && at <= offset)) found = index
        }
        return found
    }

    override fun onDraw(canvas: Canvas) {
        val units = pages.getOrNull(page) ?: return
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val total = units.sumOf { it.height } + GAP_PX * (units.size - 1).coerceAtLeast(0)
        var y = (height - paddingBottom - total).toFloat().coerceAtLeast(paddingTop.toFloat())
        val shown = mutableListOf<DrawnBubble>()
        units.forEachIndexed { index, unit ->
            if (index > 0) y += GAP_PX
            var top = y
            if (leadsWithCaption(unit)) {
                drawCaption(canvas, top, contentWidth)
                top += captionLineHeight() + CAPTION_GAP_PX
            }
            if (!unit.caption) shown += drawSlice(canvas, unit, top, contentWidth)
            y += unit.height
        }
        drawn = shown
    }

    private fun drawCaption(canvas: Canvas, top: Float, contentWidth: Float) {
        val text = caption ?: return
        small.textAlign = Paint.Align.CENTER
        small.letterSpacing = CAPTION_LETTER_SPACING
        canvas.drawText(text, paddingLeft + contentWidth / 2f, top - small.ascent(), small)
        small.letterSpacing = 0f
    }

    private fun drawSlice(canvas: Canvas, unit: Piece, sliceTop: Float, contentWidth: Float): DrawnBubble {
        val bubble = bubbles[unit.bubble]
        val layout = layouts[unit.bubble]
        // A group thread's received message names its sender above it, small and muted; never guessed.
        val sender = bubble.sender?.takeIf { unit.sender }
        if (sender != null) {
            small.textAlign = Paint.Align.LEFT
            canvas.drawText(sender, paddingLeft.toFloat(), sliceTop - small.ascent(), small)
        }
        val top = if (sender != null) sliceTop + metaLineHeight() + SENDER_GAP_PX else sliceTop
        val linesHeight = layout.getLineBottom(unit.last - 1) - layout.getLineTop(unit.first)
        val live = bubble.style == BubbleStyle.LIVE
        val caretRoom = if (live) CARET_GAP_PX + CARET_WIDTH_PX else 0f
        val boxWidth = when (bubble.side) {
            BubbleSide.NONE -> contentWidth
            else -> (widths[unit.bubble] + caretRoom + 2 * PAD_H_PX + PageStyle.HAIR_STROKE_PX).coerceAtMost(contentWidth * MAX_WIDTH_FRACTION)
        }
        val left = paddingLeft + if (bubble.side == BubbleSide.RIGHT) contentWidth - boxWidth else 0f
        val half = PageStyle.HAIR_STROKE_PX / 2f
        val rect = RectF(left + half, top + half, left + boxWidth - half, top + linesHeight + 2 * PAD_V_PX - half)

        val dashed = bubble.style != BubbleStyle.SOLID
        val outlineColor = when {
            dashed -> if (dimmed) PageStyle.muted else PageStyle.phosphor
            bubble.side == BubbleSide.RIGHT -> PageStyle.dim
            else -> PageStyle.outline
        }
        val big = BUBBLE_RADIUS_PX
        val tail = TAIL_RADIUS_PX
        // Corners top-left, top-right, bottom-right, bottom-left: the tail is the speaker's lower corner.
        val corners = when (bubble.side) {
            BubbleSide.LEFT -> floatArrayOf(big, big, big, tail)
            BubbleSide.RIGHT -> floatArrayOf(big, big, tail, big)
            BubbleSide.NONE -> floatArrayOf(big, big, big, big)
        }
        corners.forEachIndexed { index, radius ->
            radii[index * 2] = radius
            radii[index * 2 + 1] = radius
        }
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        outline.color = outlineColor
        outline.pathEffect = if (dashed) dash else null
        canvas.drawPath(path, outline)

        val textColor = when {
            dimmed -> PageStyle.muted
            bubble.latest && bubble.side == BubbleSide.LEFT -> PageStyle.phosphor
            else -> PageStyle.ink
        }
        body.color = textColor
        val textLeft = left + PAD_H_PX
        val textTop = top + PAD_V_PX
        canvas.save()
        canvas.clipRect(textLeft, textTop, left + boxWidth - PAD_H_PX + caretRoom, textTop + linesHeight)
        canvas.translate(textLeft, textTop - layout.getLineTop(unit.first))
        layout.draw(canvas)
        canvas.restore()

        if (live && unit.last == layout.lineCount) {
            val line = unit.last - 1
            val x = textLeft + layout.getLineRight(line).coerceAtLeast(0f) + CARET_GAP_PX
            val lineTop = textTop + layout.getLineTop(line) - layout.getLineTop(unit.first)
            val lineBottom = textTop + layout.getLineBottom(line) - layout.getLineTop(unit.first)
            val caretHeight = PageStyle.BODY_PX * CARET_HEIGHT_EM
            val caretTop = lineTop + ((lineBottom - lineTop) - caretHeight) / 2f
            canvas.drawRect(x, caretTop, x + CARET_WIDTH_PX, caretTop + caretHeight, caret)
        }

        if (unit.meta) {
            val text = bubble.meta.orEmpty()
            val baseline = rect.bottom + half + META_GAP_PX - small.ascent()
            if (bubble.side == BubbleSide.LEFT) {
                small.textAlign = Paint.Align.LEFT
                canvas.drawText(text, left, baseline, small)
            } else {
                small.textAlign = Paint.Align.RIGHT
                canvas.drawText(text, paddingLeft + contentWidth, baseline, small)
            }
        }
        return DrawnBubble(bubble.key, bubble.side, bubble.style, RectF(rect), textColor, outlineColor, sender)
    }

    private fun layoutFor(bubble: Bubble, width: Int): StaticLayout {
        val box = if (bubble.side == BubbleSide.NONE) width.toFloat() else width * MAX_WIDTH_FRACTION
        val caretRoom = if (bubble.style == BubbleStyle.LIVE) CARET_GAP_PX + CARET_WIDTH_PX else 0f
        val textWidth = (box - 2 * PAD_H_PX - PageStyle.HAIR_STROKE_PX - caretRoom).toInt().coerceAtLeast(1)
        // An empty live capture still draws one line, so its caret has somewhere to stand.
        val text = bubble.text.ifEmpty { " " }
        return StaticLayout.Builder.obtain(text, 0, text.length, body, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(LINE_EXTRA_PX, 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()
    }

    private fun leadsWithCaption(piece: Piece): Boolean = piece.caption || (caption != null && piece.bubble == 0 && piece.first == 0)

    private fun captionLineHeight(): Int = (small.descent() - small.ascent()).toInt() + 1

    private fun metaLineHeight(): Int = (small.descent() - small.ascent()).toInt() + 1

    companion object {
        const val MAX_WIDTH_FRACTION = 0.8f
        const val PAD_V_PX = 7
        const val PAD_H_PX = 13f
        const val GAP_PX = 8
        const val META_GAP_PX = 4
        const val SENDER_GAP_PX = 3
        const val CAPTION_GAP_PX = 10
        const val BUBBLE_RADIUS_PX = 14f
        const val TAIL_RADIUS_PX = 4f
        const val CARET_WIDTH_PX = 2f
        const val CARET_GAP_PX = 3f
        const val CARET_HEIGHT_EM = 1.05f
        const val CAPTION_LETTER_SPACING = 0.06f

        /** The mockup's 1.35 line height for 17 px text, over the font's own line height. */
        const val LINE_EXTRA_PX = 3f
    }
}
