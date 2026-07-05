package com.anezium.rokidbus.lyrics.lyrics

import android.util.Log
import kotlinx.coroutines.CancellationException

class CompositeLyricsProvider(
    private val providers: List<LyricsProvider>,
) {
    suspend fun fetch(request: LyricsLookupRequest): CompositeLyricsFetchResult {
        val disabledProviders = mutableListOf<String>()
        val attemptDetails = mutableListOf<String>()
        val attemptSummaries = mutableListOf<ProviderAttemptSummary>()

        for (provider in providers) {
            debugLog {
                "attempt provider=${provider.providerName} title='${request.title}' artist='${request.artist}' album='${request.album}' duration=${request.durationSeconds}"
            }
            val attempt = try {
                provider.fetch(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                attemptDetails += "${provider.providerName}: ${error.message ?: "unknown error"}"
                errorLog("provider=${provider.providerName} failed: ${error.message ?: "unknown error"}", error)
                attemptSummaries += ProviderAttemptSummary(
                    provider = provider.providerName,
                    outcome = ProviderAttemptOutcome.ERROR,
                    detail = error.message ?: "unknown error",
                )
                continue
            }

            when (attempt) {
                is LyricsProviderAttempt.Success -> {
                    debugLog {
                        "provider=${attempt.result.provider} success synced=${attempt.result.synced} lines=${attempt.result.lines.size} summary='${attempt.result.sourceSummary}'"
                    }
                    attemptSummaries += ProviderAttemptSummary(
                        provider = attempt.result.provider,
                        outcome = ProviderAttemptOutcome.SUCCESS,
                        detail = attempt.result.sourceSummary,
                    )
                    val sourceSummary = if (attemptDetails.isEmpty()) {
                        attempt.result.sourceSummary
                    } else {
                        "${attempt.result.sourceSummary} Fallback context: ${attemptDetails.joinToString(" | ")}"
                    }
                    return CompositeLyricsFetchResult(
                        result = attempt.result.copy(sourceSummary = sourceSummary),
                        attemptSummaries = attemptSummaries.toList(),
                    )
                }
                is LyricsProviderAttempt.NoMatch -> {
                    debugLog { "provider=${attempt.provider} no-match reason='${attempt.reason}'" }
                    attemptSummaries += ProviderAttemptSummary(
                        provider = attempt.provider,
                        outcome = ProviderAttemptOutcome.NO_MATCH,
                        detail = attempt.reason,
                    )
                    attemptDetails += "${attempt.provider}: ${attempt.reason}"
                    continue
                }
                is LyricsProviderAttempt.Disabled -> {
                    debugLog { "provider=${attempt.provider} disabled reason='${attempt.reason}'" }
                    attemptSummaries += ProviderAttemptSummary(
                        provider = attempt.provider,
                        outcome = ProviderAttemptOutcome.DISABLED,
                        detail = attempt.reason,
                    )
                    disabledProviders += attempt.provider
                    attemptDetails += "${attempt.provider}: ${attempt.reason}"
                }
            }
        }

        val activeProviders = providers.map { it.providerName } - disabledProviders.toSet()
        val providerSummary = activeProviders.ifEmpty { providers.map { it.providerName } }.joinToString("+")
        val sourceSummary = when {
            disabledProviders.size == providers.size -> buildString {
                append("No synced lyrics provider is configured yet.")
                if (attemptDetails.isNotEmpty()) {
                    append(' ')
                    append(attemptDetails.joinToString(" | "))
                }
            }
            attemptDetails.isNotEmpty() -> "No synced lyrics found on $providerSummary. ${attemptDetails.joinToString(" | ")}"
            else -> "No synced lyrics found on $providerSummary."
        }
        return CompositeLyricsFetchResult(
            result = LyricsFetchResult(
                trackTitle = request.title,
                artistName = request.artist,
                albumName = request.album,
                durationSeconds = request.durationSeconds,
                provider = providerSummary,
                synced = false,
                lines = emptyList(),
                plainLyrics = "",
                sourceSummary = sourceSummary,
            ),
            attemptSummaries = attemptSummaries.toList(),
        )
    }
}

data class CompositeLyricsFetchResult(
    val result: LyricsFetchResult,
    val attemptSummaries: List<ProviderAttemptSummary>,
)

private const val TAG = "RokidLyricsProviders"

private fun debugLog(message: () -> String) {
    runCatching { Log.d(TAG, message()) }
}

private fun errorLog(message: String, error: Throwable) {
    runCatching { Log.e(TAG, message, error) }
}

data class ProviderAttemptSummary(
    val provider: String,
    val outcome: ProviderAttemptOutcome,
    val detail: String,
)

enum class ProviderAttemptOutcome {
    SUCCESS,
    NO_MATCH,
    DISABLED,
    ERROR,
}
