package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/** Background car/bike guidance: distance title, next location text, bitmap maneuver. */
internal object MapsMeParser {
    fun parse(raw: NavNotification): NavGuidance? {
        if (NavSource.of(raw.packageName) != NavSource.MAPS_ME) return null
        if (!raw.ongoing || raw.channelId != "ActiveNavigationChannel" || raw.category != "navigation") return null
        val notification = raw.withPlainSpaces()
        val distance = notification.title?.trim()?.takeIf(NavText::isDistance) ?: return null
        val street = notification.text?.trim()?.takeIf(String::isNotEmpty)
        return NavGuidance(
            source = NavSource.MAPS_ME,
            glyph = NavText.ROUTE_GLYPH,
            primary = NavText.fit(distance, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = street?.let { NavText.fit(it, ActivitySurfaceContract.MAX_SECONDARY_CHARS) },
            stepKey = "${NavSource.MAPS_ME.name}|${street.orEmpty()}",
            instruction = listOfNotNull(distance, street).joinToString(" · "),
        )
    }
}
