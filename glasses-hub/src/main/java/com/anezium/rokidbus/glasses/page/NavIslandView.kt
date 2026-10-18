package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The three ambient sizes of a guidance island (r17 1.1-1.3), by their HUD width. */
internal enum class IslandForm(val widthPx: Int) {
    TEXT(232),
    MINIMAP(262),
    PANEL(374),
}

/**
 * The line under an island's map. [strong] is the part of [left] drawn in ink (the arrival time);
 * the rest stays muted. [badge] leads it with a transit line's box ("RER B").
 */
internal data class IslandFoot(
    val left: String,
    val right: String? = null,
    val strong: String? = null,
    val badge: String? = null,
)

/** What an island says. The hub decides it; the island only draws it, and takes no input. */
internal sealed interface IslandContent {
    /**
     * Turn-by-turn or a walk to a stop: the glyph, the figure ("120 m", "4 min") with an optional
     * [measure] beside it ("250 m"), the street. A degraded position is [approximate]: "~" and a
     * muted figure, the caller passing the lost-position glyph and a degraded map. A panel adds
     * the arrival [time] in its corner and a [then] line ([thenStrong] in ink). [map] is drawn by
     * the minimap and panel forms only.
     */
    data class Guidance(
        val glyph: String,
        val figure: String,
        val measure: String? = null,
        val street: String? = null,
        val approximate: Boolean = false,
        val time: String? = null,
        val then: String? = null,
        val thenStrong: String? = null,
        val map: MapInput? = null,
        val foot: IslandFoot? = null,
        /** A shorter map than the form's own, as the home's three-resident cap draws it (r17 1.4: 150). */
        val mapHeightPx: Int? = null,
    ) : IslandContent

    /** Waiting at a stop (r17 6.2): no map, the line, its direction and platform, the next departures. */
    data class Departure(
        val line: String,
        val direction: String? = null,
        val platform: String? = null,
        val figure: String,
        val later: String? = null,
        val then: String? = null,
        val thenStrong: String? = null,
    ) : IslandContent
}

/**
 * One guidance island at a fixed ambient size, passive: text (232 px), minimap (262 px, a 259x190
 * heading-up map with the next turn circled and a scale, no street names) or panel (374 px, a
 * 371x300 map with up to two street names and the step after). The figure and the street are
 * drawn at the card sizes of [PageStyle], small enough to leave the world visible, and the
 * street scrolls to its end rather than losing it. It owns no policy: which form,
 * which map view and whether the position is trusted all come in through [bind]. With [outlined]
 * false it draws no border of its own, for a host that draws the island's one outline.
 */
