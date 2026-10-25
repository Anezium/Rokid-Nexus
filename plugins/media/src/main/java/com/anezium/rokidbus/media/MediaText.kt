package com.anezium.rokidbus.media

/** Text shaping for the HUD: one line, never cut through a character, an ellipsis when cut. */
internal object MediaText {
    private const val ELLIPSIS = "…"

    fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    /** [value] with every control character a space, so it can only ever draw as text. */
    fun printable(value: String): String = value.map { if (it.isISOControl()) ' ' else it }.joinToString("")

    /** [value] on one line in at most [limit] UTF-16 units, ending in an ellipsis when it was cut. */
    fun ellipsize(value: String, limit: Int): String {
        val line = oneLine(value)
        if (line.length <= limit) return line
        if (limit <= ELLIPSIS.length) return line.take(limit)
        var end = limit - ELLIPSIS.length
        if (Character.isHighSurrogate(line[end - 1])) end -= 1
        return line.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** [value] on one line in at most [limit] UTF-16 units, cut without a mark. */
    fun clip(value: String, limit: Int): String {
        val line = oneLine(value)
        if (line.length <= limit) return line
        var end = limit
        if (Character.isHighSurrogate(line[end - 1])) end -= 1
        return line.substring(0, end).trimEnd()
    }
}
