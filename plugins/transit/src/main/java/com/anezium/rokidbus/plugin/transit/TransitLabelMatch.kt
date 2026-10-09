package com.anezium.rokidbus.plugin.transit

import java.text.Normalizer
import java.util.Locale

/**
 * Finds the lines and directions of a board that the wearer named the way a person says them:
 * "ligne 14" or "M14" for the line the feed calls `14`, "vers Orly" for `Aéroport d'Orly`.
 * An exact label always wins over a looser reading, and a looser reading compares whole labels
 * or whole words only, so "Or" never names "Orly" and "Orly" never names "Olympiades".
 */
internal object TransitLabelMatch {
    /** The board labels [selector] names: the exact label, else the same label once a mode word is dropped. */
    fun lines(selector: String, labels: Collection<String>): Set<String> {
        exactLines(selector, labels).takeIf { it.isNotEmpty() }?.let { return it }
        var namedLine = normalize(selector)
        while (true) {
            val prefix = MODE_WORDS.firstOrNull(namedLine::startsWith) ?: break
            if (strippedMode(namedLine) == namedLine) break
            namedLine = namedLine.substring(prefix.length).trimStart()
            val literal = exactLabel(namedLine)
            if (literal.firstOrNull()?.isLetter() == true && DIGIT_CODE.matches(literal)) {
                exactLines(namedLine, labels).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        val bare = withoutMode(selector)
        val tram = modes(selector) == setOf("TRAM")
        fun code(value: String) = if (tram && value.startsWith('t') && DIGIT_CODE.matches(value.drop(1))) value.drop(1) else value
        return labels.filter { code(withoutMode(it)) == code(bare) }.toSet()
    }

    /** Explicit transport words constrain the feed mode; generic line words do not. */
    fun exactLines(selector: String, labels: Collection<String>): Set<String> =
        labels.filter { exactLabel(it) == exactLabel(selector) }.toSet()

    private fun exactLabel(label: String): String = normalize(label).replace(SPACED_M_CODE, "m")

    fun modes(selector: String, labels: Collection<String> = emptyList()): Set<String>? {
        var label = normalize(selector)
        while (true) {
            val word = MODE_WORDS.firstOrNull(label::startsWith) ?: return null
            // M10/M41 are literal tram/bus labels in some feeds; only infer M as metro for an alias.
            if (word == "m" && exactLines(label, labels).isNotEmpty()) return null
            val bare = strippedMode(label)
            if (bare == label) return null
            when (word) {
                "metro", "m" -> return setOf("SUBWAY")
                "rer" -> return RER_MODES
                in FEED_MODE_WORDS -> return setOf(word.uppercase(Locale.ROOT))
            }
            label = label.substring(word.length).trimStart()
        }
    }

    fun candidate(mode: String, line: String): String? = when (val normalized = mode.uppercase(Locale.ROOT)) {
        "SUBWAY" -> "metro $line"
        in RER_MODES -> "rer $line"
        else -> normalized.lowercase(Locale.ROOT).takeIf { it in FEED_MODE_WORDS }?.let { "$it $line" }
    }

    fun modeGroup(mode: String): String = when (val normalized = mode.uppercase(Locale.ROOT)) {
        in RER_MODES -> "RER"
        else -> normalized
    }

    /**
     * The headsigns [selector] names: the exact headsign, else the headsign that is the selector
     * without its leading connector words, else every headsign holding all of those words.
     */
    fun directions(selector: String, headsigns: Collection<String>, preferExact: Boolean = true): Set<String> {
        val wanted = normalize(selector)
        if (preferExact) headsigns.filter { normalize(it) == wanted }.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        val wantedWords = words(selector).dropWhile { it in CONNECTORS }
        if (wantedWords.isEmpty()) return emptySet()
        if (preferExact) headsigns.filter { words(it) == wantedWords }.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        return headsigns.filter { words(it).containsAll(wantedWords) }.toSet()
    }

    /** How many different lines [labels] are, typography aside. */
    fun distinctLines(labels: Collection<String>): Int = labels.map(::normalize).distinct().size

    /** How many different directions [headsigns] are, typography and punctuation aside. */
    fun distinctDirections(headsigns: Collection<String>): Int = headsigns.map(::words).distinct().size

    fun distinctDirectionLabels(headsigns: Collection<String>): List<String> = headsigns.distinctBy(::words)

    private fun normalize(label: String): String =
        unmarked(label).lowercase(Locale.ROOT).replace(APOSTROPHES, "").replace(SPACES, " ").trim()

    private fun words(label: String): List<String> =
        unmarked(label).lowercase(Locale.ROOT).split(NOT_WORD).filter(String::isNotEmpty)

    private fun unmarked(label: String): String =
        Normalizer.normalize(label, Normalizer.Form.NFKD).replace(MARKS, "")

    private fun withoutMode(label: String): String = strippedMode(normalize(label))

    // Casing comes from whoever relayed the speech, so a glued mode word is judged by the shape of
    // what follows: a code with a digit ("m14", "tramt3a"), or after "rer" one or two letters
    // ("rerc"). Words such as "tramway", "linea", "lines", or "metropole" stay whole.
    private tailrec fun strippedMode(label: String): String {
        val word = MODE_WORDS.firstOrNull(label::startsWith) ?: return label
        val rest = label.substring(word.length)
        val bare = when {
            word == "m" -> rest.removePrefix(" ").takeIf { it.firstOrNull()?.isDigit() == true }
            rest.startsWith(' ') -> rest.substring(1).takeIf { DIGIT_CODE.matches(it) || LETTER_CODE.matches(it) || MODE_WORDS.any(it::startsWith) }
            DIGIT_CODE.matches(rest) || (word == "rer" && LETTER_CODE.matches(rest)) -> rest
            else -> null
        }
        return if (bare.isNullOrEmpty()) label else strippedMode(bare)
    }

    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['\u2018\u2019\u02bc]")
    private val SPACES = Regex("[\\s\\p{Z}\\p{Pd}]+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    // At most one letter before the digits (t3a, n01): longer runs are words (tramway1, metropole1).
    private val DIGIT_CODE = Regex("\\p{L}?\\p{N}+[\\p{L}\\p{N}]*")
    private val LETTER_CODE = Regex("\\p{L}{1,2}")
    private val SPACED_M_CODE = Regex("^m (?=\\p{N})")
    private val FEED_MODE_WORDS = setOf("subway", "suburban", "rail", "train", "regional_rail", "regional_fast_rail",
        "bus", "coach", "tram", "ferry", "airplane", "highspeed_rail", "long_distance", "night_rail",
        "funicular", "aerial_lift", "areal_lift", "cable_car", "other")
    private val MODE_WORDS = (listOf("ligne", "line", "metro", "rer", "m") + FEED_MODE_WORDS).sortedByDescending(String::length)
    // Transitous v1's METRO means suburban rail; MOTIS v5 renamed it SUBURBAN.
    private val RER_MODES = setOf("RAIL", "TRAIN", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "SUBURBAN", "METRO")
    private val CONNECTORS = setOf("vers", "direction", "dir", "to", "towards", "toward")
}