internal class NavIslandView(
    context: Context,
    private val icons: SessionIconSource = HubIconSource,
    private val leaseFactory: (View) -> RenderedMapLease = { RenderedMapLease(it) },
) : LinearLayout(context) {
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PageStyle.HAIR_STROKE_PX
        color = PageStyle.outline
    }
    private val rect = RectF()
    private val clip = Path()
    private var naturalMapHeight = LayoutParams.WRAP_CONTENT
    private var mapRule: View? = null
    private var compact: View? = null
    private var regularViews = emptyList<View>()
    private var measuredBudget: Int? = null

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value.coerceAtLeast(0)
            measuredBudget = null
            requestLayout()
        }

    var form: IslandForm = IslandForm.TEXT
        private set

    /** The island's map, or null for a form or content without one. */
    var mapView: RouteMapView? = null
        private set

    /** Whether this view draws the island outline itself. */
    var outlined = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    fun bind(form: IslandForm, content: IslandContent) {
        val resized = form != this.form
        this.form = form
        removeAllViews()
        mapView = null
        mapRule = null
        compact = null
        measuredBudget = null
        naturalMapHeight = (content as? IslandContent.Guidance)?.mapHeightPx ?: LayoutParams.WRAP_CONTENT
        val inset = BORDER_INSET_PX
        setPadding(inset, inset, inset, inset)
        when (content) {
            is IslandContent.Guidance -> guidance(form, content)
            is IslandContent.Departure -> departure(content)
        }
        regularViews = (0 until childCount).map(::getChildAt)
        if (content is IslandContent.Guidance) {
            compact = compactGuidance(content).also {
                it.visibility = GONE
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        if (resized) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.makeMeasureSpec(form.widthPx, MeasureSpec.EXACTLY)
        if (measuredBudget == maxHeightPx) {
            super.onMeasure(width, heightMeasureSpec)
            return
        }
        val naturalHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        compact?.visibility = GONE
        regularViews.forEach { it.visibility = VISIBLE }
        mapView?.let { map ->
            map.layoutParams.height = naturalMapHeight
        }
        super.onMeasure(width, naturalHeight)
        val overflow = measuredHeight - maxHeightPx
        if (overflow > 0) {
            mapView?.let { map ->
                val remaining = (map.measuredHeight - overflow).coerceAtLeast(0)
                if (remaining < 60) {
                    map.visibility = GONE
                    mapRule?.visibility = GONE
                } else {
                    map.layoutParams.height = remaining
                }
            }
            super.onMeasure(width, naturalHeight)
            if (measuredHeight > maxHeightPx && compact != null) {
                // A short resident region must reflow the guidance, not cut off its GPS status.
                regularViews.forEach { it.visibility = GONE }
                compact?.visibility = VISIBLE
            }
        }
        measuredBudget = maxHeightPx
        super.onMeasure(width, heightMeasureSpec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Like r17's island, nothing inside is drawn past its rounded edge.
        val saved = canvas.save()
        clip.reset()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), RADIUS_PX, RADIUS_PX, Path.Direction.CW)
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
        // Drawn last, so nothing inside can cover the island's one outline.
        if (!outlined) return
        val half = border.strokeWidth / 2f
        rect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(rect, RADIUS_PX, RADIUS_PX, border)
    }

    private fun guidance(form: IslandForm, content: IslandContent.Guidance) {
        val panel = form == IslandForm.PANEL
        val glyphPx = if (panel) 34 else 30
        val figurePx = if (panel) 26f else PageStyle.CARD_FIGURE_PX
        val streetPx = 16f
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glyphDrawable(context, icons, content.glyph, if (content.approximate) PageStyle.muted else PageStyle.phosphor)?.let { drawable ->
                addView(ImageView(context).apply { setImageDrawable(drawable) }, LayoutParams(glyphPx, glyphPx).apply { rightMargin = 10 })
            }
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    val figure = if (content.approximate && !content.figure.startsWith("~")) "~${content.figure}" else content.figure
                    addView(
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.BOTTOM
                            addView(PageStyle.text(context, figure, figurePx, if (content.approximate) PageStyle.muted else PageStyle.phosphor, PageStyle.monoBold, 1))
                            content.measure?.let { addView(PageStyle.text(context, it, PageStyle.CARD_BODY_PX, PageStyle.ink, PageStyle.sans, 1).apply { setPadding(8, 0, 0, 2) }) }
                        },
                    )
                    // The street or stop is kept whole: wider than the island, it scrolls to its end
                    // while the island is shown instead of ending in an ellipsis.
                    content.street?.let {
                        addView(
                            OverflowLineView(context).apply {
                                text = it
                                style(streetPx, PageStyle.ink, PageStyle.sans)
                                setPadding(0, 2, 0, 0)
                                active = true
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                        )
                    }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            if (panel) {
                content.time?.let {
                    addView(
                        PageStyle.text(context, it, PageStyle.BODY_PX, PageStyle.muted, PageStyle.sans, 1),
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP; leftMargin = 10 },
                    )
                }
            }
        }
        val sidePad = if (panel) 14 else 12
        val topPad = if (panel) 10 else 8
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = sidePad
            rightMargin = sidePad
            topMargin = topPad
            bottomMargin = if (form == IslandForm.TEXT) 8 else 0
        })
        if (panel) {
            content.then?.let { then ->
                addView(rule(), LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                    leftMargin = sidePad
                    rightMargin = sidePad
                    topMargin = 8
                })
                addView(
                    PageStyle.text(context, strong(then, content.thenStrong), PageStyle.ASIDE_PX, PageStyle.muted, PageStyle.sans, 2),
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = sidePad
                        rightMargin = sidePad
                        topMargin = 8
                    },
                )
            }
        }
        val map = content.map?.takeIf { form != IslandForm.TEXT }
        if (map != null) {
            mapRule = rule().also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = if (panel) 10 else 8 }) }
            val view = RouteMapView(context, leaseFactory).apply { bind(map.copy(form = if (panel) MapForm.PANEL else MapForm.MINIMAP)) }
            mapView = view
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, content.mapHeightPx ?: LayoutParams.WRAP_CONTENT))
        }
        content.foot?.takeIf { form != IslandForm.TEXT }?.let { addView(foot(it), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        if (form != IslandForm.TEXT && map == null && content.foot == null) {
            // A minimap or panel without a map still ends on the island's own bottom padding.
            addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, 8))
        }
    }

    private fun compactGuidance(content: IslandContent.Guidance): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(10, 0, 10, 0)
        val figure = if (content.approximate && !content.figure.startsWith("~")) "~${content.figure}" else content.figure
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glyphDrawable(context, icons, content.glyph, if (content.approximate) PageStyle.muted else PageStyle.phosphor)?.let { drawable ->
                addView(ImageView(context).apply {
                    setImageDrawable(drawable)
                    contentDescription = content.glyph
                }, LayoutParams(22, 22).apply { rightMargin = 6 })
            }
            addView(PageStyle.text(context, figure, 22f, if (content.approximate) PageStyle.muted else PageStyle.phosphor, PageStyle.monoBold, 1))
            content.street?.let {
                addView(PageStyle.text(context, it, PageStyle.BODY_PX, PageStyle.ink, PageStyle.sans, 1).apply { setPadding(8, 0, 0, 0) },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val footStatus = content.foot?.left?.takeIf(String::isNotBlank)
        val warning = "GPS lost".takeIf { content.glyph == HudGlyphs.GPS_LOST && footStatus?.startsWith(it) != true }
        val status = listOfNotNull(warning, footStatus, content.foot?.right?.takeUnless { content.approximate })
            .joinToString(" · ").ifBlank { content.then ?: content.measure.orEmpty() }
        if (status.isNotBlank()) {
            addView(PageStyle.text(context, status, PageStyle.BODY_PX, PageStyle.muted, PageStyle.sans, 1),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 2 })
        }
    }

    private fun departure(content: IslandContent.Departure) {
        val side = 14
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LauncherBlocks.badge(context, content.line, PageStyle.TITLE_PX))
                content.direction?.let { addView(PageStyle.text(context, "→ $it", PageStyle.BODY_PX, PageStyle.ink, PageStyle.sans, 1).apply { setPadding(12, 0, 0, 0) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
                    ?: addView(View(context), LayoutParams(0, 0, 1f))
                content.platform?.let { addView(PageStyle.text(context, it, 14f, PageStyle.muted, PageStyle.sans, 1).apply { setPadding(10, 0, 0, 0) }) }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = side
                rightMargin = side
                topMargin = 10
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(PageStyle.text(context, content.figure, 32f, PageStyle.phosphor, PageStyle.monoBold, 1))
                content.later?.let { addView(PageStyle.text(context, it, PageStyle.CARD_BODY_PX, PageStyle.muted, PageStyle.sans, 1).apply { setPadding(14, 0, 0, 4) }) }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = side
                rightMargin = side
                topMargin = 8
            },
        )
        content.then?.let { then ->
            addView(rule(), LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = side
                rightMargin = side
                topMargin = 12
            })
            addView(
                PageStyle.text(context, strong(then, content.thenStrong), PageStyle.ASIDE_PX, PageStyle.muted, PageStyle.sans, 2),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = side
                    rightMargin = side
                    topMargin = 8
                },
            )
        }
        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, 8))
    }

    private fun foot(foot: IslandFoot): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12, 4, 12, 8)
        foot.badge?.let { addView(LauncherBlocks.badge(context, it, PageStyle.CAPTION_PX), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 }) }
        addView(PageStyle.text(context, strong(foot.left, foot.strong), PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans, 1), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        foot.right?.let { addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans, 1).apply { setPadding(10, 0, 0, 0) }) }
    }

    private fun rule(): View = View(context).apply { setBackgroundColor(PageStyle.outline) }

    /** [text] with its first [part] in ink, as r17's `<b>` inside a muted line. */
    private fun strong(text: String, part: String?): CharSequence {
        val at = part?.takeIf(String::isNotEmpty)?.let { text.indexOf(it) } ?: -1
        if (at < 0 || part == null) return text
        return SpannableString(text).apply { setSpan(ForegroundColorSpan(PageStyle.ink), at, at + part.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** Whether the short-budget fallback is what is drawn, rather than the regular form; for tests. */
    val compactShown: Boolean get() = compact?.visibility == VISIBLE

    /** The texts drawn, in order; for tests. */
    fun texts(): List<String> {
        fun walk(view: View): List<String> {
            if (view.visibility != VISIBLE) return emptyList()
            return when (view) {
                is TextView -> listOf(view.text.toString())
                is android.view.ViewGroup -> (0 until view.childCount).flatMap { walk(view.getChildAt(it)) }
                else -> emptyList()
            }
        }
        return walk(this)
    }

    companion object {
        const val RADIUS_PX = 14f

        /** Room for the 1.5 px outline, so the map inside meets it without covering it. */
        const val BORDER_INSET_PX = 2
    }
}
