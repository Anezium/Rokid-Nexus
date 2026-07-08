package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

class SurfaceHudView(context: Context) : LinearLayout(context) {
    private val titleView = monoText(17f, BusTheme.text, bold = true)
    private val subtitleView = monoText(11f, BusTheme.muted)
    private val previousView = monoText(15f, BusTheme.dim)
    private val currentView = monoText(25f, BusTheme.phosphor, bold = true).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 5
    }
    private val nextView = monoText(17f, BusTheme.muted).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 3
    }
    private val boardView = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val footerView = monoText(10.5f, BusTheme.dim).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 1
    }
    private var surface: NexusSurface? = null

    private val ticker = object : Runnable {
        override fun run() {
            val active = surface ?: return
            renderNow(active)
            if (active.anchor?.playing == true && active.isTimed) {
                postDelayed(this, TICK_MS)
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(BusTheme.glassesBg)
        setPadding(px(18), px(16), px(18), px(12))
        isFocusable = true
        isFocusableInTouchMode = true

        applyMarquee(titleView)
        subtitleView.maxLines = 1
        subtitleView.ellipsize = TextUtils.TruncateAt.END
        previousView.gravity = Gravity.CENTER
        previousView.textAlignment = TEXT_ALIGNMENT_CENTER
        previousView.maxLines = 2
        previousView.ellipsize = TextUtils.TruncateAt.END

        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(3)
        })
        addView(previousView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(30)
        })
        addView(currentView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(boardView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(nextView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
        })
        addView(footerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(16)
        })
    }

    fun render(next: NexusSurface?) {
        removeCallbacks(ticker)
        surface = next
        if (next == null) {
            clear()
            return
        }
        renderNow(next)
        if (next.anchor?.playing == true && next.isTimed) {
            postDelayed(ticker, TICK_MS)
        }
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun renderNow(surface: NexusSurface) {
        titleView.text = surface.title
        titleView.visibility = visibleIf(surface.title.isNotBlank())
        subtitleView.text = surface.subtitle
        subtitleView.visibility = visibleIf(surface.subtitle.isNotBlank())
        footerView.text = surface.footer
        footerView.visibility = visibleIf(surface.footer.isNotBlank())

        if (surface.isTimed) {
            renderTimed(surface)
        } else {
            renderCard(surface)
        }
    }

    private fun renderTimed(surface: NexusSurface) {
        // Timed lines (lyrics) show one big centered line; cards pack a board.
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        currentView.textSize = TIMED_BODY_SP
        currentView.maxLines = TIMED_BODY_MAX_LINES
        currentView.gravity = Gravity.CENTER
        currentView.textAlignment = TEXT_ALIGNMENT_CENTER
        val index = currentTimedIndex(surface)
        previousView.text = surface.timedLines.getOrNull(index - 1)?.text.orEmpty()
        previousView.visibility = visibleIf(previousView.text.isNotBlank())
        currentView.text = surface.timedLines.getOrNull(index)?.text
            ?.takeIf { it.isNotBlank() }
            ?: surface.timedLines.firstOrNull()?.text
            ?: ""
        nextView.text = surface.timedLines.getOrNull(index + 1)?.text.orEmpty()
        nextView.visibility = visibleIf(nextView.text.isNotBlank())
    }

    private fun renderCard(surface: NexusSurface) {
        previousView.visibility = GONE
        nextView.visibility = GONE
        val rows = surface.rows.filter { it.text.isNotBlank() || it.isStructured }
        if (rows.any { it.isStructured }) {
            renderBoard(rows)
        } else {
            renderPlainCard(rows)
        }
    }

    private fun renderPlainCard(rows: List<SurfaceRow>) {
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        currentView.textSize = CARD_BODY_SP
        currentView.maxLines = CARD_BODY_MAX_LINES
        // Plain cards align as a left block; per-line centering scatters the columns.
        currentView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        currentView.textAlignment = TEXT_ALIGNMENT_VIEW_START
        currentView.text = rows.joinToString("\n") { it.text }
    }

    /** Departure-board rows: route badge, destination, wait times — one row each. */
    private fun renderBoard(rows: List<SurfaceRow>) {
        currentView.visibility = GONE
        boardView.visibility = VISIBLE
        boardView.removeAllViews()
        rows.forEachIndexed { index, row ->
            boardView.addView(
                boardRow(row),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = px(BOARD_ROW_GAP_DP)
                },
            )
        }
    }

    private fun boardRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                badgeView(row.badge),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                monoText(BOARD_TEXT_SP, BusTheme.text).apply {
                    text = row.text
                    applyMarquee(this)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = px(10)
                },
            )
            if (row.trail.isNotEmpty()) {
                addView(
                    trailView(row.trail),
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = px(10)
                    },
                )
            }
        }

    /**
     * Slow horizontal scroll for names too long for their slot. isSelected
     * keeps the marquee running without focus, which overlays never hold.
     */
    private fun applyMarquee(view: TextView) {
        view.isSingleLine = true
        view.setHorizontallyScrolling(true)
        view.ellipsize = TextUtils.TruncateAt.MARQUEE
        view.marqueeRepeatLimit = -1
        view.isSelected = true
    }

    /** Solid phosphor chip with punched-out route text — the brightest mark on the row. */
    private fun badgeView(badge: String): TextView =
        monoText(BOARD_BADGE_SP, BusTheme.glassesBg, bold = true).apply {
            text = badge.ifBlank { "·" }
            maxLines = 1
            gravity = Gravity.CENTER
            minWidth = px(44)
            setPadding(px(7), px(2), px(7), px(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(BusTheme.phosphor)
                cornerRadius = px(6).toFloat()
            }
        }

    /** Next departure large and bright, the following ones smaller and muted. */
    private fun trailView(trail: List<String>): TextView =
        monoText(BOARD_TRAIL_SP, BusTheme.phosphor, bold = true).apply {
            maxLines = 1
            text = SpannableStringBuilder().apply {
                append(trail.first())
                trail.drop(1).forEach { token ->
                    val start = length
                    append("  ")
                    append(token)
                    setSpan(
                        ForegroundColorSpan(BusTheme.muted),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        RelativeSizeSpan(0.78f),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun currentTimedIndex(surface: NexusSurface): Int {
        val position = surface.anchor?.effectivePositionMs(SystemClock.elapsedRealtime()) ?: 0L
        var candidate = 0
        for (index in surface.timedLines.indices) {
            if (surface.timedLines[index].timeMs <= position) {
                candidate = index
            } else {
                break
            }
        }
        return candidate.coerceIn(0, (surface.timedLines.size - 1).coerceAtLeast(0))
    }

    private fun clear() {
        titleView.text = ""
        subtitleView.text = ""
        previousView.text = ""
        currentView.text = ""
        nextView.text = ""
        footerView.text = ""
        boardView.removeAllViews()
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private fun monoText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = false
            setHorizontallyScrolling(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
        }

    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val TICK_MS = 100L

        // Plain card bodies (messages, chooser): smaller mono, more lines.
        private const val CARD_BODY_SP = 17f
        private const val CARD_BODY_MAX_LINES = 12
        private const val TIMED_BODY_SP = 25f
        private const val TIMED_BODY_MAX_LINES = 5

        // Structured board rows: badge chip, destination, wait times.
        private const val BOARD_BADGE_SP = 15f
        private const val BOARD_TEXT_SP = 16f
        private const val BOARD_TRAIL_SP = 18f
        private const val BOARD_ROW_GAP_DP = 12
    }
}
