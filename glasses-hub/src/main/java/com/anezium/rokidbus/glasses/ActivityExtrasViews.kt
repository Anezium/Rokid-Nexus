package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.ActivityTrack

/** How the panel fits its primary value: the size, and whether the ETA had to move down. */
internal data class ActivityPrimaryFit(
    val sizeSp: Float,
    val etaBelow: Boolean,
)

/**
 * Picks the largest primary size that fits instead of ellipsizing.
 *
 * Inline with the ETA the size may shrink to [inlineMinSp]; below that the
 * primary is worth more than the ETA's position, so the ETA moves to the
 * secondary row and the primary takes the largest size that fits alone.
 * [widthAtSp] measures the primary at a given size.
 */
internal fun fitActivityPrimary(
    availablePx: Float,
    inlineEtaPx: Float?,
    widthAtSp: (Float) -> Float,
    maxSp: Float = ACTIVITY_PRIMARY_MAX_SP,
    inlineMinSp: Float = ACTIVITY_PRIMARY_INLINE_MIN_SP,
    minSp: Float = ACTIVITY_PRIMARY_MIN_SP,
): ActivityPrimaryFit {
    if (inlineEtaPx != null) {
        var size = maxSp
        while (size >= inlineMinSp) {
            if (widthAtSp(size) + inlineEtaPx <= availablePx) return ActivityPrimaryFit(size, false)
            size -= 1f
        }
    }
    var size = maxSp
    while (size >= minSp) {
        if (widthAtSp(size) <= availablePx) return ActivityPrimaryFit(size, inlineEtaPx != null)
        size -= 1f
    }
    return ActivityPrimaryFit(minSp, inlineEtaPx != null)
}

internal const val ACTIVITY_PRIMARY_MAX_SP = 24f
internal const val ACTIVITY_PRIMARY_INLINE_MIN_SP = 20f
internal const val ACTIVITY_PRIMARY_MIN_SP = 16f

/**
 * A line or route mark ("38", "RER B") as a filled block with cut-out text.
 *
 * It stands in for the activity glyph, so it draws into whatever bounds the
 * glyph slot gives it. On the urgent band, whose own fill is phosphor, the
 * colours swap so the block still reads as a block.
 */
internal class ActivityBadgeDrawable(
    context: Context,
    private val text: String,
    inverted: Boolean = false,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (inverted) BLACK else BusTheme.phosphor
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (inverted) BusTheme.phosphor else BLACK
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.isEmpty) return
        rect.set(box)
        val radius = box.height() * CORNER_FRACTION
        canvas.drawRoundRect(rect, radius, radius, fill)

        // Largest size that fits both the height share and the width, so "38"
        // stays big and "RER B" still fits the same slot.
        val padding = 3f * density
        label.textSize = box.height() * TEXT_HEIGHT_FRACTION
        val width = label.measureText(text)
        val room = box.width() - 2 * padding
        if (width > room && width > 0f) label.textSize *= room / width
        val baseline = box.exactCenterY() - (label.descent() + label.ascent()) / 2f
        canvas.drawText(text, box.exactCenterX(), baseline, label)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        label.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        label.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val CORNER_FRACTION = 0.17f
        const val TEXT_HEIGHT_FRACTION = 0.46f
    }
}

/**
 * A row of ordered positions: passed ones filled dim, the current one filled
 * bright, the target as a ring, and later ones hollow. The target's label
 * follows the row. Every value comes from the plugin's last update; the view
 * never advances on its own.
 */
internal class ActivityTrackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LABEL_SP,
            resources.displayMetrics,
        )
    }
    private var track: ActivityTrack? = null
    private var inverted = false

    fun render(track: ActivityTrack?, inverted: Boolean = false) {
        this.track = track
        this.inverted = inverted
        visibility = if (track == null) GONE else VISIBLE
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            (HEIGHT_DP * density).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val current = track ?: return
        val bright = if (inverted) BLACK else BusTheme.phosphor
        val quiet = if (inverted) BLACK else BusTheme.dim
        val hollow = if (inverted) BusTheme.phosphor else BLACK
        val targetRadius = TARGET_RADIUS_DP * density
        val dotRadius = DOT_RADIUS_DP * density
        val centerY = height / 2f

        // The dots keep a legible minimum spacing; a long label gives way to
        // them rather than the other way round.
        val minimumDots = (current.count - 1) * MIN_STEP_DP * density + 2 * targetRadius
        val gap = LABEL_GAP_DP * density
        val labelText = current.label
            ?.let { TextUtils.ellipsize(it, label, width - minimumDots - gap, TextUtils.TruncateAt.END) }
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
        val labelWidth = labelText?.let { label.measureText(it) + gap } ?: 0f
        val start = targetRadius
        val room = (width - labelWidth - 2 * targetRadius).coerceAtLeast(0f)
        val step = if (current.count > 1) {
            (room / (current.count - 1)).coerceAtMost(MAX_STEP_DP * density)
        } else {
            0f
        }
        fun x(index: Int) = start + index * step

        stroke.strokeWidth = SEGMENT_DP * density
        for (index in 0 until current.count - 1) {
            stroke.color = if (index < current.at) bright else quiet
            canvas.drawLine(x(index), centerY, x(index + 1), centerY, stroke)
        }
        for (index in 0 until current.count) {
            val cx = x(index)
            when {
                index == current.target -> {
                    dot.color = hollow
                    canvas.drawCircle(cx, centerY, targetRadius, dot)
                    stroke.color = bright
                    stroke.strokeWidth = TARGET_STROKE_DP * density
                    canvas.drawCircle(cx, centerY, targetRadius - stroke.strokeWidth / 2f, stroke)
                    if (index == current.at) {
                        dot.color = bright
                        canvas.drawCircle(cx, centerY, dotRadius * 0.8f, dot)
                    }
                }
                index < current.at -> {
                    dot.color = quiet
                    canvas.drawCircle(cx, centerY, dotRadius, dot)
                }
                index == current.at -> {
                    dot.color = bright
                    canvas.drawCircle(cx, centerY, dotRadius, dot)
                }
                else -> {
                    dot.color = hollow
                    canvas.drawCircle(cx, centerY, dotRadius, dot)
                    stroke.color = quiet
                    stroke.strokeWidth = HOLLOW_STROKE_DP * density
                    canvas.drawCircle(cx, centerY, dotRadius - stroke.strokeWidth / 2f, stroke)
                }
            }
        }
        if (labelText != null) {
            label.color = bright
            val baseline = centerY - (label.descent() + label.ascent()) / 2f
            canvas.drawText(
                labelText,
                x(current.count - 1) + targetRadius + gap,
                baseline,
                label,
            )
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val HEIGHT_DP = 16f
        const val DOT_RADIUS_DP = 3.5f
        const val TARGET_RADIUS_DP = 6f
        const val TARGET_STROKE_DP = 2.2f
        const val HOLLOW_STROKE_DP = 1.4f
        const val SEGMENT_DP = 2f
        const val MAX_STEP_DP = 22f
        const val MIN_STEP_DP = 9f
        const val LABEL_GAP_DP = 7f
        const val LABEL_SP = 11f
    }
}
