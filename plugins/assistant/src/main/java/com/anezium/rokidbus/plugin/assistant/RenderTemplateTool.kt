package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal enum class InkTemplateId(
    val wireValue: String,
) {
    WEATHER("weather"),
    CHART("chart"),
    METRICS("metrics"),
    RANKING("ranking"),
    COMPARISON("comparison"),
    SCHEDULE("schedule"),
    STEPS("steps"),
    ;

    val id: String
        get() = wireValue

    companion object {
        fun fromWireValue(value: String): InkTemplateId? =
            entries.firstOrNull { template -> template.wireValue == value }
    }
}

internal fun interface InkTemplateLoader {
    fun load(template: InkTemplateId): String
}

internal object InkTemplateLimits {
    const val WEATHER_FORECAST_MIN = 1
    const val WEATHER_FORECAST_MAX = 5
    const val WEATHER_HOURLY_MIN = 2
    const val WEATHER_HOURLY_MAX = 24
    const val CHART_SERIES_MIN = 1
    const val CHART_SERIES_MAX = 4
    const val CHART_POINTS_MIN = 1
    const val CHART_POINTS_MAX = 64
    const val METRICS_CELLS_MIN = 2
    const val METRICS_CELLS_MAX = 6
    const val RANKING_ROWS_MIN = 1
    const val RANKING_ROWS_MAX = 10
    const val COMPARISON_ITEMS_MIN = 1
    const val COMPARISON_ITEMS_MAX = 6
    const val SCHEDULE_ENTRIES_MIN = 1
    const val SCHEDULE_ENTRIES_MAX = 12
    const val STEPS_MIN = 1
    const val STEPS_MAX = 8
}

internal data class InkTemplateProblem(
    val code: String,
    val path: String,
    val message: String,
)

internal sealed interface InkTemplateValidationResult {
    data class Valid(
        val data: JSONObject,
    ) : InkTemplateValidationResult

    data class Invalid(
        val problems: List<InkTemplateProblem>,
    ) : InkTemplateValidationResult {
        init {
            require(problems.isNotEmpty())
        }
    }
}

internal class InkTemplateValidator {
    fun validate(template: String, data: JSONObject): InkTemplateValidationResult {
        val templateId = InkTemplateId.fromWireValue(template)
            ?: return InkTemplateValidationResult.Invalid(
                listOf(
                    InkTemplateProblem(
                        code = TEMPLATE_PROBLEM_UNKNOWN_TEMPLATE,
                        path = "template",
                        message = unknownTemplateMessage(template),
                    ),
                ),
            )
        return validate(templateId, data)
    }

    fun validate(template: InkTemplateId, data: JSONObject): InkTemplateValidationResult {
        val problems = mutableListOf<InkTemplateProblem>()
        when (template) {
            InkTemplateId.WEATHER -> validateWeather(data, problems)
            InkTemplateId.CHART -> validateChart(data, problems)
            InkTemplateId.METRICS -> validateMetrics(data, problems)
            InkTemplateId.RANKING -> validateRanking(data, problems)
            InkTemplateId.COMPARISON -> validateComparison(data, problems)
            InkTemplateId.SCHEDULE -> validateSchedule(data, problems)
            InkTemplateId.STEPS -> validateSteps(data, problems)
        }
        if (problems.isNotEmpty()) return InkTemplateValidationResult.Invalid(problems)

        val normalized = when (template) {
            InkTemplateId.CHART -> normalizeChart(data)
            InkTemplateId.STEPS -> normalizeSteps(data)
            else -> JSONObject(data.toString())
        }
        return InkTemplateValidationResult.Valid(normalized)
    }

