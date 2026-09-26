package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusInkProblem
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.plugin.PluginCapability
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderInkPageToolTest {
    @Test
    fun `tool is declared only for an active granted supported session`() {
        val capabilities = FakeInkPageToolCapabilities()
        var session = AssistantToolSessionContext(
            active = true,
            grantedCapabilities = setOf(PluginCapability.INK_SURFACE.wireValue),
        )
        val registry = AssistantToolRegistry(
            definitions = listOf(RenderInkPageTool(InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES })),
            sessionContext = { session },
        )

        assertEquals(
            listOf(RENDER_INK_PAGE_TOOL_NAME),
            registry.availableDefinitions(TOOLS_SUPPORTED).map { it.name },
        )

        capabilities.supported = false
        assertTrue(registry.availableDefinitions(TOOLS_SUPPORTED).isEmpty())
        capabilities.supported = true
        session = session.copy(grantedCapabilities = emptySet())
        assertTrue(registry.availableDefinitions(TOOLS_SUPPORTED).isEmpty())
        session = session.copy(
            active = false,
            grantedCapabilities = setOf(PluginCapability.INK_SURFACE.wireValue),
        )
        assertTrue(registry.availableDefinitions(TOOLS_SUPPORTED).isEmpty())
    }

    @Test
    fun `the Visual answers setting decides which Ink tools the model is offered`() {
        var mode = AssistantVisualAnswers.DEFAULT
        val runtime = InkPageToolRuntime(FakeInkPageToolCapabilities()) { mode }
        val registry = AssistantToolRegistry(
            definitions = listOf(
                RenderTemplateTool(runtime, InkTemplateLoader { "" }),
                RenderInkPageTool(runtime),
            ),
            sessionContext = {
                AssistantToolSessionContext(
                    active = true,
                    grantedCapabilities = setOf(PluginCapability.INK_SURFACE.wireValue),
                )
            },
        )
        fun offered() = registry.availableDefinitions(TOOLS_SUPPORTED).map { it.name }

        // Templates only unless the wearer chose otherwise: the model lays out no page itself.
        assertEquals(AssistantVisualAnswers.TEMPLATES, mode)
        assertEquals(listOf(RENDER_TEMPLATE_TOOL_NAME), offered())
        mode = AssistantVisualAnswers.FREE_PAGES
        assertEquals(listOf(RENDER_TEMPLATE_TOOL_NAME, RENDER_INK_PAGE_TOOL_NAME), offered())
        mode = AssistantVisualAnswers.OFF
        assertTrue(offered().isEmpty())

        // Nothing stored, or a value this version does not know, reads as the default.
        assertEquals(AssistantVisualAnswers.TEMPLATES, AssistantVisualAnswers.fromWire(null))
        assertEquals(AssistantVisualAnswers.TEMPLATES, AssistantVisualAnswers.fromWire("hologram"))
    }

    @Test
    fun `schema and validator enforce page title and data types`() = runTest {
        val capabilities = FakeInkPageToolCapabilities()
        val tool = RenderInkPageTool(InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES })
        val schema = tool.parametersSchema.toJsonObject()
        val properties = schema.getJSONObject("properties")

        assertEquals("Drawing the card…", tool.progressLabel)
        assertTrue(tool.retiresProgressOnSuccess)
        // Strict providers require every property; optionals are nullable and
        // free-form data travels as a JSON-encoded string.
        assertEquals("object", schema.getString("type"))
        assertEquals("string", properties.getJSONObject("page").getString("type"))
        assertEquals(
            listOf("string", "null"),
            List(properties.getJSONObject("title").getJSONArray("type").length()) { index ->
                properties.getJSONObject("title").getJSONArray("type").getString(index)
            },
        )
        assertEquals(
            listOf("string", "null"),
            List(properties.getJSONObject("data").getJSONArray("type").length()) { index ->
                properties.getJSONObject("data").getJSONArray("type").getString(index)
            },
        )
        assertEquals(
            listOf("page", "title", "data"),
            List(schema.getJSONArray("required").length()) { index ->
                schema.getJSONArray("required").getString(index)
            },
        )
        assertFalse(schema.getBoolean("additionalProperties"))

        val phase = registry(capabilities).newExecutionPhase(TOOLS_SUPPORTED)
        val invalidCalls = listOf(
            "{}",
            """{"page":42}""",
            """{"page":"   "}""",
            """{"page":"<page />","title":42}""",
            """{"page":"<page />","data":[]}""",
            """{"page":"<page />","extra":true}""",
        )
        invalidCalls.forEachIndexed { index, arguments ->
            assertEquals(
                AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL),
                phase.execute(
                    AssistantToolCall("invalid-$index", RENDER_INK_PAGE_TOOL_NAME, arguments),
                ),
            )
        }
        assertEquals(0, capabilities.showCalls)

        val result = phase.execute(
            AssistantToolCall(
                "valid",
                RENDER_INK_PAGE_TOOL_NAME,
                """{"page":"<page><text>{{ value }}</text></page>","title":"Metric","data":{"value":7}}""",
            ),
        )

        assertEquals(AssistantToolResult.Json("{\"status\":\"shown\"}"), result)
        assertEquals("<page><text>{{ value }}</text></page>", capabilities.shownPage)
        assertEquals(7, capabilities.shownData?.getInt("value"))
    }

    @Test
    fun `typed ink problems are returned to the model`() = runTest {
        val capabilities = FakeInkPageToolCapabilities().apply {
            showResult = InkPageShowResult.Rejected(
                listOf(
                    NexusInkProblem(
                        code = "INK_UNKNOWN_COMPONENT",
                        message = "Unsupported component sparkline",
                        line = 4,
                        column = 9,
                        feature = "sparkline",
                    ),
                ),
            )
        }
        val phase = registry(capabilities).newExecutionPhase(TOOLS_SUPPORTED)

        val result = phase.execute(
            AssistantToolCall(
                "invalid-page",
                RENDER_INK_PAGE_TOOL_NAME,
                """{"page":"<page><sparkline /></page>"}""",
            ),
        )

        assertTrue(result is AssistantToolResult.Error)
        result as AssistantToolResult.Error
        assertEquals(TOOL_ERROR_INVALID_INK_PAGE, result.code)
        val problem = JSONObject(result.detailsJson.orEmpty())
            .getJSONArray("problems")
            .getJSONObject(0)
        assertEquals("INK_UNKNOWN_COMPONENT", problem.getString("code"))
        assertEquals("Unsupported component sparkline", problem.getString("message"))
        assertEquals(4, problem.getInt("line"))
        assertEquals(9, problem.getInt("column"))
        assertEquals("sparkline", problem.getString("feature"))
        assertEquals(0, capabilities.markShownCalls)
    }

    @Test
    fun `side effecting render executes once per phase`() = runTest {
        val capabilities = FakeInkPageToolCapabilities()
        val phase = registry(capabilities).newExecutionPhase(TOOLS_SUPPORTED)

        val first = phase.execute(
            AssistantToolCall("render-1", RENDER_INK_PAGE_TOOL_NAME, PAGE_ARGUMENTS),
        )
        val second = phase.execute(
            AssistantToolCall("render-2", RENDER_INK_PAGE_TOOL_NAME, PAGE_ARGUMENTS),
        )

        assertEquals(AssistantToolResult.Json("{\"status\":\"shown\"}"), first)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED), second)
        assertEquals(1, capabilities.showCalls)
        assertEquals(1, capabilities.markShownCalls)
    }

    @Test
    fun `description carries the compact v1 authoring contract`() {
        assertTrue(RENDER_INK_PAGE_TOOL_DESCRIPTION.toByteArray().size < 2_048)
        assertTrue(RENDER_INK_PAGE_TOOL_DESCRIPTION.contains("display:flex"))
        assertTrue(RENDER_INK_PAGE_TOOL_DESCRIPTION.contains("No <script setup>"))
        assertTrue(RENDER_INK_PAGE_TOOL_DESCRIPTION.contains("Monochrome only"))
        assertTrue(RENDER_INK_PAGE_TOOL_DESCRIPTION.contains("<chart"))
        assertTrue(RENDER_INK_PAGE_TOOL_DESCRIPTION.contains("Prefer render_template"))
    }

    @Test
    fun `send failures use stable tool errors`() {
        assertEquals(
            TOOL_ERROR_NOT_AUTHORIZED,
            inkShowStartToolErrorCode(NexusSdkResult.CAPABILITY_NOT_GRANTED),
        )
        assertEquals(
            TOOL_ERROR_INK_SURFACE_UNAVAILABLE,
            inkShowStartToolErrorCode(NexusSdkResult.CAPABILITY_NOT_AVAILABLE),
        )
        assertEquals(
            TOOL_ERROR_SURFACE_BUSY,
            inkShowStartToolErrorCode(NexusSdkResult.SURFACE_BUSY),
        )
        assertEquals(
            TOOL_ERROR_CANCELLED,
            inkShowStartToolErrorCode(NexusSdkResult.NOT_REGISTERED),
        )
        assertEquals(
            TOOL_ERROR_INK_RENDER_FAILED,
            inkShowStartToolErrorCode(NexusSdkResult.INVALID_PAYLOAD),
        )
    }

    private fun registry(capabilities: FakeInkPageToolCapabilities): AssistantToolRegistry =
        AssistantToolRegistry(
            definitions = listOf(RenderInkPageTool(InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES })),
            sessionContext = {
                AssistantToolSessionContext(
                    active = true,
                    grantedCapabilities = setOf(PluginCapability.INK_SURFACE.wireValue),
                )
            },
        )

    private class FakeInkPageToolCapabilities : InkPageToolCapabilities {
        var supported = true
        var sessionActive = true
        var showResult: InkPageShowResult = InkPageShowResult.Shown
        var showCalls = 0
        var markShownCalls = 0
        var shownPage: String? = null
        var shownData: JSONObject? = null

        override fun currentSession(): InkPageToolSession =
            InkPageToolSession("request", 1L)

        override fun isSessionActive(session: InkPageToolSession): Boolean = sessionActive

        override fun supportsInkSurface(): Boolean = supported

        override suspend fun showInkPage(
            session: InkPageToolSession,
            page: String,
            data: JSONObject?,
        ): InkPageShowResult {
            showCalls += 1
            shownPage = page
            shownData = data
            return showResult
        }

        override fun markInkShown(session: InkPageToolSession): Boolean {
            markShownCalls += 1
            return sessionActive
        }
    }

    private companion object {
        val TOOLS_SUPPORTED = AssistantProviderFeatures(
            supportsTools = true,
            supportsVision = false,
        )
        const val PAGE_ARGUMENTS = "{\"page\":\"<page><text>Ready</text></page>\"}"
    }
}
