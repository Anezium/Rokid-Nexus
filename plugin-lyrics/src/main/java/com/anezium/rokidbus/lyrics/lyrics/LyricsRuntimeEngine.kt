package com.anezium.rokidbus.lyrics.lyrics

import android.util.Log
import android.os.SystemClock
import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.lyrics.LyricsPhoneStateStore
import com.anezium.rokidbus.lyrics.media.MediaPlaybackSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LyricsRuntimeEngine(
    private val stateStore: LyricsPhoneStateStore,
    private val lyricsProvider: CompositeLyricsProvider,
    private val onLookupStarted: (() -> Unit)? = null,
    private val onAttemptSummaries: ((List<ProviderAttemptSummary>) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lookupJob: Job? = null
    private var activeMediaSnapshot: MediaPlaybackSnapshot? = null
    private var activeMediaLookupKey: String? = null
    @Volatile private var lastResolvedLookupKey: String? = null
    @Volatile private var lookupInFlightKey: String? = null
    private val lookupGeneration = AtomicLong(0L)
    @Volatile private var lastLookupErrorKey: String? = null
    @Volatile private var lastLookupErrorAtMs: Long = 0L

    fun destroy() {
        lookupJob?.cancel()
        scope.cancel()
    }

    fun refresh() {
        val mediaSnapshot = activeMediaSnapshot
        if (mediaSnapshot != null) {
            lookup(
                request = LyricsLookupRequest(
                    title = mediaSnapshot.title,
                    artist = mediaSnapshot.artist,
                    album = mediaSnapshot.album,
                    durationSeconds = mediaSnapshot.durationMs?.div(1000L)?.toInt(),
                    spotifyTrackId = mediaSnapshot.spotifyTrackId,
                ),
                mediaKey = mediaLookupKey(mediaSnapshot),
                fromMedia = true,
                force = true,
            )
            return
        }

        val current = stateStore.current().lyrics
        if (current.trackTitle.isBlank() || current.artistName.isBlank()) {
            stateStore.updateStatus { it.copy(statusLabel = "Nothing to refresh yet. Start music on the phone.") }
            return
        }
        lookup(
            request = LyricsLookupRequest(
                title = current.trackTitle,
                artist = current.artistName,
                album = current.albumName,
                durationSeconds = current.durationSeconds,
            ),
            mediaKey = activeMediaLookupKey,
            fromMedia = activeMediaSnapshot != null,
            force = true,
        )
    }

    fun onMediaStatus(message: String) {
        stateStore.updateStatus { current ->
            current.copy(statusLabel = message)
        }
    }

    fun onMediaPlaybackSnapshot(snapshot: MediaPlaybackSnapshot?) {
        if (snapshot == null) {
            activeMediaSnapshot = null
            activeMediaLookupKey = null
            cancelLookup()
            val current = stateStore.current().lyrics
            if (current.trackTitle.isBlank()) {
                stateStore.updateLyrics(
                    current.copy(
                        sessionState = LyricsSessionState.IDLE,
                        sourceSummary = "Start a media app on the phone.",
                        errorMessage = null,
                    )
                )
            } else {
                stateStore.updateLyrics(
                    current.copy(
                        sessionState = if (current.lines.isEmpty()) LyricsSessionState.IDLE else LyricsSessionState.READY,
                        sourceSummary = "No active media session. Resume playback on the phone.",
                        errorMessage = current.errorMessage,
                    )
                )
            }
            return
        }

        activeMediaSnapshot = snapshot
        val current = stateStore.current().lyrics
        val lookupKey = mediaLookupKey(snapshot)
        val matchesLoaded = normalized(current.trackTitle) == normalized(snapshot.title) &&
            normalized(current.artistName) == normalized(snapshot.artist)
        val lookupDecision = shouldLookupForCurrentTrack(current, lookupKey, matchesLoaded)
        if (lookupDecision.shouldLookup || lookupDecision.reason != "skip_already_resolved") {
            debugLog {
                "media snapshot key=$lookupKey activeKey=$activeMediaLookupKey resolvedKey=$lastResolvedLookupKey inFlight=$lookupInFlightKey matchesLoaded=$matchesLoaded state=${current.sessionState} lines=${current.lines.size} decision=${lookupDecision.reason}"
            }
        }
        if (lookupDecision.shouldLookup) {
            activeMediaLookupKey = lookupKey
            lookup(
                request = LyricsLookupRequest(
                    title = snapshot.title,
                    artist = snapshot.artist,
                    album = snapshot.album,
                    durationSeconds = snapshot.durationMs?.div(1000L)?.toInt(),
                    spotifyTrackId = snapshot.spotifyTrackId,
                ),
                mediaKey = lookupKey,
                fromMedia = true,
            )
            return
        }

        activeMediaLookupKey = lookupKey
        stateStore.updateStatus {
            it.copy(statusLabel = "Tracking ${sourceLabel(snapshot.packageName)} - ${snapshot.title} / ${snapshot.artist}")
        }
        stateStore.updateLyrics(applyMediaSnapshot(current, snapshot))
    }

    private fun lookup(
        request: LyricsLookupRequest,
        mediaKey: String?,
        fromMedia: Boolean,
        force: Boolean = false,
    ) {
        val title = request.title.trim()
        val artist = request.artist.trim()
        if (title.isBlank() || artist.isBlank()) {
            stateStore.updateLyrics(
                stateStore.current().lyrics.copy(
                    sessionState = LyricsSessionState.ERROR,
                    errorMessage = "Lyrics lookup requires both title and artist.",
                    sourceSummary = "The current media session does not expose enough metadata.",
                )
            )
            return
        }

        val normalizedRequest = request.copy(
            title = title,
            artist = artist,
            album = request.album.trim(),
        )
        val requestKey = mediaKey ?: lyricsLookupRequestKey(normalizedRequest)
        if (!force && requestKey == lookupInFlightKey) {
            return
        }

        val generation = lookupGeneration.incrementAndGet()
        lookupInFlightKey = requestKey
        lookupJob?.cancel()
        val currentLyrics = stateStore.current().lyrics
        val preserveVisibleLyrics = shouldPreserveVisibleLyrics(currentLyrics, normalizedRequest)
        val preservedSnapshot = currentLyrics.takeIf { preserveVisibleLyrics }
        debugLog {
            "lookup start key=$requestKey fromMedia=$fromMedia title='${normalizedRequest.title}' artist='${normalizedRequest.artist}' album='${normalizedRequest.album}' duration=${normalizedRequest.durationSeconds}"
        }

        onLookupStarted?.invoke()
        var loadingSnapshot = buildLookupLoadingSnapshot(
            current = currentLyrics,
            request = normalizedRequest,
            fromMedia = fromMedia,
            mediaSourceLabel = sourceLabel(activeMediaSnapshot?.packageName),
            preserveVisibleLyrics = preserveVisibleLyrics,
        )
        val latestMediaForLoading = activeMediaSnapshot
        if (preserveVisibleLyrics && mediaKey != null && latestMediaForLoading != null && mediaLookupKey(latestMediaForLoading) == mediaKey) {
            loadingSnapshot = applyMediaSnapshot(loadingSnapshot, latestMediaForLoading)
        }
        stateStore.updateLyrics(loadingSnapshot)

        lookupJob = scope.launch {
            try {
                val compositeResult = lyricsProvider.fetch(normalizedRequest)
                if (!shouldCommitLookup(generation, requestKey, mediaKey)) {
                    return@launch
                }
                lastResolvedLookupKey = requestKey
                onAttemptSummaries?.invoke(compositeResult.attemptSummaries)
                val result = compositeResult.result
                var next = LyricsSnapshot(
                    sessionState = LyricsSessionState.READY,
                    trackTitle = result.trackTitle,
                    artistName = result.artistName,
                    albumName = result.albumName,
                    durationSeconds = result.durationSeconds,
                    provider = result.provider,
                    sourceSummary = result.sourceSummary,
                    synced = result.synced,
                    progressMs = 0L,
                    currentLineIndex = initialLineIndex(result.lines),
                    lines = result.lines,
                    plainLyrics = result.plainLyrics,
                    errorMessage = null,
                )
                val latestMedia = activeMediaSnapshot
                if (mediaKey != null && latestMedia != null && mediaLookupKey(latestMedia) == mediaKey) {
                    next = applyMediaSnapshot(next, latestMedia)
                }
                clearLookupError(requestKey)
                stateStore.updateLyrics(next)
                debugLog {
                    "lookup success key=$requestKey provider=${next.provider} synced=${next.synced} lines=${next.lines.size} progress=${next.progressMs} currentLineIndex=${next.currentLineIndex} summary='${next.sourceSummary}'"
                }
                stateStore.updateStatus {
                    it.copy(
                        statusLabel = if (next.synced) {
                            "Lyrics loaded for ${next.trackTitle}."
                        } else {
                            "Track resolved without timed lyrics."
                        },
                        lastError = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!shouldCommitLookup(generation, requestKey, mediaKey)) {
                    return@launch
                }
                markLookupError(requestKey)
                errorLog(
                    message = "lookup failure key=$requestKey title='$title' artist='$artist' message='${error.message ?: "Lyrics lookup failed."}'",
                    error = error,
                )
                var failureSnapshot = buildLookupFailureSnapshot(
                    current = stateStore.current().lyrics,
                    errorMessage = error.message ?: "Lyrics lookup failed.",
                    preservedSnapshot = preservedSnapshot,
                )
                val latestMediaForFailure = activeMediaSnapshot
                if (preservedSnapshot != null && mediaKey != null && latestMediaForFailure != null && mediaLookupKey(latestMediaForFailure) == mediaKey) {
                    failureSnapshot = applyMediaSnapshot(failureSnapshot, latestMediaForFailure)
                }
                stateStore.updateLyrics(failureSnapshot)
                stateStore.updateStatus {
                    it.copy(
                        statusLabel = if (preservedSnapshot != null) {
                            "Lyrics refresh failed for $title / $artist. Keeping current lyrics."
                        } else {
                            "Lyrics lookup failed for $title / $artist."
                        },
                        lastError = error.message,
                    )
                }
            } finally {
                if (lookupGeneration.get() == generation) {
                    lookupInFlightKey = null
                }
            }
        }
    }

    private fun shouldLookupForCurrentTrack(
        current: LyricsSnapshot,
        lookupKey: String,
        matchesLoaded: Boolean,
    ): LookupDecision {
        if (lookupInFlightKey == lookupKey) {
            return LookupDecision(
                shouldLookup = false,
                reason = "skip_in_flight",
            )
        }
        if (current.sessionState == LyricsSessionState.ERROR) {
            val canRetry = canRetryFailedLookup(lookupKey)
            return LookupDecision(
                shouldLookup = canRetry,
                reason = if (canRetry) "retry_after_error" else "skip_recent_error",
            )
        }
        if (lookupKey == lastResolvedLookupKey) {
            return LookupDecision(
                shouldLookup = false,
                reason = "skip_already_resolved",
            )
        }
        if (lookupKey != activeMediaLookupKey) {
            return LookupDecision(
                shouldLookup = true,
                reason = "new_media_key",
            )
        }
        if (!matchesLoaded) {
            return LookupDecision(
                shouldLookup = true,
                reason = "loaded_track_mismatch",
            )
        }
        if (current.lines.isNotEmpty() || current.plainLyrics.isNotBlank()) {
            return LookupDecision(
                shouldLookup = false,
                reason = "skip_loaded_lyrics_present",
            )
        }
        return LookupDecision(
            shouldLookup = current.sessionState == LyricsSessionState.IDLE,
            reason = if (current.sessionState == LyricsSessionState.IDLE) {
                "idle_without_lyrics"
            } else {
                "skip_waiting_for_existing_result"
            },
        )
    }

    private fun cancelLookup() {
        lookupGeneration.incrementAndGet()
        lookupInFlightKey = null
        lookupJob?.cancel()
    }

    private fun shouldCommitLookup(
        generation: Long,
        requestKey: String,
        mediaKey: String?,
    ): Boolean {
        if (lookupGeneration.get() != generation) return false
        if (lookupInFlightKey != requestKey) return false
        if (mediaKey == null) return true
        if (activeMediaLookupKey != mediaKey) return false
        val latestMedia = activeMediaSnapshot ?: return false
        return mediaLookupKey(latestMedia) == mediaKey
    }

    private fun markLookupError(requestKey: String) {
        lastLookupErrorKey = requestKey
        lastLookupErrorAtMs = SystemClock.elapsedRealtime()
    }

    private fun clearLookupError(requestKey: String) {
        if (lastLookupErrorKey == requestKey) {
            lastLookupErrorKey = null
            lastLookupErrorAtMs = 0L
        }
    }

    private fun canRetryFailedLookup(requestKey: String): Boolean {
        if (lastLookupErrorKey != requestKey) return true
        return SystemClock.elapsedRealtime() - lastLookupErrorAtMs >= LOOKUP_ERROR_RETRY_MS
    }

    private fun initialLineIndex(@Suppress("UNUSED_PARAMETER") lines: List<LyricsLine>): Int = -1

    private fun applyMediaSnapshot(
        base: LyricsSnapshot,
        snapshot: MediaPlaybackSnapshot,
    ): LyricsSnapshot {
        val progressMs = snapshot.positionMs.coerceAtLeast(0L)
        val trackingSummary = "Tracking ${sourceLabel(snapshot.packageName)} via Android media session."
        val keepsLoading =
            base.sessionState == LyricsSessionState.LOADING &&
                base.lines.isEmpty() &&
                base.plainLyrics.isBlank() &&
                base.errorMessage == null
        return base.copy(
            sessionState = when {
                keepsLoading -> LyricsSessionState.LOADING
                snapshot.isPlaying -> LyricsSessionState.PLAYING
                else -> LyricsSessionState.READY
            },
            progressMs = progressMs,
            currentLineIndex = indexForProgress(base.lines, progressMs),
            sourceSummary = base.sourceSummary.ifBlank { trackingSummary },
            errorMessage = null,
        )
    }

    private fun indexForProgress(lines: List<LyricsLine>, progressMs: Long): Int {
        if (lines.isEmpty()) return -1
        var candidate = -1
        for (index in lines.indices) {
            if (lines[index].startTimeMs <= progressMs) {
                candidate = index
            } else {
                break
            }
        }
        return candidate
    }

    private fun normalized(value: String): String =
        value.trim().lowercase()

    private fun sourceLabel(packageName: String?): String = when (packageName) {
        "com.spotify.music" -> "Spotify"
        null, "" -> "media"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun debugLog(message: () -> String) {
        runCatching { Log.d(TAG, message()) }
    }

    private fun errorLog(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }

    private data class LookupDecision(
        val shouldLookup: Boolean,
        val reason: String,
    )

    private companion object {
        private const val TAG = "RokidLyricsRuntime"
        private const val LOOKUP_ERROR_RETRY_MS = 15_000L
    }
}

internal fun shouldPreserveVisibleLyrics(
    current: LyricsSnapshot,
    request: LyricsLookupRequest,
): Boolean =
    hasVisibleLyrics(current) &&
        current.trackTitle.normalizedLookupValue() == request.title.normalizedLookupValue() &&
        current.artistName.normalizedLookupValue() == request.artist.normalizedLookupValue()

internal fun buildLookupLoadingSnapshot(
    current: LyricsSnapshot,
    request: LyricsLookupRequest,
    fromMedia: Boolean,
    mediaSourceLabel: String,
    preserveVisibleLyrics: Boolean,
): LyricsSnapshot {
    val loadingSummary = if (fromMedia) {
        "Resolving lyrics for $mediaSourceLabel..."
    } else {
        "Querying lyrics providers..."
    }
    if (preserveVisibleLyrics) {
        return current.copy(
            sessionState = LyricsSessionState.READY,
            trackTitle = request.title,
            artistName = request.artist,
            albumName = request.album,
            durationSeconds = request.durationSeconds,
            errorMessage = null,
            sourceSummary = current.sourceSummary.ifBlank { loadingSummary },
        )
    }
    return current.copy(
        sessionState = LyricsSessionState.LOADING,
        trackTitle = request.title,
        artistName = request.artist,
        albumName = request.album,
        durationSeconds = request.durationSeconds,
        provider = "",
        errorMessage = null,
        synced = false,
        lines = emptyList(),
        plainLyrics = "",
        currentLineIndex = -1,
        progressMs = 0L,
        sourceSummary = loadingSummary,
    )
}

internal fun buildLookupFailureSnapshot(
    current: LyricsSnapshot,
    errorMessage: String,
    preservedSnapshot: LyricsSnapshot?,
): LyricsSnapshot {
    if (preservedSnapshot != null && hasVisibleLyrics(preservedSnapshot)) {
        return preservedSnapshot.copy(
            sessionState = LyricsSessionState.READY,
            errorMessage = null,
        )
    }
    return current.copy(
        sessionState = LyricsSessionState.ERROR,
        errorMessage = errorMessage,
        sourceSummary = "Lyrics providers did not return a usable lyrics payload.",
        lines = emptyList(),
        plainLyrics = "",
        progressMs = 0L,
        currentLineIndex = -1,
        synced = false,
    )
}

internal fun hasVisibleLyrics(snapshot: LyricsSnapshot): Boolean =
    snapshot.lines.isNotEmpty() || snapshot.plainLyrics.isNotBlank()

internal fun String.normalizedLookupValue(): String =
    trim().lowercase()

internal fun lyricsLookupRequestKey(request: LyricsLookupRequest): String =
    listOf(
        request.title.normalizedLookupValue(),
        request.artist.normalizedLookupValue(),
        request.album.normalizedLookupValue(),
        request.durationSeconds?.toString().orEmpty(),
        request.spotifyTrackId?.trim().orEmpty(),
    ).joinToString("|")

internal fun mediaLookupKey(snapshot: MediaPlaybackSnapshot): String =
    listOf(
        snapshot.packageName,
        snapshot.title.normalizedLookupValue(),
        snapshot.artist.normalizedLookupValue(),
        snapshot.album.normalizedLookupValue(),
        snapshot.durationMs?.div(1000L)?.toString().orEmpty(),
        snapshot.spotifyTrackId?.trim().orEmpty(),
    ).joinToString("|")