    private fun validateWeather(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateShape(
            value = data,
            path = DATA_PATH,
            allowed = WEATHER_KEYS,
            required = setOf("temperature", "condition"),
            problems = problems,
        )
        optionalString(data, "location", DATA_PATH, problems)
        requiredString(data, "temperature", DATA_PATH, problems)
        requiredString(data, "condition", DATA_PATH, problems)
        optionalString(data, "high", DATA_PATH, problems)
        optionalString(data, "low", DATA_PATH, problems)
        optionalString(data, "precipitation", DATA_PATH, problems)
        optionalString(data, "humidity", DATA_PATH, problems)
        optionalString(data, "wind", DATA_PATH, problems)

        if (!data.has("forecast") && !data.has("hourly")) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_MISSING_KEY,
                path = DATA_PATH,
                message = "Weather needs forecast periods, an hourly curve, or both",
            )
            return
        }
        (data.opt("forecast") as? JSONArray)?.let { forecast ->
            validateCount(
                array = forecast,
                path = "$DATA_PATH.forecast",
                minimum = InkTemplateLimits.WEATHER_FORECAST_MIN,
                maximum = InkTemplateLimits.WEATHER_FORECAST_MAX,
                problems = problems,
            )
            forEachObject(forecast, "$DATA_PATH.forecast", problems) { item, itemPath ->
                validateShape(
                    value = item,
                    path = itemPath,
                    allowed = WEATHER_FORECAST_KEYS,
                    required = setOf("label", "temperature"),
                    problems = problems,
                )
                requiredString(item, "label", itemPath, problems)
                requiredString(item, "temperature", itemPath, problems)
                optionalString(item, "condition", itemPath, problems)
            }
        } ?: if (data.has("forecast")) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_WRONG_TYPE,
                path = "$DATA_PATH.forecast",
                message = "forecast must be an array",
            )
        } else Unit
        (data.opt("hourly") as? JSONArray)?.let { hourly ->
            validateCount(
                array = hourly,
                path = "$DATA_PATH.hourly",
                minimum = InkTemplateLimits.WEATHER_HOURLY_MIN,
                maximum = InkTemplateLimits.WEATHER_HOURLY_MAX,
                problems = problems,
            )
            forEachObject(hourly, "$DATA_PATH.hourly", problems) { item, itemPath ->
                validateShape(
                    value = item,
                    path = itemPath,
                    allowed = WEATHER_HOURLY_KEYS,
                    required = setOf("label", "temp"),
                    problems = problems,
                )
                requiredString(item, "label", itemPath, problems)
                if (item.opt("temp") !is Number) {
                    problems += InkTemplateProblem(
                        code = TEMPLATE_PROBLEM_WRONG_TYPE,
                        path = "$itemPath.temp",
                        message = "temp must be a number",
                    )
                }
            }
        } ?: if (data.has("hourly")) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_WRONG_TYPE,
                path = "$DATA_PATH.hourly",
                message = "hourly must be an array",
            )
        } else Unit
    }

    private fun validateChart(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateShape(
            value = data,
            path = DATA_PATH,
            allowed = CHART_KEYS,
            required = setOf("type", "labels", "series"),
            problems = problems,
        )
        val chartType = requiredString(data, "type", DATA_PATH, problems)
        if (chartType != null && chartType !in CHART_TYPES) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_INVALID_VALUE,
                path = "$DATA_PATH.type",
                message = "Expected one of ${CHART_TYPES.joinToString()}; received '$chartType'.",
            )
        }
        optionalString(data, "caption", DATA_PATH, problems)

        val labels = requiredArray(data, "labels", DATA_PATH, problems)
        if (labels != null) {
            validateCount(
                array = labels,
                path = "$DATA_PATH.labels",
                minimum = InkTemplateLimits.CHART_POINTS_MIN,
                maximum = InkTemplateLimits.CHART_POINTS_MAX,
                problems = problems,
            )
            forEachValue(labels) { index, value ->
                validateStringValue(value, "$DATA_PATH.labels[$index]", problems)
            }
        }

        val series = requiredArray(data, "series", DATA_PATH, problems) ?: return
        validateCount(
            array = series,
            path = "$DATA_PATH.series",
            minimum = InkTemplateLimits.CHART_SERIES_MIN,
            maximum = InkTemplateLimits.CHART_SERIES_MAX,
            problems = problems,
        )
        if (chartType == "pie" && series.length() != 1) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE,
                path = "$DATA_PATH.series",
                message = "Pie charts require exactly 1 series; received ${series.length()}.",
            )
        }
        forEachObject(series, "$DATA_PATH.series", problems) { item, itemPath ->
            validateShape(
                value = item,
                path = itemPath,
                allowed = CHART_SERIES_KEYS,
                required = CHART_SERIES_KEYS,
                problems = problems,
            )
            requiredString(item, "label", itemPath, problems)
            val values = requiredArray(item, "values", itemPath, problems)
            if (values != null) {
                if (labels != null && values.length() != labels.length()) {
                    problems += InkTemplateProblem(
                        code = TEMPLATE_PROBLEM_LENGTH_MISMATCH,
                        path = "$itemPath.values",
                        message = "Expected ${labels.length()} values to match data.labels; " +
                            "received ${values.length()}.",
                    )
                }
                forEachValue(values) { index, value ->
                    validateNumberValue(value, "$itemPath.values[$index]", problems)
                }
            }
        }
        if (chartType == "pie" && series.length() == 1) {
            validatePieValues(series.optJSONObject(0)?.optJSONArray("values"), problems)
        }
    }

    private fun validatePieValues(
        values: JSONArray?,
        problems: MutableList<InkTemplateProblem>,
    ) {
        if (values == null) return
        var allFiniteNumbers = true
        var hasPositiveValue = false
        forEachValue(values) { index, value ->
            val number = value as? Number
            val numericValue = number?.toDouble()
            if (numericValue == null || !numericValue.isFinite()) {
                allFiniteNumbers = false
            } else {
                if (numericValue < 0.0) {
                    problems += InkTemplateProblem(
                        code = TEMPLATE_PROBLEM_VALUE_OUT_OF_RANGE,
                        path = "$DATA_PATH.series[0].values[$index]",
                        message = "Pie chart values must be non-negative; received $numericValue.",
                    )
                }
                if (numericValue > 0.0) hasPositiveValue = true
            }
        }
        if (allFiniteNumbers && !hasPositiveValue) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_INVALID_VALUE,
                path = "$DATA_PATH.series[0].values",
                message = "Pie charts require at least one value greater than zero.",
            )
        }
    }

    private fun validateMetrics(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateSingleArrayObject(data, "cells", DATA_PATH, problems) { cells ->
            validateCount(
                array = cells,
                path = "$DATA_PATH.cells",
                minimum = InkTemplateLimits.METRICS_CELLS_MIN,
                maximum = InkTemplateLimits.METRICS_CELLS_MAX,
                problems = problems,
            )
            validateLabeledValueItems(cells, "$DATA_PATH.cells", hasDetail = true, problems)
        }
    }

    private fun validateRanking(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateSingleArrayObject(data, "rows", DATA_PATH, problems) { rows ->
            validateCount(
                array = rows,
                path = "$DATA_PATH.rows",
                minimum = InkTemplateLimits.RANKING_ROWS_MIN,
                maximum = InkTemplateLimits.RANKING_ROWS_MAX,
                problems = problems,
            )
            validateLabeledValueItems(rows, "$DATA_PATH.rows", hasDetail = true, problems)
        }
    }

    private fun validateComparison(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateShape(
            value = data,
            path = DATA_PATH,
            allowed = COMPARISON_KEYS,
            required = setOf("left", "right"),
            problems = problems,
        )
        optionalString(data, "verdict", DATA_PATH, problems)
        validateComparisonSide(data, "left", problems)
        validateComparisonSide(data, "right", problems)
    }

    private fun validateComparisonSide(
        data: JSONObject,
        key: String,
        problems: MutableList<InkTemplateProblem>,
    ) {
        val side = requiredObject(data, key, DATA_PATH, problems) ?: return
        val sidePath = "$DATA_PATH.$key"
        validateShape(
            value = side,
            path = sidePath,
            allowed = COMPARISON_SIDE_KEYS,
            required = COMPARISON_SIDE_KEYS,
            problems = problems,
        )
        requiredString(side, "label", sidePath, problems)
        val items = requiredArray(side, "items", sidePath, problems) ?: return
        validateCount(
            array = items,
            path = "$sidePath.items",
            minimum = InkTemplateLimits.COMPARISON_ITEMS_MIN,
            maximum = InkTemplateLimits.COMPARISON_ITEMS_MAX,
            problems = problems,
        )
        validateLabeledValueItems(items, "$sidePath.items", hasDetail = false, problems)
    }

    private fun validateSchedule(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateSingleArrayObject(data, "entries", DATA_PATH, problems) { entries ->
            validateCount(
                array = entries,
                path = "$DATA_PATH.entries",
                minimum = InkTemplateLimits.SCHEDULE_ENTRIES_MIN,
                maximum = InkTemplateLimits.SCHEDULE_ENTRIES_MAX,
                problems = problems,
            )
            forEachObject(entries, "$DATA_PATH.entries", problems) { item, itemPath ->
                validateShape(
                    value = item,
                    path = itemPath,
                    allowed = SCHEDULE_ENTRY_KEYS,
                    required = setOf("time", "title"),
                    problems = problems,
                )
                requiredString(item, "time", itemPath, problems)
                requiredString(item, "title", itemPath, problems)
                optionalString(item, "detail", itemPath, problems)
            }
        }
    }

    private fun validateSteps(
        data: JSONObject,
        problems: MutableList<InkTemplateProblem>,
    ) {
        validateShape(
            value = data,
            path = DATA_PATH,
            allowed = STEPS_KEYS,
            required = STEPS_KEYS,
            problems = problems,
        )
        val current = requiredInteger(data, "current", DATA_PATH, problems)
        val steps = requiredArray(data, "steps", DATA_PATH, problems)
        if (steps != null) {
            validateCount(
                array = steps,
                path = "$DATA_PATH.steps",
                minimum = InkTemplateLimits.STEPS_MIN,
                maximum = InkTemplateLimits.STEPS_MAX,
                problems = problems,
            )
            forEachObject(steps, "$DATA_PATH.steps", problems) { item, itemPath ->
                validateShape(
                    value = item,
                    path = itemPath,
                    allowed = STEP_KEYS,
                    required = setOf("label"),
                    problems = problems,
                )
                requiredString(item, "label", itemPath, problems)
                optionalString(item, "detail", itemPath, problems)
            }
            if (current != null && current !in 0..steps.length()) {
                problems += InkTemplateProblem(
                    code = TEMPLATE_PROBLEM_VALUE_OUT_OF_RANGE,
                    path = "$DATA_PATH.current",
                    message = "Expected an integer from 0 through ${steps.length()}; received $current.",
                )
            }
        }
    }

    private fun validateSingleArrayObject(
        data: JSONObject,
        key: String,
        path: String,
        problems: MutableList<InkTemplateProblem>,
        validateArray: (JSONArray) -> Unit,
    ) {
        validateShape(
            value = data,
            path = path,
            allowed = setOf(key),
            required = setOf(key),
            problems = problems,
        )
        requiredArray(data, key, path, problems)?.let(validateArray)
    }

    private fun validateLabeledValueItems(
        array: JSONArray,
        path: String,
        hasDetail: Boolean,
        problems: MutableList<InkTemplateProblem>,
    ) {
        val allowed = if (hasDetail) LABELED_VALUE_DETAIL_KEYS else LABELED_VALUE_KEYS
        forEachObject(array, path, problems) { item, itemPath ->
            validateShape(
                value = item,
                path = itemPath,
                allowed = allowed,
                required = LABELED_VALUE_KEYS,
                problems = problems,
            )
            requiredString(item, "label", itemPath, problems)
            requiredString(item, "value", itemPath, problems)
            if (hasDetail) optionalString(item, "detail", itemPath, problems)
        }
    }

    private fun normalizeChart(data: JSONObject): JSONObject {
        val labels = data.getJSONArray("labels")
        val sourceSeries = data.getJSONArray("series")
        val chartSeries = JSONArray()
        val legend = JSONArray()
        repeat(sourceSeries.length()) { seriesIndex ->
            val label = sourceSeries.getJSONObject(seriesIndex).getString("label")
            chartSeries.put(
                JSONObject()
                    .put("yName", "value$seriesIndex")
                    .put("label", label),
            )
            if (data.getString("type") != "pie") {
                legend.put(JSONObject().put("label", label))
            }
        }
        if (data.getString("type") == "pie") {
            repeat(labels.length()) { index ->
                legend.put(JSONObject().put("label", labels.getString(index)))
            }
        }

        val chartPoints = JSONArray()
        repeat(labels.length()) { pointIndex ->
            val point = JSONObject().put("label", labels.getString(pointIndex))
            repeat(sourceSeries.length()) { seriesIndex ->
                val value = sourceSeries
                    .getJSONObject(seriesIndex)
                    .getJSONArray("values")
                    .get(pointIndex)
                point.put("value$seriesIndex", value)
            }
            chartPoints.put(point)
        }

        return JSONObject()
            .put("chartType", data.getString("type"))
            .put("chartSeries", chartSeries)
            .put("chartPoints", chartPoints)
            .put("legend", legend)
            .apply {
                if (data.has("caption")) put("caption", data.getString("caption"))
            }
    }

    private fun normalizeSteps(data: JSONObject): JSONObject {
        val normalized = JSONObject(data.toString())
        val current = data.getInt("current")
        val count = data.getJSONArray("steps").length()
        val progressPercent = current * 100 / count
        return normalized.put("progressPercent", progressPercent)
    }

    private companion object {
        const val DATA_PATH = "data"

        val WEATHER_KEYS = setOf(
            "location", "temperature", "condition", "high", "low",
            "precipitation", "humidity", "wind", "forecast", "hourly",
        )
        val WEATHER_FORECAST_KEYS = setOf("label", "temperature", "condition")
        val WEATHER_HOURLY_KEYS = setOf("label", "temp")
        val CHART_KEYS = setOf("type", "labels", "series", "caption")
        val CHART_TYPES = setOf("line", "area", "bar", "pie")
        val CHART_SERIES_KEYS = setOf("label", "values")
        val LABELED_VALUE_KEYS = setOf("label", "value")
        val LABELED_VALUE_DETAIL_KEYS = setOf("label", "value", "detail")
        val COMPARISON_KEYS = setOf("left", "right", "verdict")
        val COMPARISON_SIDE_KEYS = setOf("label", "items")
        val SCHEDULE_ENTRY_KEYS = setOf("time", "title", "detail")
        val STEPS_KEYS = setOf("current", "steps")
        val STEP_KEYS = setOf("label", "detail")
    }
}

