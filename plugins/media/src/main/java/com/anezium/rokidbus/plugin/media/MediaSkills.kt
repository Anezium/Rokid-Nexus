package com.anezium.rokidbus.plugin.media

import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillProviderChoice
import com.anezium.rokidbus.shared.skills.SkillProviderReference
import com.anezium.rokidbus.shared.skills.SkillLimits
import org.json.JSONObject

internal data class MediaSkillSession(
    val reference: String,
    val player: String,
    val state: String,
    val title: String? = null,
    val artist: String? = null,
)

internal enum class MediaPauseOutcome { PAUSED, ALREADY_PAUSED, ACCEPTED, STALE, SETUP_REQUIRED, UNSUPPORTED, DEADLINE, UNKNOWN }

internal interface MediaSkillAccess {
    fun hasAccess(): Boolean
    fun sessions(includeMetadata: Boolean): List<MediaSkillSession>
    fun pause(reference: String, remainingMs: () -> Long, cancelled: () -> Boolean): MediaPauseOutcome
}

internal sealed interface MediaSkillOutcome {
    data class Completed(val data: JSONObject) : MediaSkillOutcome
    data class Choice(val choices: List<SkillProviderChoice>) : MediaSkillOutcome
    data class Failed(val code: String) : MediaSkillOutcome
    data object Accepted : MediaSkillOutcome
    data object Unknown : MediaSkillOutcome
}

/** Selection is independent of the HUD's preferred-player heuristic and never retargets a ref. */
internal class MediaSkills(private val access: MediaSkillAccess) {
    fun run(operation: String, arguments: JSONObject, remainingMs: () -> Long, cancelled: () -> Boolean): MediaSkillOutcome {
        if (operation != NOW_PLAYING && operation != PAUSE) return MediaSkillOutcome.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION)
        if (cancelled() || remainingMs() <= 0) return MediaSkillOutcome.Failed(SkillErrorCodes.DEADLINE_EXCEEDED)
        val sessions = try {
            if (!access.hasAccess()) return MediaSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED)
            access.sessions(includeMetadata = operation == NOW_PLAYING)
        } catch (_: SecurityException) {
            return MediaSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED)
        } catch (_: Exception) {
            return MediaSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        }
        val reference = arguments.optString("session").takeIf(String::isNotBlank)
        val selected = if (reference != null) {
            sessions.find { it.reference == reference }
                ?: return MediaSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
        } else {
            val playing = sessions.filter { it.state == "playing" }
            val candidates = if (operation == NOW_PLAYING || playing.isNotEmpty()) playing else sessions
            if (candidates.size > SkillLimits.MAX_CHOICES) return MediaSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
            val labels = candidates.map { it.player.trim().ifBlank { "Media player" }.take(SkillLimits.MAX_CHOICE_LABEL_CHARS) }
            if (candidates.size > 1) return MediaSkillOutcome.Choice(candidates.mapIndexed { index, session ->
                val label = labels[index]
                val distinctLabel = if (labels.count { it == label } > 1)
                    label.take(SkillLimits.MAX_CHOICE_LABEL_CHARS - 4) + " #${index + 1}" else label
                SkillProviderChoice(distinctLabel, session.state, SkillProviderReference("media_session", session.reference))
            })
            candidates.singleOrNull()
        }
        if (selected == null) {
            return if (operation == NOW_PLAYING) MediaSkillOutcome.Completed(
                JSONObject().put("state", "no_session").put("focus", focus(null)),
            ) else MediaSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        }
        if (operation == NOW_PLAYING) return MediaSkillOutcome.Completed(
            JSONObject().put("session", selected.reference).put("player", selected.player.take(80))
                .put("state", selected.state).putOpt("title", selected.title?.take(240))
                .putOpt("artist", selected.artist?.take(160)).put("focus", focus(selected)),
        )
        return when (val outcome = access.pause(selected.reference, remainingMs, cancelled)) {
            MediaPauseOutcome.PAUSED, MediaPauseOutcome.ALREADY_PAUSED -> MediaSkillOutcome.Completed(
                JSONObject().put("state", "paused").put("already_paused", outcome == MediaPauseOutcome.ALREADY_PAUSED)
                    .put("session", selected.reference).put("focus", focus(selected.copy(state = "paused"))),
            )
            MediaPauseOutcome.ACCEPTED -> MediaSkillOutcome.Accepted
            MediaPauseOutcome.STALE -> MediaSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
            MediaPauseOutcome.SETUP_REQUIRED -> MediaSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED)
            MediaPauseOutcome.UNSUPPORTED -> MediaSkillOutcome.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION)
            MediaPauseOutcome.DEADLINE -> MediaSkillOutcome.Failed(SkillErrorCodes.DEADLINE_EXCEEDED)
            MediaPauseOutcome.UNKNOWN -> MediaSkillOutcome.Unknown
        }
    }

    private fun focus(session: MediaSkillSession?): JSONObject = JSONObject()
        .put("kind", "media_session_focus").put("state", session?.state ?: "no_session")
        .apply { session?.let { put("session", it.reference); put("player", it.player.take(80)) } }

    companion object {
        const val NOW_PLAYING = "get_now_playing"
        const val PAUSE = "pause"
    }
}
