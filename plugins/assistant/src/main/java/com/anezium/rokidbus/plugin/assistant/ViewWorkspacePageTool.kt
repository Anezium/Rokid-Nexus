package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject
import java.util.Base64

internal const val VIEW_WORKSPACE_PAGE_TOOL_NAME = "view_workspace_page"

/**
 * Shows the model one Workspace PDF page or image when its recognized text is not enough, such as a
 * chart or a diagram. Only a page the excerpts already cite is sent, and only to vision providers.
 */
internal class ViewWorkspacePageTool private constructor(
    private val workspaceVersion: Pair<Long, Long>?,
    private val access: () -> WorkspaceSearchAccess?,
) : AssistantToolDefinition {
    constructor(access: () -> WorkspaceSearchAccess?) : this(null, access)

    override fun bindToTurn(workspaceVersion: Pair<Long, Long>?) = ViewWorkspacePageTool(workspaceVersion, access)
    override val name = VIEW_WORKSPACE_PAGE_TOOL_NAME
    override val description = "Look at one page of a Workspace PDF or image file when the Workspace excerpts " +
        "point to it but their text alone cannot answer, for example a chart, a table layout, a diagram, a " +
        "photo, or garbled recognized text. Pass the file exactly as cited before \" › \" and, for a PDF, the " +
        "page number cited. Do not call it when the excerpt text already answers."
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
            context.provider.supportsWorkspaceSearch && access()?.let {
                it.isSearchAvailable() && it.searchVersion() == workspaceVersion && it.hasViewablePages()
            } == true

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
        val current = access()?.takeIf { it.isSearchAvailable() && it.searchVersion() == workspaceVersion }
            ?: return AssistantToolResult.Error(executionFailureCode)
        val page = if (arguments.has("page")) arguments.getInt("page") else null
        val jpeg = current.viewPage(arguments.getString("file"), page)
            ?: return AssistantToolResult.Error(executionFailureCode)
        if (!current.isSearchAvailable() || current.searchVersion() != workspaceVersion) {
            return AssistantToolResult.Error(executionFailureCode)
        }
        return AssistantToolResult.Image(mimeType = "image/jpeg", base64 = Base64.getEncoder().encodeToString(jpeg))
    }
}
