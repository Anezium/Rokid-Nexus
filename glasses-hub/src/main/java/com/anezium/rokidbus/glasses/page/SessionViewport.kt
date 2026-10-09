package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The column between the fixed header and hint. It never scrolls by itself or by touch: it only
 * follows the focused block, moving as little as needed to keep it whole on screen, so the first
 * hidden row, the last row and a row that wraps all come into view when they are selected.
 */
internal class SessionViewport(context: Context) : ViewGroup(context) {
    /** [fill] takes exactly the height the other blocks leave; [side] is the left and right margin. */
    class Params(val fill: Boolean = false, val side: Int = PageStyle.SIDE_PX) :
        ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    var focus: View? = null
        set(value) {
            field = value
            requestLayout()
        }

    /**
     * The block that must be in view, with everything above it, at the top of the column; when a
     * layout puts its bottom below the viewport, [onOverflow] runs once, after that pass.
     */
    var fit: View? = null
        set(value) {
            field = value
            requestLayout()
        }
    var onOverflow: (() -> Unit)? = null

    /** Blocks drawn in capped lines: one cut short does not fit either, as part of its text is never shown. */
    var whole: List<View> = emptyList()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** How far the column is moved up; kept across renders so a still-visible focus does not jump. */
    var offset = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        fun widthOf(child: View) = MeasureSpec.makeMeasureSpec((width - 2 * paramsOf(child).side).coerceAtLeast(0), MeasureSpec.EXACTLY)
        var used = PageStyle.GAP_PX * (childCount - 1).coerceAtLeast(0)
        val fills = mutableListOf<View>()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (paramsOf(child).fill) {
                fills += child
                continue
            }
            child.measure(widthOf(child), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            used += child.measuredHeight
        }
        val left = MeasureSpec.makeMeasureSpec((height - used).coerceAtLeast(0) / fills.size.coerceAtLeast(1), MeasureSpec.EXACTLY)
        fills.forEach { it.measure(widthOf(it), left) }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val height = bottom - top
        var y = 0
        val tops = IntArray(childCount)
        for (index in 0 until childCount) {
            if (index > 0) y += PageStyle.GAP_PX
            tops[index] = y
            y += getChildAt(index).measuredHeight
        }
        val until = fit?.takeIf { it.parent === this }?.let(::indexOfChild) ?: -1
        val tall = until >= 0 && tops[until] + getChildAt(until).measuredHeight > height
        if (until >= 0 && height > 0 && (tall || whole.any { it.parent === this && it.cutShort() })) {
            val overflow = onOverflow
            onOverflow = null
            // Reported after this pass, on the main looper whether or not the view is attached
            // yet: the state it updates draws this column again.
            if (overflow != null) main.post(overflow)
        }
        val target = focus?.takeIf { it.parent === this }?.let(::indexOfChild) ?: -1
        if (target >= 0) {
            val focusTop = tops[target]
            val focusBottom = focusTop + getChildAt(target).measuredHeight
            if (focusTop - offset < 0) offset = focusTop
            if (focusBottom - offset > height) offset = focusBottom - height
        }
        offset = offset.coerceIn(0, (y - height).coerceAtLeast(0))
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val side = paramsOf(child).side
            child.layout(side, tops[index] - offset, side + child.measuredWidth, tops[index] - offset + child.measuredHeight)
        }
    }

    private fun View.cutShort(): Boolean = when (this) {
        is TextView -> layout?.let { layout ->
            val last = minOf(layout.lineCount, maxLines) - 1
            last >= 0 && (layout.getEllipsisCount(last) > 0 || layout.getLineEnd(last) < text.length)
        } == true
        is ViewGroup -> (0 until childCount).any { getChildAt(it).cutShort() }
        else -> false
    }

    /** Forgets the scroll position, for a new screen rather than an update of the same one. */
    fun resetOffset() {
        offset = 0
    }

    override fun generateDefaultLayoutParams(): LayoutParams = Params()

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is Params

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = Params()

    private fun paramsOf(child: View): Params = child.layoutParams as? Params ?: Params()
}
