package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.MapSize
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.shared.MediaPlaybackState

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
    /**
     * Now playing, as an inert outlined card (its controls are rows or the command strip): the
     * source in the caption row with the playback state as a glyph, never a word; the title
     * bright only while it plays; artist, album and the page's own status under it. Nothing to
     * play, or a player out of reach, is drawn muted throughout.
     */
    fun media(context: Context, icons: SessionIconSource, block: ScreenBlock.Media): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = PageStyle.outlined(selected = false)
        setPadding(PageStyle.CARD_PADDING_PX, 10, PageStyle.CARD_PADDING_PX, 12)
        val absent = block.state == MediaPlaybackState.NONE || block.state == MediaPlaybackState.UNAVAILABLE
        val stateGlyph = when (block.state) {
            MediaPlaybackState.PLAYING -> "play"
            MediaPlaybackState.PAUSED -> "pause"
            MediaPlaybackState.STOPPED -> "stop"
            MediaPlaybackState.NONE, MediaPlaybackState.UNAVAILABLE -> null
        }
        val playing = block.state == MediaPlaybackState.PLAYING
        if (block.source != null || stateGlyph != null) {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        PageStyle.text(context, block.source.orEmpty(), PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono, 1, 0.14f),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    stateGlyph?.let { name ->
                        glyphDrawable(context, icons, name, if (playing) PageStyle.phosphor else PageStyle.muted)?.let { drawable ->
                            addView(
                                ImageView(context).apply {
                                    setImageDrawable(drawable)
                                    tag = MediaStateTag(block.state)
                                },
                                LinearLayout.LayoutParams(MEDIA_STATE_PX, MEDIA_STATE_PX).apply { leftMargin = 10 },
                            )
                        }
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 },
            )
        }
        val titleColor = when {
            absent -> PageStyle.muted
            playing -> PageStyle.phosphor
            else -> PageStyle.ink
        }
        addView(PageStyle.text(context, block.title, PageStyle.TITLE_PX, titleColor, PageStyle.sans).apply { setLineSpacing(0f, 1.2f) })
        block.artist?.takeIf(String::isNotBlank)?.let {
            addView(PageStyle.text(context, it, PageStyle.BODY_PX, if (absent) PageStyle.muted else PageStyle.ink, PageStyle.sans).apply { setPadding(0, 4, 0, 0) })
        }
        block.album?.takeIf(String::isNotBlank)?.let {
            addView(PageStyle.text(context, it, PageStyle.ASIDE_PX, PageStyle.muted, PageStyle.sans).apply { setPadding(0, 3, 0, 0) })
        }
        block.status?.takeIf(String::isNotBlank)?.let {
            addView(PageStyle.text(context, it, PageStyle.CAPTION_PX, PageStyle.muted, PageStyle.mono).apply { setPadding(0, 8, 0, 0) })
        }
    }

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
                block.street?.takeIf(String::isNotBlank)?.let {
                    addView(PageStyle.text(context, it, scale.streetPx, PageStyle.ink, PageStyle.sans).apply { setPadding(0, 3, 0, 0) })
                }
                block.eta?.takeIf(String::isNotBlank)?.let {
                    addView(PageStyle.text(context, it, PageStyle.ASIDE_PX, PageStyle.muted, PageStyle.sans).apply { setPadding(0, 3, 0, 0) })
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    /** A compact or panel map as an outlined card; a full map outside the chromeless mode, bare. */
    fun map(context: Context, block: ScreenBlock.Map): View {
        val form = when (block.size) {
            MapSize.COMPACT -> MapForm.COMPACT
            MapSize.PANEL -> MapForm.PANEL
            MapSize.FULL -> MapForm.FULL
        }
        val map = RouteMapView(context).apply { bind(MapInput(block.geometry, form, block.view, block.headingDeg, block.degraded)) }
        if (block.size == MapSize.FULL) return map
        return MapCardView(context, map).apply { bind(block) }
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

    /** What a media block's state glyph shows, kept on the view for tests. */
    data class MediaStateTag(val state: MediaPlaybackState)

    const val MEDIA_STATE_PX = 20
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
