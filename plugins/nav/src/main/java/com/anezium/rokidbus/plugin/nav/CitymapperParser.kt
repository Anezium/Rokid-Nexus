package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.ActivitySurfaceContract

/**
 * Citymapper's GO notification (channel `trip-progress`). It carries no
 * standard text at all: everything is in its own layout, read here by view
 * name. One step at a time, as captured in French:
 *
 *     walk       title "Marcher vers l'arrêt de bus"   subtitle "<stop>"   prediction "(à 4 min)"
 *     wait       title "Attendre 38 ou 85"         subtitle "0, 11, 11 min"
 *     departure  title "17:34 ZECO Melun (à l'heure)"  subtitle "voie 2"
 *     ride       title "3 arrêts jusqu'à"              subtitle "<stop to get off at>"
 *     every step eta "Arrivée : 18:23 (83 min)"
 *
 * No line icon is exposed, so a ride's vehicle and line number come from the
 * wait or departure step before it; the parser keeps that much of the trip.
 * The stop count only ever comes from Citymapper, so a ride's track is the
 * count first announced for that stop and the count now remaining.
 */
internal class CitymapperParser {
    private var legGlyph: String? = null
    private var legBadge: String? = null
    private var rideDestination: String? = null
    private var rideStops = 0

    fun parse(notification: NavNotification, labels: NavLabels = NavLabels()): NavGuidance? {
        if (notification.packageName != NavSource.CITYMAPPER.packageName || !notification.ongoing) return null
        if (notification.channelId != null && notification.channelId != CHANNEL_TRIP) return null
        val texts = notification.viewTexts
        val title = texts[VIEW_TITLE]?.clean()?.takeIf(String::isNotEmpty) ?: return null
        val subtitle = texts[VIEW_SUBTITLE]?.clean()?.takeIf(String::isNotEmpty)
        val prediction = texts[VIEW_PREDICTION]?.clean()?.let(::minutes)
        val etaLine = texts[VIEW_ETA]?.clean()
        val eta = NavText.clock(etaLine)
        val remaining = etaLine?.let { ETA_REMAINING.find(it)?.groupValues?.get(1) }
        val folded = NavText.fold(title)

        RIDE.find(folded)?.let { match ->
            return ride(match.groupValues[1].toInt(), title, subtitle, eta, imminent = false)
        }
        if (NEXT_STOP.any(folded::contains)) return ride(1, title, subtitle, eta, imminent = true)
        if (ARRIVED.any(folded::contains)) return arrived(title, eta, labels)
        WAIT.find(title)?.let { match -> return wait(match.groupValues[1], title, subtitle, eta, labels) }
        DEPARTURE.find(title)?.let { match ->
            if (subtitle != null && PLATFORM.containsMatchIn(NavText.fold(subtitle))) {
                return departure(match, subtitle, eta)
            }
        }
        if (WALK.any(folded::startsWith)) return walk(title, subtitle, prediction, eta)
        // A step Citymapper words in a way this parser does not know: its own
        // words are still right, only the arrow is unknown.
        val primary = prediction ?: remaining?.let { "$it min" } ?: return null
        return guidance(
            glyph = NavText.ROUTE_GLYPH,
            primary = primary,
            secondary = title,
            eta = eta,
            stepKey = "other|$folded",
            instruction = title,
        )
    }

    fun reset() {
        legGlyph = null
        legBadge = null
        rideDestination = null
        rideStops = 0
    }

    private fun walk(title: String, subtitle: String?, prediction: String?, eta: String?): NavGuidance? {
        rideDestination = null
        // Without a walk time, Citymapper's own verb leads: the trip's
        // remaining minutes would read as the length of this walk.
        val primary = prediction ?: title.substringBefore(' ').takeIf(String::isNotEmpty) ?: return null
        return guidance(
            glyph = "walk",
            primary = primary,
            secondary = subtitle ?: title,
            detail = if (subtitle != null) listOf(title) else emptyList(),
            eta = eta,
            stepKey = "walk|${NavText.fold(subtitle ?: title)}",
            instruction = title,
        )
    }

