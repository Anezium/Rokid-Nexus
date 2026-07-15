package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject

data class PluginGrantKey(
    val packageName: String,
    val pluginId: String,
    val signingDigestSha256: String,
)

enum class PluginGrantDecision { APPROVED, DENIED }

data class PluginGrant(
    val key: PluginGrantKey,
    val requestedCapabilities: Set<PluginCapability>,
    val approvedCapabilities: Set<PluginCapability>,
    val decision: PluginGrantDecision,
    val enabled: Boolean,
)

sealed interface PluginGrantState {
    data object Pending : PluginGrantState
    data class Approved(val capabilities: Set<PluginCapability>) : PluginGrantState
    data object Denied : PluginGrantState
    data object Disabled : PluginGrantState
}

fun PhonePluginPrincipal.grantKey(): PluginGrantKey = PluginGrantKey(
    packageName = packageName,
    pluginId = descriptor.id,
    signingDigestSha256 = signingDigestSha256,
)

interface PluginGrantStorage {
    fun read(): String?
    fun write(value: String)
}

class PluginGrantStore(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        SharedPreferencesGrantStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    @Synchronized
    fun stateFor(principal: PhonePluginPrincipal): PluginGrantState {
        val grant = readGrants()[principal.grantKey()] ?: return PluginGrantState.Pending
        if (grant.requestedCapabilities != principal.descriptor.requestedCapabilities) {
            return PluginGrantState.Pending
        }
        return when {
            grant.decision == PluginGrantDecision.DENIED -> PluginGrantState.Denied
            !grant.enabled -> PluginGrantState.Disabled
            else -> PluginGrantState.Approved(grant.approvedCapabilities)
        }
    }

    @Synchronized
    fun approve(
        principal: PhonePluginPrincipal,
        approvedCapabilities: Set<PluginCapability>,
        enabled: Boolean = true,
    ) {
        require(approvedCapabilities.all { it in principal.descriptor.requestedCapabilities })
        update(
            PluginGrant(
                key = principal.grantKey(),
                requestedCapabilities = principal.descriptor.requestedCapabilities,
                approvedCapabilities = approvedCapabilities,
                decision = PluginGrantDecision.APPROVED,
                enabled = enabled,
            ),
        )
    }

    @Synchronized
    fun deny(principal: PhonePluginPrincipal) {
        update(
            PluginGrant(
                key = principal.grantKey(),
                requestedCapabilities = principal.descriptor.requestedCapabilities,
                approvedCapabilities = emptySet(),
                decision = PluginGrantDecision.DENIED,
                enabled = false,
            ),
        )
    }

    @Synchronized
    fun setEnabled(principal: PhonePluginPrincipal, enabled: Boolean) {
        val grants = readGrants().toMutableMap()
        val existing = grants[principal.grantKey()] ?: return
        grants[existing.key] = existing.copy(enabled = enabled)
        writeGrants(grants.values)
    }

    @Synchronized
    fun revoke(principal: PhonePluginPrincipal) {
        val grants = readGrants().toMutableMap()
        grants.remove(principal.grantKey())
        writeGrants(grants.values)
    }

    @Synchronized
    fun reconcile(installed: Collection<PhonePluginPrincipal>) {
        val installedKeys = installed.mapTo(linkedSetOf(), PhonePluginPrincipal::grantKey)
        val retained = readGrants().values.filter { it.key in installedKeys }
        writeGrants(retained)
    }

    @Synchronized
    fun all(): List<PluginGrant> = readGrants().values.sortedWith(GRANT_ORDER)

    @Synchronized
    fun hasGrantFor(principal: PhonePluginPrincipal): Boolean =
        readGrants().containsKey(principal.grantKey())

    private fun update(grant: PluginGrant) {
        val grants = readGrants().toMutableMap()
        grants.keys.removeAll { existing ->
            existing.packageName == grant.key.packageName && existing != grant.key
        }
        grants[grant.key] = grant
        writeGrants(grants.values)
    }

    private fun readGrants(): Map<PluginGrantKey, PluginGrant> =
        PluginGrantCodec.decode(storage.read().orEmpty()).associateBy(PluginGrant::key)

    private fun writeGrants(grants: Collection<PluginGrant>) {
        storage.write(PluginGrantCodec.encode(grants))
    }

    companion object {
        private const val PREFERENCES_NAME = "nexus_plugin_grants"
        private val GRANT_ORDER = compareBy<PluginGrant>(
            { it.key.packageName },
            { it.key.pluginId },
            { it.key.signingDigestSha256 },
        )
    }
}

object PluginGrantCodec {
    fun encode(grants: Collection<PluginGrant>): String {
        val sorted = grants.sortedWith(
            compareBy({ it.key.packageName }, { it.key.pluginId }, { it.key.signingDigestSha256 }),
        )
        val items = JSONArray()
        sorted.forEach { grant ->
            items.put(
                JSONObject()
                    .put("package", grant.key.packageName)
                    .put("pluginId", grant.key.pluginId)
                    .put("signingDigestSha256", grant.key.signingDigestSha256)
                    .put("requested", PluginCapability.serialize(grant.requestedCapabilities))
                    .put("approved", PluginCapability.serialize(grant.approvedCapabilities))
                    .put("decision", grant.decision.name)
                    .put("enabled", grant.enabled),
            )
        }
        return JSONObject().put("version", 1).put("grants", items).toString()
    }

    fun decode(value: String): List<PluginGrant> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(value)
            if (root.optInt("version") != 1) return@runCatching emptyList()
            val items = root.optJSONArray("grants") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val packageName = item.optString("package")
                    val pluginId = item.optString("pluginId")
                    val digest = item.optString("signingDigestSha256")
                    val decision = runCatching {
                        PluginGrantDecision.valueOf(item.optString("decision"))
                    }.getOrNull() ?: continue
                    val requested = parseCapabilities(item.optString("requested")) ?: continue
                    val approved = parseCapabilities(item.optString("approved")) ?: continue
                    if (packageName.isBlank() || pluginId.isBlank() || digest.isBlank() ||
                        !requested.containsAll(approved)
                    ) continue
                    add(
                        PluginGrant(
                            key = PluginGrantKey(packageName, pluginId, digest),
                            requestedCapabilities = requested,
                            approvedCapabilities = approved,
                            decision = decision,
                            enabled = item.optBoolean("enabled"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseCapabilities(value: String): Set<PluginCapability>? {
        val parsed = com.anezium.rokidbus.shared.plugin.PluginCapability.parseList(value)
        return (parsed as? com.anezium.rokidbus.shared.plugin.CapabilityParseResult.Valid)?.capabilities
    }
}

private class SharedPreferencesGrantStorage(
    private val preferences: android.content.SharedPreferences,
) : PluginGrantStorage {
    override fun read(): String? = preferences.getString(KEY_GRANTS, null)

    override fun write(value: String) {
        preferences.edit().putString(KEY_GRANTS, value).commit()
    }

    companion object {
        private const val KEY_GRANTS = "grants_v1"
    }
}
