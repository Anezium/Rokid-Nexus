package com.anezium.rokidbus.plugin.agents

enum class AgentProvider(
    val wireValue: String,
    val marker: String,
) {
    CLAUDE("claude", "CC"),
    CODEX("codex", "CX"),
    OPENCLAW("openclaw", "OC"),
    ;

    companion object {
        fun fromWire(value: String?): AgentProvider? =
            values().firstOrNull { it.wireValue == value }

        /**
         * The providers the daemon link speaks for. A snapshot from it is
         * authoritative for all of them at once, while OpenClaw arrives on its
         * own connection and must never be touched by one.
         */
        val AGENTD_PROVIDERS = setOf(CLAUDE, CODEX)
    }
}

enum class AgentStatus(val wireValue: String) {
    WORKING("working"),
    NEEDS_YOU("needs_you"),
    IDLE("idle"),
    DONE("done"),
    ERROR("error"),
}

enum class PendingRequestKind(val wireValue: String) {
    PERMISSION("permission"),
    QUESTION("question"),
    IDLE_PROMPT("idle_prompt"),
}

data class AgentTurn(
    val lastTool: String? = null,
    val activeSince: Long? = null,
)

data class AgentPendingRequest(
    val kind: PendingRequestKind,
    val summary: String? = null,
    val createdAt: Long? = null,
)

data class AgentSession(
    val id: String,
    val provider: AgentProvider,
    val machineId: String? = null,
    val machineName: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    val project: String? = null,
    val status: AgentStatus,
    val statusDetail: String? = null,
    val stale: Boolean = false,
    val lastActivityAt: Long? = null,
    val lastAssistantText: String? = null,
    val turn: AgentTurn? = null,
    val pendingRequest: AgentPendingRequest? = null,
) {
    val key: String
        get() = "${provider.wireValue}:$id"

    val displayTitle: String
        get() = title?.takeIf(String::isNotBlank)
            ?: project?.takeIf(String::isNotBlank)
            ?: cwd?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)
            ?: id
}

enum class ApprovalDecision(val wireValue: String) {
    ALLOW("allow"),
    DENY("deny"),
}

/** One folder on a linked computer, offered by the daemon's fs browser. */
data class FsEntry(
    val name: String,
    val path: String,
)

/** The daemon's answer to one fs_list request. */
data class FsListing(
    val requestId: String,
    val path: String?,
    val parent: String?,
    val entries: List<FsEntry>,
    val truncated: Boolean,
    val error: String?,
)

/** A folder the wearer anchored as a project on one of their computers. */
data class AgentProject(
    val name: String,
    val path: String,
)

/** The daemon's verdict on one thread_start request. */
data class ThreadStartResult(
    val requestId: String,
    val ok: Boolean,
    val provider: AgentProvider?,
    val sessionId: String?,
    val error: String?,
)

/**
 * The daemon's verdict on one session_input request — typing a reply into a
 * session's terminal. Failure reasons (still working, not running in screen
 * or tmux, too long, etc.) come straight from the daemon so the wearer sees
 * the real reason rather than a generic "couldn't send".
 */
data class SessionInputResult(
    val requestId: String,
    val ok: Boolean,
    val error: String?,
)

/**
 * A tool call an agent is holding still for, waiting on the wearer.
 *
 * This is the only thing in the product that is *decidable* rather than
 * observable, so it is kept apart from [AgentPendingRequest]: that one is what
 * monitoring inferred about a session, this one is a live question with an
 * identity to answer.
 */
data class AgentApproval(
    val requestId: String,
    val sessionId: String,
    val provider: AgentProvider,
    val tool: String,
    val summary: String,
    val detail: String? = null,
    val createdAt: Long? = null,
) {
    val sessionKey: String
        get() = "${provider.wireValue}:$sessionId"

    companion object {
        const val MAX_PENDING = 32
    }
}

enum class MessageRole(val wireValue: String, val label: String) {
    USER("user", "YOU"),
    ASSISTANT("assistant", "CC"),
    TOOL("tool", "RUN"),
}

data class AgentMessage(
    val role: MessageRole,
    val text: String,
    val at: Long? = null,
    val tool: String? = null,
)

/** The conversation the wearer currently has open, as served by the daemon. */
data class AgentConversation(
    val sessionKey: String,
    val sessionId: String,
    val provider: AgentProvider,
    val loading: Boolean = true,
    val messages: List<AgentMessage> = emptyList(),
) {
    companion object {
        const val MAX_MESSAGES = 60
    }
}

enum class ConnectionState(val wireValue: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    AUTH_FAILED("auth_failed"),
}

data class ProviderConnectionState(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val detail: String? = null,
)
