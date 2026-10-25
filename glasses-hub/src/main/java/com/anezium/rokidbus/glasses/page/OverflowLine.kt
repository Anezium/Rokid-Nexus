package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
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
 * Where the moving lines of one owner stand, by key, so a line the owner rebuilds continues
 * where its predecessor was. A screen is drawn afresh on every settled event and an island is
 * rebound on every changed figure; without this a value ticking once a second would restart
 * the selected line's hold each time and its hidden end would never arrive.
 *
 * The owner, not the line, decides what is forgotten: it keeps only the keys of the lines that
 * may move now ([retainOnly]) and clears everything on a change of context ([clear]). A line
 * continues only for the same text in the same room ([resume]); anything else starts afresh.
 * An entry is a string, a width and a time, nothing that holds a view or a lease.
 */
internal class OverflowMemory {
    private data class Entry(val text: String, val roomPx: Int, val startedAtMs: Long)

    private val entries = HashMap<String, Entry>()

    /** The start time to continue from for [key] with [text] in [roomPx], else [nowMs], now recorded. */
    fun resume(key: String, text: String, roomPx: Int, nowMs: Long): Long {
        val entry = entries[key]
        if (entry != null && entry.text == text && entry.roomPx == roomPx) return entry.startedAtMs
        entries[key] = Entry(text, roomPx, nowMs)
        return nowMs
    }

    fun forget(key: String) {
        entries.remove(key)
    }

    /** Keeps the lines that may move now and drops every other: deselected, removed, gone. */
    fun retainOnly(keys: Collection<String>) {
        entries.keys.retainAll(keys.toSet())
    }

    fun clear() {
        entries.clear()
    }

    /** For tests: whether a line under [key] is remembered, and how many are. */
    fun remembers(key: String): Boolean = key in entries
    val size: Int get() = entries.size
}

/**
 * A single line that keeps its whole text: at rest it is ellipsized like any line, and while
 * [active] it scrolls to its hidden end as [OverflowScroll] times it. It only ever moves while
 * it is attached, shown on a lit display and actually wider than its line, and it stops by itself
 * the moment any of that ends, so a card that lost the selection, a hidden island form, a screen
 * that went away or glasses that went dark cost nothing. The text itself is never changed: a
 * test or a reader sees the full value.
 *
 * With a [memory] and a [memoryKey], a line rebuilt by its owner continues from where the one it
 * replaces was, as long as the text and the room are the same. The line itself only ever forgets
 * on its own deselection; a line hidden and shown again, or whose text or room changed, starts
 * afresh; the owner prunes the keys of lines that are gone.
 */
internal class OverflowLineView(context: Context) : TextView(context) {
    /** Whether this line may move: the selected card's lines, a visible island's street. */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Deselected while on screen: this line's place is over. A line built at rest is
            // not a deselection; its owner decides what it replaces.
            if (!value && isAttachedToWindow) memoryKey?.let { memory?.forget(it) }
            sync()
        }

    var memory: OverflowMemory? = null
    var memoryKey: String? = null

    /**
     * What the line names beyond its exact text: a text changing under the same continuity, a
     * title whose live measure ticks, keeps its place instead of starting over.
     */
    var continuity: String? = null

    /** Whether the line is moving right now; for tests. */
    var scrolling: Boolean = false
        private set

    private var startedAtMs = 0L
    private var startedText = ""
    private var startedContinuity: String? = null
    private var startedRoom = 0
    private var hiddenSinceStart = false
    private var screenLit = true
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

    /** The width the text may use, beside any glyph drawn in the line. */
    fun roomPx(): Int = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0)

    /** How many pixels of the text lie past the line's width; 0 when it fits. */
    fun overflowPx(): Int {
        val room = roomPx()
        if (room <= 0) return 0
        return (ceil(paint.measureText(text.toString())).toInt() - room).coerceAtLeast(0)
    }

    /**
     * How long until this line's first pass has reached its end and held it there; 0 when it
     * fits, is still, or has already been read once.
     */
    fun firstPassLeftMs(nowMs: Long = SystemClock.uptimeMillis()): Long {
        if (!scrolling) return 0
        val overflow = overflowPx()
        if (overflow <= 0) return 0
        return (startedAtMs + OverflowScroll.cycleMs(overflow) - nowMs).coerceAtLeast(0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        sync()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A detached line hears no screen-state change: one that went dark, was taken off and is
        // put back on lit glasses would otherwise wait for a next wake that may never come.
        screenLit = display?.state != Display.STATE_OFF
        sync()
    }

    override fun onDetachedFromWindow() {
        // Only the callbacks end here: the owner's memory keeps the place for a successor.
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            sync()
        } else {
            // Hidden: the same line shown again starts over. A detach also passes here, and the
            // flag then dies with this view while the memory keeps the successor's place. An
            // owner rebuilt while hidden resets its own memory, so its new line starts over too.
            if (scrolling) hiddenSinceStart = true
            stop()
        }
    }

    /** A dark display is not shown either: the line stops, and starts over once it is lit. */
    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        screenLit = screenState == SCREEN_STATE_ON
        if (!screenLit && scrolling) hiddenSinceStart = true
        sync()
    }

    /**
     * TextView's own pre-draw brings a layout wider than the view back to its start, which
     * would draw a continued line at its first character once before the next tick moved it
     * again. The phase is put back after it, so the first drawn frame is the continued one.
     */
    override fun onPreDraw(): Boolean {
        val proceed = super.onPreDraw()
        if (scrolling) scrollTo(OverflowScroll.offsetAt(SystemClock.uptimeMillis() - startedAtMs, overflowPx()), 0)
        return proceed
    }

    private fun shouldScroll(): Boolean =
        active && isAttachedToWindow && isShown && screenLit && display?.state != Display.STATE_OFF && overflowPx() > 0

    private fun sync() {
        // A moving line whose text or room changed under it reads its new value from the hold.
        val sameLine = text.toString() == startedText || (continuity != null && continuity == startedContinuity)
        if (scrolling && (!sameLine || roomPx() != startedRoom)) {
            memoryKey?.let { memory?.forget(it) }
            stop()
        }
        if (shouldScroll()) start() else stop()
    }

    private fun start() {
        if (scrolling) return
        scrolling = true
        val now = SystemClock.uptimeMillis()
        val value = text.toString()
        val room = roomPx()
        if (hiddenSinceStart) {
            memoryKey?.let { memory?.forget(it) }
            hiddenSinceStart = false
        }
        startedAtMs = memoryKey?.let { memory?.resume(it, value, room, now) } ?: now
        startedText = value
        startedContinuity = continuity
        startedRoom = room
        // Laid out at its full width so the text past the line exists to scroll to.
        setHorizontallyScrolling(true)
        ellipsize = null
        scrollTo(OverflowScroll.offsetAt(now - startedAtMs, overflowPx()), 0)
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
