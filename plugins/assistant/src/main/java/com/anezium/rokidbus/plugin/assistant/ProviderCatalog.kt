package com.anezium.rokidbus.plugin.assistant

data class SuggestedModel(
    val id: String,
    val title: String,
    val caption: String,
    val vision: Boolean,
)

enum class ProviderBackend {
    OPENAI_COMPAT,
    HERMES,
}

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<SuggestedModel>,
    val supportedEfforts: List<String>,
    val extraHeaders: Map<String, String>,
    val keyHint: String,
    val backend: ProviderBackend = ProviderBackend.OPENAI_COMPAT,
    // Models no longer suggested that a saved setting may still name; they keep their photo support.
    val retiredVisionModels: Set<String> = emptySet(),
)

object ProviderCatalog {
    val openAi = ProviderPreset(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-6-luna",
        suggestedModels = listOf(
            SuggestedModel(
                id = "gpt-6-luna",
                title = "GPT-6-Luna",
                caption = "Fastest, and cheapest to run",
                vision = true,
            ),
            SuggestedModel(
                id = "gpt-6.1-sol",
                title = "GPT-6.1-Sol",
                caption = "Everyday workhorse for most questions",
                vision = true,
            ),
            SuggestedModel(
                id = "gpt-6-astra",
                title = "GPT-6-Astra",
                caption = "Smartest, costs the most per question",
                vision = true,
            ),
        ),
        supportedEfforts = emptyList(),
        extraHeaders = emptyMap(),
        keyHint = "sk-...",
        retiredVisionModels = setOf("gpt-4o-mini", "gpt-4o"),
    )

    val openRouter = ProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openrouter/auto",
        suggestedModels = listOf(
            SuggestedModel(
                id = "openrouter/auto",
                title = "Auto",
                caption = "Picks a model per question",
                vision = false,
            ),
        ),
        supportedEfforts = listOf("low", "medium", "high"),
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://github.com/Anezium/Rokid-Nexus",
            "X-Title" to "Rokid Nexus Assistant",
        ),
        keyHint = "sk-or-v1-...",
    )

    val minimax = ProviderPreset(
        id = "minimax",
        displayName = "MiniMax",
        defaultBaseUrl = "https://api.minimax.io/v1",
        defaultModel = "MiniMax-M3",
        suggestedModels = listOf(
            SuggestedModel(
                id = "MiniMax-M3",
                title = "MiniMax M3",
                caption = "Flagship, and it sees photos",
                vision = true,
            ),
        ),
        supportedEfforts = emptyList(),
        extraHeaders = emptyMap(),
        keyHint = "sk-cp-... (Coding Plan key)",
    )

    val deepSeek = ProviderPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        suggestedModels = listOf(
            SuggestedModel(
                id = "deepseek-chat",
                title = "DeepSeek Chat",
                caption = "Fast general answers",
                vision = false,
            ),
            SuggestedModel(
                id = "deepseek-reasoner",
                title = "DeepSeek Reasoner",
                caption = "Thinks before answering",
                vision = false,
            ),
        ),
        supportedEfforts = emptyList(),
        extraHeaders = emptyMap(),
        keyHint = "sk-...",
    )

    val zai = ProviderPreset(
        id = "zai",
        displayName = "GLM (Z.ai)",
        defaultBaseUrl = "https://api.z.ai/api/paas/v4",
        defaultModel = "glm-4.6",
        suggestedModels = listOf(
            SuggestedModel(
                id = "glm-4.6",
                title = "GLM 4.6",
                caption = "Zhipu's flagship",
                vision = false,
            ),
        ),
        supportedEfforts = emptyList(),
        extraHeaders = emptyMap(),
        keyHint = "Z.ai API key",
    )

    val hermes = ProviderPreset(
        id = "hermes",
        displayName = "Hermes",
        defaultBaseUrl = "",
        defaultModel = "hermes-agent",
        suggestedModels = emptyList(),
        supportedEfforts = emptyList(),
        extraHeaders = emptyMap(),
        keyHint = "Hermes API server key",
        backend = ProviderBackend.HERMES,
    )

    val custom = ProviderPreset(
        id = "custom",
        displayName = "Custom",
        defaultBaseUrl = "",
        defaultModel = "",
        suggestedModels = emptyList(),
        supportedEfforts = emptyList(),
        extraHeaders = emptyMap(),
        keyHint = "API key",
    )

    val presets: List<ProviderPreset> = listOf(
        openAi,
        openRouter,
        minimax,
        deepSeek,
        zai,
        hermes,
        custom,
    )

    private val byId = presets.associateBy(ProviderPreset::id)

    init {
        require(byId.size == presets.size) { "Provider preset IDs must be unique." }
    }

    fun preset(id: String): ProviderPreset? = byId[id]

    fun requirePreset(id: String): ProviderPreset =
        preset(id) ?: throw IllegalArgumentException("Unknown provider preset: $id")

    fun supportsVision(
        preset: ProviderPreset,
        model: String,
        visionOverride: Boolean?,
        backend: ProviderBackend = preset.backend,
    ): Boolean = visionOverride
        ?: preset.suggestedModels
            .firstOrNull { suggested -> suggested.id == model.trim() }
            ?.vision
        ?: model.trim().takeIf { it in preset.retiredVisionModels }?.let { true }
        ?: (backend == ProviderBackend.HERMES)
}