internal class RenderTemplateTool(
    private val runtime: InkPageToolRuntime,
    private val templateLoader: InkTemplateLoader,
    private val validator: InkTemplateValidator = InkTemplateValidator(),
) : AssistantToolDefinition {
    override val name: String = RENDER_TEMPLATE_TOOL_NAME
    override val description: String = RENDER_TEMPLATE_TOOL_DESCRIPTION
    override val parametersSchema: AssistantToolJsonSchema = RENDER_TEMPLATE_PARAMETERS_SCHEMA
    override val sideEffecting: Boolean = true
    override val progressLabel: String = "Drawing the card…"
    override val retiresProgressOnSuccess: Boolean = true
    override val executionFailureCode: String = TOOL_ERROR_INK_RENDER_FAILED

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        runtime.offersTemplates(context)

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val problems = mutableListOf<InkTemplateProblem>()

        // Strict-schema providers require every property, so optionals arrive
        // as JSON null, and free-form objects arrive JSON-encoded in a string.
        if (arguments.opt("title") === JSONObject.NULL) arguments.remove("title")
        if (arguments.opt("data") === JSONObject.NULL) arguments.remove("data")
        (arguments.opt("data") as? String)?.let { encoded ->
            val parsed = runCatching { JSONObject(encoded) }.getOrNull()
                ?: return AssistantToolValidation.Invalid(
                    invalidTemplateDataError(
                        arguments.optString("template"),
                        listOf(
                            InkTemplateProblem(
                                code = TEMPLATE_PROBLEM_WRONG_TYPE,
                                path = "data",
                                message = "data must be a JSON-encoded object",
                            ),
                        ),
                    ),
                )
            arguments.put("data", parsed)
        }

        validateShape(
            value = arguments,
            path = ROOT_PATH,
            allowed = RENDER_TEMPLATE_ARGUMENTS,
            required = setOf("template", "data"),
            problems = problems,
        )
        val templateValue = requiredString(arguments, "template", ROOT_PATH, problems)
        val title = optionalString(arguments, "title", ROOT_PATH, problems)
        val data = requiredObject(arguments, "data", ROOT_PATH, problems)
        val templateId = templateValue?.let { InkTemplateId.fromWireValue(it) }
        if (templateValue != null && templateId == null) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_UNKNOWN_TEMPLATE,
                path = "template",
                message = unknownTemplateMessage(templateValue),
            )
        }

        var normalizedData: JSONObject? = null
        if (templateId != null && data != null) {
            when (val result = validator.validate(templateId, data)) {
                is InkTemplateValidationResult.Valid -> normalizedData = result.data
                is InkTemplateValidationResult.Invalid -> problems += result.problems
            }
        }
        if (problems.isNotEmpty()) {
            return AssistantToolValidation.Invalid(
                invalidTemplateDataError(templateValue, problems),
            )
        }

        check(templateId != null && normalizedData != null)
        if (title != null) normalizedData.put("title", title)
        return AssistantToolValidation.Valid(
            JSONObject()
                .put("template", templateId.wireValue)
                .put("data", normalizedData),
        )
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult {
        val template = InkTemplateId.fromWireValue(arguments.getString("template"))
            ?: return AssistantToolResult.Error(TOOL_ERROR_INK_RENDER_FAILED)
        val page = try {
            templateLoader.load(template)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return AssistantToolResult.Error(TOOL_ERROR_INK_RENDER_FAILED)
        }
        if (page.isBlank()) return AssistantToolResult.Error(TOOL_ERROR_INK_RENDER_FAILED)
        return runtime.show(page = page, data = arguments.getJSONObject("data"))
    }

    private companion object {
        const val ROOT_PATH = ""
        val RENDER_TEMPLATE_ARGUMENTS = setOf("template", "title", "data")
    }
}

