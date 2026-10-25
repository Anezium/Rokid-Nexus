package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.page.HudGlyphs
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePositionQuality

/** What the hub holds live for an owner's route: its latest `/activity/geometry` and smoothed heading. */
internal data class LiveRoute(val geometry: RouteGeometry? = null, val headingDeg: Float? = null)

/**
 * A route frame away from its glance page (plan 028a §5.5, r17 3.5-3.7): [MAP] is the chrome-less
 * full map, where Step switches the view; [COMMANDS] is the page's command list opened from the
 * full map, whose Back returns to the map; [PAGE_COMMANDS] is the same list opened from the page's
 * Commands row, whose Back returns to the page with Commands selected.
 */
internal enum class FullMapMode { MAP, COMMANDS, PAGE_COMMANDS }

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

    // r17 3.4-3.8 in the contract's English.
    const val COMMANDS_SUB = "views · size · actions"
    const val VIEW_FOLLOW_SUB = "centred on you, heading up"
    const val VIEW_OVERVIEW_SUB = "the whole route, north up"
    const val FULL_SCREEN_SUB = "the map fills the screen"
    const val DISPLAY_SUB = "island size during the trip"
    const val CONFIRM_SUB = "asks for confirmation"
    const val PLUGIN_ACTIONS = "PLUGIN ACTIONS"
    const val APP = "APP"
    const val AMBIENT_SECTION = "AMBIENT SIZE · DURING THE TRIP"
    const val DISPLAY_NOTE = "Provisional on swipe, saved on tap. In an app the hub caps islands at Text. Back cancels."
    const val NO_MAP_SUB = "no map for this route"
    const val MAP_HINT = "swipe map ↔ Commands · tap full screen"
    const val COMMANDS_HINT = "swipe map ↔ Commands · tap open"
    const val FULL_NEXT_HINT = "swipe next view · tap commands"
    const val FULL_PREVIOUS_HINT = "swipe previous view · tap commands"

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

    /**
     * Whether the position the page stands on can no longer be trusted. A stale position says
     * so itself, and a map drawn with no position is a journey that has lost or not yet found
     * its fix. A route with no map at all, another app's text guidance, never had a position to
     * lose: its glyph and figures are the app's own and nothing about GPS is said.
     */
    fun degraded(body: PageBody.Route?, geometry: RouteGeometry?): Boolean {
        if (geometry?.position?.quality?.let { it != RoutePositionQuality.LIVE } == true) return true
        return when (body?.position) {
            null, RoutePositionQuality.LIVE -> false
            RoutePositionQuality.STALE -> true
            RoutePositionQuality.NONE -> hasMap(body, geometry)
        }
    }

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

    /** The view's name on the full map's floating header: `FOLLOW`, `WHOLE ROUTE`. */
    fun viewName(view: MapView): String = if (view == MapView.FOLLOW) "FOLLOW" else "WHOLE ROUTE"

    /** The full map's hint: Step goes the one way it can, toward the other view. */
    fun fullMapHint(view: MapView): String = if (VIEWS.indexOf(view) < VIEWS.lastIndex) FULL_NEXT_HINT else FULL_PREVIOUS_HINT

    /** What each size draws, under its name on the display page. */
    fun sizeSub(size: AmbientSize): String = when (size) {
        AmbientSize.TEXT -> "arrow, distance, street"
        AmbientSize.MINIMAP -> "text + heading-up map"
        AmbientSize.PANEL -> "wide map, street names"
    }

    /**
     * Whether the display page may select and save [size]. Only a route with geometry, its page's
     * or its owner's live one, has a map to draw: another app's text guidance has none, so
     * choosing Minimap or Panel there would change nothing the wearer could see.
     */
    fun sizeAvailable(size: AmbientSize, hasMap: Boolean): Boolean = size == AmbientSize.TEXT || hasMap

    /** The index of the last size the display page may select; the map sizes follow Text. */
    fun lastAvailableSize(hasMap: Boolean): Int = SIZES.indexOfLast { sizeAvailable(it, hasMap) }

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
