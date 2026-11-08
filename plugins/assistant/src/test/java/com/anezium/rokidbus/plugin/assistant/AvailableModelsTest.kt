package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailableModelsTest {
    @Test
    fun apiCatalogAcceptsFutureIdsWithoutInventingCapabilities() {
        val models = AvailableModels.parse("""{"data":[{"id":"future/model-v9"},{"id":"future/model-v9"},{"id":"bad id"}]}""", false)
        assertEquals(listOf("future/model-v9"), models.map { it.id })
        assertNull(models.single().vision)
        assertNull(models.single().efforts)
    }

    @Test
    fun codexCatalogKeepsVisibleModelsAndTheirAdvertisedEfforts() {
        val models = AvailableModels.parse("""{"models":[
            {"slug":"future-model","display_name":"Future Model","visibility":"list",
             "input_modalities":["text","image"],"default_reasoning_level":"max",
             "supported_reasoning_levels":[{"effort":"low"},{"effort":"max"}]},
            {"slug":"hidden","visibility":"hide"},
            {"slug":"not-in-picker","show_in_picker":false}
        ]}""", true)
        assertEquals(1, models.size)
        assertEquals("Future Model", models.single().title)
        assertEquals(true, models.single().vision)
        assertEquals(listOf("low", "max"), models.single().efforts)
        assertEquals("max", models.single().effort("none"))
    }

    @Test
    fun openRouterCatalogFiltersNonTextOutputsAndReadsVision() {
        val models = AvailableModels.parse("""{"data":[
            {"id":"lab/vision","name":"Vision","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
            {"id":"lab/draw","architecture":{"input_modalities":["text"],"output_modalities":["image"]}},
            {"id":"lab/transcribe","input_modalities":["audio"],"output_modalities":["text"]}
        ]}""", false)
        assertEquals(listOf("lab/vision"), models.map { it.id })
        assertEquals(true, models.single().vision)
    }

    @Test
    fun deepSeekCatalogCarriesTextOnlyAndModelSpecificReasoning() {
        val model = AvailableModels.parse("""{"data":[{"id":"deepseek-v4-pro","name":"Pro",
            "input_modalities":["text"],"output_modalities":["text"],
            "effort":{"supported_levels":["low","high","max"],"default_level":"high"}}]}""", false).single()
        assertEquals(false, model.vision)
        assertEquals("high", model.effort("medium"))
        assertEquals("max", model.effort("max"))
    }

    @Test
    fun apiCatalogDoesNotOfferOpenAiImageAudioOrEmbeddingEndpoints() {
        val models = AvailableModels.parse("""{"data":[{"id":"gpt-next"},{"id":"gpt-image-9"},
            {"id":"text-embedding-next"},{"id":"whisper-2"},{"id":"gpt-next-audio"}]}""", false)
        assertEquals(listOf("gpt-next"), models.map { it.id })
    }

    @Test
    fun cachedMetadataRoundTripsIncludingUnknownAndExplicitlyAbsentCapabilities() {
        val models = listOf(AvailableModel("unknown"), AvailableModel("text-only", vision = false, efforts = emptyList()),
            AvailableModel("vision", "Vision", true, listOf("max"), "max"))
        val payload = JSONObject().put("data", AvailableModels.encode(models)).toString()
        assertEquals(models, AvailableModels.parse(payload, false))
    }

    @Test
    fun malformedCatalogCannotMasqueradeAsAnEmptySuccessfulRefresh() {
        assertThrows(Exception::class.java) { AvailableModels.parse("<html>challenge</html>", true) }
        assertThrows(Exception::class.java) { AvailableModels.parse("{}", false) }
        assertTrue(AvailableModels.parse("""{"data":[]}""", false).isEmpty())
        assertFalse(AvailableModels.parse("""{"data":[{"id":"future"}]}""", false).isEmpty())
    }
}
