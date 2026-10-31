package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.MapSize
import com.anezium.rokidbus.glasses.session.ScreenBlock

/** How large a manoeuvre is drawn: on a page, or floating over the full-screen map. */
internal enum class ManeuverScale(val glyphPx: Int, val distancePx: Float, val streetPx: Float) {
    PAGE(40, PageStyle.FIGURE_PX, PageStyle.BODY_PX),
    FULL(50, 40f, 18f),
}

/**
 * The launcher's typed page blocks: now playing, the next manoeuvre, the route map card and the
 * transit ribbon. Each is measured at its real height; nothing here caps lines to fit a slot.
 */
internal object LauncherBlocks {
    fun media(context: Context, icons: SessionIconSource, block: ScreenBlock.Media): View =
        MediaPlayerBlockView(context, icons, block)

    /**
     * The next manoeuvre: its glyph, the distance as the figure, the street and the arrival. A
     * degraded position says so: the distance takes a "~" and drops to muted, as r17 5.9 does.
     */
    fun maneuver(context: Context, icons: SessionIconSource, block: ScreenBlock.Maneuver, scale: ManeuverScale): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        glyphDrawable(context, icons, block.glyph, if (block.approximate) PageStyle.muted else PageStyle.phosphor)?.let { drawable ->
            addView(
                ImageView(context).apply { setImageDrawable(drawable) },
                LinearLayout.LayoutParams(scale.glyphPx, scale.glyphPx).apply { rightMargin = MANEUVER_GAP_PX },
            )
        }
        val distance = if (block.approximate && !block.distance.startsWith(APPROXIMATE)) "$APPROXIMATE${block.distance}" else block.distance
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(PageStyle.text(context, distance, scale.distancePx, if (block.approximate) PageStyle.muted else PageStyle.phosphor, PageStyle.monoBold))
                // One line that scrolls a long place to its end, as the island's street does, so the
                // map under it keeps its place whatever the step names.
                block.street?.takeIf(String::isNotBlank)?.let {
                    addView(
                        OverflowLineView(context).apply {
                            text = it
                            style(scale.streetPx, PageStyle.ink, PageStyle.sans)
                            setPadding(0, 3, 0, 0)
                            active = true
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                    )
                }
                block.eta?.takeIf(String::isNotBlank)?.let {
                    addView(PageStyle.text(context, it, PageStyle.ASIDE_PX, PageStyle.muted, PageStyle.sans).apply { setPadding(0, 3, 0, 0) })
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    /** A compact or panel map as an outlined card; a full map outside the chromeless mode, bare. */
    fun map(context: Context, block: ScreenBlock.Map, previous: View? = null,
        leaseFactory: (View) -> RenderedMapLease = { RenderedMapLease(it) },
    ): View {
        val form = when (block.size) {
            MapSize.COMPACT -> MapForm.COMPACT
            MapSize.PANEL -> MapForm.PANEL
            MapSize.FULL -> MapForm.FULL
        }
        val card = (previous as? MapCardView)?.takeIf { block.size != MapSize.FULL }
        val map = (card?.map ?: (previous as? RouteMapView)?.takeIf { block.size == MapSize.FULL }
            ?: RouteMapView(context, leaseFactory)).apply {
            bind(MapInput(block.geometry, form, block.view, block.headingDeg, block.degraded, renderContext = block.renderContext))
        }
        if (block.size == MapSize.FULL) return map
        return (card ?: MapCardView(context, map)).apply { bind(block) }
    }

    /** A transit ride: the line's badge, the ribbon of stops, and the note under it. */
    fun ribbon(context: Context, block: ScreenBlock.Ribbon): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        block.line?.takeIf(String::isNotBlank)?.let { line ->
            addView(badge(context, line, 13f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })
        }
        addView(RibbonView(context).apply { bind(block.stops, block.between) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        block.note?.takeIf(String::isNotBlank)?.let {
            addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.sans).apply { setPadding(4, 6, 4, 0) })
        }
    }

    /** A transit line's name in a bright outlined box: r17's `.badge`. */
    fun badge(context: Context, line: String, sizePx: Float): TextView =
        PageStyle.text(context, line, sizePx, PageStyle.phosphor, PageStyle.sansBold, maxLines = 1).apply {
            background = PageStyle.stroked(PageStyle.phosphor, radius = 6f)
            setPadding(7, 1, 7, 2)
        }

    const val MANEUVER_GAP_PX = 12
    const val APPROXIMATE = "~"
}

/**
 * The page's map card: a caption row ("MAP · FOLLOW", "1/2") over the map, inside one outline
 * that is bright while the card holds the selection. The map's corners are rounded to sit inside
 * it, so no part of the drawing crosses the outline.
 */
internal class MapCardView(context: Context, val map: RouteMapView) : LinearLayout(context) {
    private val label = PageStyle.text(context, "", PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1, 0.14f)
    private val trail = PageStyle.text(context, "", PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1)
    private val caption = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(CAPTION_SIDE_PX, 10, CAPTION_SIDE_PX, 8)
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(trail)
    }

    init {
        orientation = VERTICAL
        val inset = PageStyle.STROKE_PX
        setPadding(inset, inset, inset, inset)
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        map.cornerRadius = PageStyle.RADIUS_PX - inset
    }

    fun bind(block: ScreenBlock.Map) {
        background = PageStyle.outlined(block.selected)
        label.text = block.label.orEmpty()
        trail.text = block.trail.orEmpty()
        caption.visibility = if (block.label == null && block.trail == null) View.GONE else View.VISIBLE
    }

    private companion object {
        const val CAPTION_SIDE_PX = 12
    }
}
