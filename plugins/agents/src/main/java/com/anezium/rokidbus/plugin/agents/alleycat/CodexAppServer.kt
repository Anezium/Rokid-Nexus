package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONArray
import org.json.JSONObject

/** Four verdicts the HUD can send back to a Codex approval request. */
enum class ApprovalVerdict(val wire: String) {
    ACCEPT("Accept"),
    ACCEPT_FOR_SESSION("AcceptForSession"),
    DECLINE("Decline"),
    CANCEL("Cancel"),
}

sealed class JsonRpcId {
    data class NumberId(val value: Long) : JsonRpcId()
    data class StringId(val value: String) : JsonRpcId()

    fun putOn(json: JSONObject) {
        when (this) {
            is NumberId -> json.put("id", value)
            is StringId -> json.put("id", value)
        }
    }

    companion object {
        fun from(json: JSONObject): JsonRpcId {
            if (!json.has("id") || json.isNull("id")) {
                throw AlleycatException("JSON-RPC message missing id")
            }
            return when (val raw = json.get("id")) {
                is Number -> NumberId(raw.toLong())
                is String -> StringId(raw)
                else -> throw AlleycatException("unsupported JSON-RPC id")
            }
        }
    }
}

sealed class CodexInbound {
    data class Result(val id: JsonRpcId, val result: JSONObject) : CodexInbound()
    data class Error(val id: JsonRpcId, val code: Int, val message: String) : CodexInbound()
    data class Notification(val method: String, val params: JSONObject?) : CodexInbound() {
        val isItemDelta: Boolean
            get() = method.contains("delta", ignoreCase = true) || method.startsWith("item/")
        val isCommandExecution: Boolean
            get() = method.contains("commandExecution") || method.contains("command_execution")
    }
    data class ApprovalRequest(
        val id: JsonRpcId,
        val method: String,
        val params: JSONObject,
    ) : CodexInbound()
}

data class CodexThread(
    val id: String,
    val preview: String? = null,
    val raw: JSONObject,
)

data class ThreadListPage(
    val data: List<CodexThread>,
    val nextCursor: String?,
)

data class CodexTurn(
    val id: String,
    val status: String? = null,
    val raw: JSONObject,
)

data class TurnSteerResult(
    val turnId: String?,
    val raw: JSONObject,
)

/**
 * Minimal Codex app-server JSON-RPC client. Methods match the HUD table in
 * plan 022; the `"jsonrpc":"2.0"` envelope is omitted on the wire.
 */
