package com.anezium.rokidbus.plugin.agents

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

data class AgentdConfig(
    val host: String,
    val port: Int,
    val token: String,
    val name: String,
) {
    val configured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()
}

data class OpenClawConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val token: String,
) {
    val configured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 18789
    }
}

data class AgentsConfig(
    val agentdEnabled: Boolean,
    val agentd: AgentdConfig?,
    val openClawEnabled: Boolean,
    val openClaw: OpenClawConfig?,
    val directComputers: List<DirectComputer> = emptyList(),
) {
    // The agentd link (Claude Code and Codex) needs no configuration any more:
    // enabling it makes the phone listen, and the daemon on the LAN finds it
    // by itself. A saved direct ws(s):// computer is itself the opt-in.
    val shouldMonitor: Boolean
        get() = agentdEnabled ||
            openClawEnabled && openClaw?.configured == true ||
            directComputers.isNotEmpty()
}

sealed interface AgentdPairingParseResult {
    data class Valid(val config: AgentdConfig) : AgentdPairingParseResult
    data class Invalid(val reason: String) : AgentdPairingParseResult
}

object AgentdPairingParser {
    fun parse(raw: String): AgentdPairingParseResult {
        val json = runCatching { JSONObject(raw.trim()) }.getOrNull()
            ?: return AgentdPairingParseResult.Invalid("Pairing data is not valid JSON.")
        if (json.intOrNull("v") != 1) {
            return AgentdPairingParseResult.Invalid("Unsupported pairing version.")
        }
        if (json.nullableString("kind", MAX_WIRE_TYPE_CHARS) != "nexus-agentd") {
            return AgentdPairingParseResult.Invalid("Pairing kind must be nexus-agentd.")
        }
        val host = json.identifierOrNull("host", MAX_HOST_CHARS)
            ?: return AgentdPairingParseResult.Invalid("Host is missing.")
        val port = json.intOrNull("port") ?: -1
        val token = json.identifierOrNull("token", MAX_AUTH_TOKEN_CHARS)
            ?: return AgentdPairingParseResult.Invalid("Token is missing.")
        val name = json.nullableString("name", MAX_HUD_LABEL_CHARS) ?: host
        if (port !in 1..65535) return AgentdPairingParseResult.Invalid("Port is invalid.")
        return AgentdPairingParseResult.Valid(AgentdConfig(host, port, token, name))
    }
}

data class TrustedMachine(
    val machineId: String,
    val name: String,
    val lastSeenAtMs: Long?,
)

internal enum class MachineTrustResult(val rejectionReason: String? = null) {
    TRUSTED,
    NEWLY_TRUSTED,

    /** We know this machine id, under a different token. */
    REJECTED_BAD_TOKEN(AgentdProtocolCodec.REJECT_BAD_TOKEN),

    /** A stranger, and the wearer is not holding the door open. */
    REJECTED_NOT_INVITED(AgentdProtocolCodec.REJECT_UNKNOWN_MACHINE),
    ;

    val isRejection: Boolean
        get() = rejectionReason != null
}

internal fun decideMachineTrust(
    knownToken: String?,
    hasAnyTrustedMachine: Boolean,
    linkWindowOpen: Boolean,
    presentedToken: String,
): MachineTrustResult = when {
    knownToken != null && knownToken == presentedToken -> MachineTrustResult.TRUSTED
    knownToken != null -> MachineTrustResult.REJECTED_BAD_TOKEN
    !hasAnyTrustedMachine || linkWindowOpen -> MachineTrustResult.NEWLY_TRUSTED
    else -> MachineTrustResult.REJECTED_NOT_INVITED
}

internal fun linkWindowDeadline(now: Long): Long =
    now.coerceAtMost(Long.MAX_VALUE - LINK_WINDOW_DURATION_MS) + LINK_WINDOW_DURATION_MS

internal fun isLinkWindowDeadlineOpen(deadline: Long, now: Long): Boolean =
    deadline - now in 1L..LINK_WINDOW_DURATION_MS

