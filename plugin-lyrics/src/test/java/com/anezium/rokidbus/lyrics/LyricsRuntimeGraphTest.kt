package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.lyrics.ProviderAttemptOutcome
import com.anezium.rokidbus.lyrics.lyrics.ProviderAttemptSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsRuntimeGraphTest {
    @Test
    fun providerStatusLabel_hidesIntermediateErrorWhenFallbackSucceeds() {
        val label = providerStatusLabel(
            summary = ProviderAttemptSummary(
                provider = "MUSIXMATCH",
                outcome = ProviderAttemptOutcome.ERROR,
                detail = "track.subtitle.get 404",
            ),
            resolvedProvider = "NETEASE",
        )

        assertEquals("Current track: fallback resolved by NETEASE.", label)
    }

    @Test
    fun providerStatusLabel_keepsErrorWhenNoProviderResolvedTheTrack() {
        val label = providerStatusLabel(
            summary = ProviderAttemptSummary(
                provider = "MUSIXMATCH",
                outcome = ProviderAttemptOutcome.ERROR,
                detail = "track.subtitle.get 404",
            ),
            resolvedProvider = null,
        )

        assertEquals("Last lookup failed: track.subtitle.get 404", label)
    }

    @Test
    fun providerStatusViewState_marksWinningProviderAndSoftensFallbackErrors() {
        val next = providerStatusViewState(
            current = ProviderSettingsViewState(
                musixmatchConfigured = true,
                musixmatchStatusLabel = "stale",
                neteaseStatusLabel = "stale",
            ),
            defaults = ProviderSettingsViewState(
                musixmatchConfigured = true,
                musixmatchStatusLabel = "Musixmatch is configured. Waiting for the next lyrics lookup.",
                neteaseStatusLabel = "Netease is enabled on this phone. No sign-in is required.",
            ),
            summaries = listOf(
                ProviderAttemptSummary(
                    provider = "MUSIXMATCH",
                    outcome = ProviderAttemptOutcome.ERROR,
                    detail = "track.subtitle.get 404",
                ),
                ProviderAttemptSummary(
                    provider = "NETEASE",
                    outcome = ProviderAttemptOutcome.SUCCESS,
                    detail = "Current track resolved.",
                ),
            ),
        )

        assertEquals("Current track: fallback resolved by NETEASE.", next.musixmatchStatusLabel)
        assertEquals("Current track: synced lyrics found.", next.neteaseStatusLabel)
    }
}
