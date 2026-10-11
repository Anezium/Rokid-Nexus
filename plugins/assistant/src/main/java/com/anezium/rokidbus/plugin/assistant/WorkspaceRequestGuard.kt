package com.anezium.rokidbus.plugin.assistant

/**
 * Binds a request to its Workspace turn. While the turn's access is valid, every send runs the
 * turn's guard, which records a supplied prompt and refuses a send only once Workspace text or
 * pixels went out and access was withdrawn. A turn already withdrawn at dispatch drops the
 * Workspace prompt and tools, so an unrelated answer still proceeds.
 */
internal fun ChatRequest.forCurrentWorkspace(
    promptCarriesEvidence: Boolean,
    promptWithoutWorkspace: () -> String,
): ChatRequest {
    val version = workspaceVersion ?: return this
    val turn = workspaceTurn
    if (turn != null && turn.version == version && turn.isUsable()) {
        return copy(beforeSend = { turn.beforeSend(promptCarriesEvidence) })
    }
    turn?.withdraw("changed")
    return copy(systemPrompt = promptWithoutWorkspace(), workspaceVersion = null, workspaceTurn = null, beforeSend = null)
}

internal val WORKSPACE_TOOL_NAMES = setOf(SEARCH_WORKSPACE_TOOL_NAME, VIEW_WORKSPACE_PAGE_TOOL_NAME)
