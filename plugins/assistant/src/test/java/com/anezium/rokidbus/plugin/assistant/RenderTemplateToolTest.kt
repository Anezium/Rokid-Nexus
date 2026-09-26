package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.plugin.PluginCapability
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RenderTemplateToolTest {
    @Test
    fun `tool has the same active granted supported availability gate as freeform ink`() {
        val capabilities = FakeInkPageCapabilities()
        var session = grantedSession()
        val registry = registry(capabilities) { session }

        assertEquals(
            listOf(RENDER_TEMPLATE_TOOL_NAME),
            registry.availableDefinitions(TOOLS_SUPPORTED).map { it.name },
        )

        capabilities.supported = false
        assertTrue(registry.availableDefinitions(TOOLS_SUPPORTED).isEmpty())
        capabilities.supported = true
        session = session.copy(grantedCapabilities = emptySet())
        assertTrue(registry.availableDefinitions(TOOLS_SUPPORTED).isEmpty())
        session = grantedSession().copy(active = false)
        assertTrue(registry.availableDefinitions(TOOLS_SUPPORTED).isEmpty())
    }

    @Test
    fun `schema and compact description enumerate the complete template contract`() {
        val tool = tool(FakeInkPageCapabilities())
        val schema = tool.parametersSchema.toJsonObject()
        val properties = schema.getJSONObject("properties")
        val templateEnum = properties.getJSONObject("template").getJSONArray("enum")

        assertEquals("Drawing the card…", tool.progressLabel)
        assertTrue(tool.retiresProgressOnSuccess)
        // Strict providers require every property; optionals are nullable and
        // free-form data travels as a JSON-encoded string.
        assertEquals("object", schema.getString("type"))
        assertEquals("string", properties.getJSONObject("template").getString("type"))
        assertEquals(
            listOf("string", "null"),
            properties.getJSONObject("title").getJSONArray("type").toStringList(),
        )
        assertEquals("string", properties.getJSONObject("data").getString("type"))
        assertEquals(listOf("template", "title", "data"), schema.getJSONArray("required").toStringList())
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(
            InkTemplateId.entries.map(InkTemplateId::wireValue),
            templateEnum.toStringList(),
        )
        assertTrue(
            RENDER_TEMPLATE_TOOL_DESCRIPTION.toByteArray(StandardCharsets.UTF_8).size <= 2_560,
        )
        InkTemplateId.entries.forEach { template ->
            assertTrue(RENDER_TEMPLATE_TOOL_DESCRIPTION.contains("${template.wireValue} -"))
        }
    }

    @Test
    fun `valid chart call loads its asset and sends normalized data with title`() = runTest {
        val capabilities = FakeInkPageCapabilities()
        var loadedTemplate: InkTemplateId? = null
        val tool = RenderTemplateTool(
            runtime = InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES },
            templateLoader = InkTemplateLoader { template ->
                loadedTemplate = template
                "<page><text>chart asset</text></page>"
            },
        )
        val phase = AssistantToolRegistry(
            definitions = listOf(tool),
            sessionContext = ::grantedSession,
        ).newExecutionPhase(TOOLS_SUPPORTED)

        val result = phase.execute(
            AssistantToolCall(
                callId = "chart",
                name = RENDER_TEMPLATE_TOOL_NAME,
                argumentsJson = """{"template":"chart","title":"Quarterly","data":{"type":"line","labels":["Q1","Q2"],"series":[{"label":"Revenue","values":[12,18]}]}}""",
            ),
        )

        assertEquals(AssistantToolResult.Json("{\"status\":\"shown\"}"), result)
        assertEquals(InkTemplateId.CHART, loadedTemplate)
        assertEquals("<page><text>chart asset</text></page>", capabilities.shownPage)
        val shown = checkNotNull(capabilities.shownData)
        assertEquals("Quarterly", shown.getString("title"))
        assertEquals("line", shown.getString("chartType"))
        assertEquals("value0", shown.getJSONArray("chartSeries").getJSONObject(0).getString("yName"))
        assertEquals(18, shown.getJSONArray("chartPoints").getJSONObject(1).getInt("value0"))
        assertFalse(shown.has("labels"))
        assertFalse(shown.has("series"))
    }

    @Test
    fun `strict providers pass data as a JSON string and optionals as null`() = runTest {
        val capabilities = FakeInkPageCapabilities()
        val tool = RenderTemplateTool(
            runtime = InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES },
            templateLoader = InkTemplateLoader { "<page><text>metrics asset</text></page>" },
        )
        val phase = AssistantToolRegistry(
            definitions = listOf(tool),
            sessionContext = ::grantedSession,
        ).newExecutionPhase(TOOLS_SUPPORTED)

        val result = phase.execute(
            AssistantToolCall(
                callId = "strict",
                name = RENDER_TEMPLATE_TOOL_NAME,
                argumentsJson =
                    """{"template":"metrics","title":null,"data":"{\"cells\":[{\"label\":\"CPU\",\"value\":\"42%\"},{\"label\":\"RAM\",\"value\":\"61%\"}]}"}""",
            ),
        )

        assertEquals(AssistantToolResult.Json("{\"status\":\"shown\"}"), result)
        val shown = checkNotNull(capabilities.shownData)
        assertEquals("CPU", shown.getJSONArray("cells").getJSONObject(0).getString("label"))

        val invalid = tool.validate("""{"template":"metrics","title":null,"data":"not json"}""")
        assertTrue(invalid is AssistantToolValidation.Invalid)
    }

    @Test
    fun `side effecting template render executes once per phase`() = runTest {
        val capabilities = FakeInkPageCapabilities()
        val phase = registry(capabilities) { grantedSession() }
            .newExecutionPhase(TOOLS_SUPPORTED)
        val arguments =
            """{"template":"metrics","data":{"cells":[{"label":"A","value":"1"},{"label":"B","value":"2"}]}}"""

        val first = phase.execute(
            AssistantToolCall("template-1", RENDER_TEMPLATE_TOOL_NAME, arguments),
        )
        val second = phase.execute(
            AssistantToolCall("template-2", RENDER_TEMPLATE_TOOL_NAME, arguments),
        )

        assertEquals(AssistantToolResult.Json("{\"status\":\"shown\"}"), first)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED), second)
        assertEquals(1, capabilities.showCalls)
        assertEquals(1, capabilities.markShownCalls)
    }

    @Test
    fun `each template reports a typed actionable validation error`() {
        val validator = InkTemplateValidator()
        val cases = listOf(
            InvalidCase(
                InkTemplateId.WEATHER,
                """{"temperature":"18 C","condition":"Clear"}""",
                TEMPLATE_PROBLEM_MISSING_KEY,
                "data",
            ),
            InvalidCase(
                InkTemplateId.WEATHER,
                """{"temperature":"18 C","condition":"Clear","hourly":[{"label":"03:00","temp":"18"},{"label":"06:00","temp":17}]}""",
                TEMPLATE_PROBLEM_WRONG_TYPE,
                "data.hourly[0].temp",
            ),
            InvalidCase(
                InkTemplateId.CHART,
                """{"type":"line","labels":["A","B"],"series":[{"label":"Value","values":[1]}]}""",
                TEMPLATE_PROBLEM_LENGTH_MISMATCH,
                "data.series[0].values",
            ),
            InvalidCase(
                InkTemplateId.METRICS,
                """{"cells":[{"label":"Only","value":"1"}]}""",
                TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE,
                "data.cells",
            ),
            InvalidCase(
                InkTemplateId.RANKING,
                """{"rows":[{"label":"A","value":"1","unknown":"x"}]}""",
                TEMPLATE_PROBLEM_EXTRA_KEY,
                "data.rows[0].unknown",
            ),
            InvalidCase(
                InkTemplateId.COMPARISON,
                """{"left":{"label":"A","items":[]},"right":{"label":"B","items":[{"label":"Cost","value":"2"}]}}""",
                TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE,
                "data.left.items",
            ),
            InvalidCase(
                InkTemplateId.SCHEDULE,
                """{"entries":[42]}""",
                TEMPLATE_PROBLEM_WRONG_TYPE,
                "data.entries[0]",
            ),
            InvalidCase(
                InkTemplateId.STEPS,
                """{"current":2,"steps":[{"label":"Only"}]}""",
                TEMPLATE_PROBLEM_VALUE_OUT_OF_RANGE,
                "data.current",
            ),
        )

        cases.forEach { case ->
            val result = validator.validate(case.template, JSONObject(case.data))
            assertTrue("Expected ${case.template.wireValue} to fail", result is InkTemplateValidationResult.Invalid)
            val problems = (result as InkTemplateValidationResult.Invalid).problems
            assertTrue(
                "Missing ${case.code} at ${case.path}: $problems",
                problems.any { it.code == case.code && it.path == case.path },
            )
        }
    }

    @Test
    fun `top-level and nested failures bridge as typed model-correctable details`() {
        val tool = tool(FakeInkPageCapabilities())
        val invalid = tool.validate(
            """{"template":"metrics","title":" ","data":{"cells":[]},"extra":true}""",
        )

        assertTrue(invalid is AssistantToolValidation.Invalid)
        val error = (invalid as AssistantToolValidation.Invalid).error
        assertEquals(TOOL_ERROR_INVALID_TEMPLATE_DATA, error.code)
        val details = JSONObject(error.detailsJson.orEmpty())
        assertEquals("metrics", details.getString("template"))
        val problems = details.getJSONArray("problems")
        assertTrue(problems.hasProblem(TEMPLATE_PROBLEM_INVALID_VALUE, "title"))
        assertTrue(problems.hasProblem(TEMPLATE_PROBLEM_EXTRA_KEY, "extra"))
        assertTrue(problems.hasProblem(TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE, "data.cells"))

        val unknown = tool.validate("""{"template":"dashboard","data":{}}""")
            as AssistantToolValidation.Invalid
        val unknownProblems = JSONObject(unknown.error.detailsJson.orEmpty())
            .getJSONArray("problems")
        assertTrue(unknownProblems.hasProblem(TEMPLATE_PROBLEM_UNKNOWN_TEMPLATE, "template"))
    }

    @Test
    fun `template limits remain explicit and local to the assistant library`() {
        assertEquals(5, InkTemplateLimits.WEATHER_FORECAST_MAX)
        assertEquals(4, InkTemplateLimits.CHART_SERIES_MAX)
        assertEquals(64, InkTemplateLimits.CHART_POINTS_MAX)
        assertEquals(2..6, InkTemplateLimits.METRICS_CELLS_MIN..InkTemplateLimits.METRICS_CELLS_MAX)
        assertEquals(10, InkTemplateLimits.RANKING_ROWS_MAX)
        assertEquals(6, InkTemplateLimits.COMPARISON_ITEMS_MAX)
        assertEquals(12, InkTemplateLimits.SCHEDULE_ENTRIES_MAX)
        assertEquals(8, InkTemplateLimits.STEPS_MAX)
    }

    @Test
    fun `steps progress measures completed work and reaches one hundred when complete`() {
        val validator = InkTemplateValidator()
        val active = validator.validate(
            InkTemplateId.STEPS,
            JSONObject("""{"current":1,"steps":[{"label":"A"},{"label":"B"},{"label":"C"}]}"""),
        ) as InkTemplateValidationResult.Valid
        val complete = validator.validate(
            InkTemplateId.STEPS,
            JSONObject("""{"current":3,"steps":[{"label":"A"},{"label":"B"},{"label":"C"}]}"""),
        ) as InkTemplateValidationResult.Valid

        assertEquals(33, active.data.getInt("progressPercent"))
        assertEquals(100, complete.data.getInt("progressPercent"))
    }

    @Test
    fun `pie charts require one series and use category labels in the legend`() {
        val validator = InkTemplateValidator()
        val invalid = validator.validate(
            InkTemplateId.CHART,
            JSONObject(
                """{"type":"pie","labels":["A"],"series":[{"label":"First","values":[1]},{"label":"Second","values":[2]}]}""",
            ),
        ) as InkTemplateValidationResult.Invalid
        val valid = validator.validate(
            InkTemplateId.CHART,
            JSONObject(
                """{"type":"pie","labels":["North","South"],"series":[{"label":"Share","values":[60,40]}]}""",
            ),
        ) as InkTemplateValidationResult.Valid

        assertTrue(
            invalid.problems.any {
                it.code == TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE && it.path == "data.series"
            },
        )
        assertEquals(
            listOf("North", "South"),
            valid.data.getJSONArray("legend").toStringList("label"),
        )
    }

    @Test
    fun `pie values reject negatives and an all-zero chart`() {
        val validator = InkTemplateValidator()
        val negative = validator.validate(
            InkTemplateId.CHART,
            JSONObject(
                """{"type":"pie","labels":["A","B"],"series":[{"label":"Share","values":[2,-1]}]}""",
            ),
        ) as InkTemplateValidationResult.Invalid
        val zero = validator.validate(
            InkTemplateId.CHART,
            JSONObject(
                """{"type":"pie","labels":["A","B"],"series":[{"label":"Share","values":[0,0]}]}""",
            ),
        ) as InkTemplateValidationResult.Invalid

        assertTrue(
            negative.problems.any {
                it.code == TEMPLATE_PROBLEM_VALUE_OUT_OF_RANGE &&
                    it.path == "data.series[0].values[1]"
            },
        )
        assertTrue(
            zero.problems.any {
                it.code == TEMPLATE_PROBLEM_INVALID_VALUE &&
                    it.path == "data.series[0].values"
            },
        )
    }

    private fun registry(
        capabilities: FakeInkPageCapabilities,
        sessionContext: () -> AssistantToolSessionContext,
    ): AssistantToolRegistry = AssistantToolRegistry(
        definitions = listOf(tool(capabilities)),
        sessionContext = sessionContext,
    )

    private fun tool(capabilities: FakeInkPageCapabilities): RenderTemplateTool =
        RenderTemplateTool(
            runtime = InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES },
            templateLoader = InkTemplateLoader { template ->
                "<page><text>${template.wireValue}</text></page>"
            },
        )

    private class FakeInkPageCapabilities : InkPageToolCapabilities {
        var supported = true
        var sessionActive = true
        var showCalls = 0
        var markShownCalls = 0
        var shownPage: String? = null
        var shownData: JSONObject? = null

        override fun currentSession(): InkPageToolSession = InkPageToolSession("request", 1L)

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
            return InkPageShowResult.Shown
        }

        override fun markInkShown(session: InkPageToolSession): Boolean {
            markShownCalls += 1
            return sessionActive
        }
    }

    private data class InvalidCase(
        val template: InkTemplateId,
        val data: String,
        val code: String,
        val path: String,
    )

    private companion object {
        val TOOLS_SUPPORTED = AssistantProviderFeatures(
            supportsTools = true,
            supportsVision = false,
        )

        fun grantedSession(): AssistantToolSessionContext = AssistantToolSessionContext(
            active = true,
            grantedCapabilities = setOf(PluginCapability.INK_SURFACE.wireValue),
        )
    }
}

private fun org.json.JSONArray.toStringList(): List<String> =
    List(length()) { index -> getString(index) }

private fun org.json.JSONArray.toStringList(objectKey: String): List<String> =
    List(length()) { index -> getJSONObject(index).getString(objectKey) }

private fun org.json.JSONArray.hasProblem(code: String, path: String): Boolean =
    (0 until length()).any { index ->
        getJSONObject(index).let { problem ->
            problem.getString("code") == code && problem.getString("path") == path
        }
    }
