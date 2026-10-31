package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityAction
import com.anezium.rokidbus.client.plugin.NexusActivityActivation
import com.anezium.rokidbus.client.plugin.NexusActivityCommandResult
import com.anezium.rokidbus.client.plugin.NexusActivityPersistence
import com.anezium.rokidbus.client.plugin.NexusPageDestination
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckControlOutcome
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaPlaybackPhase
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

    /** Nexus ended or refused it (replaced, or turned off there); retried on a new registration. */
    SUSPENDED,
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
    /**
     * Media Deck's own mark (`res/values/nexus_glyphs.xml`), in every state. A play or pause mark
     * here would sit beside the glasses' own media pill and could only ever agree with it or
     * contradict it; the state is said in words on the second line instead.
     */
    const val GLYPH = "disc"

    fun status(enabled: Boolean, suspended: Boolean, state: MediaDeckState, bus: MediaDeckBus): MediaDeckActivityStatus = when {
        !enabled -> MediaDeckActivityStatus.DISABLED
        suspended -> MediaDeckActivityStatus.SUSPENDED
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
     * The activity for [state], including an idle destination; null without access or readable
     * session state. The card opens the integrated media page on its first tap; its destination
     * stays openable offline. Older command-capable hubs retain the explicit transport actions.
     * The whole title rides as the headline for the surfaces with room for it.
     */
    fun activity(state: MediaDeckState, artwork: com.anezium.rokidbus.shared.LauncherArtwork? = null): NexusActivity? {
        if (state is MediaDeckState.NoSession) return NexusActivity(
            glyph = GLYPH,
            primary = "Media Deck",
            secondary = "Ready to play",
            destination = NexusPageDestination(MediaDeckPages.MAIN),
            activation = NexusActivityActivation.DESTINATION,
            persistence = NexusActivityPersistence.ONGOING,
        )
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
        // A podcast or a video often has no artist; the app it plays in is still true.
        val byline = MediaText.oneLine(MediaText.printable(active.track.artist.ifBlank { active.track.sourceLabel }))
        val note = noteOf(active.phase)
        val room = ActivitySurfaceContract.MAX_SECONDARY_CHARS - (note?.let { it.length + NOTE_SEPARATOR.length } ?: 0)
        val secondary = listOfNotNull(note, MediaText.ellipsize(byline, room).takeIf(String::isNotBlank))
            .joinToString(NOTE_SEPARATOR).takeIf(String::isNotBlank)
        val wholeSecondary = MediaText.ellipsize(
            listOfNotNull(note, byline.takeIf(String::isNotBlank)).joinToString(NOTE_SEPARATOR),
            ActivitySurfaceContract.MAX_WHOLE_CHARS,
        ).takeIf { it.isNotBlank() && it != secondary }
        return NexusActivity(
            glyph = GLYPH,
            primary = MediaText.ellipsize(active.track.title, ActivitySurfaceContract.MAX_PRIMARY_CHARS),
            secondary = secondary,
            wholeSecondary = wholeSecondary,
            eventKey = com.anezium.rokidbus.media.session.shortHash(
                active.track.packageName + "\u0000" + active.track.mediaId.ifBlank {
                    listOf(active.track.title, active.track.artist, active.track.album).joinToString("\u0000")
                },
            ),
            headline = MediaText.ellipsize(MediaText.printable(active.track.title), ActivitySurfaceContract.MAX_HEADLINE_CHARS),
            actions = actions,
            destination = NexusPageDestination(MediaDeckPages.MAIN),
            activation = NexusActivityActivation.COMMANDS,
            artwork = artwork,
            defaultActionId = MediaDeckActionIds.encode(playPause, active.revision),
            persistence = NexusActivityPersistence.ONGOING,
        )
    }

    /**
     * What the second line starts with while nothing plays; nothing while it does. It is the
     * activity's only playback state, so it comes first: a line too narrow on the glasses cuts
     * the artist, never the state.
     */
    private fun noteOf(phase: MediaPlaybackPhase): String? = when (phase) {
        MediaPlaybackPhase.PLAYING -> null
        MediaPlaybackPhase.PAUSED -> "Paused"
        MediaPlaybackPhase.STOPPED -> "Stopped"
    }

    private const val NOTE_SEPARATOR = " · "

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

    /** What the phone settings say about the activity; never a track title (user data). */
    fun summaryOf(status: MediaDeckActivityStatus): String = when (status) {
        MediaDeckActivityStatus.DISABLED -> "Off"
        MediaDeckActivityStatus.ACCESS_REQUIRED -> "On · needs media access"
        MediaDeckActivityStatus.REVOKED -> "On · media access was turned off"
        MediaDeckActivityStatus.NO_SESSION -> "On · ready to play"
        MediaDeckActivityStatus.PLAYING,
        MediaDeckActivityStatus.PAUSED,
        MediaDeckActivityStatus.SESSION_CHANGED,
        -> "On · showing what plays"
        MediaDeckActivityStatus.UNAVAILABLE -> "On · media control unavailable"
        MediaDeckActivityStatus.LINK_LOST -> "On · glasses not connected"
        MediaDeckActivityStatus.OWNER_GONE -> "On · waiting for Nexus"
        MediaDeckActivityStatus.SUSPENDED -> "On · stopped by Nexus until Media Deck opens"
    }
}

/** The answer an activity command carries for an outcome; never a success it did not check. */
internal fun MediaDeckControlOutcome.toActivityResult(): NexusActivityCommandResult = when (status) {
    MediaDeckControlOutcome.Status.DONE -> NexusActivityCommandResult.done(message)
    MediaDeckControlOutcome.Status.STALE -> NexusActivityCommandResult.stale(message)
    MediaDeckControlOutcome.Status.REJECTED -> NexusActivityCommandResult.rejected(message)
}
