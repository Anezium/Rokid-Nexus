package com.anezium.rokidbus.plugin.nav

/** The navigation apps Navigation follows, by the package that posts their guidance. */
internal enum class NavSource(val packageName: String, val label: String) {
    GOOGLE_MAPS("com.google.android.apps.maps", "Google Maps"),
    CITYMAPPER("com.citymapper.app.release", "Citymapper"),
    ;

    companion object {
        fun of(packageName: String): NavSource? = values().firstOrNull { it.packageName == packageName }
    }
}

/**
 * The parts of one posted notification a parser may read, copied out of the
 * Android objects so parsing is plain Kotlin and every case can be a unit test.
 */
internal data class NavNotification(
    val packageName: String,
    val channelId: String? = null,
    val category: String? = null,
    val ongoing: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val bigText: String? = null,
    val textLines: List<String> = emptyList(),
    /** Android 16's status-chip text (`Notification.EXTRA_SHORT_CRITICAL_TEXT`). */
    val shortCriticalText: String? = null,
    val progress: Int = 0,
    val progressMax: Int = 0,
    val actions: List<String> = emptyList(),
    /** Samsung's Now Bar copy of the guidance, when the app filled it in. */
    val nowBarPrimary: String? = null,
    val nowBarSecondary: String? = null,
    /**
     * Text drawn by the app's own notification layout, by the resource entry
     * name of each TextView ("notification_title"). Citymapper puts its whole
     * guidance there and nothing in the standard fields.
     */
    val viewTexts: Map<String, String> = emptyMap(),
)

/**
 * What the wearer should see for one guidance notification. Every value comes
 * from the notification itself; nothing is estimated between two of them.
 */
internal data class NavGuidance(
    val source: NavSource,
    val glyph: String,
    val primary: String,
    val secondary: String? = null,
    val eta: String? = null,
    val detail: List<String> = emptyList(),
    val badge: String? = null,
    val track: NavTrack? = null,
    val progressPercent: Int? = null,
    /** Identifies the current step; a new key is a new maneuver or leg. */
    val stepKey: String,
    /** The step is about to happen: the turn is a few metres away, the stop is next. */
    val imminent: Boolean = false,
    val arrived: Boolean = false,
    /** The whole instruction, for the plugin's own card where there is room for it. */
    val instruction: String? = null,
)

/** Stops or stages of a ride, mirroring the SDK track without depending on it in parsers. */
internal data class NavTrack(val count: Int, val at: Int, val target: Int, val label: String? = null)

/** Words the plugin supplies itself rather than reading from a notification. */
internal data class NavLabels(
    val arrived: String = "Arrived",
    val now: String = "Now",
)
