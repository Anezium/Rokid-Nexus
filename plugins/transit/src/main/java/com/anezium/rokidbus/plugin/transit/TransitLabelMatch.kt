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
        val bare = withoutMode(selector)
        return labels.filter { withoutMode(it) == bare }.toSet()
    }

    /**
     * The headsigns [selector] names: the exact headsign, else the headsign that is the selector
     * without its leading connector words, else every headsign holding all of those words.
     */
    fun directions(selector: String, headsigns: Collection<String>): Set<String> {
        val wanted = normalize(selector)
        headsigns.filter { normalize(it) == wanted }.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        val wantedWords = words(selector).dropWhile { it in CONNECTORS }
        if (wantedWords.isEmpty()) return emptySet()
        headsigns.filter { words(it) == wantedWords }.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        return headsigns.filter { words(it).containsAll(wantedWords) }.toSet()
    }

    /** How many different lines [labels] are, typography aside. */
    fun distinctLines(labels: Collection<String>): Int = labels.map(::normalize).distinct().size

    /** How many different directions [headsigns] are, typography and punctuation aside. */
    fun distinctDirections(headsigns: Collection<String>): Int = headsigns.map(::words).distinct().size

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
            rest.startsWith(' ') -> rest.substring(1)
            DIGIT_CODE.matches(rest) || (word == "rer" && LETTER_CODE.matches(rest)) -> rest
            else -> null
        }
        return if (bare.isNullOrEmpty()) label else strippedMode(bare)
    }

    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['\u2018\u2019\u02bc]")
    private val SPACES = Regex("[\\s\\p{Z}\\p{Pd}]+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val DIGIT_CODE = Regex("[\\p{L}\\p{N}]*\\p{N}[\\p{L}\\p{N}]*")
    private val LETTER_CODE = Regex("\\p{L}{1,2}")
    private val MODE_WORDS = listOf("ligne", "line", "metro", "bus", "tram", "rer", "m")
    private val CONNECTORS = setOf("vers", "direction", "dir", "to", "towards", "toward")
}
