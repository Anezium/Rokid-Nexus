package com.anezium.rokidbus.plugin.agents

import org.json.JSONArray
import org.json.JSONObject

/** Original implementation of the public Codex app-server v2 wire contract. */
internal object LitterProtocol {
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    const val MAX_TEXT = 16_000
    const val MAX_SESSIONS = 200

    fun request(id: String, method: String, params: JSONObject) =
        JSONObject().put("id", id).put("method", method).put("params", params)

    fun input(text: String): JSONArray = JSONArray().put(JSONObject().put("type", "text").put("text", text))

    fun thread(json: JSONObject, endpoint: LitterEndpoint): AgentSession? {
        val id = json.wireString("id", 256) ?: return null
        val cwd = json.wireString("cwd", 4_096)
        return AgentSession(
            id = id, provider = AgentProvider.CODEX, machineName = endpoint.name,
            title = json.wireString("name", 240) ?: json.wireString("preview", 240), cwd = cwd,
            project = cwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\'),
            status = status(json.optJSONObject("status")),
            lastActivityAt = json.optLong("updatedAt", 0).takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000),
        )
    }

    fun status(json: JSONObject?): AgentStatus = when (json?.optString("type")) {
        "active" -> if (json.optJSONArray("activeFlags").strings().any {
                it == "waitingOnApproval" || it == "waitingOnUserInput"
            }) AgentStatus.NEEDS_YOU else AgentStatus.WORKING
        "systemError" -> AgentStatus.ERROR
        else -> AgentStatus.IDLE
    }

    fun item(json: JSONObject): AgentMessage? {
        val type = json.optString("type")
        val text = when (type) {
            "userMessage" -> json.optJSONArray("content").objects().mapNotNull {
                if (it.optString("type") == "text") it.wireString("text", MAX_TEXT) else null
            }.joinToString("\n")
            "agentMessage", "plan" -> json.wireString("text", MAX_TEXT).orEmpty()
            "commandExecution" -> listOfNotNull(json.wireString("command", 2_000),
                json.wireString("aggregatedOutput", MAX_TEXT)).joinToString("\n")
            "fileChange" -> json.optJSONArray("changes").objects().take(20).mapNotNull {
                it.wireString("path", 1_000)
            }.joinToString("\n")
            "mcpToolCall" -> listOfNotNull(json.wireString("server", 120), json.wireString("tool", 120)).joinToString(" / ")
            else -> return null
        }.takeLast(MAX_TEXT)
        val role = when (type) {
            "userMessage" -> MessageRole.USER
            "agentMessage", "plan" -> MessageRole.ASSISTANT
            else -> MessageRole.TOOL
        }
        return AgentMessage(role, text, tool = if (role == MessageRole.TOOL) type else null)
    }
}

internal fun JSONObject.wireString(key: String, max: Int): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() }?.take(max)

internal fun JSONObject.wireId(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 256 && it.none { char -> char.code < 32 } }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

internal fun rpcIdentity(id: Any?): String? = when (id) {
    is String -> id.takeIf { it.isNotEmpty() && it.length <= 256 }?.let { "s:$it" }
    is Int, is Long -> "n:$id"
    else -> null
}
