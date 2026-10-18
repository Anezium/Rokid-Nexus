package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.RouteManeuver
import com.anezium.rokidbus.shared.RoutePositionQuality
import com.anezium.rokidbus.shared.RouteRibbon
import com.anezium.rokidbus.shared.RouteRibbonBetween
import com.anezium.rokidbus.shared.RouteRibbonStop
import com.anezium.rokidbus.shared.RouteStopState

/**
 * `nav:main` as a route page, between hubs that negotiated the launcher bundle.
 *
 * A walking journey the wearer started carries its maneuver, its facts and both map views (follow
 * and whole route) with the wearer's position as it truly is: live, stale with the age of the last
 * fix, or none. The maps draw the OpenStreetMap streets loaded for them, and a fact says whose
 * streets they are, or that the map is the route alone for now. Guidance read from another app's
 * notification has no geometry behind it, so its page is text only with no position: no street is
 * drawn that nobody measured. A transit ride gets a stop ribbon from what the app counted; only the
 * stop to get off at has a name.
 */
internal object NavRoutePage {
    const val END_JOURNEY = "end-journey"

    /** A stop the app counted but did not name; a mark, never an invented name. */
    const val UNNAMED_STOP = "·"
    const val ESTIMATE_NOTE = "Between two stops: estimated from the stops left, not a live position."
    const val ROUTE_DATA = "Transitous · OpenStreetMap"
    const val STREETS_LOADING = "Loading"
    const val STREETS_UNAVAILABLE = "Not available, route only"

    private const val TITLE = "Navigation"
    private const val BETWEEN_ESTIMATE = 0.5
    private val GLYPH_NAME = Regex("^[a-z0-9-]{1,${PageTemplateContract.MAX_GLYPH_CHARS}}$")

    fun content(revision: Long, state: NavPageState, text: NavPageText): NexusPage.Content =
        NexusPage.Content(revision, TITLE, body(state, text), actions(state), live = true)

    /** Ending a journey is a plugin action the hub confirms; the full app stays behind its launch step. */
    fun actions(state: NavPageState): List<NexusPageActionSpec> = listOfNotNull(
        NexusPageActionSpec(END_JOURNEY, "End the journey", NexusPageActionKind.PLUGIN, confirm = true)
            .takeIf { state.journeyActive },
        NexusPageActionSpec(NavPages.OPEN_APP, "Open full app", NexusPageActionKind.IMMERSION, confirm = false),
    )

    fun body(state: NavPageState, text: NavPageText): PageBody.Route {
        if (state.journeyActive) return journey(state, text)
        // Checked first: without the listener the last route is not current.
        if (!state.listenerConnected) return status("No notification access", text.noAccess)
        if (!state.enabled) return status("Navigation is off", text.off)
        val guidance = state.guidance ?: return status("No active route", text.idle)
        return notification(guidance, state.updatedAt, text)
    }

