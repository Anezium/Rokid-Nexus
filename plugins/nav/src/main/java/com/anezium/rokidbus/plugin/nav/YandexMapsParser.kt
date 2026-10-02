package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/** Driving captures: distance title, street text, arrival clock in the expanded RemoteViews. */
internal object YandexMapsParser {
    fun parse(raw: NavNotification): NavGuidance? {
        if (NavSource.of(raw.packageName) != NavSource.YANDEX_MAPS) return null
        if (!raw.ongoing || raw.category != "navigation" ||
            raw.channelId !in listOf("foreground_notification", "bg_notification")) return null
        val notification = raw.withPlainSpaces()
        val distance = notification.title?.trim()?.takeIf {
            NavText.isDistance(it) || RUSSIAN_DISTANCE.matches(it)
        } ?: return null
        val street = notification.text?.trim()?.takeIf(String::isNotEmpty)
        // titleView repeats the distance. The maneuver image and traffic-light
        // countdown cannot identify a turn, so neither generates an urgent arrow.
        return NavGuidance(
            source = NavSource.YANDEX_MAPS,
            glyph = NavText.ROUTE_GLYPH,
            primary = NavText.fit(distance, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = street?.let { NavText.fit(it, ActivitySurfaceContract.MAX_SECONDARY_CHARS) },
            eta = NavText.clock(notification.viewTexts["timeOfArrivalView"]),
            stepKey = "${NavSource.YANDEX_MAPS.name}|${street.orEmpty()}",
            instruction = listOfNotNull(distance, street).joinToString(" · "),
        )
    }

    private val RUSSIAN_DISTANCE = Regex("""^\d+(?:[.,]\d+)?\s?(?:м|км)$""")
}
