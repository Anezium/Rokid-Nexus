package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.page.IslandContent
import com.anezium.rokidbus.glasses.page.IslandFoot
import com.anezium.rokidbus.glasses.page.IslandForm
import com.anezium.rokidbus.glasses.page.MapForm
import com.anezium.rokidbus.glasses.page.MapInput
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.glasses.session.RoutePresentation
import com.anezium.rokidbus.glasses.session.SessionScreens
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RenderedMapContract

/** A guidance island as decided, before any view: its ambient form and what it says. */
internal data class GuidanceIsland(val form: IslandForm, val content: IslandContent.Guidance)

/**
 * Which islands are drawn as guidance islands, and how (plan 028a §8.1). Pure.
 *
 * Navigation may choose an ambient size; any provider may supply live route geometry. Every other
 * owner keeps the existing island. An explicit size is drawn as is (the activity tier already
 * capped it at text over an app); an automatic one is its minimap while expanded and its text
 * form while folded, so the ten-second collapse still applies to it. Other providers' saved size
 * overrides are inert: their developer owns their presentation. The map is heading-up
 * (Follow); a position that is not live draws the lost glyph, "~" figures and a degraded map.
 *
 * The foot under a minimap or panel (r17 1.2-1.4, 5.9, 5.10) says only what is known: the
 * arrival time and the remaining measure on a minimap; `GPS lost · last fix <n> s` for a lost
 * position; the last-known time while the phone link is down; and, for a source that draws no
 * route, the name of the plugin that sent it, never a guessed app.
 */
internal object GuidanceIslands {
    const val ARRIVAL = "arrival"

    /** r17 1.4: with three residents at home, the minimap's map is shorter. */
    const val CROWDED_MAP_HEIGHT_PX = 150

    fun of(
        item: ActivityRenderItem,
        geometry: RouteGeometry?,
        headingDeg: Float?,
        /** The owner's catalog name, said under a map that draws no route. */
        sourceLabel: String? = null,
        /** Three residents share the home: the minimap keeps a shorter map. */
        crowded: Boolean = false,
        clock: (Long) -> String = ::clockOf,
        geometryRevision: Long? = null,
        /**
         * The new launcher's ambient presentation is in force. Without it (the classic launcher, an
         * older phone) live geometry never selects these islands: the panel and chip stay.
         */
        launcherAmbient: Boolean = true,
    ): GuidanceIsland? {
        if (item.presentation == ActivityPresentation.HIDDEN || !launcherAmbient) return null
        val size = item.size.takeIf {
            com.anezium.rokidbus.shared.AmbientPreferencesContract.hasSizeChoice(item.activity.ownerPluginId)
        }
        if (size == null && geometry == null) return null
        val form = when (size) {
            AmbientSize.TEXT -> IslandForm.TEXT
            AmbientSize.MINIMAP -> IslandForm.MINIMAP
            AmbientSize.PANEL -> IslandForm.PANEL
            null -> if (item.presentation == ActivityPresentation.PANEL) IslandForm.MINIMAP else IslandForm.TEXT
        }
        val content = item.activity.content
        val degraded = RoutePresentation.degraded(null, geometry)
        val statusInFoot = degraded && form != IslandForm.TEXT && content.primary == RoutePresentation.GPS_LOST
        val map = geometry?.takeIf { form != IslandForm.TEXT }?.let {
            MapInput(
                geometry = it,
                form = if (form == IslandForm.PANEL) MapForm.PANEL else MapForm.MINIMAP,
                view = MapView.FOLLOW,
                headingDeg = RoutePresentation.heading(MapView.FOLLOW, degraded, headingDeg),
                degraded = degraded,
                renderContext = geometryRevision?.takeUnless { item.stale }?.let { revision ->
                    RenderedMapContext(RenderedMapContract.AMBIENT, item.activity.surfaceId, revision, seq = item.activity.seq)
                },
            )
        }
        val minimap = form == IslandForm.MINIMAP
        val noRoute = geometry != null && geometry.paths.none { it.role == RoutePathRole.ROUTE }
        val arrival = content.eta?.let { "$it $ARRIVAL" }
        val left = when {
            item.stale -> item.knownAtWallMs?.let { "${SessionScreens.OFFLINE_HINT} ${clock(it)}" } ?: SessionScreens.OFFLINE
            degraded -> geometry?.position?.fixAgeS?.let { "${RoutePresentation.GPS_LOST} · last fix $it s" } ?: RoutePresentation.GPS_LOST
            minimap || noRoute -> arrival
            else -> null
        }
        // On a minimap the remaining measure sits in the foot, not beside the figure (r17 1.2).
        val footMeasure = content.measure.takeIf { minimap && !noRoute && !statusInFoot }
        val right = if (noRoute) sourceLabel else footMeasure
        val foot = if (left == null && right == null) {
            null
        } else {
            IslandFoot(left = left.orEmpty(), right = right, strong = content.eta?.takeIf { left == arrival })
        }
        return GuidanceIsland(
            form,
            IslandContent.Guidance(
                glyph = RoutePresentation.glyph(content.glyph, degraded),
                figure = if (statusInFoot) content.measure ?: "Navigation" else content.primary,
                measure = content.measure.takeUnless { statusInFoot || (foot != null && footMeasure != null) },
                street = content.secondary,
                // A figure the phone no longer confirms is drawn as approximate, muted.
                approximate = degraded || item.stale,
                time = content.eta,
                then = content.detail.firstOrNull()?.takeUnless {
                    statusInFoot && (it == "GPS signal lost" || it == RoutePresentation.GPS_LOST)
                },
                map = map,
                foot = foot,
                mapHeightPx = CROWDED_MAP_HEIGHT_PX.takeIf { crowded && minimap && map != null },
            ),
        )
    }

    /**
     * What a non-guidance island draws for [item]: its content as received, or, while the phone
     * link is down, the same content saying it is last known (`offline · last known HH:MM` on its
     * second line, the street moved into its details) with no action offered.
     */
    fun lastKnown(item: ActivityRenderItem, clock: (Long) -> String = ::clockOf): ActivitySurfaceContent {
        val content = item.activity.content
        if (!item.stale) return content
        val line = item.knownAtWallMs?.let { "${SessionScreens.OFFLINE_HINT} ${clock(it)}" } ?: SessionScreens.OFFLINE
        return content.copy(
            secondary = line,
            detail = (listOfNotNull(content.secondary) + content.detail).take(2),
            actions = emptyList(),
        )
    }

    private fun clockOf(wallMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(wallMs))
}
