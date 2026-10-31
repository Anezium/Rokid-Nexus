package com.anezium.rokidbus.shared

/** Wearer-owned presentation; plugins supply events, never these choices. */
enum class ActivityDisplayMode(val wireValue: String) {
    AUTOMATIC("automatic"),
    PERSISTENT("persistent"),
    HOME("home"),
    EVENTS("events"),
    HIDDEN("hidden");

    companion object {
        fun fromWireValue(value: String): ActivityDisplayMode? = entries.firstOrNull { it.wireValue == value }
    }
}

data class ActivityDisplayPreference(
    val mode: ActivityDisplayMode = ActivityDisplayMode.AUTOMATIC,
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    init { require(durationMs in DURATIONS_MS) }

    companion object {
        const val DEFAULT_DURATION_MS = 5_000L
        val DURATIONS_MS = setOf(3_000L, 5_000L, 10_000L)
        val DEFAULT = ActivityDisplayPreference()
    }
}
