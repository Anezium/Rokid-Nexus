package com.anezium.rokidbus.plugin.agents

import java.util.UUID
import org.json.JSONObject

internal data class LitterApproval(
    val display: AgentApproval,
    val wireId: Any,
    val epoch: Long,
    val turnId: String,
    val itemId: String,
    val expiresAt: Long,
    val canAllow: Boolean,
)

/** A reply must match the live connection and the exact turn that asked for it. */
internal class LitterApprovals(private val now: () -> Long) {
    private val pending = linkedMapOf<String, LitterApproval>()
    private val seenWireIds = mutableSetOf<String>()

    fun offer(
        frame: JSONObject,
        epoch: Long,
        activeTurn: String?,
        itemDetail: String?,
        createdAt: Long = System.currentTimeMillis(),
    ): LitterApproval? {
        val wireId = frame.opt("id") ?: return null
        val identity = rpcIdentity(wireId) ?: return null
        if (identity in seenWireIds || seenWireIds.size >= 256 || pending.size >= AgentApproval.MAX_PENDING) return null
        val method = frame.optString("method")
        if (method !in METHODS) return null
        val params = frame.optJSONObject("params") ?: return null
        val thread = params.wireId("threadId") ?: return null
        val turn = params.wireId("turnId")?.takeIf { it == activeTurn } ?: return null
        val item = params.wireId("itemId") ?: return null
        val command = params.opt("command") as? String
        val network = params.optJSONObject("networkApprovalContext")
        val detail = when {
            network != null -> "Network access: ${network.wireString("protocol", 32).orEmpty()} " +
                network.wireString("host", 512).orEmpty()
            method == COMMAND -> command
            else -> itemDetail
        }
        val decisions = params.optJSONArray("availableDecisions")
        val canAllow = !detail.isNullOrBlank() && detail.length <= LitterProtocol.MAX_TEXT &&
            (decisions == null || "accept" in decisions.strings()) &&
            (network == null || network.wireString("host", 512) != null) &&
            (!params.has("additionalPermissions") || params.isNull("additionalPermissions"))
        val reason = params.wireString("reason", 2_000)
        val cwd = params.wireString("cwd", 4_096) ?: params.wireString("grantRoot", 4_096)
        val approval = LitterApproval(
            display = AgentApproval(
                requestId = UUID.randomUUID().toString(), sessionId = thread, provider = AgentProvider.CODEX,
                tool = if (method == COMMAND) "Command" else "File changes",
                summary = (detail ?: reason ?: "Review this request on the computer").take(240),
                detail = listOfNotNull(detail?.take(LitterProtocol.MAX_TEXT), cwd?.let { "Folder: $it" }, reason,
                    if (!canAllow) "Approval unavailable here; review the complete request on the computer." else null)
                    .joinToString("\n\n"),
                createdAt = createdAt,
            ),
            wireId = wireId, epoch = epoch, turnId = turn, itemId = item,
            expiresAt = now() + TTL_MS, canAllow = canAllow,
        )
        pending[approval.display.requestId] = approval
        seenWireIds += identity
        return approval
    }

    fun answer(id: String, sessionId: String, epoch: Long, activeTurn: String?, allow: Boolean): LitterApproval? {
        val approval = pending[id] ?: return null
        if (approval.display.sessionId != sessionId || approval.epoch != epoch ||
            approval.turnId != activeTurn || now() >= approval.expiresAt || (allow && !approval.canAllow)
        ) return null
        pending.remove(id)
        return approval
    }

    fun resolve(wireId: Any?, sessionId: String): List<LitterApproval> = removeWhere {
        rpcIdentity(it.wireId) == rpcIdentity(wireId) && it.display.sessionId == sessionId
    }

    fun invalidateWire(wireId: Any?): List<LitterApproval> = removeWhere {
        rpcIdentity(it.wireId) == rpcIdentity(wireId)
    }

    fun finishTurn(sessionId: String, turnId: String): List<LitterApproval> = removeWhere {
        it.display.sessionId == sessionId && it.turnId == turnId
    }

    fun finishItem(sessionId: String, turnId: String, itemId: String): List<LitterApproval> = removeWhere {
        it.display.sessionId == sessionId && it.turnId == turnId && it.itemId == itemId
    }

    fun expired(): List<LitterApproval> = removeWhere { now() >= it.expiresAt }
    fun get(id: String): LitterApproval? = pending[id]
    fun clear() { pending.clear(); seenWireIds.clear() }

    private fun removeWhere(predicate: (LitterApproval) -> Boolean): List<LitterApproval> {
        val removed = pending.values.filter(predicate)
        removed.forEach { pending.remove(it.display.requestId) }
        return removed
    }

    companion object {
        const val COMMAND = "item/commandExecution/requestApproval"
        const val FILE_CHANGE = "item/fileChange/requestApproval"
        const val TTL_MS = 5 * 60_000L
        val METHODS = setOf(COMMAND, FILE_CHANGE)
    }
}
