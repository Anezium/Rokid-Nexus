package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/**
 * NavigationNotification: title "80 m • Turn right and go", BigTextStyle
 * description followed by "trip distance • duration • ETA [• speed]".
 * The optional distance after the description belongs to the following leg.
 * Paused/calculating notifications have no distance title. Zero distance
 * alone is not arrival; OsmAnd ends the navigation service when finished.
 */
internal object OsmAndParser {
    fun parse(raw: NavNotification): NavGuidance? {
        if (NavSource.of(raw.packageName) != NavSource.OSMAND) return null
        if (!raw.ongoing || raw.category != "navigation" || raw.channelId != "osmand_background_service") return null
        val notification = raw.withPlainSpaces()
        val title = notification.title?.trim() ?: return null
        val distance = title.substringBefore(SEPARATOR).trim().takeIf(NavText::isDistance) ?: return null
        val maneuver = title.substringAfter(SEPARATOR, "").trim()
        val lines = notification.bigText?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val summary = lines.lastOrNull()?.split(SEPARATOR)?.map(String::trim).orEmpty()
        if (summary.size !in 3..4 || !NavText.isDistance(summary[0])) return null
        // Only the third summary field is an arrival clock, never the duration.
        val eta = NavText.clock(summary[2])
        val description = lines.firstOrNull()?.takeIf { lines.size > 1 && !it.contains(SEPARATOR) }
        val withoutDistance = description?.replace(TRAILING_DISTANCE, "")?.trim()
        // After a locale switch, the cached description can still use the old
        // maneuver language. Only a matching prefix identifies where the street starts.
        val street = withoutDistance?.takeIf { maneuver.isNotEmpty() && it.startsWith("$maneuver ") }
            ?.removePrefix(maneuver)?.trim()?.takeIf(String::isNotEmpty)
        val glyph = NavText.maneuverGlyph(maneuver).takeUnless { it == "arrive" } ?: NavText.ROUTE_GLYPH
        return NavGuidance(
            source = NavSource.OSMAND,
            glyph = glyph,
            primary = NavText.fit(distance, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = (street ?: maneuver.takeIf(String::isNotEmpty))
                ?.let { NavText.fit(it, ActivitySurfaceContract.MAX_SECONDARY_CHARS) },
            eta = eta,
            detail = if (street != null && maneuver.isNotEmpty()) {
                listOf(NavText.fit(maneuver, ActivitySurfaceContract.MAX_DETAIL_CHARS))
            } else emptyList(),
            stepKey = "${NavSource.OSMAND.name}|$maneuver|${street.orEmpty()}",
            imminent = glyph !in listOf("straight", NavText.ROUTE_GLYPH) &&
                NavText.metres(distance)?.let { it <= 40.0 } == true,
            instruction = listOfNotNull(title, street).joinToString(" · "),
        )
    }

    private const val SEPARATOR = " • "
    private val TRAILING_DISTANCE = Regex("""\s+\d+(?:[.,]\d+)?\s?(?:m|km|mi|ft|yd)$""")
}