internal const val RENDER_TEMPLATE_TOOL_NAME = "render_template"
internal const val TOOL_ERROR_INVALID_TEMPLATE_DATA = "invalid_template_data"

internal const val TEMPLATE_PROBLEM_MISSING_KEY = "missing_key"
internal const val TEMPLATE_PROBLEM_EXTRA_KEY = "extra_key"
internal const val TEMPLATE_PROBLEM_WRONG_TYPE = "wrong_type"
internal const val TEMPLATE_PROBLEM_INVALID_VALUE = "invalid_value"
internal const val TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE = "count_out_of_range"
internal const val TEMPLATE_PROBLEM_VALUE_OUT_OF_RANGE = "value_out_of_range"
internal const val TEMPLATE_PROBLEM_LENGTH_MISMATCH = "length_mismatch"
internal const val TEMPLATE_PROBLEM_UNKNOWN_TEMPLATE = "unknown_template"

internal val RENDER_TEMPLATE_PARAMETERS_SCHEMA = AssistantToolJsonSchema(
    """{"type":"object","properties":{"template":{"type":"string","enum":["weather","chart","metrics","ranking","comparison","schedule","steps"]},"title":{"type":["string","null"]},"data":{"type":"string","description":"JSON-encoded object matching the chosen template's data schema"}},"required":["template","title","data"],"additionalProperties":false}""",
)

