package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityAction
import com.anezium.rokidbus.client.plugin.NexusActivityActivation
import com.anezium.rokidbus.client.plugin.NexusActivityPersistence
import com.anezium.rokidbus.client.plugin.NexusPageDestination
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.shared.ActivitySurfaceContract

/** How the activity's owner reaches the hub. */
internal enum class MediaDeckBus {
    READY,

    /** Registered, but no usable link to the glasses. */
    LINK_LOST,

    /** No registration to publish through: the service is gone or not approved. */
    OWNER_GONE,
}

/** Everything the Media Deck activity can be, from the user's switch down to the bus. */
internal enum class MediaDeckActivityStatus {
    DISABLED,
    ACCESS_REQUIRED,
    REVOKED,
    NO_SESSION,
    PLAYING,
    PAUSED,
    SESSION_CHANGED,
    UNAVAILABLE,
    LINK_LOST,
    OWNER_GONE,
}

/**
 * An activity command's id carries the state revision it was published at, so a command is bound
 * to what the wearer saw even when the next revision is already on its way to the hub. Ids stay
 * safe page-style ids (`next@12`).
 */
internal object MediaDeckActionIds {
    private const val SEPARATOR = '@'

    fun encode(command: MediaDeckCommand, revision: Long): String = "${prefix(command)}$SEPARATOR$revision"

    /** The command and revision of an id this plugin published, or null for anything else. */
    fun parse(actionId: String): Pair<MediaDeckCommand, Long>? {
        val separator = actionId.indexOf(SEPARATOR)
        if (separator <= 0) return null
        val command = MediaDeckCommand.entries.firstOrNull { prefix(it) == actionId.substring(0, separator) } ?: return null
        val digits = actionId.substring(separator + 1)
        if (digits.isEmpty() || digits.length > 18 || !digits.all { it in '0'..'9' }) return null
        return command to digits.toLong()
    }

    private fun prefix(command: MediaDeckCommand): String = when (command) {
        MediaDeckCommand.PREVIOUS -> "prev"
        MediaDeckCommand.PLAY -> "play"
        MediaDeckCommand.PAUSE -> "pause"
        MediaDeckCommand.NEXT -> "next"
    }
}

internal object MediaDeckActivityModel {
    const val GLYPH_PLAYING = "play"
    const val GLYPH_PAUSED = "pause"

    fun status(enabled: Boolean, state: MediaDeckState, bus: MediaDeckBus): MediaDeckActivityStatus = when {
        !enabled -> MediaDeckActivityStatus.DISABLED
        state is MediaDeckState.AccessRequired ->
            if (state.revoked) MediaDeckActivityStatus.REVOKED else MediaDeckActivityStatus.ACCESS_REQUIRED
        state is MediaDeckState.Unknown || state is MediaDeckState.NoSession -> MediaDeckActivityStatus.NO_SESSION
        state is MediaDeckState.Unavailable -> MediaDeckActivityStatus.UNAVAILABLE
        bus == MediaDeckBus.OWNER_GONE -> MediaDeckActivityStatus.OWNER_GONE
        bus == MediaDeckBus.LINK_LOST -> MediaDeckActivityStatus.LINK_LOST
        state is MediaDeckState.Active && state.sessionChanged -> MediaDeckActivityStatus.SESSION_CHANGED
        state is MediaDeckState.Active && state.playing -> MediaDeckActivityStatus.PLAYING
        else -> MediaDeckActivityStatus.PAUSED
    }

    /**
     * The activity for [state], or null when there is nothing true to show: no session, no access,
     * or sessions that cannot be read. The card's first tap focuses the play/pause command for the
     * state shown (never a toggle); its destination, the media page, stays openable offline.
     */
    fun activity(state: MediaDeckState): NexusActivity? {
        val active = state as? MediaDeckState.Active ?: return null
        val playPause = MediaDeckCommand.desiredFor(active.phase)
        val actions = listOf(MediaDeckCommand.PREVIOUS, playPause, MediaDeckCommand.NEXT).map { command ->
            NexusActivityAction(
                id = MediaDeckActionIds.encode(command, active.revision),
                glyph = glyphOf(command),
                label = labelOf(command),
                typed = command.typed,
            )
        }
        return NexusActivity(
            glyph = if (active.playing) GLYPH_PLAYING else GLYPH_PAUSED,
            primary = MediaText.ellipsize(active.track.title, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = MediaText.ellipsize(active.track.artist, ActivitySurfaceContract.MAX_SECONDARY_CHARS)
                .takeIf(String::isNotBlank),
            actions = actions,
            destination = NexusPageDestination(MediaDeckPages.MAIN),
            activation = NexusActivityActivation.COMMANDS,
            defaultActionId = MediaDeckActionIds.encode(playPause, active.revision),
            persistence = NexusActivityPersistence.ONGOING,
        )
    }

    fun glyphOf(command: MediaDeckCommand): String = when (command) {
        MediaDeckCommand.PREVIOUS -> "prev"
        MediaDeckCommand.PLAY -> "play"
        MediaDeckCommand.PAUSE -> "pause"
        MediaDeckCommand.NEXT -> "next"
    }

    fun labelOf(command: MediaDeckCommand): String = when (command) {
        MediaDeckCommand.PREVIOUS -> "Previous"
        MediaDeckCommand.PLAY -> "Play"
        MediaDeckCommand.PAUSE -> "Pause"
        MediaDeckCommand.NEXT -> "Next"
    }
}