class AgentsConfigStore(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AgentsConfig {
        val agentdHost = prefs.getString(KEY_AGENTD_HOST, null)
        val agentd = agentdHost?.let {
            AgentdConfig(
                host = it,
                port = prefs.getInt(KEY_AGENTD_PORT, 8792),
                token = prefs.getString(KEY_AGENTD_TOKEN, "").orEmpty(),
                name = prefs.getString(KEY_AGENTD_NAME, it).orEmpty(),
            )
        }?.takeIf(AgentdConfig::configured)
        val openClawHost = prefs.getString(KEY_OPENCLAW_HOST, null)
        val openClaw = openClawHost?.let {
            OpenClawConfig(
                host = it,
                port = prefs.getInt(KEY_OPENCLAW_PORT, OpenClawConfig.DEFAULT_PORT),
                token = prefs.getString(KEY_OPENCLAW_TOKEN, "").orEmpty(),
            )
        }?.takeIf(OpenClawConfig::configured)
        return AgentsConfig(
            agentdEnabled = prefs.getBoolean(KEY_AGENTD_ENABLED, false),
            agentd = agentd,
            openClawEnabled = prefs.getBoolean(KEY_OPENCLAW_ENABLED, false),
            openClaw = openClaw,
            directComputers = readDirectComputers(),
        )
    }

    fun saveAgentd(config: AgentdConfig?, enabled: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_AGENTD_ENABLED, enabled)
            if (config == null) {
                remove(KEY_AGENTD_HOST)
                remove(KEY_AGENTD_PORT)
                remove(KEY_AGENTD_TOKEN)
                remove(KEY_AGENTD_NAME)
            } else {
                putString(KEY_AGENTD_HOST, config.host)
                putInt(KEY_AGENTD_PORT, config.port)
                putString(KEY_AGENTD_TOKEN, config.token)
                putString(KEY_AGENTD_NAME, config.name)
            }
        }.apply()
    }

    fun saveOpenClaw(config: OpenClawConfig?, enabled: Boolean) {
        val previous = load().openClaw
        prefs.edit().apply {
            putBoolean(KEY_OPENCLAW_ENABLED, enabled)
            if (config == null) {
                remove(KEY_OPENCLAW_HOST)
                remove(KEY_OPENCLAW_PORT)
                remove(KEY_OPENCLAW_TOKEN)
            } else {
                putString(KEY_OPENCLAW_HOST, config.host)
                putInt(KEY_OPENCLAW_PORT, config.port)
                putString(KEY_OPENCLAW_TOKEN, config.token)
            }
            if (config != previous) {
                remove(KEY_OPENCLAW_DEVICE_TOKEN)
            }
        }.apply()
    }

    fun openClawSeed(): ByteArray? = prefs.getString(KEY_OPENCLAW_SEED, null)
        ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
        ?.takeIf { it.size == 32 }

    fun saveOpenClawSeed(seed: ByteArray) {
        require(seed.size == 32)
        prefs.edit().putString(
            KEY_OPENCLAW_SEED,
            android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP),
        ).apply()
    }

    fun openClawDeviceToken(): String? =
        prefs.getString(KEY_OPENCLAW_DEVICE_TOKEN, null)?.takeIf(String::isNotBlank)

    fun saveOpenClawDeviceToken(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString(KEY_OPENCLAW_DEVICE_TOKEN, token).apply()
    }

    /**
     * Trust on first use: the first computer that dials in is remembered with its
     * token, and later connections must present the same one. No code to type,
     * and a stranger on the network cannot take over a known machine's identity.
     */
    fun isMachineTrusted(machineId: String, token: String): Boolean {
        val known = prefs.getString(machineKey(machineId), null) ?: return false
        return known == token
    }

    /**
     * Atomically applies the LAN trust-on-first-use/link-window policy.
     * Existing machine tokens are never changed by this operation.
     */
    @Synchronized
    internal fun authorizeMachine(
        machineId: String,
        token: String,
        machineName: String,
    ): MachineTrustResult {
        val key = machineKey(machineId)
        val knownToken = prefs.getString(key, null)
        val hasAnyTrustedMachine = prefs.all.keys.any { it.startsWith(KEY_MACHINE_PREFIX) }
        val result = decideMachineTrust(
            knownToken = knownToken,
            hasAnyTrustedMachine = hasAnyTrustedMachine,
            linkWindowOpen = isLinkWindowOpen(),
            presentedToken = token,
        )
        if (result == MachineTrustResult.NEWLY_TRUSTED) {
            prefs.edit()
                .putString(key, token)
                .putString("$KEY_MACHINE_NAME_PREFIX$machineId", machineName)
                .remove(KEY_LINK_WINDOW_DEADLINE)
                .apply()
        }
        return result
    }

    fun trustMachine(machineId: String, token: String, machineName: String) {
        prefs.edit()
            .putString(machineKey(machineId), token)
            .putString("$KEY_MACHINE_NAME_PREFIX$machineId", machineName)
            .apply()
    }

    fun armLinkWindow() {
        prefs.edit()
            .putLong(KEY_LINK_WINDOW_DEADLINE, linkWindowDeadline(elapsedRealtime()))
            .apply()
    }

    @Synchronized
    fun isLinkWindowOpen(): Boolean = linkWindowRemainingMs() > 0L

    @Synchronized
    fun linkWindowRemainingMs(): Long {
        val deadline = prefs.getLong(KEY_LINK_WINDOW_DEADLINE, 0L)
        val now = elapsedRealtime()
        val remaining = if (isLinkWindowDeadlineOpen(deadline, now)) deadline - now else 0L
        if (remaining == 0L && deadline != 0L) {
            prefs.edit().remove(KEY_LINK_WINDOW_DEADLINE).apply()
        }
        return remaining
    }

    fun trustedMachineNames(): List<String> = prefs.all.keys
        .filter { it.startsWith(KEY_MACHINE_NAME_PREFIX) }
        .mapNotNull { prefs.getString(it, null) }
        .sorted()

    fun trustedMachines(): List<TrustedMachine> = prefs.all.keys
        .filter { it.startsWith(KEY_MACHINE_PREFIX) }
        .map { it.removePrefix(KEY_MACHINE_PREFIX) }
        .map { id ->
            TrustedMachine(
                machineId = id,
                name = prefs.getString("$KEY_MACHINE_NAME_PREFIX$id", null)?.takeIf(String::isNotBlank) ?: id,
                lastSeenAtMs = prefs.getLong("$KEY_MACHINE_SEEN_PREFIX$id", 0L).takeIf { it > 0L },
            )
        }
        .sortedBy { it.name.lowercase() }

    fun touchMachineSeen(machineId: String, now: Long = System.currentTimeMillis()) {
        if (prefs.getString(machineKey(machineId), null) == null) return
        prefs.edit().putLong("$KEY_MACHINE_SEEN_PREFIX$machineId", now).apply()
    }

    fun forgetMachine(machineId: String) {
        prefs.edit()
            .remove(machineKey(machineId))
            .remove("$KEY_MACHINE_NAME_PREFIX$machineId")
            .remove("$KEY_MACHINE_SEEN_PREFIX$machineId")
            .remove("$KEY_PROJECTS_PREFIX$machineId")
            .remove("$KEY_DIRECT_URL_PREFIX$machineId")
            .remove("$KEY_DIRECT_NAME_PREFIX$machineId")
            .remove("$KEY_DIRECT_SEEN_PREFIX$machineId")
            .apply()
    }

    fun directComputers(): List<DirectComputer> = readDirectComputers()

    fun saveDirectComputer(computer: DirectComputer) {
        prefs.edit()
            .putString("$KEY_DIRECT_URL_PREFIX${computer.computerId}", computer.url)
            .putString("$KEY_DIRECT_NAME_PREFIX${computer.computerId}", computer.name)
            .putLong("$KEY_DIRECT_SEEN_PREFIX${computer.computerId}", System.currentTimeMillis())
            .apply()
    }

    fun touchDirectComputerSeen(computerId: String, now: Long = System.currentTimeMillis()) {
        if (prefs.getString("$KEY_DIRECT_URL_PREFIX$computerId", null) == null) return
        prefs.edit().putLong("$KEY_DIRECT_SEEN_PREFIX$computerId", now).apply()
    }

    fun listedComputers(): List<TrustedMachine> =
        (trustedMachines() + directComputers().map { it.asListed() })
            .sortedBy { it.name.lowercase() }

    private fun readDirectComputers(): List<DirectComputer> = prefs.all.keys
        .filter { it.startsWith(KEY_DIRECT_URL_PREFIX) }
        .map { it.removePrefix(KEY_DIRECT_URL_PREFIX) }
        .mapNotNull { id ->
            val url = prefs.getString("$KEY_DIRECT_URL_PREFIX$id", null)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            DirectComputer(
                computerId = id,
                name = prefs.getString("$KEY_DIRECT_NAME_PREFIX$id", null)?.takeIf { it.isNotBlank() }
                    ?: DirectComputerUrl.redactUserinfo(url),
                url = url,
                lastSeenAtMs = prefs.getLong("$KEY_DIRECT_SEEN_PREFIX$id", 0L).takeIf { it > 0L },
            )
        }
        .sortedBy { it.name.lowercase() }

    fun projects(machineId: String): List<AgentProject> {
        val raw = prefs.getString("$KEY_PROJECTS_PREFIX$machineId", null) ?: return emptyList()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val name = json.nullableString("name", MAX_HUD_LABEL_CHARS) ?: continue
                val path = json.nullableString("path", MAX_PATH_CHARS) ?: continue
                add(AgentProject(name, path))
            }
        }
    }

    fun addProject(machineId: String, project: AgentProject) {
        val current = projects(machineId).filterNot { it.path == project.path }
        saveProjects(machineId, (current + project).takeLast(MAX_PROJECTS_PER_MACHINE))
    }

    fun removeProject(machineId: String, path: String) {
        saveProjects(machineId, projects(machineId).filterNot { it.path == path })
    }

    private fun saveProjects(machineId: String, projects: List<AgentProject>) {
        val key = "$KEY_PROJECTS_PREFIX$machineId"
        if (projects.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val array = org.json.JSONArray()
        projects.forEach { project ->
            array.put(JSONObject().put("name", project.name).put("path", project.path))
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun cancelLinkWindow() {
        prefs.edit().remove(KEY_LINK_WINDOW_DEADLINE).apply()
    }

    fun forgetMachines() {
        prefs.edit().apply {
            prefs.all.keys
                .filter {
                    it.startsWith(KEY_MACHINE_PREFIX) ||
                        it.startsWith(KEY_MACHINE_NAME_PREFIX) ||
                        it.startsWith(KEY_MACHINE_SEEN_PREFIX)
                }
                .forEach(::remove)
        }.apply()
    }

    private fun machineKey(machineId: String): String = "$KEY_MACHINE_PREFIX$machineId"

    fun notificationFingerprint(sessionKey: String): String? =
        prefs.getString("$KEY_NOTIFICATION_PREFIX$sessionKey", null)

    fun saveNotificationFingerprint(sessionKey: String, fingerprint: String) {
        prefs.edit().putString("$KEY_NOTIFICATION_PREFIX$sessionKey", fingerprint).apply()
    }

    /**
     * An alert fingerprint outlives the session it describes, so without this
     * the preferences file grows by one entry per session the wearer ever saw.
     */
    fun forgetNotificationFingerprints(sessionKeys: Set<String>) {
        if (sessionKeys.isEmpty()) return
        prefs.edit().apply {
            sessionKeys.forEach { remove("$KEY_NOTIFICATION_PREFIX$it") }
        }.apply()
    }

    private companion object {
        const val PREFS = "nexus_plugin_agents"
        const val KEY_AGENTD_ENABLED = "agentd.enabled"
        const val KEY_AGENTD_HOST = "agentd.host"
        const val KEY_AGENTD_PORT = "agentd.port"
        const val KEY_AGENTD_TOKEN = "agentd.token"
        const val KEY_AGENTD_NAME = "agentd.name"
        const val KEY_OPENCLAW_ENABLED = "openclaw.enabled"
        const val KEY_OPENCLAW_HOST = "openclaw.host"
        const val KEY_OPENCLAW_PORT = "openclaw.port"
        const val KEY_OPENCLAW_TOKEN = "openclaw.token"
        const val KEY_OPENCLAW_SEED = "openclaw.device_seed"
        const val KEY_OPENCLAW_DEVICE_TOKEN = "openclaw.device_token"
        const val KEY_NOTIFICATION_PREFIX = "notification."
        const val KEY_MACHINE_PREFIX = "machine.token."
        const val KEY_MACHINE_NAME_PREFIX = "machine.name."
        const val KEY_MACHINE_SEEN_PREFIX = "machine.seen."
        const val KEY_PROJECTS_PREFIX = "machine.projects."
        const val KEY_DIRECT_URL_PREFIX = "direct.url."
        const val KEY_DIRECT_NAME_PREFIX = "direct.name."
        const val KEY_DIRECT_SEEN_PREFIX = "direct.seen."
        const val MAX_PROJECTS_PER_MACHINE = 30
        const val KEY_LINK_WINDOW_DEADLINE = "machine.link_window_deadline"
    }
}

internal const val LINK_WINDOW_DURATION_MS = 2L * 60L * 1000L