internal val RENDER_TEMPLATE_TOOL_DESCRIPTION = """
    Render a fast, prevalidated Ink layout on the glasses. Whenever your answer contains numbers, times, temperatures, forecasts, comparisons, rankings, schedules, or step progress, CALL this tool alongside a concise spoken answer instead of listing the values in prose. Prefer this over render_ink_page when one of these shapes fits. Arguments are {template, title?: nonblank string, data}; localize all supplied strings.
    weather - current conditions with an hourly temperature curve and/or period cells; prefer hourly when you have it. data: {location?:string, temperature:string, condition:string, high?:string, low?:string, precipitation?:string, humidity?:string, wind?:string, hourly?:[{label:string, temp:number}] (2-24), forecast?:[{label:string, temperature:string, condition?:string}] (1-5)} - at least one of hourly/forecast.
    chart - line, area, bar, or pie visualization. data: {type:"line"|"area"|"bar"|"pie", labels:string[1..64], series:[{label:string, values:number[labels.length]}], caption?:string} (1-4 series; pie requires 1 with non-negative values and at least one >0).
    metrics - bordered value cells for a numeric/status snapshot. data: {cells:[{label:string, value:string, detail?:string}]} (2-6 cells).
    ranking - ordered results with values. data: {rows:[{label:string, value:string, detail?:string}]} (1-10 rows).
    comparison - two labeled columns and an optional conclusion. data: {left:{label:string, items:[{label:string, value:string}]}, right:{label:string, items:[{label:string, value:string}]}, verdict?:string} (1-6 items per side).
    schedule - time-labeled agenda entries. data: {entries:[{time:string, title:string, detail?:string}]} (1-12 entries).
    steps - progress through named steps. data: {current:integer, steps:[{label:string, detail?:string}]} (1-8 steps; current is the number completed and zero-based active index, or steps.length when complete).
""".trimIndent()

