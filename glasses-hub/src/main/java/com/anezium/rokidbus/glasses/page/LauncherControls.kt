package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.anezium.rokidbus.glasses.session.CommandSlot
import com.anezium.rokidbus.glasses.session.CommandStrip
import com.anezium.rokidbus.glasses.session.FooterButton
import com.anezium.rokidbus.glasses.session.InlineFooter
import com.anezium.rokidbus.glasses.session.StatusLineStyle

/**
 * An activity card's commands in command focus: one row of outlined slots on the card's one
 * swipe axis. The plugin's commands come first; the hub's Open app sits after a hairline with its
 * own box-and-arrow mark and a quieter outline at rest, so it never reads as a fourth plugin
 * command. The focused slot is the only bright one, glyph and label together. Up to three slots
 * always keep their labels (shortened if they must be); from four, when every label cannot fit,
 * the slots at rest keep only their glyph and the focused one its whole label: what tap would
 * run is always read.
 */
internal class CommandStripView(context: Context, private val icons: SessionIconSource) : View(context) {
    private var items: List<CommandSlot> = emptyList()
    private var focused = 0
    private var glyphs: List<Drawable?> = emptyList()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PageStyle.STROKE_PX.toFloat()
    }
    private val rule = Paint().apply { color = PageStyle.outline }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PageStyle.BODY_PX
        typeface = PageStyle.sans
    }
    private val rect = RectF()

    /** Each slot's outline, in this view's coordinates, as last laid out. */
    var slotRects: List<RectF> = emptyList()
        private set

    /** Whether each slot drew its label, as last laid out. */
    var labelsShown: List<Boolean> = emptyList()
        private set

    /** The hairline between the plugin's commands and the hub's slot, or null without one. */
    var separatorX: Float? = null
        private set

    fun bind(strip: CommandStrip) {
        items = strip.items
        focused = strip.focusedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        glyphs = items.mapIndexed { index, slot ->
            glyphDrawable(context, icons, if (slot.hub) HudGlyphs.OPEN_APP else slot.glyph, colorOf(slot, index == focused))
        }
        contentDescription = items.getOrNull(focused)?.label
        arrange()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), HEIGHT_PX)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        arrange()
    }

    private fun arrange() {
        val width = width.toFloat()
        if (items.isEmpty() || width <= 0f) {
            slotRects = emptyList()
            labelsShown = emptyList()
            separatorX = null
            return
        }
        val hubAt = items.indexOfFirst { it.hub }.takeIf { it > 0 }
        val gaps = (items.size - 1) * GAP_PX + if (hubAt != null) SEPARATOR_PX + 2 * SEPARATOR_GAP_PX - GAP_PX else 0
        val available = width - gaps
        val share = available / items.size
        val natural = items.map { naturalWidth(it, withLabel = true) }
        val labelled = natural.all { it <= share } || items.size < GLYPH_ONLY_FROM
        val widths = if (labelled) {
            List(items.size) { share }
        } else {
            val bare = GLYPH_PX + 2f * PADDING_PX
            val rest = (items.size - 1) * bare
            items.indices.map { if (it == focused) (available - rest).coerceAtLeast(bare) else bare }
        }
        labelsShown = items.indices.map { labelled || it == focused }
        var x = 0f
        val rects = mutableListOf<RectF>()
        separatorX = null
        items.indices.forEach { index ->
            if (index > 0) {
                if (index == hubAt) {
                    x += SEPARATOR_GAP_PX
                    separatorX = x + SEPARATOR_PX / 2f
                    x += SEPARATOR_PX + SEPARATOR_GAP_PX
                } else {
                    x += GAP_PX
                }
            }
            rects += RectF(x, 0f, x + widths[index], HEIGHT_PX.toFloat())
            x += widths[index]
        }
        slotRects = rects
    }

    private fun naturalWidth(slot: CommandSlot, withLabel: Boolean): Float =
        GLYPH_PX + 2f * PADDING_PX + if (withLabel) LABEL_GAP_PX + label.measureText(slot.label) else 0f

    override fun onDraw(canvas: Canvas) {
        separatorX?.let { x ->
            val inset = HEIGHT_PX * 0.2f
            canvas.drawRect(x - SEPARATOR_PX / 2f, inset, x + SEPARATOR_PX / 2f, HEIGHT_PX - inset, rule)
        }
        items.forEachIndexed { index, slot ->
            val box = slotRects.getOrNull(index) ?: return@forEachIndexed
            val isFocused = index == focused
            val half = outline.strokeWidth / 2f
            rect.set(box.left + half, box.top + half, box.right - half, box.bottom - half)
            outline.color = outlineOf(slot, isFocused)
            canvas.drawRoundRect(rect, PageStyle.RADIUS_PX, PageStyle.RADIUS_PX, outline)

            val color = colorOf(slot, isFocused)
            val showLabel = labelsShown.getOrElse(index) { false }
            val room = box.width() - 2 * PADDING_PX - GLYPH_PX - LABEL_GAP_PX
            val text = if (showLabel && room > 0) TextUtils.ellipsize(slot.label, label, room, TextUtils.TruncateAt.END).toString() else ""
            val textWidth = if (text.isEmpty()) 0f else label.measureText(text)
            val content = GLYPH_PX + if (text.isEmpty()) 0f else LABEL_GAP_PX + textWidth
            var x = box.centerX() - content / 2f
            val cy = box.centerY()
            glyphs.getOrNull(index)?.let { glyph ->
                glyph.setBounds(x.toInt(), (cy - GLYPH_PX / 2f).toInt(), (x + GLYPH_PX).toInt(), (cy + GLYPH_PX / 2f).toInt())
                glyph.draw(canvas)
            }
            x += GLYPH_PX + LABEL_GAP_PX
            if (text.isNotEmpty()) {
                label.color = color
                canvas.drawText(text, x, cy - (label.descent() + label.ascent()) / 2f, label)
            }
        }
    }

    private fun outlineOf(slot: CommandSlot, isFocused: Boolean): Int = when {
        isFocused -> PageStyle.phosphor
        !slot.enabled || slot.hub -> PageStyle.outline
        else -> PageStyle.dim
    }

    private fun colorOf(slot: CommandSlot, isFocused: Boolean): Int = when {
        isFocused && slot.enabled -> PageStyle.phosphor
        !slot.enabled -> PageStyle.muted
        else -> PageStyle.ink
    }

    companion object {
        const val HEIGHT_PX = 48
        const val GAP_PX = 8f
        const val SEPARATOR_PX = 1f
        const val SEPARATOR_GAP_PX = 6f
        const val GLYPH_PX = 22f
        const val PADDING_PX = 12f
        const val LABEL_GAP_PX = 8f

        /** From this many slots, the ones at rest may drop their labels to keep the focused one whole. */
        const val GLYPH_ONLY_FROM = 4
    }
}

