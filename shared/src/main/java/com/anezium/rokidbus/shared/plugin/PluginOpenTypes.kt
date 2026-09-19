package com.anezium.rokidbus.shared.plugin

/**
 * The `type` a `/system/plugin/open` lifecycle message carries: why the hub is opening the
 * plugin. Version-1 receivers treat any value they do not know as [OPEN].
 */
object PluginOpenTypes {
    /** A deliberate open: the launcher on the glasses, or *Open* on the phone. */
    const val OPEN = "open"

    /** A deliberate open of a plugin that was kept alive by its microphone lease. */
    const val RESUME = "resume"

    /**
     * The assist button handed over to this plugin. A follow-up on `/system/plugin/ai-assist`
     * carries the gesture id and whether the button is still held.
     */
    const val AI_ASSIST = "ai_assist"

    /** The hub restarted and found this plugin already on the glasses; it is re-adopting it. */
    const val ADOPTED = "adopted"

    /** The camera session opened for this plugin's frozen view. */
    const val CAMERA_SESSION = "camera_session_open"
}