private fun validateShape(
    value: JSONObject,
    path: String,
    allowed: Set<String>,
    required: Set<String>,
    problems: MutableList<InkTemplateProblem>,
) {
    required.forEach { key ->
        if (!value.has(key)) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_MISSING_KEY,
                path = childPath(path, key),
                message = "Required key '$key' is missing.",
            )
        }
    }
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key !in allowed) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_EXTRA_KEY,
                path = childPath(path, key),
                message = "Unexpected key '$key'; allowed keys are ${allowed.sorted().joinToString()}.",
            )
        }
    }
}

private fun requiredString(
    value: JSONObject,
    key: String,
    path: String,
    problems: MutableList<InkTemplateProblem>,
): String? {
    if (!value.has(key)) return null
    return validateStringValue(value.opt(key), childPath(path, key), problems)
}

private fun optionalString(
    value: JSONObject,
    key: String,
    path: String,
    problems: MutableList<InkTemplateProblem>,
): String? {
    if (!value.has(key)) return null
    return validateStringValue(value.opt(key), childPath(path, key), problems)
}

private fun validateStringValue(
    value: Any?,
    path: String,
    problems: MutableList<InkTemplateProblem>,
): String? {
    if (value !is String) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_WRONG_TYPE,
            path = path,
            message = "Expected a string; received ${jsonTypeName(value)}.",
        )
        return null
    }
    if (value.isBlank()) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_INVALID_VALUE,
            path = path,
            message = "Expected a nonblank string.",
        )
        return null
    }
    return value
}

