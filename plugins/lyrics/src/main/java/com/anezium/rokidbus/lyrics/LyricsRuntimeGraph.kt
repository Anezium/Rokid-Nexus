package com.anezium.rokidbus.lyrics

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.anezium.rokidbus.lyrics.lyrics.CompositeLyricsProvider
import com.anezium.rokidbus.lyrics.lyrics.LrcLibLyricsClient
import com.anezium.rokidbus.lyrics.lyrics.LyricsRuntimeEngine
import com.anezium.rokidbus.lyrics.lyrics.MusixmatchLyricsProvider
import com.anezium.rokidbus.lyrics.lyrics.NeteaseLyricsProvider
import com.anezium.rokidbus.lyrics.lyrics.ProviderAttemptOutcome
import com.anezium.rokidbus.lyrics.lyrics.ProviderAttemptSummary
import com.anezium.rokidbus.lyrics.lyrics.SpotifyLyricsProvider
import com.anezium.rokidbus.lyrics.media.MediaSessionMonitor
import com.anezium.rokidbus.lyrics.media.MediaNotificationListenerService
import com.anezium.rokidbus.lyrics.settings.LyricsProviderSettingsStore

object LyricsRuntimeGraph {
    val stateStore = LyricsPhoneStateStore()

    @Volatile private var initialized = false
    lateinit var lyricsRuntimeEngine: LyricsRuntimeEngine
        private set
    lateinit var mediaSessionMonitor: MediaSessionMonitor
        private set
    lateinit var providerSettingsStore: LyricsProviderSettingsStore
        private set
    private lateinit var appContext: Context
    private lateinit var spotifyLyricsProvider: SpotifyLyricsProvider
    private lateinit var musixmatchLyricsProvider: MusixmatchLyricsProvider

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        providerSettingsStore = LyricsProviderSettingsStore(appContext)
        spotifyLyricsProvider = SpotifyLyricsProvider(
            spDcSource = providerSettingsStore,
        )
        musixmatchLyricsProvider = MusixmatchLyricsProvider(
            credentialsSource = providerSettingsStore,
            sessionCacheSource = providerSettingsStore,
        )
        lyricsRuntimeEngine = LyricsRuntimeEngine(
            stateStore = stateStore,
            lyricsProvider = CompositeLyricsProvider(
                providers = listOf(
                    spotifyLyricsProvider,
                    musixmatchLyricsProvider,
                    NeteaseLyricsProvider(),
                    LrcLibLyricsClient(),
                ),
            ),
            onLookupStarted = { syncProviderSettingsState(preserveDynamicLabels = false) },
            onAttemptSummaries = ::onProviderAttemptSummaries,
        )
        mediaSessionMonitor = MediaSessionMonitor(
            context = appContext,
            onPlaybackSnapshot = lyricsRuntimeEngine::onMediaPlaybackSnapshot,
            onStatusChanged = { message ->
                lyricsRuntimeEngine.onMediaStatus(message)
                syncNotificationAccessFlag()
            },
        )
        initialized = true
        syncProviderSettingsState()
        syncNotificationAccessFlag()
    }

    @Synchronized
    fun start(context: Context) {
        initialize(context)
        syncProviderSettingsState()
        syncNotificationAccessFlag()
        mediaSessionMonitor.start()
    }

    fun refresh() {
        if (!initialized) return
        syncNotificationAccessFlag()
        mediaSessionMonitor.refresh()
    }

    fun togglePlayback() {
        if (initialized) mediaSessionMonitor.togglePlayback()
    }

    fun skipToNext() {
        if (initialized) mediaSessionMonitor.skipToNext()
    }

    fun skipToPrevious() {
        if (initialized) mediaSessionMonitor.skipToPrevious()
    }

    fun onSpotifyCookieChanged() {
        if (!initialized) return
        spotifyLyricsProvider.invalidateSession()
        syncProviderSettingsState(preserveDynamicLabels = false)
        lyricsRuntimeEngine.refresh()
    }

    fun notificationAccessEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        val target = ComponentName(appContext, MediaNotificationListenerService::class.java)
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == target
        }
    }

    @Synchronized
    fun destroy() {
        if (!initialized) return
        runCatching { mediaSessionMonitor.stop() }
        runCatching { lyricsRuntimeEngine.destroy() }
        initialized = false
    }

    private fun syncProviderSettingsState(preserveDynamicLabels: Boolean = true) {
        if (!initialized) return
        val defaults = defaultProviderSettingsViewState()
        stateStore.updateProviders { current ->
            if (!preserveDynamicLabels) {
                defaults
            } else {
                current.copy(
                    spotifyConfigured = defaults.spotifyConfigured,
                    spotifyStatusLabel = current.spotifyStatusLabel.takeUnless {
                        it.isBlank() ||
                            it.startsWith("Spotify is configured.") ||
                            it.startsWith("Paste your Spotify sp_dc cookie") ||
                            it.startsWith("Add your Spotify sp_dc cookie")
                    } ?: defaults.spotifyStatusLabel,
                    musixmatchConfigured = defaults.musixmatchConfigured,
                    musixmatchStatusLabel = current.musixmatchStatusLabel.takeUnless {
                        it.isBlank() ||
                            it.startsWith("Musixmatch is configured.") ||
                            it.startsWith("Musixmatch is not configured yet.") ||
                            it.startsWith("Sign in to Musixmatch")
                    } ?: defaults.musixmatchStatusLabel,
                    neteaseStatusLabel = current.neteaseStatusLabel.takeUnless {
                        it.isBlank() || it.startsWith("Netease is enabled.")
                    } ?: defaults.neteaseStatusLabel,
                )
            }
        }
    }

    private fun onProviderAttemptSummaries(summaries: List<ProviderAttemptSummary>) {
        if (!initialized || summaries.isEmpty()) return
        val defaults = defaultProviderSettingsViewState()
        stateStore.updateProviders { current ->
            providerStatusViewState(
                current = current,
                defaults = defaults,
                summaries = summaries,
            )
        }
    }

    private fun defaultProviderSettingsViewState(): ProviderSettingsViewState {
        val spotifyConfigured = providerSettingsStore.hasSpotifySpDc()
        val musixmatchConfigured = providerSettingsStore.hasMusixmatchCredentials()
        return ProviderSettingsViewState(
            spotifyConfigured = spotifyConfigured,
            spotifyStatusLabel = if (spotifyConfigured) {
                "Spotify is configured. Waiting for the next lyrics lookup."
            } else {
                "Add your Spotify sp_dc cookie to try Spotify's own synced lyrics first."
            },
            musixmatchConfigured = musixmatchConfigured,
            musixmatchStatusLabel = if (musixmatchConfigured) {
                "Musixmatch is configured. Waiting for the next lyrics lookup."
            } else {
                "Sign in to Musixmatch to try line-synced subtitles before LRCLIB."
            },
            neteaseStatusLabel = "Netease is enabled on this phone. No sign-in is required.",
        )
    }

    private fun syncNotificationAccessFlag() {
        if (!initialized) return
        val enabled = notificationAccessEnabled(appContext)
        stateStore.updateStatus { current ->
            current.copy(
                notificationAccessEnabled = enabled,
                statusLabel = current.statusLabel.ifBlank {
                    if (enabled) {
                        "Waiting for active media playback."
                    } else {
                        "Enable notification access so Lyrics can read media sessions."
                    }
                },
            )
        }
    }
}

