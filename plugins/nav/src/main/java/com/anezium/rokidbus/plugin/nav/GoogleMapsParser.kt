package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/**
 * Google Maps' turn-by-turn notification, as posted while navigating
 * (category `navigation`, ongoing, `ProgressStyle` on Android 16):
 *
 *     title     "80 m · Prendre à droite sur Rue de Rivoli"
 *     subText   "Arrivée à 22:50"
 *     shortCriticalText "80 m"
 *     progress  metres travelled of progressMax
 *
 * The maneuver itself is only a bitmap, so the arrow comes from the
 * instruction's words. Anything that is not a navigation notification with an
 * instruction yields null: Navigation shows nothing rather than a wrong street.
 */
internal object GoogleMapsParser {
    private const val SEPARATOR = " · "
    private const val IMMINENT_METRES = 40.0

    fun parse(notification: NavNotification, labels: NavLabels = NavLabels()): NavGuidance? {
        if (notification.packageName != NavSource.GOOGLE_MAPS.packageName) return null
        if (notification.category != CATEGORY_NAVIGATION || !notification.ongoing) return null
        val title = notification.title?.trim()?.takeIf(String::isNotEmpty) ?: return null

        val leading = title.substringBefore(SEPARATOR, missingDelimiterValue = "").trim()
        val distance = leading.takeIf(NavText::isDistance)
            ?: notification.shortCriticalText?.trim()?.takeIf(NavText::isDistance)
        val instruction = (if (leading.isNotEmpty()) title.substringAfter(SEPARATOR) else title)
            .trim()
            .takeIf(String::isNotEmpty)
            ?: return null

        val glyph = NavText.maneuverGlyph(instruction)
        val arrived = glyph == "arrive" && distance == null
        // Before the first maneuver ("Aller vers <street>") Maps gives no
        // distance. Its own verb leads then; keeping the previous step on
        // screen would show a turn that is no longer the instruction.
        val phrase = NavText.maneuverPhrase(instruction)
        val primary = when {
            distance != null -> distance
            arrived -> labels.arrived
            glyph != NavText.ROUTE_GLYPH && phrase.length <= ActivitySurfaceContract.MAX_PRIMARY_CHARS -> phrase
            else -> return null
        }
        val street = notification.nowBarSecondary?.trim()?.takeIf { it.isNotEmpty() && it != distance }
            ?: NavText.street(instruction)
        val secondary = NavText.fit(street ?: instruction, ActivitySurfaceContract.MAX_SECONDARY_CHARS)
        // The street is already the second line; the detail keeps the words of
        // the maneuver the arrow stands for.
        val detail = if (street != null && phrase != primary) {
            listOf(NavText.fit(phrase, ActivitySurfaceContract.MAX_DETAIL_CHARS))
        } else {
            emptyList()
        }
        val metres = NavText.metres(distance)
        return NavGuidance(
            source = NavSource.GOOGLE_MAPS,
            glyph = glyph,
            primary = NavText.fit(primary, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = secondary,
            eta = NavText.clock(notification.subText),
            detail = detail,
            progressPercent = notification.progressPercent(),
            stepKey = "${NavSource.GOOGLE_MAPS.name}|$glyph|${NavText.fold(instruction)}",
            imminent = !arrived && metres != null && metres <= IMMINENT_METRES &&
                glyph != "straight" && glyph != NavText.ROUTE_GLYPH,
            arrived = arrived,
            instruction = instruction,
        )
    }

    private fun NavNotification.progressPercent(): Int? =
        if (progressMax > 0 && progress in 0..progressMax) progress * 100 / progressMax else null

    private const val CATEGORY_NAVIGATION = "navigation"
}
