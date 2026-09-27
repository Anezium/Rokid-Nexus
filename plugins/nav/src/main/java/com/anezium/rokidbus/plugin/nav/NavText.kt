package com.anezium.rokidbus.plugin.nav

import java.text.Normalizer
import java.util.Locale

/**
 * Reading guidance text. Navigation apps localise their instructions, so the
 * maneuver comes from phrases in the languages Navigation knows (English,
 * French and Korean); an instruction it cannot place gets the neutral route mark rather
 * than a guessed arrow.
 */
internal object NavText {
    /** The plugin's own neutral mark (declared in its glyph array). */
    const val ROUTE_GLYPH = "route"

    private val DISTANCE = Regex("""^(\d+(?:[.,]\d+)?)\s?(m|km|mi|ft|yd)$""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex(
        """(?:(오전|오후)\s?)?\b(\d{1,2})[:h](\d{2})(?:\s?([ap])\.?\s?m\.?)?""",
        RegexOption.IGNORE_CASE,
    )

    /** Ordered: the most specific phrase wins, so "slight right" never reads as "right". */
    private val MANEUVERS = listOf(
        "u-turn" to listOf("demi-tour", "u-turn", "make a u turn", "유턴"),
        "roundabout" to listOf("rond-point", "giratoire", "roundabout", "traffic circle", "회전교차로", "로터리"),
        "turn-slight-right" to listOf(
            "legerement a droite", "legerement sur la droite", "restez a droite", "serrez a droite",
            "slight right", "keep right", "bear right", "약간 오른쪽", "오른쪽 방향 유지", "오른쪽 차선 유지",
            "slightly right", "legerement vers la droite", "약한 우회전", "오른쪽으로 가십시오",
        ),
        "turn-slight-left" to listOf(
            "legerement a gauche", "legerement sur la gauche", "restez a gauche", "serrez a gauche",
            "slight left", "keep left", "bear left", "약간 왼쪽", "왼쪽 방향 유지", "왼쪽 차선 유지",
            "slightly left", "legerement vers la gauche", "약한 좌회전", "왼쪽을 유지",
        ),
        "turn-sharp-right" to listOf("fortement a droite", "franchement a droite", "sharp right", "급우회전", "sharply right", "virage serre a droite", "급한 우회전"),
        "turn-sharp-left" to listOf("fortement a gauche", "franchement a gauche", "sharp left", "급좌회전", "sharply left", "virage serre a gauche", "급한 좌회전"),
        "turn-right" to listOf("a droite", "turn right", "right onto", "right on ", "right at ", "우회전", "오른쪽으로"),
        "turn-left" to listOf("a gauche", "turn left", "left onto", "left on ", "left at ", "좌회전", "왼쪽으로"),
        // Before the arrival phrases: "Continue to your destination" is a
        // straight step, not the arrival.
        "straight" to listOf(
            "continuez", "continuer", "tout droit", "poursuivez", "dirigez-vous", "aller vers", "allez vers",
            "head ", "continue",
            "straight", "직진", "계속 진행",
        ),
        "arrive" to listOf(
            "vous etes arrive", "votre destination", "destination", "arrive", "you have arrived",
            "도착했습니다", "목적지",
        ),
    )

    /** Street connectors, most specific first. */
    private val STREET_CONNECTORS = listOf(
        " en direction de ", " sur ", " dans ", " vers ", " onto ", " toward ", " towards ", " on ",
    )

    fun isDistance(value: String?): Boolean = value != null && DISTANCE.matches(value.trim())

    /** Metres for a distance the apps print ("80 m", "1,2 km", "500 ft"), or null. */
    fun metres(value: String?): Double? {
        val match = value?.trim()?.let(DISTANCE::matchEntire) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "m" -> number
            "km" -> number * 1000.0
            "mi" -> number * 1609.344
            "ft" -> number * 0.3048
            "yd" -> number * 0.9144
            else -> null
        }
    }

    /**
     * The first clock time in [value] ("Arrivée à 22:50" -> "22:50", "10:05 pm" -> "10:05 PM").
     * Korean twelve-hour times keep their leading half-day: "오후 6:51 도착" -> "오후 6:51".
     */
    fun clock(value: String?): String? {
        val match = value?.let(CLOCK::find) ?: return null
        val (halfDay, hour, minute, meridiem) = match.destructured
        val time = "${hour.toInt()}:$minute"
        return when {
            halfDay.isNotEmpty() -> "$halfDay $time"
            meridiem.isNotEmpty() -> "$time ${meridiem.uppercase(Locale.ROOT)}M"
            else -> time
        }
    }

    fun maneuverGlyph(instruction: String): String {
        val plain = fold(instruction)
        return MANEUVERS.firstOrNull { (_, phrases) -> phrases.any(plain::contains) }?.first ?: ROUTE_GLYPH
    }

    /** The street an instruction names ("… sur Rue de Rivoli"), or null. */
    fun street(instruction: String): String? =
        connectorAt(instruction)?.let { (index, connector) ->
            instruction.substring(index + connector.length).trim().takeIf(String::isNotEmpty)
        }

    /** The instruction without the street it names: "Prendre à droite". */
    fun maneuverPhrase(instruction: String): String =
        connectorAt(instruction)
            ?.let { (index, _) -> instruction.substring(0, index).trim() }
            ?.takeIf(String::isNotEmpty)
            ?: instruction.trim()

    private fun connectorAt(instruction: String): Pair<Int, String>? {
        val lower = instruction.lowercase(Locale.ROOT)
        STREET_CONNECTORS.forEach { connector ->
            val index = lower.indexOf(connector)
            if (index >= 0) return index to connector
        }
        return null
    }

    /** At most [max] characters, cut at a word where one is close, with an ellipsis. */
    fun fit(value: String, max: Int): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length <= max) return trimmed
        val room = max - 1
        val cut = trimmed.lastIndexOf(' ', room).takeIf { it >= room * 2 / 3 } ?: room
        return trimmed.substring(0, cut).trimEnd() + "…"
    }

    /**
     * Apps print "80\u00A0m" with no-break spaces (U+00A0, U+202F, U+2007),
     * which the patterns' \s does not match.
     */
    fun spaces(value: String?): String? = value?.replace(NO_BREAK_SPACES, " ")

    private val NO_BREAK_SPACES = Regex("[\u00A0\u202F\u2007]")

    /**
     * Lower case with accents removed, so "Légèrement à droite" matches
     * "legerement a droite". Recomposed afterwards: NFD splits every Hangul
     * syllable into jamo, which no Korean phrase would match.
     */
    fun fold(value: String): String =
        Normalizer.normalize(
            Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), ""),
            Normalizer.Form.NFC,
        ).replace('’', '\'')
}