internal fun providerStatusViewState(
    current: ProviderSettingsViewState,
    defaults: ProviderSettingsViewState,
    summaries: List<ProviderAttemptSummary>,
): ProviderSettingsViewState {
    val resolvedProvider = summaries.lastOrNull { it.outcome == ProviderAttemptOutcome.SUCCESS }?.provider
    var next = defaults.copy(
        spotifyConfigured = current.spotifyConfigured,
        musixmatchConfigured = current.musixmatchConfigured,
    )
    summaries.forEach { summary ->
        next = when (summary.provider) {
            "SPOTIFY" -> next.copy(
                spotifyStatusLabel = providerStatusLabel(summary, resolvedProvider),
            )
            "MUSIXMATCH" -> next.copy(
                musixmatchStatusLabel = providerStatusLabel(summary, resolvedProvider),
            )
            "NETEASE" -> next.copy(
                neteaseStatusLabel = providerStatusLabel(summary, resolvedProvider),
            )
            else -> next
        }
    }
    return next
}

internal fun providerStatusLabel(
    summary: ProviderAttemptSummary,
    resolvedProvider: String?,
): String {
    if (resolvedProvider != null && summary.provider != resolvedProvider) {
        return when (summary.outcome) {
            ProviderAttemptOutcome.DISABLED -> summary.detail
            ProviderAttemptOutcome.SUCCESS -> "Current track: synced lyrics found."
            ProviderAttemptOutcome.NO_MATCH,
            ProviderAttemptOutcome.ERROR,
            -> "Current track: fallback resolved by $resolvedProvider."
        }
    }
    return when (summary.outcome) {
        ProviderAttemptOutcome.SUCCESS -> "Current track: synced lyrics found."
        ProviderAttemptOutcome.NO_MATCH -> "Current track: no synced lyrics found."
        ProviderAttemptOutcome.DISABLED -> summary.detail
        ProviderAttemptOutcome.ERROR -> "Last lookup failed: ${summary.detail}"
    }
}
