package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject
import java.util.Base64

internal const val VIEW_WORKSPACE_PAGE_TOOL_NAME = "view_workspace_page"

/**
 * Shows the model one Workspace PDF page or image when its recognized text is not enough, such as a
 * chart or a diagram. A page cited by text this question supplied may be viewed; so may any
 * cataloged page of the file the wearer explicitly named. Only vision providers are offered it.
 */
internal class ViewWorkspacePageTool private constructor(
    private val workspaceVersion: Pair<Long, Long>?,
    private val turn: WorkspaceTurnAccess?,
    private val sessionOpen: () -> Boolean,
) : AssistantToolDefinition {
    constructor(sessionOpen: () -> Boolean = { true }) : this(null, null, sessionOpen)

    override fun bindToTurn(workspaceVersion: Pair<Long, Long>?, workspaceTurn: WorkspaceTurnAccess?) =
        ViewWorkspacePageTool(workspaceVersion, workspaceTurn, sessionOpen)
    override val name = VIEW_WORKSPACE_PAGE_TOOL_NAME
    override val description = "Look at one page of a Workspace PDF or image file when its text alone cannot " +
        "answer, for example a chart, a table layout, a diagram, a photo, garbled recognized text, or a page " +
        "with no indexed text in the file the wearer named. Pass the file reference as cited or returned, " +
        "without its trailing \" › page N\" or visual note, and for a PDF the page number. Pages of other " +
        "files must have been cited by an excerpt in this question. Do not call it when the text already answers."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"file":{"type":"string","minLength":1,"maxLength":160},""" +
            """"page":{"type":["integer","null"],"minimum":1,"maximum":${WorkspaceLimits.MAX_PDF_PAGES},""" +
            """"description":"PDF page number; null for an image"}},""" +
            """"required":["file","page"],"additionalProperties":false}""",
    )
    override val sideEffecting = false
    override val maxExecutionsPerTurn = 1
    override val progressLabel = "Looking at the page…"
    override val executionFailureCode = "workspace_page_unavailable"

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.session.active && context.provider.supportsTools && context.provider.supportsVision &&
            context.provider.supportsWorkspaceSearch && sessionOpen() && turn != null &&
            turn.version == workspaceVersion && turn.isUsable() && turn.hasViewablePages()

    override fun validate(argumentsJson: String): AssistantToolValidation {
        if (argumentsJson.length > 2_048) return AssistantToolValidation.Invalid()
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val file = (arguments.opt("file") as? String)?.trim()
        val page = arguments.opt("page").takeUnless { it == JSONObject.NULL }
        val validPage = page == null || page is Int && page in 1..WorkspaceLimits.MAX_PDF_PAGES
        val known = arguments.keys().asSequence().all { it == "file" || it == "page" }
        return if (known && validPage && !file.isNullOrEmpty() && file.length <= 160) {
            AssistantToolValidation.Valid(JSONObject().put("file", file).apply { if (page != null) put("page", page) })
        } else {
            AssistantToolValidation.Invalid()
        }
    }

    override suspend fun execute(call: AssistantToolCall, arguments: JSONObject): AssistantToolResult {
        val current = turn?.takeIf { it.version == workspaceVersion && it.isUsable() }
            ?: return AssistantToolResult.Error(if (turn != null) WORKSPACE_SOURCE_CHANGED else executionFailureCode)
        val page = if (arguments.has("page")) arguments.getInt("page") else null
        return when (val outcome = current.viewPage(arguments.getString("file"), page)) {
            is WorkspaceToolOutcome.Image -> AssistantToolResult.Image(mimeType = "image/jpeg",
                base64 = Base64.getEncoder().encodeToString(outcome.jpeg), caption = outcome.caption)
            is WorkspaceToolOutcome.Failure -> AssistantToolResult.Error(outcome.code)
            is WorkspaceToolOutcome.Text -> AssistantToolResult.Error(executionFailureCode)
        }
    }
}
