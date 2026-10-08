// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.session

/**
 * What a session was opened over. The session is an excursion: closing it leaves this base as it
 * was, and a Nexus surface underneath never receives a close because of the session.
 */
internal sealed interface Underneath {
    data object Home : Underneath
    data object NativeApp : Underneath
    data class NexusSurface(val surfaceId: String) : Underneath
    data object Unknown : Underneath
}
