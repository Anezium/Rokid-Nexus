package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.page.IslandContent
import com.anezium.rokidbus.glasses.page.IslandForm
import com.anezium.rokidbus.glasses.page.MapForm
import com.anezium.rokidbus.glasses.page.MapInput
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.glasses.session.RoutePresentation
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.RouteGeometry

/** A guidance island as decided, before any view: its ambient form and what it says. */
internal data class GuidanceIsland(val form: IslandForm, val content: IslandContent.Guidance)

/**
 * Which islands are drawn as guidance islands, and how (plan 028a §8.1). Pure.
 *
 * An owner is guidance when it chose an ambient size or sends live route geometry; every other
 * owner keeps the existing island. An explicit size is drawn as is (the activity tier already
 * capped it at text over an app); an automatic one is its minimap while expanded and its text
 * form while folded, so the ten-second collapse still applies to it. The map is heading-up
 * (Follow); a position that is not live draws the lost glyph, "~" figures and a degraded map.
 */
internal object GuidanceIslands {
    fun of(
        item: ActivityRenderItem,
        geometry: RouteGeometry?,
        headingDeg: Float?,
    ): GuidanceIsland? {
        val size = item.size
        if (size == null && geometry == null) return null
        val form = when (size) {
            AmbientSize.TEXT -> IslandForm.TEXT
            AmbientSize.MINIMAP -> IslandForm.MINIMAP
            AmbientSize.PANEL -> IslandForm.PANEL
            null -> if (item.presentation == ActivityPresentation.PANEL) IslandForm.MINIMAP else IslandForm.TEXT
        }
        val content = item.activity.content
        val degraded = RoutePresentation.degraded(null, geometry)
        val map = geometry?.takeIf { form != IslandForm.TEXT }?.let {
            MapInput(
                geometry = it,
                form = if (form == IslandForm.PANEL) MapForm.PANEL else MapForm.MINIMAP,
                view = MapView.FOLLOW,
                headingDeg = RoutePresentation.heading(MapView.FOLLOW, degraded, headingDeg),
                degraded = degraded,
            )
        }
        return GuidanceIsland(
            form,
            IslandContent.Guidance(
                glyph = RoutePresentation.glyph(content.glyph, degraded),
                figure = content.primary,
                measure = content.measure,
                street = content.secondary,
                approximate = degraded,
                time = content.eta,
                then = content.detail.firstOrNull(),
                map = map,
            ),
        )
    }
}
