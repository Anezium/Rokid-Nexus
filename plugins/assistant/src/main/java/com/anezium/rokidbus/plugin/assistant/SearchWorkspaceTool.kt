package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject

internal const val SEARCH_WORKSPACE_TOOL_NAME = "search_workspace"

internal class SearchWorkspaceTool private constructor(
    private val workspaceVersion: Pair<Long, Long>?,
    private val turn: WorkspaceTurnAccess?,
    private val sessionOpen: () -> Boolean,
) : AssistantToolDefinition {
    constructor(sessionOpen: () -> Boolean = { true }) : this(null, null, sessionOpen)

    override fun bindToTurn(workspaceVersion: Pair<Long, Long>?, workspaceTurn: WorkspaceTurnAccess?) =
        SearchWorkspaceTool(workspaceVersion, workspaceTurn, sessionOpen)
    override val name = SEARCH_WORKSPACE_TOOL_NAME
    override val description = "Search the enabled local workspace for relevant document passages by keywords. " +
        "Only for questions requiring the wearer's documents; do not search for unrelated general knowledge. " +
        "Use injected Workspace excerpts first; call when they miss a part or differ in wording or language. " +
        "With file null it searches the file the wearer named in this question, if any; otherwise every " +
        "passage must contain all keywords of a part, so search a missing named subject by its full proper " +
        "name alone, never mixed with generic attributes (\"Orphee\", not \"Orphee code\"), and put separate " +
        "parts in one query joined with a semicolon (\"Vega; Aurora\"). Set file to a file reference this " +
        "question named or a previous result returned to search inside that file, where attributes such " +
        "as code or schedule are fine. Check the same full subject is named before using an excerpt; a " +
        "shared first name is not enough. When coverage is missing, do not repeat personal details from " +
        "unrelated excerpts. At most two different searches per user turn. Cite the returned file names " +
        "and pages, and say what the coverage line leaves unsearched."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":240},""" +
            """"file":{"type":["string","null"],"minLength":1,"maxLength":160}},""" +
            """"required":["query","file"],"additionalProperties":false}""",
    )
    override val sideEffecting = false
    // Two different searches; an identical retry returns the first result without searching again.
    override val maxExecutionsPerTurn = 4
    override val progressLabel = "Searching your workspace…"
    override val executionFailureCode = "workspace_unavailable"

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.session.active && context.provider.supportsTools && context.provider.supportsWorkspaceSearch &&
            sessionOpen() && turn != null && turn.version == workspaceVersion && turn.isUsable() &&
            turn.hasSearchableText()

    override fun validate(argumentsJson: String): AssistantToolValidation {
        if (argumentsJson.length > 2_048) return AssistantToolValidation.Invalid()
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val query = arguments.opt("query") as? String
        val rawFile = arguments.opt("file")
        val file = rawFile as? String
        val known = arguments.keys().asSequence().all { it == "query" || it == "file" }
        val validFile = rawFile == null || rawFile == JSONObject.NULL ||
            file != null && file.isNotBlank() && file.length <= 160
        return if (known && query != null && query.isNotBlank() && query.length <= WorkspaceLimits.MAX_QUERY_CHARS &&
            validFile
        ) AssistantToolValidation.Valid(JSONObject().put("query", query.trim()).put("file", file?.trim() ?: JSONObject.NULL))
        else AssistantToolValidation.Invalid()
    }

    override suspend fun execute(call: AssistantToolCall, arguments: JSONObject): AssistantToolResult {
        val current = turn?.takeIf { it.version == workspaceVersion && it.isUsable() }
            ?: return AssistantToolResult.Error(if (turn != null) WORKSPACE_SOURCE_CHANGED else executionFailureCode)
        val file = arguments.opt("file").takeUnless { it == JSONObject.NULL } as? String
        return when (val outcome = current.search(arguments.getString("query"), file)) {
            is WorkspaceToolOutcome.Text -> AssistantToolResult.Json(outcome.json)
            is WorkspaceToolOutcome.Failure -> AssistantToolResult.Error(outcome.code)
            is WorkspaceToolOutcome.Image -> AssistantToolResult.Error(executionFailureCode)
        }
    }
}
