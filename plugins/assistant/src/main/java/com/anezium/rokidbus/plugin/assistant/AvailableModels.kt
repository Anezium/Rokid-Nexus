package com.anezium.rokidbus.plugin.assistant

import org.json.JSONArray
import org.json.JSONObject

internal data class AvailableModel(
    val id: String,
    val title: String = id,
    val vision: Boolean? = null,
    val efforts: List<String>? = null,
    val defaultEffort: String? = null,
) {
    fun effort(requested: String): String? = efforts?.let { allowed ->
        requested.takeIf(allowed::contains) ?: defaultEffort?.takeIf(allowed::contains) ?: allowed.firstOrNull()
    }
}

internal object AvailableModels {
    const val MAX_MODELS = 2_000
    const val MAX_BYTES = 2 * 1024 * 1024
    private val modelId = Regex("[A-Za-z0-9][A-Za-z0-9._:/+@-]{0,255}")

    fun parse(payload: String, chatGpt: Boolean): List<AvailableModel> {
        val root = JSONObject(payload)
        val entries = root.getJSONArray(if (chatGpt) "models" else "data")
        require(entries.length() <= MAX_MODELS) { "Model catalog is too large." }
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString(if (chatGpt) "slug" else "id").trim()
            if (!modelId.matches(id)) return@mapNotNull null
            if (chatGpt && (entry.optString("visibility", "list") != "list" ||
                    !entry.optBoolean("show_in_picker", true))) return@mapNotNull null
            val architecture = entry.optJSONObject("architecture")
            val inputs = entry.optJSONArray("input_modalities") ?: architecture?.optJSONArray("input_modalities")
            val outputs = entry.optJSONArray("output_modalities") ?: architecture?.optJSONArray("output_modalities")
            if (inputs != null && "text" !in inputs.strings()) return@mapNotNull null
            if (outputs != null && "text" !in outputs.strings()) return@mapNotNull null
            // OpenAI's API list also contains image, audio, embedding and moderation models.
            if (!chatGpt && inputs == null && outputs == null && nonChatModel(id)) return@mapNotNull null
            val reasoning = entry.optJSONArray("supported_reasoning_levels")
            val effort = entry.optJSONObject("effort")
            val efforts = if (reasoning != null) {
                (0 until reasoning.length()).mapNotNull {
                    reasoning.optJSONObject(it)?.optString("effort")?.takeIf(String::isNotBlank)
                }
            } else effort?.optJSONArray("supported_levels")?.strings()
            AvailableModel(
                id = id,
                title = entry.optString(if (chatGpt) "display_name" else "name")
                    .trim().takeIf { it.isNotEmpty() && it != "null" }?.take(160) ?: id,
                vision = inputs?.strings()?.contains("image"),
                efforts = efforts?.filter { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) }?.distinct(),
                defaultEffort = (if (chatGpt) entry.optString("default_reasoning_level")
                    else effort?.optString("default_level"))?.takeIf { it.isNotBlank() && it != "null" },
            )
        }.distinctBy(AvailableModel::id)
    }

    private fun nonChatModel(id: String): Boolean = listOf(
        "embedding", "moderation", "whisper", "tts", "dall-e", "gpt-image", "sora", "realtime",
        "transcribe", "audio", "search-api",
    ).any { it in id.lowercase() }

    fun encode(models: List<AvailableModel>): JSONArray = JSONArray().apply {
        models.forEach { model ->
            put(JSONObject().put("id", model.id).put("name", model.title).apply {
                model.vision?.let { put("input_modalities", JSONArray(if (it) listOf("text", "image") else listOf("text"))) }
                model.efforts?.let {
                    put("effort", JSONObject().put("supported_levels", JSONArray(it)).put("default_level", model.defaultEffort))
                }
            })
        }
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { opt(it) as? String }
}
