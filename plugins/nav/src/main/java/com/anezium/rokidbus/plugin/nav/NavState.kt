package com.anezium.rokidbus.plugin.nav

/**
 * What the selected guidance source last reported, shared with the plugin's card, pages and
 * settings screen in the same process. It lives only while the process does; nothing about a
 * route or a journey is ever stored.
 */
internal object NavState {
    @Volatile
    var guidance: NavGuidance? = null

    /** The running walking journey's last state, once its route is planned. */
    @Volatile
    var journey: NavRouteSnapshot? = null

    /** Where the running walking journey goes, from the moment it starts. */
    @Volatile
    var journeyDestination: String? = null

    /** What the journey last said about itself, kept after it ends for the phone panel. */
    @Volatile
    var journeyMessage: String? = null

    /** Wall-clock time the guidance last changed, for the page's received line. */
    @Volatile
    var updatedAtMs: Long? = null

    @Volatile
    var listenerConnected: Boolean = false

    /** The route page, with its maps, is on the glasses now. */
    @Volatile
    var routePageVisible: Boolean = false

    /** Whether the running journey's street map is drawn, loading or unavailable, for its page. */
    @Volatile
    var basemapState: BasemapState = BasemapState.NONE

    /** The last hub answer to the listener's registration, for the settings screen. */
    @Volatile
    var registration: Int? = null
}
