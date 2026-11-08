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
import com.anezium.rokidbus.glasses.drawable
import com.anezium.rokidbus.glasses.session.Bubble
import com.anezium.rokidbus.glasses.session.BubbleSide
import com.anezium.rokidbus.glasses.session.BubbleStyle
import com.anezium.rokidbus.glasses.session.DocumentAnchor

/**
 * A conversation scrolled as one column, newest nearest the footer. Each message is one outlined
 * bubble at most four fifths of the width: received on the left in the hairline, ours on the
 * right in the dim green, an unknown author across the whole width; a draft or an unconfirmed
 * message is dashed bright, a capture in progress adds a caret. Nothing is ever filled. A
 * message's thumbnail (launcher bundle v5) sits inside its bubble above the text, at its own
 * size when it fits the bubble's width, else scaled down; a message with a thumbnail and no text
 * is the thumbnail alone.
 *
 * The column is laid out once at its full height and read through a window the size of the
 * view, so a tall thumbnail never leaves a blank page behind it. The window rests at stops about
 * half a window apart, each snapped to the top of a message or of one of its lines; the last stop
 * is the column's end, against the footer, which is also where a column shorter than the window
 * sits. A swipe moves the window one stop. Text lines are drawn whole or not at all, so a window
 * edge never cuts through glyphs; a thumbnail and a bubble's outline are simply clipped by it.
 * Stops are reported as anchors naming the bubble's key and the text offset of the first line
 * fully in view, so the reducer steps through them and keeps its place across relayouts.
 */
internal class ConversationView(context: Context) : View(context) {
    /** One bubble's run in the column, in px from the column's top: sender line, box, meta line. */
    private class Box(
        val index: Int,
        /** Where the run starts: the sender line when there is one, else the outline. */
        val top: Int,
        val senderTop: Int?,
        val boxTop: Int,
        val imageTop: Int?,
        /** Line 0 of the text layout is drawn here; the box's bottom padding starts here when textless. */
        val textTop: Int,
        val boxBottom: Int,
        val metaTop: Int?,
        /** Where the run ends, the meta line included. */
        val bottom: Int,
    )

    /** Where the window may rest: the top of a message's run or of one of its text lines. */
    private class Rest(val top: Int, val bubble: Int, val anchor: DocumentAnchor)

    /** A stop of the window: its top edge in column px, and the anchor the reducer knows it by. */
    private class Stop(val top: Int, val bubble: Int, val anchor: DocumentAnchor)