/**
 * A checkbox as a line drawing: an outlined square and, when checked, a check stroke inside it.
 * Never a filled box: a lit square would outshine the task it marks. Dashed while the desired
 * state is requested and not yet confirmed by the plugin.
 */
internal class CheckboxMarkView(context: Context) : View(context) {
    var checked = false
        private set
    var pending = false
        private set
    private var boxColor = PageStyle.muted
    private var checkColor = PageStyle.ink
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
    }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    fun bind(checked: Boolean, pending: Boolean, selected: Boolean, enabled: Boolean) {
        this.checked = checked
        this.pending = pending
        boxColor = when {
            !enabled -> PageStyle.outline
            selected -> PageStyle.phosphor
            else -> PageStyle.muted
        }
        checkColor = when {
            !enabled -> PageStyle.muted
            selected -> PageStyle.phosphor
            else -> PageStyle.ink
        }
        box.pathEffect = if (pending) DashPathEffect(floatArrayOf(4f, 3f), 0f) else null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(SIZE_PX, SIZE_PX)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = STROKE / 2f + 1f
        rect.set(inset, inset, width - inset, height - inset)
        box.color = boxColor
        canvas.drawRoundRect(rect, RADIUS, RADIUS, box)
        if (!checked) return
        val w = rect.width()
        path.reset()
        path.moveTo(rect.left + w * 0.22f, rect.top + w * 0.52f)
        path.lineTo(rect.left + w * 0.43f, rect.top + w * 0.72f)
        path.lineTo(rect.left + w * 0.78f, rect.top + w * 0.30f)
        check.color = checkColor
        canvas.drawPath(path, check)
    }

    companion object {
        const val SIZE_PX = 22
        const val STROKE = 2f
        const val RADIUS = 4f
    }
}

