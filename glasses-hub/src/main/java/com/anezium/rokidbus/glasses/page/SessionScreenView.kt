package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.TextRole

/**
 * Draws a [SessionScreen] on the 480x640 HUD: a fixed header, the viewport that follows the
 * selection, and a fixed hint, on pure black. It takes no key and owns no selection: the outline,
 * the scroll and the document page all come from the screen it is given.
 */
internal class SessionScreenView(context: Context) : LinearLayout(context) {
    /** The document's measured pages, reported back to the session's pure state. */
    var onDocumentPaged: ((SessionEvent.DocumentPaged) -> Unit)? = null

    /** Content found taller than the viewport, reported back so it is read page by page. */
    var onContentOverflowed: ((SessionEvent.ContentOverflowed) -> Unit)? = null

    private val headerTitle = PageStyle.text(context, "", 13f, PageStyle.phosphor, PageStyle.mono, maxLines = 1, letterSpacing = 0.1f)
    private val headerTrail = PageStyle.text(context, "", PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, maxLines = 1)
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(PageStyle.TEXT_SIDE_PX, 0, PageStyle.TEXT_SIDE_PX, 0)
        addView(headerTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(headerTrail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val headerLine = View(context).apply { setBackgroundColor(PageStyle.outline) }
    val viewport = SessionViewport(context)
    private val hintLine = View(context).apply { setBackgroundColor(PageStyle.outline) }
    private val hintAction = PageStyle.text(context, "", PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, maxLines = 1)
    private val hintBack = PageStyle.text(context, "", PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, maxLines = 1)
    private val hint = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(PageStyle.TEXT_SIDE_PX, 0, PageStyle.TEXT_SIDE_PX, 0)
        addView(hintAction, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(hintBack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private var document: DocumentPageView? = null
    private var shownTitle: String? = null

    /** The views of the blocks last drawn, by block key; for tests and focus. */
    val blockViews = linkedMapOf<String, View>()

    init {
        orientation = VERTICAL
        setBackgroundColor(PageStyle.black)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, PageStyle.HEADER_HEIGHT_PX))
        addView(headerLine, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(viewport, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = PageStyle.GAP_PX * 2 })
        addView(hintLine, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, PageStyle.HINT_HEIGHT_PX))
    }

    /** Honours the HUD's top inset, in pixels, above the fixed header. */
    fun setTopInsetPx(px: Int) {
        if (paddingTop == px) return
        setPadding(0, px.coerceAtLeast(0), 0, 0)
    }

    fun show(screen: SessionScreen) {
        val title = screen.header?.title
        header.visibility = if (screen.header == null) View.GONE else View.VISIBLE
        headerLine.visibility = header.visibility
        headerTitle.text = title.orEmpty()
        headerTrail.text = screen.header?.trail.orEmpty()
        if (title != shownTitle) viewport.resetOffset()
        shownTitle = title

        val status = screen.status
        hintAction.text = status ?: screen.hint?.action.orEmpty()
        hintAction.setTextColor(if (status != null) PageStyle.phosphor else PageStyle.muted)
        hintBack.text = screen.hint?.back.orEmpty()
        hint.visibility = if (screen.hint == null && status == null) View.GONE else View.VISIBLE
        hintLine.visibility = hint.visibility

        // A document coming back on screen reports its pages again, as the state may have reset them meanwhile.
        if (document?.parent !== viewport) document?.forgetReport()
        viewport.removeAllViews()
        blockViews.clear()
        screen.blocks.forEach { block ->
            val view = viewFor(block)
            viewport.addView(view, paramsFor(block))
            blockViews[block.key] = view
        }
        viewport.focus = screen.focusKey?.let(blockViews::get)
        val fit = screen.fit
        viewport.onOverflow = fit?.let {
            { onContentOverflowed?.invoke(SessionEvent.ContentOverflowed(it.frame, it.pageId, it.revision)) }
        }
        viewport.whole = screen.blocks.filterIsInstance<ScreenBlock.Fact>().mapNotNull { blockViews[it.key] }
        viewport.fit = fit?.untilKey?.let(blockViews::get)
    }

    private fun paramsFor(block: ScreenBlock): SessionViewport.Params = when (block) {
        is ScreenBlock.Document -> SessionViewport.Params(fill = true, side = PageStyle.TEXT_SIDE_PX)
        is ScreenBlock.Text, is ScreenBlock.Fact, is ScreenBlock.Progress ->
            SessionViewport.Params(side = if (block is ScreenBlock.Progress) 140 else PageStyle.TEXT_SIDE_PX)
        else -> SessionViewport.Params()
    }

    private fun viewFor(block: ScreenBlock): View = when (block) {
        is ScreenBlock.Card -> card(block)
        is ScreenBlock.Row -> row(block)
        is ScreenBlock.Text -> text(block)
        is ScreenBlock.Fact -> fact(block)
        is ScreenBlock.Progress -> progress(block)
        is ScreenBlock.Document -> documentView(block)
    }

    /** A root card keeps its lines whatever the values: three rows, each one line, ellipsized. */
    private fun card(block: ScreenBlock.Card): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = PageStyle.outlined(block.selected, dashed = block.ended)
        setPadding(PageStyle.CARD_PADDING_PX, 10, PageStyle.CARD_PADDING_PX, 12)
        val bright = if (block.ended) PageStyle.muted else PageStyle.phosphor
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(PageStyle.text(context, block.label, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1, 0.14f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                block.time?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1)) }
            },
        )
        val primarySize = if (block.figure) PageStyle.FIGURE_PX else PageStyle.BODY_PX
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, 6, 0, 0)
                addView(
                    PageStyle.text(context, block.primary, primarySize, if (block.figure) bright else PageStyle.ink, if (block.figure) PageStyle.monoBold else PageStyle.sans, 1),
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                block.measure?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1)) }
            },
        )
        if (block.figure) {
            // The street line is always reserved, so the card never changes height under a new value.
            addView(
                PageStyle.text(context, block.secondary ?: " ", PageStyle.BODY_PX, if (block.ended) PageStyle.muted else PageStyle.ink, PageStyle.sans, 1)
                    .apply { setPadding(0, 3, 0, 0) },
            )
        }
    }

    private fun row(block: ScreenBlock.Row): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or if (block.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        minimumHeight = PageStyle.ROW_MIN_HEIGHT_PX
        background = PageStyle.outlined(block.selected, dashed = block.tombstone)
        setPadding(PageStyle.ROW_PADDING_PX, 8, PageStyle.ROW_PADDING_PX, 8)
        val labelColor = when {
            block.tombstone || !block.enabled -> PageStyle.muted
            block.selected -> PageStyle.phosphor
            else -> PageStyle.ink
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = if (block.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
            addView(PageStyle.text(context, block.label, PageStyle.BODY_PX, labelColor, PageStyle.sans, maxLines = 3))
            block.sub?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans, maxLines = 2).apply { setPadding(0, 2, 0, 0) }) }
        }
        addView(texts, LayoutParams(if (block.centered) LayoutParams.WRAP_CONTENT else 0, LayoutParams.WRAP_CONTENT, if (block.centered) 0f else 1f))
        block.trail?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1).apply { setPadding(12, 0, 0, 0) }) }
    }

    private fun text(block: ScreenBlock.Text): TextView {
        val (size, color, face) = when (block.role) {
            TextRole.FIGURE -> Triple(PageStyle.FIGURE_PX, PageStyle.phosphor, PageStyle.monoBold)
            TextRole.TITLE -> Triple(PageStyle.TITLE_PX, PageStyle.ink, PageStyle.sans)
            TextRole.BODY -> Triple(PageStyle.BODY_PX, PageStyle.ink, PageStyle.sans)
            TextRole.CAPTION -> Triple(PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans)
            TextRole.SECTION -> Triple(PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono)
        }
        return PageStyle.text(context, block.text, size, if (block.dimmed) PageStyle.muted else color, face, letterSpacing = if (block.role == TextRole.SECTION) 0.14f else 0f).apply {
            gravity = if (block.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
            setLineSpacing(0f, 1.25f)
        }
    }

    private fun fact(block: ScreenBlock.Fact): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Without baselines the label is measured from what the value leaves, so a value as wide as
        // the row cuts the label short (and is read whole) instead of pushing past the row's edge.
        isBaselineAligned = false
        addView(PageStyle.text(context, block.label, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1, 0.1f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(PageStyle.text(context, block.value, PageStyle.BODY_PX, if (block.dimmed) PageStyle.muted else PageStyle.ink, PageStyle.sans, 2))
    }

    private fun progress(block: ScreenBlock.Progress): View = ProgressLine(context, block.fraction)

    /** A thin track with its done part drawn over it; nothing about it is laid out twice. */
    private class ProgressLine(context: Context, private val fraction: Float) : View(context) {
        private val paint = android.graphics.Paint()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 3)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            paint.color = PageStyle.outline
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = PageStyle.phosphor
            canvas.drawRect(0f, 0f, width * fraction.coerceIn(0f, 1f), height.toFloat(), paint)
        }
    }

    private fun documentView(block: ScreenBlock.Document): View {
        val view = document ?: DocumentPageView(context).also { document = it }
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.onPaged = { starts, anchorPage ->
            onDocumentPaged?.invoke(SessionEvent.DocumentPaged(block.frame, block.pageId, block.revision, starts, anchorPage))
        }
        view.bind(block.segments, block.anchor, block.dimmed, Triple(block.frame, block.pageId, block.revision), block.footer)
        return view
    }
}
