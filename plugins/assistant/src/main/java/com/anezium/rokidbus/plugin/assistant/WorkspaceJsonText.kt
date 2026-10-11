package com.anezium.rokidbus.plugin.assistant

/**
 * Appends [value] as a JSON string with one escaping rule on every platform: only quotes,
 * backslashes, control characters, line separators, and unpaired surrogates are escaped, so the
 * length measured here is the length sent or written.
 */
internal fun appendWorkspaceJsonString(out: StringBuilder, value: String) {
    fun unicode(c: Char) {
        out.append("\\u").append(String.format("%04x", c.code))
    }
    out.append('"')
    var index = 0
    while (index < value.length) {
        val c = value[index]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> unicode(c)
            Character.isHighSurrogate(c) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) -> {
                out.append(c).append(value[index + 1])
                index++
            }
            Character.isSurrogate(c) -> unicode(c)
            else -> out.append(c)
        }
        index++
    }
    out.append('"')
}