/** A small dashed ring after a row whose action awaits its result: asked, not yet answered. */
internal class PendingMarkView(context: Context, private val color: Int) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(SIZE_PX, SIZE_PX)
    }

    override fun onDraw(canvas: Canvas) {
        ring.color = color
        canvas.drawCircle(width / 2f, height / 2f, SIZE_PX / 2f - 2f, ring)
    }

    companion object {
        const val SIZE_PX = 14
    }
}

/**
 * The fixed footer between the viewport and the hint: two or three outlined buttons (four keep
 * only their glyphs), or one status line. Outlines are dim at rest and the selected one bright;
 * nothing is ever filled. A capture runs under a solid bright line; something unavailable sits
 * in a dashed hairline, muted.
 */
internal class InlineFooterView(context: Context, private val icons: SessionIconSource) : LinearLayout(context) {
    /** The buttons last drawn, in order; empty for a status line. */
    val buttonViews = mutableListOf<View>()

    /** The status line last drawn, or null for buttons. */
    var statusView: View? = null
        private set

    init {
        orientation = HORIZONTAL
    }

    fun bind(footer: InlineFooter?) {
        removeAllViews()
        buttonViews.clear()
        statusView = null
        when (footer) {
            null -> Unit
            is InlineFooter.Buttons -> {
                val glyphOnly = footer.buttons.size > MAX_LABELLED
                footer.buttons.forEachIndexed { index, button ->
                    val view = button(button, glyphOnly)
                    addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) leftMargin = PageStyle.FOOTER_BUTTON_GAP_PX })
                    buttonViews += view
                }
            }
            is InlineFooter.StatusLine -> {
                val view = statusLine(footer)
                addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                statusView = view
            }
        }
    }

    private fun button(button: FooterButton, glyphOnly: Boolean): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val outline = when {
            button.selected && button.enabled -> PageStyle.phosphor
            button.enabled -> PageStyle.dim
            else -> PageStyle.outline
        }
        val color = when {
            button.selected && button.enabled -> PageStyle.phosphor
            button.enabled -> PageStyle.ink
            else -> PageStyle.muted
        }
        background = PageStyle.stroked(outline)
        contentDescription = button.label
        tag = FooterTag(button.key, button.selected)
        button.glyph?.let { name ->
            glyphDrawable(context, icons, name, color)?.let { drawable ->
                addView(ImageView(context).apply { setImageDrawable(drawable) }, LayoutParams(PageStyle.FOOTER_GLYPH_PX, PageStyle.FOOTER_GLYPH_PX))
            }
        }
        if (!glyphOnly || button.glyph == null) {
            val label = PageStyle.text(context, button.label, PageStyle.BODY_PX, color, PageStyle.sans, maxLines = 1)
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (childCount > 0) leftMargin = PageStyle.FOOTER_BUTTON_GAP_PX })
        }
    }

    private fun statusLine(line: InlineFooter.StatusLine): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(STATUS_PADDING_PX, 0, STATUS_PADDING_PX, 0)
        val capture = line.style == StatusLineStyle.CAPTURE
        background = if (capture) PageStyle.stroked(PageStyle.phosphor) else PageStyle.stroked(PageStyle.outline, dashed = true)
        tag = FooterTag(if (capture) STATUS_CAPTURE else STATUS_OFF, false)
        // An unavailable line names what is missing in words; a glyph would read as an affordance.
        line.glyph?.takeIf { capture }?.let { name ->
            glyphDrawable(context, icons, name, PageStyle.phosphor)?.let { drawable ->
                addView(ImageView(context).apply { setImageDrawable(drawable) }, LayoutParams(PageStyle.FOOTER_GLYPH_PX, PageStyle.FOOTER_GLYPH_PX).apply { rightMargin = STATUS_GAP_PX })
            }
        }
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(PageStyle.text(context, line.text, PageStyle.BODY_PX, if (capture) PageStyle.ink else PageStyle.muted, PageStyle.sans, maxLines = 1))
                line.sub?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, maxLines = 1).apply { setPadding(0, 2, 0, 0) }) }
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    /** What a footer child shows, kept on the view for tests and accessibility. */
    data class FooterTag(val key: String, val selected: Boolean)

    companion object {
        /** Beyond three, buttons keep only their glyph: four labels do not fit one HUD row. */
        const val MAX_LABELLED = 3
        const val STATUS_PADDING_PX = 16
        const val STATUS_GAP_PX = 14
        const val STATUS_CAPTURE = "status capture"
        const val STATUS_OFF = "status off"
    }
}
