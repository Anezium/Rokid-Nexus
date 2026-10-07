package com.anezium.rokidbus.plugin.assistant

internal fun workspaceTurnGuard(access: WorkspaceSearchAccess, version: Pair<Long, Long>): () -> Unit = {
    check(access.isSearchAvailable() && access.searchVersion() == version) {
        "Workspace changed during this answer. Ask your question again."
    }
}

internal fun ChatRequest.forCurrentWorkspace(access: WorkspaceSearchAccess?, promptWithoutWorkspace: () -> String): ChatRequest {
    val version = workspaceVersion ?: return this
    if (access != null && access.isSearchAvailable() && access.searchVersion() == version) {
        return copy(beforeSend = workspaceTurnGuard(access, version))
    }
    return copy(systemPrompt = promptWithoutWorkspace(), workspaceVersion = null, beforeSend = null)
}
