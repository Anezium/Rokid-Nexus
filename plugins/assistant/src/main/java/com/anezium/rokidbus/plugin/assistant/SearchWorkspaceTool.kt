package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject

internal const val SEARCH_WORKSPACE_TOOL_NAME = "search_workspace"

internal class SearchWorkspaceTool private constructor(
    private val workspaceVersion: Pair<Long, Long>?,
    private val access: () -> WorkspaceSearchAccess?,
) : AssistantToolDefinition {
    constructor(access: () -> WorkspaceSearchAccess?) : this(null, access)

    override fun bindToTurn(workspaceVersion: Pair<Long, Long>?) = SearchWorkspaceTool(workspaceVersion, access)
    override val name = SEARCH_WORKSPACE_TOOL_NAME
    override val description = "Search the enabled local workspace for relevant document passages. " +
        "Use the already injected Workspace excerpts first; call when they miss part of the question, such as " +
        "a second topic or wording or a language the documents use differently. It matches keywords: name " +
        "each missing part by one to three specific words, above all proper names from the question, and " +
        "join parts with \"and\" (for example \"Vega and Aurora prototype\"). " +
        "At most one search per user turn. Cite the returned file names and acknowledge missing coverage."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":240}},"required":["query"],"additionalProperties":false}""",
    )
    override val sideEffecting = false
    override val maxExecutionsPerTurn = 1
    override val progressLabel = "Searching your workspace…"
    override val executionFailureCode = "workspace_unavailable"

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.session.active && context.provider.supportsTools && context.provider.supportsWorkspaceSearch &&
            access()?.let { it.isSearchAvailable() && it.searchVersion() == workspaceVersion } == true

    override fun validate(argumentsJson: String): AssistantToolValidation {
        if (argumentsJson.length > 2_048) return AssistantToolValidation.Invalid()
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val query = arguments.opt("query") as? String
        return if (arguments.length() == 1 && query != null && query.isNotBlank() &&
            query.length <= WorkspaceLimits.MAX_QUERY_CHARS
        ) AssistantToolValidation.Valid(JSONObject().put("query", query.trim()))
        else AssistantToolValidation.Invalid()
    }

    override suspend fun execute(call: AssistantToolCall, arguments: JSONObject): AssistantToolResult {
        val current = access()?.takeIf { it.isSearchAvailable() && it.searchVersion() == workspaceVersion }
            ?: return AssistantToolResult.Error(executionFailureCode)
        val result = current.search(arguments.getString("query"))
            ?: return AssistantToolResult.Error(executionFailureCode)
        if (!current.isSearchAvailable() || current.searchVersion() != workspaceVersion) return AssistantToolResult.Error(executionFailureCode)
        return AssistantToolResult.Json(JSONObject().put("ok", true)
            .put("matchCount", result.matchCount).put("excerpts", result.excerpts).toString())
    }
}
