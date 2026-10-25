package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusGlyphs
import kotlin.math.ceil

/** One drawable command in a [HudActionRowView], stripped of whose tier it came from. */
internal data class HudActionChip(val glyph: String, val label: String)

/**
 * The platform's row of choices, drawn identically wherever it appears.
 *
 * An activity panel and a notice band are different tiers with different
 * lifetimes, but the affordance is one thing: a short row of glyphs, exactly
 * one of them selected, stepped through with forward and backward and fired
 * with confirm. Two drawings of that would be two things for the wearer to
 * learn, so the drawing lives here and each tier only supplies its own list.
 *
 * The row hides itself when there is nothing to offer, so a caller can render
 * unconditionally.
 *
 * With [wholeLabels] a label is never cut: the labels show only when every one
 * of them fits whole in its share, and otherwise the row is glyphs alone.
 */
internal class HudActionRowView(
    context: Context,
    private val wholeLabels: Boolean = false,
    private val glyphOf: (String) -> Drawable = builtInGlyphs(context),
) : LinearLayout(context) {
    private val labels = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.START
    }

    /** Whether the labels are drawn; for tests. */
    val labelsShown: Boolean get() = labels.isNotEmpty() && labels.all { it.visibility == View.VISIBLE }

    fun render(actions: List<HudActionChip>, selectedIndex: Int) {
        removeAllViews()
        labels.clear()
        actions.forEachIndexed { index, action ->
            addView(
                chip(action, selected = index == selectedIndex),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = BusTheme.dp(context, CHIP_GAP_DP)
                },
            )
        }
        visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Every chip is given an equal share of the row before it is measured.
     *
     * A chip wraps its label, so without a ceiling one long label simply makes
     * the row wider than the band and pushes the chips after it off the glass —
     * the ellipsize on the label never fires, because nothing ever told the
     * label it was short on room. Handing each chip the same share means three
     * long labels all truncate instead of the last one disappearing, and a short
     * label still costs only what it needs.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        if (available > 0 && labels.isNotEmpty()) {
            val gaps = BusTheme.dp(context, CHIP_GAP_DP) * (labels.size - 1)
            val chrome = BusTheme.dp(context, CHIP_CHROME_DP)
            val share = (available - gaps) / labels.size - chrome
            val ceiling = share.coerceAtLeast(BusTheme.dp(context, MIN_LABEL_DP))
            labels.forEach { it.maxWidth = ceiling }
            if (wholeLabels) {
                val fit = labels.all { ceil(it.paint.measureText(it.text.toString())).toInt() <= share }
                labels.forEach { label ->
                    val visibility = if (fit) View.VISIBLE else View.GONE
                    if (label.visibility != visibility) label.visibility = visibility
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun chip(action: HudActionChip, selected: Boolean) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val horizontal = BusTheme.dp(context, 6)
        val vertical = BusTheme.dp(context, 4)
        setPadding(horizontal, vertical, horizontal, vertical)
        background = GradientDrawable().apply {
            // Pure black, like every other HUD fill: the additive optics emit
            // nothing for it, so only the border and the label light up.
            setColor(0xFF000000.toInt())
            setStroke(
                BusTheme.dp(context, if (selected) 2 else 1),
                if (selected) BusTheme.phosphor else BusTheme.hairline,
            )
            cornerRadius = BusTheme.dp(context, 5).toFloat()
        }
        addView(
            ImageView(context).apply { setImageDrawable(glyphOf(action.glyph)) },
            LayoutParams(
                BusTheme.dp(context, ACTION_GLYPH_DP),
                BusTheme.dp(context, ACTION_GLYPH_DP),
            ),
        )
        addView(
            label(if (selected) BusTheme.phosphor else BusTheme.muted).apply {
                text = action.label
                labels.add(this)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = BusTheme.dp(context, 4)
            },
        )
    }

    private fun label(color: Int) = TextView(context).apply {
        textSize = ACTION_LABEL_SP
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    companion object {
        /** The platform's own command glyphs, as every row draws them outside tests. */
        fun builtInGlyphs(context: Context): (String) -> Drawable =
            { glyph -> requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(glyph))) }

        private const val ACTION_GLYPH_DP = 18
        private const val ACTION_LABEL_SP = 10f
        private const val CHIP_GAP_DP = 6

        /** A chip's own width around the label: padding, glyph, and the gap after it. */
        private const val CHIP_CHROME_DP = 6 + ACTION_GLYPH_DP + 4 + 6

        /** Below this the label reads as noise, so the row overflows rather than lie. */
        private const val MIN_LABEL_DP = 24
    }
}
