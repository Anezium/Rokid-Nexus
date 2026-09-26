package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusInkProblem
import com.anezium.rokidbus.shared.plugin.PluginCapability
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class InkPageToolSession(
    val requestId: String,
    val generation: Long,
)

internal sealed interface InkPageShowResult {
    data object Shown : InkPageShowResult
    data class Rejected(val problems: List<NexusInkProblem>) : InkPageShowResult
    data class Failed(val code: String) : InkPageShowResult
}

internal interface InkPageToolCapabilities {
    fun currentSession(): InkPageToolSession?
    fun isSessionActive(session: InkPageToolSession): Boolean
    fun supportsInkSurface(): Boolean

    suspend fun showInkPage(
        session: InkPageToolSession,
        page: String,
        data: JSONObject?,
    ): InkPageShowResult

    fun markInkShown(session: InkPageToolSession): Boolean
}

internal class InkPageToolRuntime(
    private val capabilities: InkPageToolCapabilities,
    private val visualAnswers: () -> AssistantVisualAnswers,
) {
    fun offersTemplates(context: AssistantToolAvailabilityContext): Boolean =
        visualAnswers().allowsTemplates && isAvailable(context)

    fun offersFreePages(context: AssistantToolAvailabilityContext): Boolean =
        visualAnswers().allowsFreePages && isAvailable(context)

    private fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.session.active &&
            PluginCapability.INK_SURFACE.wireValue in context.session.grantedCapabilities &&
            capabilities.supportsInkSurface()

    suspend fun show(page: String, data: JSONObject?): AssistantToolResult {
        val session = capabilities.currentSession()
            ?: return AssistantToolResult.Error(TOOL_ERROR_CANCELLED)
        if (!capabilities.isSessionActive(session)) {
            return AssistantToolResult.Error(TOOL_ERROR_CANCELLED)
        }

        val result = withTimeoutOrNull(INK_PAGE_SHOW_TIMEOUT_MS) {
            capabilities.showInkPage(session = session, page = page, data = data)
        } ?: return AssistantToolResult.Error(TOOL_ERROR_INK_RENDER_FAILED)

        return when (result) {
            InkPageShowResult.Shown -> {
                if (!capabilities.markInkShown(session)) {
                    AssistantToolResult.Error(TOOL_ERROR_CANCELLED)
                } else {
                    AssistantToolResult.Json("{\"status\":\"shown\"}")
                }
            }
            is InkPageShowResult.Rejected -> AssistantToolResult.Error(
                code = TOOL_ERROR_INVALID_INK_PAGE,
                detailsJson = inkProblemsJson(result.problems),
            )
            is InkPageShowResult.Failed -> AssistantToolResult.Error(result.code)
        }
    }

    private companion object {
        const val INK_PAGE_SHOW_TIMEOUT_MS = 8_000L
    }
}

private fun inkProblemsJson(problems: List<NexusInkProblem>): String =
    JSONObject()
        .put(
            "problems",
            JSONArray().apply {
                problems.forEach { problem ->
                    put(
                        JSONObject()
                            .put("code", problem.code)
                            .put("message", problem.message)
                            .put("severity", problem.severity)
                            .apply {
                                problem.line?.let { put("line", it) }
                                problem.column?.let { put("column", it) }
                                problem.feature?.let { put("feature", it) }
                            },
                    )
                }
            },
        )
        .toString()
