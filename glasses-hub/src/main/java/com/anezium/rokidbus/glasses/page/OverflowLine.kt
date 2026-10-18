package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.ceil

/**
 * When a one-line value is wider than its line, the hidden end is shown by scrolling the line
 * slowly to it and back, rather than cutting it with an ellipsis the wearer cannot resolve.
 * Pure timing: a hold at the start, a travel at a reading pace, a hold at the end, then the
 * start again. One cycle for [overflowPx] hidden pixels is [cycleMs].
 */
internal object OverflowScroll {
    const val HOLD_MS = 1_400L
    const val END_HOLD_MS = 1_000L
    const val PX_PER_S = 36

    /** How often the line is redrawn while it moves: a slow crawl needs no 60 Hz. */
    const val FRAME_MS = 40L

    fun travelMs(overflowPx: Int): Long = overflowPx.coerceAtLeast(0) * 1_000L / PX_PER_S

    fun cycleMs(overflowPx: Int): Long = HOLD_MS + travelMs(overflowPx) + END_HOLD_MS

    /** The scroll offset [elapsedMs] after the line started moving: 0 at rest, [overflowPx] at the end. */
    fun offsetAt(elapsedMs: Long, overflowPx: Int): Int {
        if (overflowPx <= 0) return 0
        val t = elapsedMs.coerceAtLeast(0) % cycleMs(overflowPx)
        val travel = travelMs(overflowPx)
        return when {
            t < HOLD_MS -> 0
            t < HOLD_MS + travel -> ((t - HOLD_MS) * PX_PER_S / 1_000L).toInt().coerceIn(0, overflowPx)
            else -> overflowPx
        }
    }
}

/**
 * A single line that keeps its whole text: at rest it is ellipsized like any line, and while
 * [active] it scrolls to its hidden end as [OverflowScroll] times it. It only ever moves while
 * it is attached, shown and actually wider than its line, and it stops by itself the moment any
 * of that ends, so a card that lost the selection, a hidden island form or a screen that went
 * away costs nothing. The text itself is never changed: a test or a reader sees the full value.
 */
internal class OverflowLineView(context: Context) : TextView(context) {
    /** Whether this line may move: the selected card's lines, a visible island's street. */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    /** Whether the line is moving right now; for tests. */
    var scrolling: Boolean = false
        private set

    private var startedAtMs = 0L
    private val step = object : Runnable {
        override fun run() {
            if (!shouldScroll()) {
                stop()
                return
            }
            scrollTo(OverflowScroll.offsetAt(SystemClock.uptimeMillis() - startedAtMs, overflowPx()), 0)
            postDelayed(this, OverflowScroll.FRAME_MS)
        }
    }

    init {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    fun style(sizePx: Float, color: Int, typeface: Typeface) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(color)
        this.typeface = typeface
    }

    /** How many pixels of the text lie past the line's width; 0 when it fits. */
    fun overflowPx(): Int {
        val room = width - paddingLeft - paddingRight
        if (room <= 0) return 0
        return (ceil(paint.measureText(text.toString())).toInt() - room).coerceAtLeast(0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        sync()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) sync() else stop()
    }

    private fun shouldScroll(): Boolean = active && isAttachedToWindow && isShown && overflowPx() > 0

    private fun sync() {
        if (shouldScroll()) start() else stop()
    }

    private fun start() {
        if (scrolling) return
        scrolling = true
        startedAtMs = SystemClock.uptimeMillis()
        // Laid out at its full width so the text past the line exists to scroll to.
        setHorizontallyScrolling(true)
        ellipsize = null
        postDelayed(step, OverflowScroll.FRAME_MS)
    }

    private fun stop() {
        if (!scrolling) return
        scrolling = false
        removeCallbacks(step)
        scrollTo(0, 0)
        setHorizontallyScrolling(false)
        ellipsize = TextUtils.TruncateAt.END
    }
}