    /** What a drawn bubble covers in view coordinates, for tests and accessibility. */
    data class DrawnBubble(
        val key: String,
        val side: BubbleSide,
        val style: BubbleStyle,
        val rect: RectF,
        val textColor: Int,
        val outlineColor: Int,
        /** The sender line drawn above it, a group thread's received message only, when in view. */
        val sender: String? = null,
        /** Where its thumbnail was drawn, when it carries one and any of it is in view. */
        val imageRect: RectF? = null,
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
    /** Each bubble's thumbnail as drawn, width to height in px, or null. */
    private var images: List<Pair<Int, Int>?> = emptyList()
    private var boxes: List<Box> = emptyList()
    private var contentHeight = 0
    private var stops: List<Stop> = emptyList()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PageStyle.scaledPx(context, PageStyle.BODY_PX)
        typeface = PageStyle.sans
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PageStyle.scaledPx(context, PageStyle.CAPTION_PX)
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

    /** The window's stops in the current layout, oldest first, as last measured. */
    var pageStarts: List<DocumentAnchor> = emptyList()
        private set

    /** The stop the window rests at. */
    var page = 0
        private set

    /** The bubbles with any part in the window, top to bottom, as last drawn. */
    var drawn: List<DrawnBubble> = emptyList()
        private set

    /** Whether the caption is wholly in the window, as last laid out. */
    val captionShown: Boolean
        get() = caption != null && boxes.isNotEmpty() && captionLineHeight() <= windowTop() + windowHeight() && windowTop() <= 0

    /**
     * [identity] names what is shown (frame, page and revision); null for a retained conversation,
     * which reports nothing and simply shows its end.
     */
    fun bind(caption: String?, bubbles: List<Bubble>, anchor: DocumentAnchor?, dimmed: Boolean, identity: Any?) {
        val changed = caption != this.caption || bubbles != this.bubbles || dimmed != this.dimmed
        this.caption = caption?.takeIf(String::isNotBlank)
        this.bubbles = bubbles
        this.anchor = anchor
        this.dimmed = dimmed
        this.identity = identity
        if (changed) laidOutFor = null
        measureColumn()
        invalidate()
    }

    /** Reports the next layout even when it equals the last one reported: its reader may have dropped it. */
    fun forgetReport() {
        reported = null
    }

    /** The keys of the bubbles readable in the window (a whole text line or any of a thumbnail), oldest first. */
    fun visibleKeys(): List<String> {
        val top = windowTop()
        val bottom = top + windowHeight()
        return boxes.filter { readable(it, top, bottom) }.map { bubbles[it.index].key }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureColumn()
    }

    private fun windowHeight(): Int = height - paddingTop - paddingBottom

    /** The window's top edge in column px; negative when the column is shorter than the window. */
    private fun windowTop(): Int = stops.getOrNull(page)?.top ?: (contentHeight - windowHeight())

    private fun measureColumn() {
        val width = width - paddingLeft - paddingRight
        val height = windowHeight()
        if (width <= 0 || height <= 0) return
        if (laidOutFor != width to height) {
            layouts = bubbles.map { layoutFor(it, width) }
            widths = layouts.map { layout -> (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f }
            images = bubbles.map { imageSize(it, width) }
            boxes = stack()
            stops = stopsFor(rests(), height)
            laidOutFor = width to height
        }
        pageStarts = stops.map { it.anchor }
        page = stopOf(anchor)
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

    /** Lays the runs out top to bottom, the caption before the first, and measures the column. */
    private fun stack(): List<Box> {
        var y = caption?.let { captionLineHeight() + CAPTION_GAP_PX } ?: 0
        val result = bubbles.mapIndexed { index, bubble ->
            val top = y
            val senderTop = if (bubble.sender.isNullOrBlank()) null else y.also { y += metaLineHeight() + SENDER_GAP_PX }
            val boxTop = y
            val image = images[index]
            val imageTop = image?.let { boxTop + PAD_V_PX }
            val textless = textless(index)
            val textTop = boxTop + PAD_V_PX + (image?.let { it.second + if (textless) 0 else IMAGE_GAP_PX } ?: 0)
            val layout = layouts[index]
            val boxBottom = textTop + (if (textless) 0 else layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(0)) + PAD_V_PX
            y = boxBottom
            val metaTop = if (bubble.meta.isNullOrBlank()) null else (y + META_GAP_PX).also { y = it + metaLineHeight() }
            val bottom = y
            y += GAP_PX
            Box(index, top, senderTop, boxTop, imageTop, textTop, boxBottom, metaTop, bottom)
        }
        contentHeight = result.lastOrNull()?.bottom ?: (y - CAPTION_GAP_PX).coerceAtLeast(0)
        return result
    }

    /**
     * Where the window may rest, top to bottom: each run's top (the caption's, for the first) and
     * each text line after the first. A thumbnail's text starts at the same offset as its run,
     * so it is no rest of its own: an anchor could not tell them apart.
     */
    private fun rests(): List<Rest> = buildList {
        boxes.forEach { box ->
            val key = bubbles[box.index].key
            add(Rest(if (box.index == 0) 0 else box.top, box.index, DocumentAnchor(key, 0)))
            if (textless(box.index)) return@forEach
            val layout = layouts[box.index]
            for (line in 1 until layout.lineCount) {
                add(Rest(box.textTop + layout.getLineTop(line), box.index, DocumentAnchor(key, layout.getLineStart(line))))
            }
        }
    }

    /**
     * The stops from the column's top, about [STEP_FRACTION] of the window apart and snapped back
     * to a rest, then the column's end. A stop too near the end gives way to it, so the first
     * swipe up from the newest message always moves the window noticeably.
     */
    private fun stopsFor(rests: List<Rest>, height: Int): List<Stop> {
        if (rests.isEmpty()) return emptyList()
        val end = contentHeight - height
        if (end <= 0) return listOf(rests.first().stopAt(end))
        val step = (height * STEP_FRACTION).toInt().coerceAtLeast(1)
        val result = mutableListOf(rests.first().stopAt(0))
        var cursor = 0
        while (true) {
            val target = cursor + step
            val next = rests.lastOrNull { it.top in (cursor + 1)..target } ?: rests.firstOrNull { it.top > cursor } ?: break
            if (next.top >= end) break
            result += next.stopAt(next.top)
            cursor = next.top
        }
        val last = (rests.firstOrNull { it.top >= end } ?: rests.last { it.top <= end }).stopAt(end)
        while (result.size > 1 && (result.last().anchor == last.anchor || end - result.last().top < step / 3)) result.removeAt(result.lastIndex)
        return result + last
    }

    private fun Rest.stopAt(top: Int) = Stop(top, bubble, anchor)

    /** The stop holding [anchor]: the last one starting at or before it; with none, or one that is gone, the end. */
    private fun stopOf(anchor: DocumentAnchor?): Int {
        if (stops.isEmpty()) return 0
        val bubble = anchor?.let { a -> bubbles.indexOfFirst { it.key == a.segmentId } } ?: -1
        if (bubble < 0) return stops.lastIndex
        val offset = anchor?.offset ?: 0
        var found = 0
        stops.forEachIndexed { index, stop ->
            if (stop.bubble < bubble || (stop.bubble == bubble && stop.anchor.offset <= offset)) found = index
        }
        return found
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty() && caption == null) return
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val top = windowTop()
        val bottom = top + windowHeight()
        val shown = mutableListOf<DrawnBubble>()
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        canvas.translate(paddingLeft.toFloat(), (paddingTop - top).toFloat())
        if (caption != null && top <= 0 && captionLineHeight() <= bottom) drawCaption(canvas, contentWidth)
        boxes.forEach { box ->
            if (box.bottom > top && box.top < bottom) shown += drawBox(canvas, box, top, bottom, contentWidth)
        }
        canvas.restore()
        drawn = shown
    }

    private fun drawCaption(canvas: Canvas, contentWidth: Float) {
        val text = caption ?: return
        small.textAlign = Paint.Align.CENTER
        small.letterSpacing = CAPTION_LETTER_SPACING
        canvas.drawText(text, contentWidth / 2f, -small.ascent(), small)
        small.letterSpacing = 0f
    }

    /** Draws one run in column coordinates, whole text lines only; [top]..[bottom] is the window. */
    private fun drawBox(canvas: Canvas, box: Box, top: Int, bottom: Int, contentWidth: Float): DrawnBubble {
        val bubble = bubbles[box.index]
        val layout = layouts[box.index]
        val dy = (paddingTop - top).toFloat()
        // A group thread's received message names its sender above it, small and muted; never guessed.
        val senderTop = box.senderTop
        val sender = bubble.sender?.takeIf { senderTop != null && senderTop >= top && senderTop + metaLineHeight() <= bottom }
        if (sender != null && senderTop != null) {
            small.textAlign = Paint.Align.LEFT
            canvas.drawText(sender, 0f, senderTop - small.ascent(), small)
        }
        val textless = textless(box.index)
        val image = images[box.index]
        val live = bubble.style == BubbleStyle.LIVE
        val caretRoom = if (live) CARET_GAP_PX + CARET_WIDTH_PX else 0f
        val inner = maxOf(if (textless) 0f else widths[box.index] + caretRoom, (image?.first ?: 0).toFloat())
        val boxWidth = when (bubble.side) {
            BubbleSide.NONE -> contentWidth
            else -> (inner + 2 * PAD_H_PX + PageStyle.HAIR_STROKE_PX).coerceAtMost(contentWidth * MAX_WIDTH_FRACTION)
        }
        val left = if (bubble.side == BubbleSide.RIGHT) contentWidth - boxWidth else 0f
        val half = PageStyle.HAIR_STROKE_PX / 2f
        val rect = RectF(left + half, box.boxTop + half, left + boxWidth - half, box.boxBottom - half)

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
        var imageRect: RectF? = null
        if (image != null && box.imageTop != null && box.imageTop < bottom && box.imageTop + image.second > top) {
            val at = RectF(textLeft, box.imageTop.toFloat(), textLeft + image.first, (box.imageTop + image.second).toFloat())
            bubble.image?.drawable(context, PageStyle.phosphor)?.let { drawable ->
                drawable.setBounds(at.left.toInt(), at.top.toInt(), at.right.toInt(), at.bottom.toInt())
                drawable.alpha = if (dimmed) DIMMED_IMAGE_ALPHA else 255
                drawable.draw(canvas)
            }
            imageRect = at.apply { offset(paddingLeft.toFloat(), dy) }
        }
        var lastDrawn = -1
        if (!textless) {
            val first = (0 until layout.lineCount).firstOrNull { box.textTop + layout.getLineTop(it) >= top }
            val last = (0 until layout.lineCount).lastOrNull { box.textTop + layout.getLineBottom(it) <= bottom }
            if (first != null && last != null && first <= last) {
                canvas.save()
                canvas.clipRect(
                    textLeft, (box.textTop + layout.getLineTop(first)).toFloat(),
                    left + boxWidth - PAD_H_PX + caretRoom, (box.textTop + layout.getLineBottom(last)).toFloat(),
                )
                canvas.translate(textLeft, box.textTop.toFloat())
                layout.draw(canvas)
                canvas.restore()
                lastDrawn = last
            }
        }

        if (live && lastDrawn == layout.lineCount - 1) {
            val line = lastDrawn
            val x = textLeft + layout.getLineRight(line).coerceAtLeast(0f) + CARET_GAP_PX
            val lineTop = box.textTop + layout.getLineTop(line)
            val lineBottom = box.textTop + layout.getLineBottom(line)
            val caretHeight = body.textSize * CARET_HEIGHT_EM
            val caretTop = lineTop + ((lineBottom - lineTop) - caretHeight) / 2f
            canvas.drawRect(x, caretTop, x + CARET_WIDTH_PX, caretTop + caretHeight, caret)
        }

        if (box.metaTop != null && box.metaTop >= top && box.metaTop + metaLineHeight() <= bottom) {
            val text = bubble.meta.orEmpty()
            val baseline = box.metaTop - small.ascent()
            if (bubble.side == BubbleSide.LEFT) {
                small.textAlign = Paint.Align.LEFT
                canvas.drawText(text, left, baseline, small)
            } else {
                small.textAlign = Paint.Align.RIGHT
                canvas.drawText(text, contentWidth, baseline, small)
            }
        }
        rect.offset(paddingLeft.toFloat(), dy)
        return DrawnBubble(bubble.key, bubble.side, bubble.style, rect, textColor, outlineColor, sender, imageRect)
    }

    /** A run is readable when one of its text lines is wholly in the window, or any of its thumbnail is. */
    private fun readable(box: Box, top: Int, bottom: Int): Boolean {
        val image = images[box.index]
        if (image != null && box.imageTop != null && box.imageTop < bottom && box.imageTop + image.second > top) return true
        if (textless(box.index)) return false
        val layout = layouts[box.index]
        return (0 until layout.lineCount).any { box.textTop + layout.getLineTop(it) >= top && box.textTop + layout.getLineBottom(it) <= bottom }
    }

    /** A thumbnail's drawn size: its own, scaled down only when the bubble's width cannot hold it. */
    private fun imageSize(bubble: Bubble, width: Int): Pair<Int, Int>? {
        val image = bubble.image ?: return null
        val box = if (bubble.side == BubbleSide.NONE) width.toFloat() else width * MAX_WIDTH_FRACTION
        val room = (box - 2 * PAD_H_PX - PageStyle.HAIR_STROKE_PX).coerceAtLeast(1f)
        val scale = minOf(1f, room / image.width)
        return (image.width * scale).toInt().coerceAtLeast(1) to (image.height * scale).toInt().coerceAtLeast(1)
    }

    /** A message that is its thumbnail alone: no text line is laid out for it. */
    private fun textless(index: Int): Boolean = bubbles[index].text.isBlank() && bubbles[index].image != null

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

    private fun captionLineHeight(): Int = (small.descent() - small.ascent()).toInt() + 1

    private fun metaLineHeight(): Int = (small.descent() - small.ascent()).toInt() + 1

    companion object {
        const val MAX_WIDTH_FRACTION = 0.8f
        const val PAD_V_PX = 7
        const val PAD_H_PX = 13f
        const val GAP_PX = 8
        const val META_GAP_PX = 4
        const val SENDER_GAP_PX = 3
        const val IMAGE_GAP_PX = 6
        const val DIMMED_IMAGE_ALPHA = 110
        const val CAPTION_GAP_PX = 10
        const val BUBBLE_RADIUS_PX = 14f
        const val TAIL_RADIUS_PX = 4f
        const val CARET_WIDTH_PX = 2f
        const val CARET_GAP_PX = 3f
        const val CARET_HEIGHT_EM = 1.05f
        const val CAPTION_LETTER_SPACING = 0.06f

        /** How far one swipe moves the window, as a fraction of its height, before snapping to a rest. */
        const val STEP_FRACTION = 0.5f

        /** The mockup's 1.35 line height for 17 px text, over the font's own line height. */
        const val LINE_EXTRA_PX = 3f
    }
}
