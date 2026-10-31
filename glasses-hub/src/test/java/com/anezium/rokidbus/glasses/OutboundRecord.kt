package com.anezium.rokidbus.glasses

/**
 * The paths the glasses sent or tried to send the phone, read from the host's own log lines, one
 * per attempt: a successful SPP write, a CXR send, or the session's drop of a message no transport
 * took. A CXR send that failed is followed on the very next line by the session's drop of the same
 * path, and that pair alone is one attempt; any other drop is an attempt of its own.
 */
internal object OutboundRecord {
    const val SPP = "SPP TX "
    const val CXR = "CXR-S TX "
    const val DROPPED = "session send dropped path="

    /** The SPP server's write failure; it names no path, and the CXR or drop line that follows does. */
    private const val SPP_FAILED = "SPP TX failed"

    fun paths(lines: List<String>): List<String> {
        val paths = mutableListOf<String>()
        var failedCxr: String? = null
        lines.forEach { line ->
            val pairedDrop = failedCxr
            failedCxr = null
            when {
                line.startsWith(DROPPED) -> {
                    val path = pathAfter(line, DROPPED)
                    if (path != pairedDrop) paths += path
                }
                line.startsWith(CXR) -> {
                    val path = pathAfter(line, CXR)
                    paths += path
                    if (!succeeded(line)) failedCxr = path
                }
                line.startsWith(SPP) && !line.startsWith(SPP_FAILED) -> paths += pathAfter(line, SPP)
            }
        }
        return paths
    }

    private fun pathAfter(line: String, prefix: String): String = line.removePrefix(prefix).substringBefore(' ')

    /** `result=` is the vendor bridge's answer: zero or more is a send, anything else is not. */
    private fun succeeded(line: String): Boolean =
        line.substringAfter(" result=", "").trim().toIntOrNull()?.let { it >= 0 } == true
}
