package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject

internal class RenderInkPageTool(
    private val runtime: InkPageToolRuntime,
) : AssistantToolDefinition {
    override val name: String = RENDER_INK_PAGE_TOOL_NAME
    override val description: String = RENDER_INK_PAGE_TOOL_DESCRIPTION
    override val parametersSchema: AssistantToolJsonSchema = RENDER_INK_PAGE_PARAMETERS_SCHEMA
    override val sideEffecting: Boolean = true
    override val progressLabel: String = "Drawing the card…"
    override val retiresProgressOnSuccess: Boolean = true
    override val executionFailureCode: String = TOOL_ERROR_INK_RENDER_FAILED

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        runtime.offersFreePages(context)

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val keys = arguments.keys()
        while (keys.hasNext()) {
            if (keys.next() !in RENDER_INK_PAGE_ARGUMENTS) {
                return AssistantToolValidation.Invalid()
            }
        }
        // Strict-schema providers require every property, so optionals arrive
        // as JSON null, and free-form objects arrive JSON-encoded in a string.
        if (arguments.opt("title") === JSONObject.NULL) arguments.remove("title")
        if (arguments.opt("data") === JSONObject.NULL) arguments.remove("data")
        (arguments.opt("data") as? String)?.let { encoded ->
            val parsed = runCatching { JSONObject(encoded) }.getOrNull()
                ?: return AssistantToolValidation.Invalid()
            arguments.put("data", parsed)
        }
        val page = arguments.opt("page")
        if (page !is String || page.isBlank()) return AssistantToolValidation.Invalid()
        if (arguments.has("title") && arguments.opt("title") !is String) {
            return AssistantToolValidation.Invalid()
        }
        if (arguments.has("data") && arguments.opt("data") !is JSONObject) {
            return AssistantToolValidation.Invalid()
        }
        return AssistantToolValidation.Valid(arguments)
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = runtime.show(
        page = arguments.getString("page"),
        data = arguments.optJSONObject("data"),
    )

    private companion object {
        val RENDER_INK_PAGE_ARGUMENTS = setOf("page", "title", "data")
    }
}

internal const val RENDER_INK_PAGE_TOOL_NAME = "render_ink_page"
internal const val TOOL_ERROR_INVALID_INK_PAGE = "invalid_ink_page"
internal const val TOOL_ERROR_INK_RENDER_FAILED = "ink_render_failed"
internal const val TOOL_ERROR_INK_SURFACE_UNAVAILABLE = "ink_surface_unavailable"
internal const val TOOL_ERROR_SURFACE_BUSY = "surface_busy"

internal val RENDER_INK_PAGE_PARAMETERS_SCHEMA = AssistantToolJsonSchema(
    """{"type":"object","properties":{"page":{"type":"string"},"title":{"type":["string","null"]},"data":{"type":["string","null"],"description":"Optional JSON-encoded object of initial page data"}},"required":["page","title","data"],"additionalProperties":false}""",
)

internal val RENDER_INK_PAGE_TOOL_DESCRIPTION = """
    Render a rich Nexus Ink page on the glasses as optional presentation alongside the required normal text answer. Use it for numbers, comparisons, trends, metric layouts, or multi-value/animated status; do not use it for plain prose.
    Prefer render_template when one of its fixed layouts fits; use this tool for freeform pages.

    Nexus Ink v1 supports view, text, asset-only image, scroll-view, progress, chart (line/area/pie/radar/bar), lottie-view, and nx-canvas; wx:if/elif/else, wx:for, interpolation, and bounded expressions. Set display:flex explicitly on every layout container. No <script setup>, JavaScript, URLs, filters, keyframes, or media queries. Page <=32 KiB, data <=16 KiB, total <=64 KiB, <=256 nodes, <=4 chart series x 256 points, <=512 canvas commands, and each Lottie JSON <=32 KiB. Monochrome only: do not author color styling; identify every chart series/point by label. Do not paint a page background or outer frame: the host renders every page as a framed opaque card.

    Compact example page:
    <script type="application/json" def>{"data":{"title":"Trend","points":[{"label":"Now","value":1}]}}</script>
    <page><view class="page"><text class="title">{{ title }}</text><chart class="chart" type="line" series="value" data="{{ points }}" animate="true" /></view></page>
    <style>.page { display: flex; flex-direction: column; gap: 12rpx; padding: 20rpx; } .title { font-size: 36rpx; font-weight: 700; } .chart { width: 100%; height: 180rpx; }</style>
""".trimIndent()
