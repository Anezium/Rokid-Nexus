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
import android.util.TypedValue
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
    private val mediaView = MediaHudView(context).apply { visibility = GONE }
    private val imageView = ImageHudView(context).apply {
        visibility = GONE
        setPadding(px(4), px(4), px(4), px(4))
    }
    private val footerView = monoText(10.5f, BusTheme.dim).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 1
    }
    private var surface: NexusSurface? = null
    private var listRenderGeneration = 0L
    private var pendingListLayoutListener: View.OnLayoutChangeListener? = null

    private val ticker = object : Runnable {
        override fun run() {
            val active = surface ?: return
            renderNow(active)
            if (shouldTick(active)) {
                postDelayed(this, tickDelay(active))
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
        addView(mediaView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
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
        if (shouldTick(next)) {
            postDelayed(ticker, tickDelay(next))
        }
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        invalidatePendingListLayout()
        super.onDetachedFromWindow()
    }

    private fun renderNow(surface: NexusSurface) {
        invalidatePendingListLayout()
        titleView.text = surface.title
        titleView.visibility = visibleIf(surface.title.isNotBlank())
        subtitleView.text = surface.subtitle
        subtitleView.visibility = visibleIf(surface.subtitle.isNotBlank())
        footerView.text = surface.footer
        footerView.visibility = visibleIf(surface.footer.isNotBlank())

        when {
            surface.isImage -> renderImage(surface)
            surface.isMedia -> renderMedia(surface)
            surface.isTimed -> renderTimed(surface)
            else -> renderCard(surface)
        }
    }

    private fun renderTimed(surface: NexusSurface) {
        // Timed lines (lyrics) show one big centered line; cards pack a board.
        mediaView.visibility = GONE
        imageView.visibility = GONE
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        // Long lyric lines must never lose their tail: shrink to fit instead of clipping.
        currentView.maxLines = TIMED_BODY_MAX_LINES
        fitTimedBody()
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

    private fun fitCardBody() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentView.setAutoSizeTextTypeUniformWithConfiguration(
                CARD_BODY_MIN_SP,
                CARD_BODY_MAX_SP,
                CARD_BODY_STEP_SP,
                TypedValue.COMPLEX_UNIT_SP,
            )
        } else {
            currentView.textSize = CARD_BODY_SP
        }
    }

    private fun fitTimedBody() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentView.setAutoSizeTextTypeUniformWithConfiguration(
                TIMED_BODY_MIN_SP,
                TIMED_BODY_MAX_SP,
                TIMED_BODY_STEP_SP,
                TypedValue.COMPLEX_UNIT_SP,
            )
        } else {
            currentView.textSize = TIMED_BODY_SP
        }
    }

    private fun renderCard(surface: NexusSurface) {
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        nextView.visibility = GONE
        val rows = surface.rows.filter { it.text.isNotBlank() || it.isStructured }
        when {
            rows.any { it.isListRow } -> renderList(rows)
            rows.any { it.isStructured } -> renderBoard(rows)
            else -> renderPlainCard(rows)
        }
    }

    /**
     * Attention-ordered list: a selection rail, per-row weight, and an optional
     * secondary line. Unlike the departure board there is no chip column — the
     * hierarchy is carried by weight and position, which is what a list of live
     * things (agent sessions, conversations) actually needs.
     */
    private fun renderList(rows: List<SurfaceRow>) {
        currentView.visibility = GONE
        boardView.visibility = VISIBLE
        boardView.gravity = Gravity.TOP
        boardView.removeAllViews()
        val rowViews = rows.mapIndexed { index, row ->
            val view = if (row.tone == SurfaceRow.TONE_BODY) bodyRow(row) else listRow(row)
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) {
                    topMargin = px(
                        if (row.tone == SurfaceRow.TONE_BODY) LIST_BODY_GAP_DP else LIST_ROW_GAP_DP,
                    )
                }
            }
            view to params
        }
        // The board's height is weight-fixed, but the chrome above it is not: a
        // title appearing on this render moves the board's bounds only at the
        // next layout pass. Window against whatever size is current, then again
        // whenever the bounds actually change — the size key keeps the listener
        // from looping on the layout its own re-attachment triggers.
        var windowedSizeKey = 0L
        fun windowNow() {
            val width = boardView.width - boardView.paddingLeft - boardView.paddingRight
            val height = boardView.height - boardView.paddingTop - boardView.paddingBottom
            if (width <= 0 || height <= 0) return
            val sizeKey = (width.toLong() shl 32) or height.toLong()
            if (sizeKey == windowedSizeKey) return
            windowedSizeKey = sizeKey
            attachListWindow(rows, rowViews)
        }

        val generation = listRenderGeneration
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Never attach from inside the layout pass: children added mid-layout
            // sit in the tree unmeasured and the board draws empty (seen on
            // device, first render of a fresh surface window). The post lands
            // after the traversal, where addView schedules a clean one.
            boardView.post {
                if (generation == listRenderGeneration) windowNow()
            }
        }
        pendingListLayoutListener = listener
        boardView.addOnLayoutChangeListener(listener)
        windowNow()
        if (windowedSizeKey == 0L) boardView.requestLayout()
    }

    private fun attachListWindow(
        rows: List<SurfaceRow>,
        rowViews: List<Pair<View, LayoutParams>>,
    ) {
        val viewportWidth = (boardView.width - boardView.paddingLeft - boardView.paddingRight)
            .coerceAtLeast(1)
        val viewportHeight = (boardView.height - boardView.paddingTop - boardView.paddingBottom)
            .coerceAtLeast(0)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val rowOuterHeights = rowViews.map { (view, params) ->
            view.measure(widthSpec, heightSpec)
            view.measuredHeight + params.topMargin
        }
        val indicatorProbe = listOverflowIndicator(up = false, hiddenCount = rows.size)
        indicatorProbe.measure(widthSpec, heightSpec)
        val selectedIndex = rows.indexOfFirst { it.selected }.takeIf { it >= 0 }
        val window = surfaceListViewport(
            rowOuterHeightsPx = rowOuterHeights,
            viewportHeightPx = viewportHeight,
            selectedIndex = selectedIndex,
            indicatorHeightPx = indicatorProbe.measuredHeight,
        )

        boardView.removeAllViews()
        if (window.hiddenAbove > 0) {
            boardView.addView(
                listOverflowIndicator(up = true, hiddenCount = window.hiddenAbove),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        for (index in window.firstRow until window.lastRowExclusive) {
            val (view, params) = rowViews[index]
            boardView.addView(view, params)
        }
        if (window.hiddenBelow > 0) {
            boardView.addView(
                listOverflowIndicator(up = false, hiddenCount = window.hiddenBelow),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun listOverflowIndicator(up: Boolean, hiddenCount: Int): TextView =
        monoText(LIST_SUB_SP, BusTheme.muted).apply {
            text = "${if (up) "▴" else "▾"} $hiddenCount"
            maxLines = 1
        }

    private fun invalidatePendingListLayout() {
        listRenderGeneration += 1
        pendingListLayoutListener?.let(boardView::removeOnLayoutChangeListener)
        pendingListLayoutListener = null
    }

    private fun listRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                selectionRail(row.selected),
                LayoutParams(px(3), LayoutParams.MATCH_PARENT),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.BOTTOM
                            addView(
                                monoText(LIST_TITLE_SP, toneColor(row), bold = row.isEmphasised)
                                    .apply {
                                        text = row.text
                                        maxLines = 1
                                        // Never marquee a list title: a scrolling row is
                                        // unreadable at a glance, which is the whole point.
                                        ellipsize = TextUtils.TruncateAt.END
                                    },
                                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                            )
                            if (row.trail.isNotEmpty()) {
                                addView(
                                    listMetaView(row),
                                    LayoutParams(
                                        LayoutParams.WRAP_CONTENT,
                                        LayoutParams.WRAP_CONTENT,
                                    ).apply { marginStart = px(8) },
                                )
                            }
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    if (row.sub.isNotBlank()) {
                        addView(
                            monoText(LIST_SUB_SP, BusTheme.muted).apply {
                                text = row.sub
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                                .apply { topMargin = px(2) },
                        )
                    }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(9) },
            )
        }

    /** Prose row: a fixed dim label, then wrapped text — a conversation, not a table. */
    private fun bodyRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            if (row.badge.isNotBlank()) {
                addView(
                    monoText(LIST_LABEL_SP, BusTheme.muted, bold = true).apply {
                        text = row.badge
                        maxLines = 1
                    },
                    LayoutParams(px(LIST_LABEL_WIDTH_DP), LayoutParams.WRAP_CONTENT),
                )
            }
            addView(
                monoText(LIST_BODY_SP, if (row.selected) BusTheme.phosphor else BusTheme.text).apply {
                    text = row.text
                    maxLines = LIST_BODY_MAX_LINES
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    private fun selectionRail(selected: Boolean): View =
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (selected) BusTheme.phosphor else BusTheme.glassesBg)
                cornerRadius = px(2).toFloat()
            }
        }

    /** Status token bright, the rest (age, counters) muted and smaller. */
    private fun listMetaView(row: SurfaceRow): TextView =
        monoText(LIST_META_SP, toneColor(row), bold = row.isEmphasised).apply {
            maxLines = 1
            text = SpannableStringBuilder().apply {
                append(row.trail.first())
                row.trail.drop(1).forEach { token ->
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
                        RelativeSizeSpan(0.82f),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun toneColor(row: SurfaceRow): Int = when {
        row.tone == SurfaceRow.TONE_ALERT -> BusTheme.phosphor
        row.tone == SurfaceRow.TONE_DIM -> BusTheme.muted
        row.selected -> BusTheme.phosphor
        else -> BusTheme.text
    }

    private fun renderMedia(surface: NexusSurface) {
        imageView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        mediaView.visibility = VISIBLE
        mediaView.render(surface)
    }

    private fun renderImage(surface: NexusSurface) {
        mediaView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        imageView.visibility = VISIBLE
        imageView.render(surface)
    }

    private fun renderPlainCard(rows: List<SurfaceRow>) {
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        // Long card bodies (assistant replies, article text) must never lose
        // their tail: shrink to fit instead of clipping, like timed lines do.
        fitCardBody()
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
        // Both renderers share this container, so each states its own alignment
        // rather than inheriting whatever the last surface left behind.
        boardView.gravity = Gravity.CENTER_VERTICAL
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
        invalidatePendingListLayout()
        titleView.text = ""
        subtitleView.text = ""
        previousView.text = ""
        currentView.text = ""
        nextView.text = ""
        footerView.text = ""
        mediaView.clear()
        mediaView.visibility = GONE
        imageView.render(null)
        imageView.visibility = GONE
        boardView.removeAllViews()
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private fun shouldTick(surface: NexusSurface): Boolean =
        surface.anchor?.playing == true && (surface.isTimed || surface.isMedia)

    private fun tickDelay(surface: NexusSurface): Long =
        if (surface.isMedia) MEDIA_TICK_MS else TICK_MS

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
        private const val MEDIA_TICK_MS = 500L

        // Plain card bodies (messages, chooser): smaller mono, more lines.
        // Auto-fit mirrors the lyrics pattern: short bodies keep the full
        // size, long ones shrink to fit instead of clipping their tail.
        private const val CARD_BODY_SP = 17f
        private const val CARD_BODY_MAX_LINES = 15
        private const val CARD_BODY_MAX_SP = 17
        private const val CARD_BODY_MIN_SP = 12
        private const val CARD_BODY_STEP_SP = 1
        private const val TIMED_BODY_SP = 25f
        private const val TIMED_BODY_MAX_LINES = 5

        // Lyrics auto-fit: keep the big size for short lines, shrink long ones to fit.
        private const val TIMED_BODY_MAX_SP = 25
        private const val TIMED_BODY_MIN_SP = 14
        private const val TIMED_BODY_STEP_SP = 1

        // Structured board rows: badge chip, destination, wait times.
        private const val BOARD_BADGE_SP = 15f
        private const val BOARD_TEXT_SP = 16f
        private const val BOARD_TRAIL_SP = 18f
        private const val BOARD_ROW_GAP_DP = 12

        // List rows: selection rail, title + meta, optional secondary line.
        private const val LIST_TITLE_SP = 16f
        private const val LIST_SUB_SP = 12.5f
        private const val LIST_META_SP = 14f
        private const val LIST_ROW_GAP_DP = 11
        // Conversation rows: fixed speaker label, wrapped prose.
        private const val LIST_BODY_SP = 14.5f
        private const val LIST_LABEL_SP = 11.5f
        private const val LIST_LABEL_WIDTH_DP = 38
        private const val LIST_BODY_MAX_LINES = 3
        private const val LIST_BODY_GAP_DP = 9
    }
}
