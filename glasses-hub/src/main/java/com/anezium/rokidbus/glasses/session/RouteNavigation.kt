package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.page.HudGlyphs
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePositionQuality

/** What the hub holds live for an owner's route: its latest `/activity/geometry` and smoothed heading. */
internal data class LiveRoute(val geometry: RouteGeometry? = null, val headingDeg: Float? = null)

/**
 * The full-map excursion of a route frame (plan 028a §5.5): [MAP] is the chrome-less map, where
 * Step switches the view; [COMMANDS] is the hub's command list Enter opened over it.
 */
internal enum class FullMapMode { MAP, COMMANDS }

/**
 * Pure decisions for route pages and guidance islands (plan 028a §2.1, §4, §5.5).
 *
 * Geometry: the page's own geometry is drawn for the view it names; a page carrying no geometry
 * at all draws its owner's live `/activity/geometry` in both views. A page with only one of the
 * two draws that one in both views, the view deciding heading-up or north-up.
 *
 * Degraded: a page position other than `live`, or a drawn geometry whose position is not `live`,
 * draws north-up with a dashed route, a dot without an arrow, and "~" distances. A heading that
 * is absent or stale (older than 3 s) draws north-up too, but is not degraded: the fix is good.
 */
internal object RoutePresentation {
    const val MAP_FOLLOW = "MAP · FOLLOW"
    const val MAP_OVERVIEW = "MAP · WHOLE ROUTE"
    const val COMMANDS = "Commands"
    const val VIEW_FOLLOW = "View · Follow"
    const val VIEW_OVERVIEW = "View · Whole route"
    const val FULL_SCREEN = "Full screen"
    const val DISPLAY = "Display"
    const val GPS_LOST = "GPS lost"

    /** The display page's sizes, in swipe order. */
    val SIZES = listOf(AmbientSize.TEXT, AmbientSize.MINIMAP, AmbientSize.PANEL)

    /** The two views in Step order; Step never wraps between them. */
    val VIEWS = listOf(MapView.FOLLOW, MapView.OVERVIEW)

    fun geometry(body: PageBody.Route, view: MapView, live: RouteGeometry?): RouteGeometry? {
        if (body.follow == null && body.overview == null) return live
        return when (view) {
            MapView.FOLLOW -> body.follow ?: body.overview
            MapView.OVERVIEW -> body.overview ?: body.follow
        }
    }

    fun hasMap(body: PageBody.Route, live: RouteGeometry?): Boolean =
        body.follow != null || body.overview != null || live != null

    fun degraded(body: PageBody.Route?, geometry: RouteGeometry?): Boolean =
        (body != null && body.position != RoutePositionQuality.LIVE) ||
            (geometry?.position?.quality?.let { it != RoutePositionQuality.LIVE } ?: false)

    /** The heading the map turns to: Follow only, never on a degraded position. Null draws north-up. */
    fun heading(view: MapView, degraded: Boolean, headingDeg: Float?): Float? =
        headingDeg.takeIf { view == MapView.FOLLOW && !degraded }

    fun approximate(distance: String, degraded: Boolean): String =
        if (degraded && !distance.startsWith("~")) "~$distance" else distance

    /** `GPS lost · last fix <n> s`, or null for a live position. */
    fun gpsLost(body: PageBody.Route, geometry: RouteGeometry?): String? {
        if (!degraded(body, geometry)) return null
        val age = body.fixAgeS ?: geometry?.position?.fixAgeS
        return if (age != null) "$GPS_LOST · last fix $age s" else GPS_LOST
    }

    fun glyph(maneuverGlyph: String, degraded: Boolean): String = if (degraded) HudGlyphs.GPS_LOST else maneuverGlyph

    fun mapLabel(view: MapView): String = if (view == MapView.FOLLOW) MAP_FOLLOW else MAP_OVERVIEW

    fun mapTrail(view: MapView): String = "${VIEWS.indexOf(view) + 1}/${VIEWS.size}"

    fun sizeLabel(size: AmbientSize): String = when (size) {
        AmbientSize.TEXT -> "Text"
        AmbientSize.MINIMAP -> "Minimap"
        AmbientSize.PANEL -> "Panel"
    }

    /** `Display · <size>`; plain `Display` while the owner keeps the automatic behaviour. */
    fun displayLabel(size: AmbientSize?): String = size?.let { "$DISPLAY · ${sizeLabel(it)}" } ?: DISPLAY

    /** The view after a Step of [delta] from [view]; the ends hold. */
    fun stepView(view: MapView, delta: Int): MapView =
        VIEWS[(VIEWS.indexOf(view) + delta.coerceIn(-1, 1)).coerceIn(0, VIEWS.lastIndex)]
}
