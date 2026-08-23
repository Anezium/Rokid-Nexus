package com.anezium.rokidbus.plugin.agents

/**
 * The identity a Codex app-server session is bound to. Shared by the direct
 * `ws(s)://` path and the Alleycat/Iroh path so [CodexSessionBridge] stays
 * transport-agnostic.
 */
data class CodexComputerRef(
    val computerId: String,
    val name: String,
)

fun DirectComputer.asCodexRef(): CodexComputerRef = CodexComputerRef(computerId, name)

fun isCodexRemoteComputer(machineId: String): Boolean =
    machineId.startsWith(DirectComputer.ID_PREFIX) ||
        machineId.startsWith(AlleycatComputer.ID_PREFIX)