    private fun journey(state: NavPageState, text: NavPageText): PageBody.Route {
        val snapshot = state.journey
        val destination = (snapshot?.route?.destination?.name ?: state.journeyDestination)?.takeIf(String::isNotBlank)
        if (snapshot == null) {
            // Planned from the first accurate fix: until then there is nothing to draw.
            return PageBody.Route(
                position = RoutePositionQuality.NONE,
                facts = listOfNotNull(
                    PageFact("status", "Walking route", fit(state.journeyMessage ?: RouteQuality.WAITING.message, MAX_VALUE)),
                    destination?.let { PageFact("destination", "To", fit(it, MAX_VALUE)) },
                ),
            )
        }
        val step = snapshot.nextStep
        val toDestination = step.direction == "ARRIVE"
        val street = (if (toDestination) destination else step.street.ifBlank { step.instruction })?.let { fit(it, PageTemplateContract.MAX_STREET_CHARS) }
        val destinationStreet = destination?.let { fit(it, PageTemplateContract.MAX_STREET_CHARS) }
        val maneuver = when {
            snapshot.quality == RouteQuality.ARRIVED -> RouteManeuver("arrive", fit(text.arrived, MAX_DISTANCE), street = destinationStreet)
            snapshot.quality == RouteQuality.OFF_ROUTE -> RouteManeuver(NavText.ROUTE_GLYPH, "Off route", street = destinationStreet)
            // Before any trusted fix there is no progress to measure a distance from, only what is wrong.
            snapshot.position == null ->
                RouteManeuver(NavText.ROUTE_GLYPH, fit(snapshot.guidance().primary, MAX_DISTANCE), street = destinationStreet)
            // From the confirmed progress; with a degraded position the hub marks the distance approximate.
            else -> RouteManeuver(
                glyph = if (toDestination) NavText.ROUTE_GLYPH else step.glyph,
                distance = distanceLabel(snapshot.stepDistanceMeters),
                street = street,
                eta = "~${snapshot.remainingMinutes} min",
            )
        }
        val position = snapshot.positionQuality
        val follow = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW)
        val overview = NavMapProjection.geometry(snapshot, NavMapView.OVERVIEW)
        // Beside the map, whose streets they are; or, truthfully, that it shows the route alone.
        val streets = (follow.attribution ?: overview.attribution)?.let { "$it contributors" }
            ?: if (state.basemap == BasemapState.LOADING || state.basemap == BasemapState.NONE) STREETS_LOADING else STREETS_UNAVAILABLE
        return PageBody.Route(
            position = position,
            maneuver = maneuver,
            facts = listOfNotNull(
                PageFact("remaining", "Remaining", distanceLabel(snapshot.remainingMeters)),
                PageFact("gps", "GPS", snapshot.quality.message),
                destination?.let { PageFact("destination", "To", fit(it, MAX_VALUE)) },
                PageFact("source", "Source", NavSource.NEXUS.label),
                PageFact("data", "Route data", ROUTE_DATA),
                PageFact("streets", "Street map", streets),
            ),
            follow = follow,
            overview = overview,
            fixAgeS = snapshot.fixAgeS.takeIf { position == RoutePositionQuality.STALE },
        )
    }

    /** Another app's guidance: its own words and numbers, with no map and no position. */
    private fun notification(guidance: NavGuidance, updatedAt: String?, text: NavPageText): PageBody.Route {
        val maneuver = RouteManeuver(
            glyph = if (guidance.arrived) "arrive" else glyphName(guidance.glyph),
            distance = fit(if (guidance.arrived) text.arrived else guidance.primary, MAX_DISTANCE),
            street = guidance.secondary?.let { fit(it, PageTemplateContract.MAX_STREET_CHARS) },
            eta = guidance.eta?.let { fit(it, PageTemplateContract.MAX_ETA_CHARS) },
        )
        val facts = listOfNotNull(
            guidance.measure?.let { PageFact("measure", "Also", fit(it, MAX_VALUE)) },
            guidance.badge?.let { PageFact("line", "Line", fit(it, MAX_VALUE)) },
            guidance.detail.joinToString(" · ").takeIf(String::isNotBlank)?.let { PageFact("detail", "Detail", fit(it, MAX_VALUE)) },
            PageFact("source", "Source", guidance.source.label),
            updatedAt?.let { PageFact("updated", "Received", it) },
        ).take(PageTemplateContract.MAX_ROUTE_FACTS)
        return PageBody.Route(
            position = RoutePositionQuality.NONE,
            maneuver = maneuver,
            facts = facts,
            ribbon = guidance.track?.let { ribbon(it, guidance) },
        )
    }

    /**
     * The ride as the app counted it: [NavTrack.count] stops, the vehicle past [NavTrack.at], the
     * wearer getting off at [NavTrack.target], the only one with a name. A ride longer than the
     * ribbon shows its last stops up to the one to get off at. Where the vehicle is between two
     * stops is not known, so it is placed halfway and the note says so.
     */
    internal fun ribbon(track: NavTrack, guidance: NavGuidance): RouteRibbon? {
        if (track.count < 2) return null
        val target = track.target.coerceIn(0, track.count - 1)
        val at = track.at.coerceIn(0, track.count - 1)
        val first = (target - PageTemplateContract.MAX_RIBBON_STOPS + 1).coerceAtLeast(0)
        val last = (first + PageTemplateContract.MAX_RIBBON_STOPS - 1).coerceAtMost(track.count - 1)
        if (last - first < 1) return null
        val alightName = (track.label ?: guidance.secondary)?.let { fit(it, PageTemplateContract.MAX_STOP_NAME_CHARS) }
            ?.takeIf(String::isNotBlank) ?: UNNAMED_STOP
        val stops = (first..last).map { index ->
            RouteRibbonStop(
                name = if (index == target) alightName else UNNAMED_STOP,
                state = when {
                    index == target -> RouteStopState.ALIGHT
                    index <= at -> RouteStopState.PASSED
                    else -> RouteStopState.UPCOMING
                },
            )
        }
        // Only a vehicle inside the shown stretch is placed, and only before the stop to get off at.
        val riding = at < target && at >= first
        return RouteRibbon(
            stops = stops,
            line = guidance.badge?.let { fit(it, PageTemplateContract.MAX_LINE_CHARS) },
            between = if (riding) RouteRibbonBetween(at - first, BETWEEN_ESTIMATE) else null,
            note = if (riding) ESTIMATE_NOTE else null,
        )
    }

    private fun status(title: String, detail: String) = PageBody.Route(
        position = RoutePositionQuality.NONE,
        facts = listOf(PageFact("status", title, fit(detail, MAX_VALUE))),
    )

    private fun glyphName(glyph: String): String = glyph.takeIf(GLYPH_NAME::matches) ?: NavText.ROUTE_GLYPH

    private fun fit(value: String, limit: Int): String = NavText.fit(value, limit)

    private const val MAX_DISTANCE = PageTemplateContract.MAX_DISTANCE_CHARS
    private const val MAX_VALUE = PageTemplateContract.MAX_DETAIL_CHARS
}
