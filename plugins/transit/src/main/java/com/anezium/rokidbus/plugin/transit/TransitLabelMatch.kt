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
        val wanted = normalize(selector)
        labels.filter { normalize(it) == wanted }.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        val bare = withoutMode(wanted)
        return labels.filter { withoutMode(normalize(it)) == bare }.toSet()
    }

    /** The headsigns [selector] names: the exact headsign, else every headsign holding all of its words. */
    fun directions(selector: String, headsigns: Collection<String>): Set<String> {
        val wanted = normalize(selector)
        val wantedWords = words(selector).dropWhile { it in CONNECTORS }
        if (wantedWords.isEmpty()) return emptySet()
        headsigns.filter { normalize(it) == wanted || words(it) == wantedWords }
            .takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        return headsigns.filter { words(it).containsAll(wantedWords) }.toSet()
    }

    /** How many different lines [labels] are, typography aside. */
    fun distinctLines(labels: Collection<String>): Int = labels.map(::normalize).distinct().size

    /** How many different directions [headsigns] are, typography and punctuation aside. */
    fun distinctDirections(headsigns: Collection<String>): Int = headsigns.map(::words).distinct().size

    private fun normalize(label: String): String =
        fold(label).replace(APOSTROPHES, "").replace(SPACES, " ").trim()

    private fun words(label: String): List<String> = fold(label).split(NOT_WORD).filter(String::isNotEmpty)

    private fun fold(label: String): String =
        Normalizer.normalize(label, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)

    // A mode word must be followed by a separator or a digit, so "Tramway" or "Linea" stay whole.
    private tailrec fun withoutMode(label: String): String {
        val stripped = label.replaceFirst(MODE_PREFIX, "")
        return if (stripped == label || stripped.isEmpty()) label else withoutMode(stripped)
    }

    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['\u2018\u2019\u02bc]")
    private val SPACES = Regex("[\\s\\p{Z}\\p{Pd}]+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val MODE_PREFIX = Regex("^(?:(?:ligne|line|metro|bus|tram|rer)(?: |(?=\\d))|m ?(?=\\d))")
    private val CONNECTORS = setOf("vers", "direction", "dir", "to", "towards", "toward")
}