private fun requiredArray(
    value: JSONObject,
    key: String,
    path: String,
    problems: MutableList<InkTemplateProblem>,
): JSONArray? {
    if (!value.has(key)) return null
    val candidate = value.opt(key)
    if (candidate !is JSONArray) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_WRONG_TYPE,
            path = childPath(path, key),
            message = "Expected an array; received ${jsonTypeName(candidate)}.",
        )
        return null
    }
    return candidate
}

private fun requiredObject(
    value: JSONObject,
    key: String,
    path: String,
    problems: MutableList<InkTemplateProblem>,
): JSONObject? {
    if (!value.has(key)) return null
    val candidate = value.opt(key)
    if (candidate !is JSONObject) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_WRONG_TYPE,
            path = childPath(path, key),
            message = "Expected an object; received ${jsonTypeName(candidate)}.",
        )
        return null
    }
    return candidate
}

private fun requiredInteger(
    value: JSONObject,
    key: String,
    path: String,
    problems: MutableList<InkTemplateProblem>,
): Int? {
    if (!value.has(key)) return null
    val candidate = value.opt(key)
    val number = candidate as? Number
    val asDouble = number?.toDouble()
    if (
        number == null ||
        asDouble == null ||
        !asDouble.isFinite() ||
        asDouble % 1.0 != 0.0 ||
        asDouble < Int.MIN_VALUE ||
        asDouble > Int.MAX_VALUE
    ) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_WRONG_TYPE,
            path = childPath(path, key),
            message = "Expected an integer; received ${jsonTypeName(candidate)}.",
        )
        return null
    }
    return asDouble.toInt()
}

