package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun retiredOpenAiModelsKeepPhotoSupport() {
        assertTrue(ProviderCatalog.supportsVision(ProviderCatalog.openAi, "gpt-4o-mini", null))
        assertTrue(ProviderCatalog.supportsVision(ProviderCatalog.openAi, " gpt-4o ", null))
        assertFalse(ProviderCatalog.supportsVision(ProviderCatalog.openAi, "gpt-4o-mini", false))
        assertFalse(ProviderCatalog.supportsVision(ProviderCatalog.openRouter, "gpt-4o-mini", null))
    }

    @Test
    fun presetIdsAreUnique() {
        val ids = ProviderCatalog.presets.map(ProviderPreset::id)

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf("openai", "openrouter", "minimax", "deepseek", "zai", "hermes", "custom"),
            ids.toSet(),
        )
    }

    @Test
    fun providerCatalogOpenAiSuggestsTheGpt6Trio() {
        val openAi = ProviderCatalog.openAi

        assertEquals("gpt-6-luna", openAi.defaultModel)
        assertEquals(
            listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra"),
            openAi.suggestedModels.map(SuggestedModel::id),
        )
        assertTrue(openAi.suggestedModels.all(SuggestedModel::vision))
        assertTrue(openAi.suggestedModels.none { it.id.startsWith("gpt-4o") })
        assertEquals(emptyList<String>(), openAi.supportedEfforts)
        listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra").forEach { model ->
            assertTrue(
                model,
                ProviderCatalog.supportsVision(openAi, model, visionOverride = null),
            )
        }
    }

    @Test
    fun visionUsesSuggestionUntilExplicitlyOverridden() {
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.openAi,
                model = "gpt-6-luna",
                visionOverride = null,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.openAi,
                model = "my-free-text-model",
                visionOverride = null,
            ),
        )
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.custom,
                model = "my-free-text-model",
                visionOverride = true,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.minimax,
                model = "MiniMax-M3",
                visionOverride = false,
            ),
        )
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.hermes,
                model = "hermes-agent",
                visionOverride = null,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.custom,
                model = "hermes-agent",
                visionOverride = null,
            ),
        )
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.custom,
                model = "profile-name-can-vary",
                visionOverride = null,
                backend = ProviderBackend.HERMES,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.hermes,
                model = "hermes-agent",
                visionOverride = false,
            ),
        )
    }
}