    private fun wait(lines: String, title: String, subtitle: String?, eta: String?, labels: NavLabels): NavGuidance {
        rideDestination = null
        val line = lines.split(LINE_SEPARATOR).first().trim()
        legGlyph = vehicleFor(line)
        legBadge = line.takeIf { it.length <= ActivitySurfaceContract.MAX_BADGE_CHARS }
        val next = subtitle?.let { NEXT_DEPARTURE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val primary = when {
            next == null -> title
            next == 0 -> labels.now
            else -> "$next min"
        }
        return guidance(
            glyph = legGlyph ?: NavText.ROUTE_GLYPH,
            badge = legBadge,
            primary = primary,
            secondary = title,
            detail = listOfNotNull(subtitle),
            eta = eta,
            stepKey = "wait|${NavText.fold(title)}",
            imminent = next != null && next <= 1,
            instruction = title,
        )
    }

    private fun departure(match: MatchResult, platform: String, eta: String?): NavGuidance {
        rideDestination = null
        val (time, _, direction, status) = match.destructured
        legGlyph = "train"
        legBadge = null
        val state = status.trim().removePrefix("(").removeSuffix(")").trim()
        return guidance(
            glyph = "train",
            primary = time,
            secondary = direction,
            detail = listOf(listOf(platform, state).filter(String::isNotEmpty).joinToString(" · ")),
            eta = eta,
            stepKey = "departure|$time|${NavText.fold(direction)}",
            instruction = match.value.trim(),
        )
    }

    private fun ride(stops: Int, title: String, subtitle: String?, eta: String?, imminent: Boolean): NavGuidance {
        val destination = subtitle ?: title
        if (destination != rideDestination) {
            rideDestination = destination
            rideStops = stops
        } else {
            rideStops = maxOf(rideStops, stops)
        }
        val track = if (rideStops in 1 until ActivitySurfaceContract.MAX_TRACK_COUNT) {
            NavTrack(
                count = rideStops + 1,
                at = rideStops - stops,
                target = rideStops,
                label = NavText.fit(destination, ActivitySurfaceContract.MAX_TRACK_LABEL_CHARS),
            )
        } else {
            null
        }
        return guidance(
            glyph = legGlyph ?: NavText.ROUTE_GLYPH,
            badge = legBadge,
            primary = RIDE_PRIMARY.find(title)?.value?.trim() ?: title,
            secondary = destination,
            eta = eta,
            track = track,
            stepKey = "ride|${NavText.fold(destination)}",
            imminent = imminent || stops <= 1,
            instruction = listOfNotNull(title, subtitle).joinToString(" "),
        )
    }

    private fun arrived(title: String, eta: String?, labels: NavLabels): NavGuidance {
        reset()
        return guidance(
            glyph = "arrive",
            primary = labels.arrived,
            secondary = title,
            eta = eta,
            stepKey = "arrived",
            arrived = true,
            instruction = title,
        )
    }

    private fun guidance(
        glyph: String,
        primary: String,
        secondary: String?,
        eta: String?,
        stepKey: String,
        detail: List<String> = emptyList(),
        badge: String? = null,
        track: NavTrack? = null,
        imminent: Boolean = false,
        arrived: Boolean = false,
        instruction: String? = null,
    ) = NavGuidance(
        source = NavSource.CITYMAPPER,
        glyph = glyph,
        primary = NavText.fit(primary, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
        secondary = secondary?.let { NavText.fit(it, ActivitySurfaceContract.MAX_SECONDARY_CHARS) },
        eta = eta,
        detail = detail.map { NavText.fit(it, ActivitySurfaceContract.MAX_DETAIL_CHARS) },
        badge = badge,
        track = track,
        stepKey = "${NavSource.CITYMAPPER.name}|$stepKey",
        imminent = imminent,
        arrived = arrived,
        instruction = instruction,
    )

    private fun String.clean(): String = replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** "(à 4 min)" or "in 4 min" -> "4 min". */
    private fun minutes(value: String): String? = MINUTES.find(value)?.value?.replace(Regex("\\s+"), " ")

    private fun vehicleFor(line: String): String {
        val plain = NavText.fold(line)
        return when {
            plain.startsWith("rer") || plain.startsWith("train") || plain.startsWith("transilien") -> "train"
            Regex("""^m(etro)?\s?\d""").containsMatchIn(plain) -> "metro"
            Regex("""^t(ram)?\s?\d""").containsMatchIn(plain) -> "tram"
            Regex("""^\d+[a-z]?$""").matches(plain) -> "bus"
            else -> NavText.ROUTE_GLYPH
        }
    }

    private companion object {
        const val CHANNEL_TRIP = "trip-progress"
        const val VIEW_TITLE = "notification_title"
        const val VIEW_SUBTITLE = "notification_subtitle"
        const val VIEW_PREDICTION = "notification_prediction"
        const val VIEW_ETA = "notification_eta"

        val RIDE = Regex("""^(\d+)\s+(arrets?|stops?)\b""")
        val RIDE_PRIMARY = Regex("""^\d+\s+\S+""")
        val NEXT_STOP = listOf("prochain arret", "next stop", "descendre", "get off")
        val ARRIVED = listOf("vous etes arrive", "you have arrived", "you've arrived")
        val WAIT = Regex("""^(?:Attendre|Attendez|Wait for|Board)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val LINE_SEPARATOR = Regex("""\s+(?:ou|or)\s+|,\s*""")
        val NEXT_DEPARTURE = Regex("""^\s*(\d+)""")
        val DEPARTURE = Regex("""^(\d{1,2}:\d{2})\s+(\S+)\s+(.+?)\s*(\([^)]*\))?\s*$""")
        val PLATFORM = Regex("""\b(voie|quai|platform|track)\b""")
        val WALK = listOf("marcher", "marchez", "rejoindre", "walk", "head to")
        val MINUTES = Regex("""\d+\s?min""")
        val ETA_REMAINING = Regex("""\((\d+)\s?min\)""")
    }
}
