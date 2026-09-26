package com.anezium.rokidbus.plugin.nav

/**
 * What the listener last understood, shared with the plugin's card and
 * settings screen in the same process. It lives only while the process does.
 */
internal object NavState {
    @Volatile
    var guidance: NavGuidance? = null

    @Volatile
    var listenerConnected: Boolean = false

    /** The last hub answer to the listener's registration, for the settings screen. */
    @Volatile
    var registration: Int? = null
}
