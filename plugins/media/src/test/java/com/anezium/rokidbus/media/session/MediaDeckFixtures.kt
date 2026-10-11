package com.anezium.rokidbus.media.session

/** A track as a player reports it, without its position. */
internal fun track(
    title: String = "Teardrop",
    artist: String = "Massive Attack",
    packageName: String = "com.example.player",
    album: String = "Mezzanine",
): MediaTrack = MediaTrack(
    packageName = packageName,
    sourceLabel = "Player",
    mediaId = "id-$title",
    title = title,
    artist = artist,
    album = album,
    durationMs = 330_000L,
)

internal val SESSION_A = MediaSessionIdentity("session-a")
internal val SESSION_B = MediaSessionIdentity("session-b")

internal fun observed(
    identity: MediaSessionIdentity = SESSION_A,
    track: MediaTrack = track(),
    phase: MediaPlaybackPhase = MediaPlaybackPhase.PLAYING,
): MediaDeckObservation.Session = MediaDeckObservation.Session(identity, track, phase)

internal fun snapshotOf(observation: MediaDeckObservation.Session, positionMs: Long = 0L): MediaDeckSnapshot =
    MediaDeckSnapshot(
        identity = observation.identity,
        track = observation.track,
        phase = observation.phase,
        positionMs = positionMs,
        advancing = observation.phase == MediaPlaybackPhase.PLAYING,
        playbackSpeed = 1f,
        artwork = null,
        artworkUri = "",
    )

/**
 * The session watcher without Android: tests set what it sees and push it. Like the real one it
 * reports at once on start and refresh, and [send] checks the live session before it "sends".
 */
internal class FakeMonitor(private val listener: MediaDeckMonitorListener) : MediaDeckMonitor {
    var started = false
        private set
    var starts = 0
        private set
    var stops = 0
        private set

    /** What a look at the sessions finds now; it may run ahead of what was reported. */
    var live: MediaDeckObservation = MediaDeckObservation.NoSession
    var livePositionMs = 0L

    /** Whether the live position moves; null follows the phase (a buffering session sets false). */
    var liveAdvancing: Boolean? = null

    /** Overrides what [send] answers, for a session that throws or refuses. */
    var sendResult: MediaDeckSendResult? = null

    val sent = mutableListOf<Pair<MediaDeckObservation.Session, MediaDeckCommand>>()

    override fun start() {
        started = true
        starts++
        report()
    }

    override fun stop() {
        started = false
        stops++
    }

    override fun refresh() {
        if (started) report()
    }

    override fun send(expected: MediaDeckObservation.Session, command: MediaDeckCommand): MediaDeckSendResult {
        if (!started) return MediaDeckSendResult.MOVED
        sendResult?.let { return it }
        if (live != expected) return MediaDeckSendResult.MOVED
        sent += expected to command
        return MediaDeckSendResult.SENT
    }

    /** The player changed and its callback arrived. */
    fun emit(observation: MediaDeckObservation, positionMs: Long = livePositionMs) {
        live = observation
        livePositionMs = positionMs
        if (started) report()
    }

    private fun report() {
        val current = live
        val snapshot = (current as? MediaDeckObservation.Session)?.let { session ->
            snapshotOf(session, livePositionMs).let { base -> liveAdvancing?.let { base.copy(advancing = it) } ?: base }
        }
        listener.onObserved(current, snapshot)
    }
}

/** A hub over a [FakeMonitor], with the monitor at hand. */
internal class FakeHub {
    lateinit var monitor: FakeMonitor
        private set
    val hub = MediaDeckMonitorHub { listener -> FakeMonitor(listener).also { monitor = it } }
}
