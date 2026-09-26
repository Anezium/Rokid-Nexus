package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/**
 * Google Maps' guidance notification, in its two shapes.
 *
 * Turn-by-turn (walking, driving; category `navigation`, ongoing,
 * `ProgressStyle` on Android 16):
 *
 *     title     "80 m · Prendre à droite sur Rue de Rivoli"
 *     subText   "Arrivée à 22:50"
 *     shortCriticalText "80 m"
 *     progress  metres travelled of progressMax
 *
 * The maneuver itself is only a bitmap, so the arrow comes from the
 * instruction's words. Progress is left out: it is the share of the whole
 * route walked, which sits near zero for most of a walk and drew only the
 * bar's empty rail, a faint line under the guidance on the glasses.
 *
 * Transit guidance ("Démarrer" on a public-transport route) fills the same
 * notification differently, and only a few seconds after it first posts it
 * empty:
 *
 *     title     "Marchez 3 min (250 m)"
 *     text      "Châtelet · Départ à 17:36"
 *     subText   "Arrivée à 18:51"
 *     shortCriticalText "3 min"
 *
 * Its text line is what tells the two apart: turn-by-turn leaves it empty.
 * Anything that is not a navigation notification with guidance in it yields
 * null: Navigation shows nothing rather than a wrong street.
 */
internal object GoogleMapsParser {
    private const val SEPARATOR = " · "
    private const val IMMINENT_METRES = 40.0

    fun parse(notification: NavNotification, labels: NavLabels = NavLabels()): NavGuidance? {
        if (notification.packageName != NavSource.GOOGLE_MAPS.packageName) return null
        if (notification.category != CATEGORY_NAVIGATION || !notification.ongoing) return null
        val title = notification.title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!notification.text.isNullOrBlank()) return transit(notification, title, labels)

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
            stepKey = "${NavSource.GOOGLE_MAPS.name}|$glyph|${NavText.fold(instruction)}",
            imminent = !arrived && metres != null && metres <= IMMINENT_METRES &&
                glyph != "straight" && glyph != NavText.ROUTE_GLYPH,
            arrived = arrived,
            instruction = instruction,
        )
    }

    /**
     * One transit step. Only the walking leg has been seen on a device; the
     * other legs are read from the same fields with the words transit apps use
     * ("3 arrêts", "bus 38", "RER D"), and a step none of them matches still
     * shows Maps' own words under the neutral route mark.
     */
    private fun transit(notification: NavNotification, title: String, labels: NavLabels): NavGuidance? {
        val parts = notification.text.orEmpty().split(SEPARATOR).map(String::trim).filter(String::isNotEmpty)
        val place = parts.firstOrNull()
        val folded = NavText.fold(title)
        val context = NavText.fold(listOf(title, notification.text.orEmpty()).joinToString(" "))
        val stops = TRANSIT_STOPS.find(title)
        val nextStop = NEXT_STOP.any(folded::contains)
        val arrived = TRANSIT_ARRIVED.any(folded::contains)
        val glyph = when {
            arrived -> "arrive"
            TRANSIT_WALK.any(folded::startsWith) -> "walk"
            else -> transitVehicle(context) ?: NavText.ROUTE_GLYPH
        }
        val short = notification.shortCriticalText?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= ActivitySurfaceContract.MAX_PRIMARY_CHARS }
        val primary = when {
            arrived -> labels.arrived
            stops != null -> stops.value
            short != null -> short
            else -> MINUTES.find(title)?.value ?: return null
        }
        val walkDistance = PARENTHESISED_DISTANCE.find(title)?.groupValues?.get(1)?.takeIf(NavText::isDistance)
        val detail = (parts.drop(1) + listOfNotNull(walkDistance))
            .ifEmpty { listOf(title).takeIf { place != null }.orEmpty() }
            .take(ActivitySurfaceContract.MAX_DETAIL_LINES)
            .map { NavText.fit(it, ActivitySurfaceContract.MAX_DETAIL_CHARS) }
        val badge = transitLine(context)?.takeIf { it.length <= ActivitySurfaceContract.MAX_BADGE_CHARS }
        val remaining = stops?.groupValues?.get(1)?.toIntOrNull()
        return NavGuidance(
            source = NavSource.GOOGLE_MAPS,
            glyph = glyph,
            primary = NavText.fit(primary, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = NavText.fit(place ?: title, ActivitySurfaceContract.MAX_SECONDARY_CHARS),
            eta = NavText.clock(notification.subText),
            detail = detail,
            badge = badge.takeIf { glyph != "walk" },
            // Minutes and metres tick down within one step; the step is the
            // kind of leg and where it leads.
            stepKey = "${NavSource.GOOGLE_MAPS.name}|transit|$glyph|${NavText.fold(place ?: title.replace(DIGITS, ""))}",
            imminent = nextStop || remaining == 1,
            arrived = arrived,
            instruction = listOfNotNull(title, notification.text).joinToString(" · "),
        )
    }

    private fun transitVehicle(context: String): String? = when {
        Regex("""\b(rer|train|transilien|ter)\b""").containsMatchIn(context) -> "train"
        Regex("""\b(metro|m\d{1,2})\b""").containsMatchIn(context) -> "metro"
        Regex("""\btram(way)?\b""").containsMatchIn(context) -> "tram"
        Regex("""\b(bus|autobus|noctilien)\b""").containsMatchIn(context) -> "bus"
        else -> null
    }

    /** "bus 38", "RER D", "ligne 4", "tram T3a" -> the line's own name. */
    private fun transitLine(context: String): String? =
        LINE.find(context)?.groupValues?.get(2)?.uppercase()

    private const val CATEGORY_NAVIGATION = "navigation"
    private val TRANSIT_STOPS = Regex("""(\d+)\s+(arr[êe]ts?|stops?)\b""", RegexOption.IGNORE_CASE)
    private val NEXT_STOP = listOf("prochain arret", "next stop")
    private val TRANSIT_ARRIVED = listOf("vous etes arrive", "you have arrived", "you've arrived")
    private val TRANSIT_WALK = listOf("marchez", "marcher", "walk")
    private val MINUTES = Regex("""\d+\s?min""")
    private val PARENTHESISED_DISTANCE = Regex("""\(([^)]+)\)""")
    private val DIGITS = Regex("""\d+""")
    private val LINE = Regex("""\b(bus|rer|ligne|line|metro|tram)\s+([a-z]?\d{1,4}[a-z]?|[a-z])\b""")
}
