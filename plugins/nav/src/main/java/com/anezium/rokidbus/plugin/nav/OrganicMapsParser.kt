package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/**
 * NavigationService posts distToTurn as title and nextStreet as text on
 * NAVIGATION. The maneuver is only a bitmap, even for walking. No ETA or
 * arrival text is posted: the service removes its notification at arrival.
 */
internal object OrganicMapsParser {
    fun parse(raw: NavNotification): NavGuidance? {
        if (NavSource.of(raw.packageName) != NavSource.ORGANIC_MAPS) return null
        if (!raw.ongoing || raw.channelId != "NAVIGATION" || raw.category != "navigation") return null
        val notification = raw.withPlainSpaces()
        val distance = notification.title?.trim()?.takeIf(NavText::isDistance) ?: return null
        val street = notification.text?.trim()?.takeIf(String::isNotEmpty)
        return NavGuidance(
            source = NavSource.ORGANIC_MAPS,
            glyph = NavText.ROUTE_GLYPH,
            primary = NavText.fit(distance, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = street?.let { NavText.fit(it, ActivitySurfaceContract.MAX_SECONDARY_CHARS) },
            stepKey = "${NavSource.ORGANIC_MAPS.name}|${street.orEmpty()}",
            instruction = listOfNotNull(distance, street).joinToString(" · "),
        )
    }
}
