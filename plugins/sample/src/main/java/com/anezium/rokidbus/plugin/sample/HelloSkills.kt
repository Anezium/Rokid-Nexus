package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONObject

/**
 * The Sample plugin's one skill operation, kept apart from the service so it is plain Kotlin a
 * test can call. A real provider does the same: the hub has already validated the arguments
 * against `res/raw/nexus_skills.json`, and the answer must match the declared output.
 */
internal object HelloSkills {
    const val COUNT_WORDS = "count_words"

    sealed interface Answer {
        data class Completed(val data: JSONObject) : Answer
        data class Failed(val code: String) : Answer
    }

    fun answer(operationId: String, arguments: JSONObject): Answer = when (operationId) {
        COUNT_WORDS -> {
            val text = arguments.optString("text")
            Answer.Completed(
                JSONObject()
                    .put("words", text.trim().split(Regex("\\s+")).count(String::isNotEmpty))
                    .put("characters", text.length),
            )
        }
        else -> Answer.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION)
    }
}
