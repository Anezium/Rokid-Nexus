package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbSession
import com.anezium.rokidbus.phone.selfarm.adb.ManualShellResult
import java.io.IOException

internal fun interface GlassesManualTlsReconnect {
    fun reconnect(host: String, oldAdbdPid: String, preferredPort: Int): ManualAdbSession
}

/** Phone-side orchestration mirror of glasses-hub SelfArmArmSequence. */
internal object GlassesManualArmRunner {
    fun run(
        initialSession: ManualAdbSession,
        dialogHost: String,
        reconnect: GlassesManualTlsReconnect,
    ) {
        var session = initialSession
        try {
            val watchdogScript = readStagedScript(session, WATCHDOG_SOURCE_PATH, "watchdog")
            val bridgeScript = readStagedScript(session, BRIDGE_SOURCE_PATH, "command bridge")
            runCatching { session.shell(CLEAN_STAGED_ASSETS_COMMAND) }

            val prepare = session.shell(
                GlassesManualArmCommand.buildPrepare(watchdogScript, bridgeScript),
            )
            if (prepare.exitCode != 0 || !GlassesManualArmCommand.prepareSucceeded(prepare.output)) {
                throw IOException("Self-arm preparation failed: ${allOutput(prepare)}")
            }

            var arm = session.shell(GlassesManualArmCommand.buildArm(restartWatchdog = true))
            if (arm.exitCode != 0 || !GlassesManualArmCommand.armSucceeded(arm.output)) {
                throw IOException("Watchdog arm failed before network teardown: ${allOutput(arm)}")
            }

            val posture = awaitPosture(session, requireSafe = false, timeoutMs = RESTART_DECISION_TIMEOUT_MS)
            if (posture.legacyLoopbackListening) {
                val restart = session.shell(GlassesManualArmCommand.buildRestartAdbd())
                val request = if (restart.exitCode == 0) {
                    GlassesManualArmCommand.restartRequest(restart.output)
                } else {
                    null
                }
                if (request == null) {
                    throw IOException("adbd restart was not scheduled: ${allOutput(restart)}")
                }
                val previousPort = session.port
                session.close()
                session = reconnect.reconnect(dialogHost, request.oldAdbdPid, previousPort)

                arm = session.shell(GlassesManualArmCommand.buildArm(restartWatchdog = true))
                if (arm.exitCode != 0 || !GlassesManualArmCommand.armSucceeded(arm.output)) {
                    throw IOException("Post-restart watchdog arm failed: ${allOutput(arm)}")
                }
            }
            awaitPosture(session, requireSafe = true, timeoutMs = SAFE_POSTURE_TIMEOUT_MS)
        } finally {
            runCatching { session.shell(CLEAN_STAGED_ASSETS_COMMAND) }
            runCatching { session.close() }
        }
    }

    private fun readStagedScript(session: ManualAdbSession, path: String, label: String): String {
        val result = session.shell("cat '$path'")
        if (result.exitCode != 0 || result.output.isBlank()) {
            throw IOException("Could not read the glasses-owned $label script: ${allOutput(result)}")
        }
        return result.output.replace("\r\n", "\n").replace("\r", "\n")
    }

    private fun awaitPosture(
        session: ManualAdbSession,
        requireSafe: Boolean,
        timeoutMs: Long,
    ): RemoteNetworkPosture {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var last: RemoteNetworkPosture? = null
        do {
            val probe = session.shell(NETWORK_POSTURE_COMMAND)
            if (probe.exitCode == 0) last = parsePosture(probe.output)
            if (last != null) {
                val propertiesClear = last.persistentPort in DISABLED_PORT_VALUES &&
                    last.servicePort in DISABLED_PORT_VALUES
                if (propertiesClear && (!requireSafe || !last.legacyLoopbackListening)) return last
            }
            try {
                Thread.sleep(POSTURE_POLL_MS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while verifying legacy ADB teardown", exception)
            }
        } while (System.nanoTime() < deadline)
        throw IOException(
            "Legacy ADB teardown was not verified: persist=${last?.persistentPort ?: "unknown"} " +
                "service=${last?.servicePort ?: "unknown"} " +
                "loopbackListening=${last?.legacyLoopbackListening ?: true}",
        )
    }

    private fun parsePosture(output: String): RemoteNetworkPosture? {
        val line = output.lineSequence().lastOrNull { it.contains(POSTURE_SENTINEL) } ?: return null
        val values = line.substringAfter(POSTURE_SENTINEL)
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { part ->
                val key = part.substringBefore("=", "")
                val value = part.substringAfter("=", "")
                key.takeIf { it.isNotBlank() }?.let { it to value }
            }
            .toMap()
        return RemoteNetworkPosture(
            persistentPort = values["persist"].orEmpty().decodeEmpty(),
            servicePort = values["service"].orEmpty().decodeEmpty(),
            legacyLoopbackListening = values["legacy_loopback"] != "0",
        )
    }

    private fun String.decodeEmpty(): String = if (this == "empty") "" else this

    private fun allOutput(result: ManualShellResult): String =
        result.allOutput.trim().take(500)

    private data class RemoteNetworkPosture(
        val persistentPort: String,
        val servicePort: String,
        val legacyLoopbackListening: Boolean,
    )

    private const val WATCHDOG_SOURCE_PATH =
        "/sdcard/Android/data/com.anezium.rokidbus.glasses/files/cmd_bridge/manual_selfarm/watchdog.sh"
    private const val BRIDGE_SOURCE_PATH =
        "/sdcard/Android/data/com.anezium.rokidbus.glasses/files/cmd_bridge/manual_selfarm/bridge.sh"
    private const val CLEAN_STAGED_ASSETS_COMMAND =
        "rm -f '$WATCHDOG_SOURCE_PATH' '$BRIDGE_SOURCE_PATH'"
    private const val POSTURE_SENTINEL = "ROKID_NEXUS_NETWORK_POSTURE"
    private const val RESTART_DECISION_TIMEOUT_MS = 3_000L
    private const val SAFE_POSTURE_TIMEOUT_MS = 8_000L
    private const val POSTURE_POLL_MS = 200L
    private val DISABLED_PORT_VALUES = setOf("", "-1", "0")

    private val NETWORK_POSTURE_COMMAND = buildString {
        appendLine("PERSIST_PORT=\"\$(getprop persist.adb.tcp.port)\"")
        appendLine("SERVICE_PORT=\"\$(getprop service.adb.tcp.port)\"")
        appendLine("LEGACY_LOOPBACK=0")
        appendLine("if (toybox ss -lnt 2>/dev/null || ss -lnt 2>/dev/null) | grep -E '(^|[.:])5555[[:space:]]' >/dev/null 2>&1; then")
        appendLine("  LEGACY_LOOPBACK=1")
        appendLine("elif grep -E ':[0]*15B3[[:space:]]' /proc/net/tcp /proc/net/tcp6 >/dev/null 2>&1; then")
        appendLine("  LEGACY_LOOPBACK=1")
        appendLine("fi")
        appendLine(
            "echo '$POSTURE_SENTINEL persist='\"\${PERSIST_PORT:-empty}\"" +
                "' service='\"\${SERVICE_PORT:-empty}\"" +
                "' legacy_loopback='\"\$LEGACY_LOOPBACK\"",
        )
    }
}
