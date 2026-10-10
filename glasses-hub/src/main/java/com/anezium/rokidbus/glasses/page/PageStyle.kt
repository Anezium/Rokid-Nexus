package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

/**
 * Sizes are logical HUD pixels at the 480x640 reference, set as raw pixels: the glasses run at
 * 240 dpi, where an sp would be one and a half of them. Hierarchy is brightness only, on black.
 */
internal object PageStyle {
    const val WIDTH_PX = 480
    const val HEIGHT_PX = 640

    const val FIGURE_PX = 30f
    const val TITLE_PX = 20f
    const val BODY_PX = 17f
    const val ASIDE_PX = 13f
    const val CAPTION_PX = 12f

    const val HEADER_HEIGHT_PX = 54
    const val HINT_HEIGHT_PX = 40
    const val SIDE_PX = 18
    const val TEXT_SIDE_PX = 22
    const val GAP_PX = 8
    const val ROW_MIN_HEIGHT_PX = 46
    const val ROW_PADDING_PX = 16
    const val CARD_PADDING_PX = 14
    const val RADIUS_PX = 12f
    const val STROKE_PX = 2
    const val SEGMENT_GAP_PX = 10

    /** App marks and place glyphs: 20 in a page header, 18 before a card label, 24 leading a row. */
    const val HEADER_ICON_PX = 20
    const val CARD_ICON_PX = 18
    const val ROW_ICON_PX = 24
    const val HEADER_ICON_GAP_PX = 10
    const val CARD_ICON_GAP_PX = 8
    const val ROW_ICON_GAP_PX = 12

    /** The inline footer: one fixed row of outlined buttons between the viewport and the hint. */
    const val FOOTER_HEIGHT_PX = 56
    const val FOOTER_TOP_GAP_PX = 8
    const val FOOTER_BOTTOM_GAP_PX = 7
    const val FOOTER_BUTTON_GAP_PX = 10
    const val FOOTER_GLYPH_PX = 22

    val phosphor: Int = BusTheme.phosphor
    val ink: Int = 0xFFC8D6CC.toInt()
    val muted: Int = BusTheme.muted
    /** The mockup's hairline: just bright enough to read as an outline on the additive optics. */
    val outline: Int = 0xFF1D3A28.toInt()

    /** The mockup's dim green: footer buttons at rest, our bubbles, streets and passed stops. */
    val dim: Int = 0xFF2F6B45.toInt()

    /** A route that can no longer be trusted, or a degraded one: still green, well under the live route. */
    val done: Int = 0xFF3D8A54.toInt()
    val black: Int = BusTheme.glassesBg

    val mono: Typeface = Typeface.MONOSPACE
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val sans: Typeface = Typeface.SANS_SERIF
    val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun text(
        context: Context,
        value: CharSequence,
        sizePx: Float,
        color: Int,
        typeface: Typeface = sans,
        maxLines: Int = Int.MAX_VALUE,
        letterSpacing: Float = 0f,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(color)
        this.typeface = typeface
        includeFontPadding = false
        this.letterSpacing = letterSpacing
        if (maxLines != Int.MAX_VALUE) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    /** Selection is an outline only, never a fill: a bright area would hide the world. */
    fun outlined(selected: Boolean, dashed: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(black)
        cornerRadius = RADIUS_PX
        val color = if (selected && !dashed) phosphor else if (dashed) muted else outline
        if (dashed) setStroke(STROKE_PX, color, DASH_PX, DASH_GAP_PX) else setStroke(STROKE_PX, color)
    }

    /**
     * An outline in [color], solid or dashed, over black. The dashed bright variant is a draft or
     * a resolved card that still holds the focus: the selection stays visible on a dashed line.
     */
    fun stroked(color: Int, dashed: Boolean = false, radius: Float = RADIUS_PX, width: Int = STROKE_PX): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(black)
            cornerRadius = radius
            if (dashed) setStroke(width, color, DASH_PX, DASH_GAP_PX) else setStroke(width, color)
        }

    /** A dashed outline that stays bright when selected, unlike [outlined]'s inert dashed line. */
    fun dashedPhosphor(selected: Boolean): GradientDrawable = stroked(if (selected) phosphor else muted, dashed = true)

    /**
     * The hint's gesture verbs read a step brighter than the rest, as in the accepted mockup; the
     * words themselves come from the screen, unchanged.
     */
    fun hint(value: String): CharSequence {
        if (value.isEmpty()) return value
        val spannable = SpannableString(value)
        var start = 0
        while (start < value.length) {
            val end = value.indexOf(HINT_SEPARATOR, start).let { if (it < 0) value.length else it }
            val segment = value.substring(start, end)
            val trimmed = segment.trimStart()
            val leading = segment.length - trimmed.length
            val verb = GESTURE_VERBS.firstOrNull { trimmed == it || trimmed.startsWith("$it ") }
            if (verb != null) {
                spannable.setSpan(ForegroundColorSpan(ink), start + leading, start + leading + verb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end + HINT_SEPARATOR.length
        }
        return spannable
    }

    const val DASH_PX = 8f
    const val DASH_GAP_PX = 6f
    private const val HINT_SEPARATOR = " · "
    private val GESTURE_VERBS = listOf("swipe", "tap", "back")
}
