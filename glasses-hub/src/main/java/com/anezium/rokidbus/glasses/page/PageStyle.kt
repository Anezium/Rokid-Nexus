package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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

    val phosphor: Int = BusTheme.phosphor
    val ink: Int = 0xFFC8D6CC.toInt()
    val muted: Int = BusTheme.muted
    /** The mockup's hairline: just bright enough to read as an outline on the additive optics. */
    val outline: Int = 0xFF1D3A28.toInt()
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
        if (dashed) setStroke(STROKE_PX, color, 8f, 6f) else setStroke(STROKE_PX, color)
    }
}
