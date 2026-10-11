package com.anezium.rokidbus.media.session

import java.security.MessageDigest

/**
 * One media session: a hash of its app's package and its session token, stable while that
 * session lives. A new session of the same app gets a new identity, so nothing decided on the
 * old one can reach it.
 */
internal data class MediaSessionIdentity(val value: String)

/** What is playing, without its position: the facts a revision is about. */
internal data class MediaTrack(
    val packageName: String,
    val sourceLabel: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
) {
    /** A content key: the same item in the same app gives the same key. */
    val key: String by lazy {
        shortHash(listOf(packageName, mediaId, title, artist, album, durationMs?.toString().orEmpty()).joinToString("\u0000"))
    }
}

/** What the monitor saw on one look at the active sessions. */
internal sealed interface MediaDeckObservation {
    /** Notification access is off, or the system refused it. */
    data object AccessRequired : MediaDeckObservation

    /** Access is on and no session has readable metadata. */
    data object NoSession : MediaDeckObservation

    /** The sessions could not be read for another reason. */
    data object Unavailable : MediaDeckObservation

    data class Session(
        val identity: MediaSessionIdentity,
        val track: MediaTrack,
        val phase: MediaPlaybackPhase,
    ) : MediaDeckObservation
}

/**
 * Media Deck's state, shared by the immersion, the activity and the page. [revision] goes up by
 * one whenever anything a wearer can act on changes (the session, the item, playing or not, or
 * whether there is anything at all) and never on position alone. It never goes down in a process.
 */
internal sealed interface MediaDeckState {
    val revision: Long

    /** Nothing observed: no subscriber runs the monitor, or it has not looked yet. */
    data class Unknown(override val revision: Long) : MediaDeckState

    /** No notification access. [revoked] when this process saw access before it went away. */
    data class AccessRequired(override val revision: Long, val revoked: Boolean) : MediaDeckState

    data class NoSession(override val revision: Long) : MediaDeckState

    data class Unavailable(override val revision: Long) : MediaDeckState

    /**
     * A session with metadata, playing or not. [sessionChanged] when it replaced another session
     * observed earlier, which makes every command decided on that other session stale.
     */
    data class Active(
        override val revision: Long,
        val identity: MediaSessionIdentity,
        val track: MediaTrack,
        val phase: MediaPlaybackPhase,
        val sessionChanged: Boolean,
    ) : MediaDeckState {
        val playing: Boolean get() = phase == MediaPlaybackPhase.PLAYING

        val observation: MediaDeckObservation.Session get() = MediaDeckObservation.Session(identity, track, phase)
    }
}

/** Turns observations into [MediaDeckState]s with a revision that only moves on a real change. */
internal class MediaDeckStateMachine {
    var state: MediaDeckState = MediaDeckState.Unknown(0)
        private set

    private var accessSeen = false
    private var lastIdentity: MediaSessionIdentity? = null

    /** Applies [observation]; true when it changed the state, which then carries a new revision. */
    fun observe(observation: MediaDeckObservation): Boolean {
        val next = state.revision + 1
        val candidate = when (observation) {
            MediaDeckObservation.AccessRequired -> MediaDeckState.AccessRequired(next, revoked = accessSeen)
            MediaDeckObservation.NoSession -> MediaDeckState.NoSession(next)
            MediaDeckObservation.Unavailable -> MediaDeckState.Unavailable(next)
            is MediaDeckObservation.Session -> MediaDeckState.Active(
                revision = next,
                identity = observation.identity,
                track = observation.track,
                phase = observation.phase,
                sessionChanged = lastIdentity != null && lastIdentity != observation.identity,
            )
        }
        if (observation is MediaDeckObservation.NoSession || observation is MediaDeckObservation.Session) accessSeen = true
        if (sameState(state, candidate)) return false
        if (candidate is MediaDeckState.Active) lastIdentity = candidate.identity
        state = candidate
        return true
    }

    /** The monitor stopped: nothing is known any more, and whatever was shown before is older. */
    fun forget() {
        if (state !is MediaDeckState.Unknown) state = MediaDeckState.Unknown(state.revision + 1)
    }

    private fun sameState(current: MediaDeckState, candidate: MediaDeckState): Boolean = when (current) {
        is MediaDeckState.Unknown -> false
        is MediaDeckState.AccessRequired -> candidate is MediaDeckState.AccessRequired && candidate.revoked == current.revoked
        is MediaDeckState.NoSession -> candidate is MediaDeckState.NoSession
        is MediaDeckState.Unavailable -> candidate is MediaDeckState.Unavailable
        is MediaDeckState.Active -> candidate is MediaDeckState.Active &&
            candidate.identity == current.identity &&
            candidate.track == current.track &&
            candidate.phase == current.phase
    }
}

/**
 * Gives each distinct session token a serial for this process: equal tokens (one session) share
 * an identity, and a new session never reuses one. Bounded; a token pushed out and seen again
 * reads as a new session, which only ever makes a command stale.
 */
internal class MediaSessionIdentities(private val capacity: Int = 16) {
    private val known = ArrayDeque<Pair<Any, Long>>()
    private var nextSerial = 1L

    fun identityOf(packageName: String, token: Any): MediaSessionIdentity {
        val serial = known.firstOrNull { it.first == token }?.second ?: nextSerial++.also { serial ->
            known.addLast(token to serial)
            while (known.size > capacity) known.removeFirst()
        }
        return MediaSessionIdentity(shortHash("$packageName\u0000$serial\u0000${token.hashCode()}"))
    }
}

/** The first eight bytes of a SHA-256, as 16 lowercase hex digits. */
internal fun shortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(16) {
        for (index in 0 until 8) {
            val byte = digest[index].toInt() and 0xff
            append(hex[byte ushr 4])
            append(hex[byte and 0x0f])
        }
    }
}