private fun validateNumberValue(
    value: Any?,
    path: String,
    problems: MutableList<InkTemplateProblem>,
) {
    val number = value as? Number
    if (number == null) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_WRONG_TYPE,
            path = path,
            message = "Expected a number; received ${jsonTypeName(value)}.",
        )
        return
    }
    if (!number.toDouble().isFinite()) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_INVALID_VALUE,
            path = path,
            message = "Expected a finite number.",
        )
    }
}

private fun validateCount(
    array: JSONArray,
    path: String,
    minimum: Int,
    maximum: Int,
    problems: MutableList<InkTemplateProblem>,
) {
    if (array.length() !in minimum..maximum) {
        problems += InkTemplateProblem(
            code = TEMPLATE_PROBLEM_COUNT_OUT_OF_RANGE,
            path = path,
            message = "Expected $minimum..$maximum items; received ${array.length()}.",
        )
    }
}

private inline fun forEachValue(
    array: JSONArray,
    action: (index: Int, value: Any?) -> Unit,
) {
    repeat(array.length()) { index -> action(index, array.opt(index)) }
}

private inline fun forEachObject(
    array: JSONArray,
    path: String,
    problems: MutableList<InkTemplateProblem>,
    action: (value: JSONObject, path: String) -> Unit,
) {
    forEachValue(array) { index, value ->
        val itemPath = "$path[$index]"
        if (value !is JSONObject) {
            problems += InkTemplateProblem(
                code = TEMPLATE_PROBLEM_WRONG_TYPE,
                path = itemPath,
                message = "Expected an object; received ${jsonTypeName(value)}.",
            )
        } else {
            action(value, itemPath)
        }
    }
}

private fun invalidTemplateDataError(
    template: String?,
    problems: List<InkTemplateProblem>,
): AssistantToolResult.Error = AssistantToolResult.Error(
    code = TOOL_ERROR_INVALID_TEMPLATE_DATA,
    detailsJson = JSONObject()
        .apply { if (template != null) put("template", template) }
        .put(
            "problems",
            JSONArray().apply {
                problems.forEach { problem ->
                    put(
                        JSONObject()
                            .put("code", problem.code)
                            .put("path", problem.path)
                            .put("message", problem.message),
                    )
                }
            },
        )
        .toString(),
)

private fun unknownTemplateMessage(value: String): String =
    "Unknown template '$value'; expected ${InkTemplateId.entries.joinToString { it.wireValue }}."

private fun childPath(parent: String, key: String): String =
    if (parent.isEmpty()) key else "$parent.$key"

private fun jsonTypeName(value: Any?): String = when (value) {
    null,
    JSONObject.NULL,
    -> "null"
    is JSONObject -> "object"
    is JSONArray -> "array"
    is String -> "string"
    is Number -> "number"
    is Boolean -> "boolean"
    else -> value::class.java.simpleName
}