class CodexAppServerClient(
    private val transport: AlleycatTransport,
    private val onNotification: (CodexInbound.Notification) -> Unit = {},
    private val onApprovalRequest: (CodexInbound.ApprovalRequest) -> Unit = {},
) {
    private var nextId = 1L

    fun threadList(cursor: String? = null, limit: Int? = null): ThreadListPage {
        val params = JSONObject()
        if (cursor != null) params.put("cursor", cursor)
        if (limit != null) params.put("limit", limit)
        val result = request("thread/list", params)
        val data = result.optionalArray("data")
            ?: throw AlleycatException("thread/list result missing data")
        val threads = data.toObjectList().map { item ->
            CodexThread(
                id = item.requiredString("id"),
                preview = item.optionalString("preview"),
                raw = item,
            )
        }
        return ThreadListPage(
            data = threads,
            nextCursor = result.optionalString("nextCursor"),
        )
    }

    fun threadStart(model: String? = null, cwd: String? = null): CodexThread {
        val params = JSONObject()
        if (model != null) params.put("model", model)
        if (cwd != null) params.put("cwd", cwd)
        return parseThread(request("thread/start", params.takeIf { it.length() > 0 }))
    }

    fun threadResume(threadId: String): CodexThread {
        return parseThread(request("thread/resume", JSONObject().put("threadId", threadId)))
    }

    fun turnStart(threadId: String, text: String): CodexTurn {
        val params = JSONObject()
            .put("threadId", threadId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        val result = request("turn/start", params)
        val turn = result.optionalObject("turn")
            ?: throw AlleycatException("turn/start result missing turn")
        return CodexTurn(
            id = turn.requiredString("id"),
            status = turn.optionalString("status"),
            raw = turn,
        )
    }

    fun turnSteer(threadId: String, text: String, expectedTurnId: String? = null): TurnSteerResult {
        val params = JSONObject()
            .put("threadId", threadId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (expectedTurnId != null) params.put("expectedTurnId", expectedTurnId)
        val result = request("turn/steer", params)
        return TurnSteerResult(
            turnId = result.optionalString("turnId"),
            raw = result,
        )
    }

    /** Reload path used when handshake `attached` is `drift_reload`. */
    fun reloadState(cursor: String? = null): ThreadListPage = threadList(cursor)

    fun replyApproval(id: JsonRpcId, verdict: ApprovalVerdict) {
        AlleycatFraming.write(transport, CodexJsonRpc.approvalReply(id, verdict))
    }

    fun receive(): CodexInbound {
        val inbound = CodexJsonRpc.parse(AlleycatFraming.read(transport))
        when (inbound) {
            is CodexInbound.Notification -> onNotification(inbound)
            is CodexInbound.ApprovalRequest -> onApprovalRequest(inbound)
            else -> Unit
        }
        return inbound
    }

    private fun request(method: String, params: JSONObject?): JSONObject {
        val id = JsonRpcId.NumberId(nextId++)
        AlleycatFraming.write(transport, CodexJsonRpc.request(id, method, params))
        while (true) {
            when (val inbound = receive()) {
                is CodexInbound.Result -> {
                    if (inbound.id == id) return inbound.result
                }
                is CodexInbound.Error -> {
                    if (inbound.id == id) {
                        throw AlleycatException("JSON-RPC error ${inbound.code}: ${inbound.message}")
                    }
                }
                is CodexInbound.Notification, is CodexInbound.ApprovalRequest -> Unit
            }
        }
    }

    private fun parseThread(result: JSONObject): CodexThread {
        val thread = result.optionalObject("thread")
            ?: throw AlleycatException("result missing thread")
        return CodexThread(
            id = thread.requiredString("id"),
            preview = thread.optionalString("preview"),
            raw = thread,
        )
    }
}

object CodexJsonRpc {
    fun request(id: JsonRpcId, method: String, params: JSONObject?): JSONObject {
        val json = JSONObject().put("method", method)
        id.putOn(json)
        if (params != null) json.put("params", params)
        return json
    }

    fun approvalReply(id: JsonRpcId, verdict: ApprovalVerdict): JSONObject {
        val json = JSONObject().put("result", JSONObject().put("decision", verdict.wire))
        id.putOn(json)
        return json
    }

    fun parse(json: JSONObject): CodexInbound {
        val hasMethod = json.has("method") && !json.isNull("method")
        val hasId = json.has("id") && !json.isNull("id")
        val hasResult = json.has("result")
        val hasError = json.has("error")
        return when {
            hasMethod && hasId && !hasResult && !hasError -> parseServerRequest(json)
            hasMethod && !hasId -> CodexInbound.Notification(
                method = json.requiredString("method"),
                params = json.optionalObject("params"),
            )
            hasId && hasError -> parseError(json)
            hasId && hasResult -> {
                val result = json.optJSONObject("result")
                    ?: JSONObject().put("value", json.get("result"))
                CodexInbound.Result(JsonRpcId.from(json), result)
            }
            else -> throw AlleycatException("unrecognized JSON-RPC message")
        }
    }

    private fun parseServerRequest(json: JSONObject): CodexInbound {
        val method = json.requiredString("method")
        if (!isApprovalMethod(method)) {
            throw AlleycatException("unsupported server request: $method")
        }
        return CodexInbound.ApprovalRequest(
            id = JsonRpcId.from(json),
            method = method,
            params = json.optionalObject("params") ?: JSONObject(),
        )
    }

    private fun parseError(json: JSONObject): CodexInbound.Error {
        val error = json.optionalObject("error")
            ?: throw AlleycatException("JSON-RPC error must be an object")
        return CodexInbound.Error(
            id = JsonRpcId.from(json),
            code = error.optInt("code", -1),
            message = error.optionalString("message") ?: "error",
        )
    }

    internal fun isApprovalMethod(method: String): Boolean =
        method.contains("requestApproval") || method.endsWith("Approval")
}
