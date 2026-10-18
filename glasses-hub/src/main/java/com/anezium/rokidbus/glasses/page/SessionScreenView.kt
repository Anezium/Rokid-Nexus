package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.IconRef
import com.anezium.rokidbus.glasses.session.MapSize
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.TextRole

/**
 * Draws a [SessionScreen] on the 480x640 HUD: a fixed header, the viewport that follows the
 * selection, the composer's fixed footer when there is one, and a fixed hint, on pure black. It
 * takes no key and owns no selection: the outline, the scroll and the page read all come from the
 * screen it is given. A chromeless screen's full map is drawn under everything, the header and
 * hint then floating over it without their rules.
 */
internal class SessionScreenView(
    context: Context,
    private val icons: SessionIconSource = HubIconSource,
    private val leaseFactory: (View) -> RenderedMapLease = { RenderedMapLease(it) },
) : LinearLayout(context) {
    /** The document's or conversation's measured pages, reported back to the session's pure state. */
    var onDocumentPaged: ((SessionEvent.DocumentPaged) -> Unit)? = null

    /** Content found taller than the viewport, reported back so it is read page by page. */
    var onContentOverflowed: ((SessionEvent.ContentOverflowed) -> Unit)? = null

    private val headerIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val headerTitle = PageStyle.text(context, "", 13f, PageStyle.phosphor, PageStyle.mono, maxLines = 1, letterSpacing = 0.1f)
    private val headerTrail = PageStyle.text(context, "", PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, maxLines = 1)
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(PageStyle.TEXT_SIDE_PX, 0, PageStyle.TEXT_SIDE_PX, 0)
        addView(headerIcon, LayoutParams(PageStyle.HEADER_ICON_PX, PageStyle.HEADER_ICON_PX).apply { rightMargin = PageStyle.HEADER_ICON_GAP_PX })
        addView(headerTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(headerTrail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val headerLine = View(context).apply { setBackgroundColor(PageStyle.outline) }
    val viewport = SessionViewport(context)

    /** The composer's buttons or capture line: under the viewport, above the hint, never scrolled. */
    val footer = InlineFooterView(context, icons)
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
    private var conversation: ConversationView? = null
    private var shownTitle: String? = null
    private val fullMapPainter = RouteMapPainter()
    private val renderedLease = leaseFactory(this)
    private var fullMapPlan: MapPlan? = null

    /** The chromeless screen's full map, drawn under the whole HUD; null otherwise. */
    var fullMap: MapInput? = null
        private set

    /** The views of the blocks last drawn, by block key; for tests and focus. */
    val blockViews = linkedMapOf<String, View>()

    /** Where the cards' moving lines stand, so a render of the same screen does not restart them. */
    private val overflowMemory = OverflowMemory()

    init {
        orientation = VERTICAL
        setBackgroundColor(PageStyle.black)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, PageStyle.HEADER_HEIGHT_PX))
        addView(headerLine, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(viewport, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = PageStyle.GAP_PX * 2 })
        addView(
            footer,
            LayoutParams(LayoutParams.MATCH_PARENT, PageStyle.FOOTER_HEIGHT_PX).apply {
                topMargin = PageStyle.FOOTER_TOP_GAP_PX
                bottomMargin = PageStyle.FOOTER_BOTTOM_GAP_PX
                leftMargin = PageStyle.SIDE_PX
                rightMargin = PageStyle.SIDE_PX
            },
        )
        footer.visibility = View.GONE
        addView(hintLine, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, PageStyle.HINT_HEIGHT_PX))
    }

    /** Honours the HUD's top inset, in pixels, above the fixed header. */
    fun setTopInsetPx(px: Int) {
        if (paddingTop == px) return
        setPadding(0, px.coerceAtLeast(0), 0, 0)
    }

    fun show(screen: SessionScreen) {
        val chromeless = screen.chromeless
        val title = screen.header?.title
        header.visibility = if (screen.header == null) View.GONE else View.VISIBLE
        headerLine.visibility = if (screen.header == null || chromeless) View.GONE else View.VISIBLE
        headerTitle.text = title.orEmpty()
        headerTrail.text = screen.header?.trail.orEmpty()
        headerIcon.visibility = if (bindIcon(headerIcon, screen.header?.icon, IconTone.BRIGHT)) View.VISIBLE else View.GONE
        if (title != shownTitle) {
            viewport.resetOffset()
            overflowMemory.clear()
        }
        shownTitle = title
        // Only the selected card's lines may move: the places of every other line, deselected or
        // gone, are dropped here, and the memory never holds more than one card's three lines.
        overflowMemory.retainOnly(
            screen.blocks.filterIsInstance<ScreenBlock.Card>().filter { it.selected }
                .flatMap { card -> CARD_LINES.map { "${card.key}:$it" } },
        )

        val status = screen.status
        hintAction.text = status ?: screen.hint?.action.orEmpty()
        hintAction.setTextColor(if (status != null) PageStyle.phosphor else PageStyle.muted)
        hintBack.text = screen.hint?.back.orEmpty()
        hint.visibility = if (screen.hint == null && status == null) View.GONE else View.VISIBLE
        hintLine.visibility = if (hint.visibility == View.GONE || chromeless) View.GONE else View.VISIBLE

        footer.bind(screen.footer)
        footer.visibility = if (screen.footer == null) View.GONE else View.VISIBLE

        // A document coming back on screen reports its pages again, as the state may have reset them meanwhile.
        if (document?.parent !== viewport) document?.forgetReport()
        if (conversation?.parent !== viewport) conversation?.forgetReport()
        val full = if (chromeless) screen.blocks.filterIsInstance<ScreenBlock.Map>().firstOrNull { it.size == MapSize.FULL } else null
        fullMap = full?.let { MapInput(it.geometry, MapForm.FULL, it.view, it.headingDeg, it.degraded,
            fullMap?.insets ?: MapInsets(), renderContext = it.renderContext) }
        renderedLease.bind(fullMap?.renderContext, fullMap?.view ?: MapView.FOLLOW)
        fullMapPlan = null
        val nextViews = screen.blocks.associate { block ->
            // The full map is the backdrop; its place in the column is an empty mark that keeps its key.
            val view = when {
                block === full -> View(context)
                block is ScreenBlock.Map -> LauncherBlocks.map(context, block, blockViews[block.key], leaseFactory)
                else -> viewFor(block, chromeless)
            }
            block.key to view
        }
        // Retain an attached map card across content revisions; a real removal still detaches it.
        for (index in viewport.childCount - 1 downTo 0) {
            if (viewport.getChildAt(index) !in nextViews.values) viewport.removeViewAt(index)
        }
        screen.blocks.forEachIndexed { index, block ->
            val view = nextViews.getValue(block.key)
            val params = if (block === full) SessionViewport.Params() else paramsFor(block)
            if (view.parent !== viewport) viewport.addView(view, index, params)
            else if (viewport.indexOfChild(view) != index) {
                viewport.removeView(view)
                viewport.addView(view, index, params)
            } else view.layoutParams = params
        }
        blockViews.clear()
        blockViews.putAll(nextViews)
        viewport.focus = screen.focusKey?.let(blockViews::get)
        val fit = screen.fit
        viewport.onOverflow = fit?.let {
            { onContentOverflowed?.invoke(SessionEvent.ContentOverflowed(it.frame, it.pageId, it.revision)) }
        }
        viewport.whole = screen.blocks.filterIsInstance<ScreenBlock.Fact>().mapNotNull { blockViews[it.key] }
        viewport.fit = fit?.untilKey?.let(blockViews::get)
        invalidate()
    }

    /** The plan of the full map at the current size: what the next draw uses. */
    fun fullMapPlan(): MapPlan? {
        val input = fullMap ?: return null
        if (width <= 0 || height <= 0) return null
        return fullMapPlan ?: fullMapPainter.plan(input, width.toFloat(), height.toFloat()).also { fullMapPlan = it }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val input = fullMap ?: return
        // The map's compass, scale bar and names stay clear of whatever floats over it.
        val contentBottom = (0 until viewport.childCount).maxOfOrNull { viewport.getChildAt(it).bottom }?.takeIf { it > 0 }?.let { viewport.top + it }
        val top = maxOf(if (header.visibility == View.VISIBLE) header.bottom else paddingTop, contentBottom ?: 0)
        val bottomEdge = listOf(footer, hint).filter { it.visibility == View.VISIBLE }.minOfOrNull { it.top } ?: height
        val insets = MapInsets(top.toFloat(), (height - bottomEdge).coerceAtLeast(0).toFloat())
        if (insets != input.insets) {
            fullMap = input.copy(insets = insets)
            fullMapPlan = null
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fullMapPlan = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        fullMap?.let { input -> fullMapPlan()?.let {
            fullMapPainter.draw(canvas, input, it, width.toFloat(), height.toFloat())
            renderedLease.drawn()
        } }
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        renderedLease.detach()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            renderedLease.detach()
            // A screen hidden and shown again starts its lines over, even ones drawn meanwhile.
            overflowMemory.clear()
        } else {
            invalidate()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) renderedLease.detach() else invalidate()
    }

    /** Shows [ref] in [view] at [tone]; false, with the slot emptied, when nothing honest can be drawn. */
    private fun bindIcon(view: ImageView, ref: IconRef?, tone: IconTone): Boolean {
        val drawable = ref?.let { icon -> runCatching { icons.drawable(context, icon) }.getOrNull() }
        if (ref == null || drawable == null) {
            view.setImageDrawable(null)
            view.tag = null
            return false
        }
        view.setImageDrawable(IconTint.apply(drawable, ref, IconTint.colorOf(tone)))
        view.tag = IconTag(ref, tone)
        return true
    }

    private companion object {
        /** The roles of a card's moving lines, in their memory keys. */
        val CARD_LINES = listOf("primary", "secondary", "tertiary")
    }

    private fun paramsFor(block: ScreenBlock): SessionViewport.Params = when (block) {
        is ScreenBlock.Document -> SessionViewport.Params(fill = true, side = PageStyle.TEXT_SIDE_PX)
        is ScreenBlock.Conversation -> SessionViewport.Params(fill = true)
        is ScreenBlock.Text, is ScreenBlock.Fact, is ScreenBlock.Maneuver -> SessionViewport.Params(side = PageStyle.TEXT_SIDE_PX)
        is ScreenBlock.Progress -> SessionViewport.Params(side = 140)
        is ScreenBlock.Map -> if (block.size == MapSize.FULL) SessionViewport.Params(fill = true, side = 0) else SessionViewport.Params()
        else -> SessionViewport.Params()
    }

    private fun viewFor(block: ScreenBlock, chromeless: Boolean): View = when (block) {
        is ScreenBlock.Card -> card(block)
        is ScreenBlock.Row -> row(block)
        is ScreenBlock.Text -> text(block)
        is ScreenBlock.Fact -> fact(block)
        is ScreenBlock.Progress -> progress(block)
        is ScreenBlock.Document -> documentView(block)
        is ScreenBlock.Conversation -> conversationView(block)
        is ScreenBlock.Media -> LauncherBlocks.media(context, icons, block)
        is ScreenBlock.Maneuver -> LauncherBlocks.maneuver(context, icons, block, if (chromeless) ManeuverScale.FULL else ManeuverScale.PAGE)
        is ScreenBlock.Map -> LauncherBlocks.map(context, block)
        is ScreenBlock.Ribbon -> LauncherBlocks.ribbon(context, block)
    }

    /**
     * A root card keeps its lines whatever the values: each one line. A line wider than the card
     * is ellipsized at rest and scrolls to its end while the card holds the selection, so a long
     * place or title is read whole on the card that is open. Its mark leads the label; a quieter
     * third line follows; an offline entry is drawn muted with its last-known line; an ended or
     * resolved one is dashed, bright only while it holds the selection; in command focus its
     * commands follow, the focused one outlined.
     */
    private fun card(block: ScreenBlock.Card): View = LinearLayout(context).apply {
        orientation = VERTICAL
        val dashed = block.ended || block.dashed
        background = if (dashed) PageStyle.dashedPhosphor(block.selected) else PageStyle.outlined(block.selected)
        setPadding(PageStyle.CARD_PADDING_PX, PageStyle.CARD_TOP_PX, PageStyle.CARD_PADDING_PX, PageStyle.CARD_BOTTOM_PX)
        fun line(role: String, value: CharSequence, sizePx: Float, color: Int, typeface: android.graphics.Typeface) =
            OverflowLineView(context).apply {
                text = value
                style(sizePx, color, typeface)
                memory = overflowMemory
                memoryKey = "${block.key}:$role"
                active = block.selected
            }
        val quiet = dashed || block.offline
        val bright = if (quiet) PageStyle.muted else PageStyle.phosphor
        val body = if (quiet) PageStyle.muted else PageStyle.ink
        val tone = when {
            dashed -> IconTone.ENDED
            block.selected -> IconTone.BRIGHT
            else -> IconTone.REST
        }
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                iconView(context, icons, block.icon, tone, PageStyle.CARD_ICON_PX)?.let {
                    addView(it, LayoutParams(PageStyle.CARD_ICON_PX, PageStyle.CARD_ICON_PX).apply { rightMargin = PageStyle.CARD_ICON_GAP_PX })
                }
                addView(PageStyle.text(context, block.label, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1, 0.14f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                block.time?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1).apply { setPadding(10, 0, 0, 0) }) }
            },
        )
        val primarySize = if (block.figure) PageStyle.CARD_FIGURE_PX else PageStyle.CARD_BODY_PX
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, 4, 0, 0)
                addView(
                    line("primary", block.primary, primarySize, if (block.figure) bright else body, if (block.figure) PageStyle.monoBold else PageStyle.sans),
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                block.measure?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1).apply { setPadding(10, 0, 0, 0) }) }
            },
        )
        if (block.figure) {
            // The street line is always reserved, so the card never changes height under a new value.
            addView(line("secondary", block.secondary ?: " ", PageStyle.CARD_BODY_PX, body, PageStyle.sans).apply { setPadding(0, 2, 0, 0) })
        } else {
            block.secondary?.takeIf(String::isNotBlank)?.let { addView(line("secondary", it, PageStyle.CARD_BODY_PX, body, PageStyle.sans).apply { setPadding(0, 2, 0, 0) }) }
        }
        block.tertiary?.takeIf(String::isNotBlank)?.let {
            addView(line("tertiary", it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans).apply { setPadding(0, 2, 0, 0) })
        }
        block.commands?.takeIf { it.items.isNotEmpty() }?.let { strip ->
            addView(
                CommandStripView(context, icons).apply { bind(strip) },
                LayoutParams(LayoutParams.MATCH_PARENT, CommandStripView.HEIGHT_PX).apply { topMargin = 8 },
            )
        }
    }

    /**
     * A row: its checkbox or its mark first, the label and sub, then a pending ring, a state tag
     * and the trail (an age, a count). A checked row is drawn as a line check, never a filled box.
     */
    private fun row(block: ScreenBlock.Row): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or if (block.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        minimumHeight = PageStyle.ROW_MIN_HEIGHT_PX
        background = PageStyle.outlined(block.selected, dashed = block.tombstone)
        setPadding(PageStyle.ROW_PADDING_PX, 8, PageStyle.ROW_PADDING_PX, 8)
        val inert = block.tombstone || !block.enabled
        val labelColor = when {
            inert -> PageStyle.muted
            block.selected -> PageStyle.phosphor
            else -> PageStyle.ink
        }
        block.checked?.let { checked ->
            addView(
                CheckboxMarkView(context).apply { bind(checked, block.pending, block.selected, !inert) },
                LayoutParams(CheckboxMarkView.SIZE_PX, CheckboxMarkView.SIZE_PX).apply { rightMargin = PageStyle.ROW_ICON_GAP_PX },
            )
        }
        iconView(context, icons, block.icon, if (block.selected && !inert) IconTone.BRIGHT else IconTone.REST, PageStyle.ROW_ICON_PX)?.let {
            addView(it, LayoutParams(PageStyle.ROW_ICON_PX, PageStyle.ROW_ICON_PX).apply { rightMargin = PageStyle.ROW_ICON_GAP_PX })
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = if (block.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
            addView(PageStyle.text(context, block.label, PageStyle.BODY_PX, labelColor, PageStyle.sans, maxLines = 3))
            block.sub?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans, maxLines = 2).apply { setPadding(0, 2, 0, 0) }) }
        }
        addView(texts, LayoutParams(if (block.centered) LayoutParams.WRAP_CONTENT else 0, LayoutParams.WRAP_CONTENT, if (block.centered) 0f else 1f))
        // A checkbox says its own request is pending with a dashed box; any other row gets the ring.
        if (block.pending && block.checked == null) {
            addView(
                PendingMarkView(context, if (block.selected) PageStyle.phosphor else PageStyle.muted),
                LayoutParams(PendingMarkView.SIZE_PX, PendingMarkView.SIZE_PX).apply { leftMargin = 10 },
            )
        }
        block.tag?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1, 0.06f).apply { setPadding(10, 0, 0, 0) }) }
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

        override fun onDraw(canvas: Canvas) {
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

    /**
     * A live conversation reports its measured pages for its frame, as a document does; a
     * retained one names no frame and only shows its newest page.
     */
    private fun conversationView(block: ScreenBlock.Conversation): View {
        val view = conversation ?: ConversationView(context).also { conversation = it }
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        val frame = block.frame
        val pageId = block.pageId
        val revision = block.revision
        val identity = if (frame != null && pageId != null && revision != null) Triple(frame, pageId, revision) else null
        view.onPaged = if (identity != null && frame != null && pageId != null && revision != null) {
            { starts, anchorPage -> onDocumentPaged?.invoke(SessionEvent.DocumentPaged(frame, pageId, revision, starts, anchorPage)) }
        } else {
            null
        }
        view.bind(block.caption, block.bubbles, block.anchor, block.dimmed, identity)
        return view
    }
}
