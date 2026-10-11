package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.skills.SkillOperation
import org.json.JSONArray
import org.json.JSONObject

/**
 * One approved caller/provider/operation relationship. Both principals are keyed by package,
 * plugin id, and signing digest, and the operation by id, major version, and the digest of
 * everything the wearer saw when approving it; any change needs a fresh approval.
 */
data class SkillGrant(
    val caller: PluginGrantKey,
    val provider: PluginGrantKey,
    val operationId: String,
    val version: Int,
    val digest: String,
)

/**
 * The wearer's per-operation skill approvals, separate from the ordinary descriptor grants.
 * Nothing here is ever implied: a new operation, a changed contract, or a new signing key has
 * no entry and is therefore disabled.
 */
class SkillGrantStore(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        SharedPreferencesGrantStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    @Synchronized
    fun isApproved(caller: PhonePluginPrincipal, provider: PhonePluginPrincipal, operation: SkillOperation): Boolean =
        read().contains(grantFor(caller, provider, operation))

    @Synchronized
    fun setApproved(
        caller: PhonePluginPrincipal,
        provider: PhonePluginPrincipal,
        operation: SkillOperation,
        approved: Boolean,
    ) {
        val grant = grantFor(caller, provider, operation)
        val grants = read().filterNot { existing ->
            existing.caller == grant.caller && existing.provider == grant.provider &&
                existing.operationId == grant.operationId
        }.toMutableSet()
        if (approved) grants += grant
        write(grants)
    }

    /** Drops every approval naming a principal that is no longer installed as it was approved. */
    @Synchronized
    fun reconcile(installed: Collection<PhonePluginPrincipal>) {
        val keys = installed.mapTo(hashSetOf(), PhonePluginPrincipal::grantKey)
        write(read().filterTo(linkedSetOf()) { it.caller in keys && it.provider in keys })
    }

    /** Drops every approval naming [key], on either side; used when a descriptor grant is revoked. */
    @Synchronized
    fun revokeAll(key: PluginGrantKey) {
        write(read().filterTo(linkedSetOf()) { it.caller != key && it.provider != key })
    }

    @Synchronized
    fun all(): Set<SkillGrant> = read()

    private fun grantFor(caller: PhonePluginPrincipal, provider: PhonePluginPrincipal, operation: SkillOperation) =
        SkillGrant(caller.grantKey(), provider.grantKey(), operation.id, operation.version, operation.digest)

    private fun read(): Set<SkillGrant> = runCatching {
        val root = JSONObject(storage.read().orEmpty().ifBlank { return emptySet() })
        if (root.optInt("version") != VERSION) return emptySet()
        val items = root.optJSONArray("grants") ?: return emptySet()
        buildSet {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val caller = item.optJSONObject("caller")?.let(::keyOf) ?: continue
                val provider = item.optJSONObject("provider")?.let(::keyOf) ?: continue
                val operationId = item.optString("operation").takeIf(SkillOperation.ID::matches) ?: continue
                val version = item.optInt("version", 0).takeIf { it in 1..SkillOperation.MAX_VERSION } ?: continue
                val digest = item.optString("digest").takeIf { it.length == DIGEST_CHARS } ?: continue
                add(SkillGrant(caller, provider, operationId, version, digest))
            }
        }
    }.getOrDefault(emptySet())

    private fun write(grants: Set<SkillGrant>) {
        val items = JSONArray()
        grants.sortedWith(compareBy({ it.provider.pluginId }, { it.caller.pluginId }, { it.operationId }))
            .forEach { grant ->
                items.put(
                    JSONObject()
                        .put("caller", keyJson(grant.caller))
                        .put("provider", keyJson(grant.provider))
                        .put("operation", grant.operationId)
                        .put("version", grant.version)
                        .put("digest", grant.digest),
                )
            }
        storage.write(JSONObject().put("version", VERSION).put("grants", items).toString())
    }

    private fun keyJson(key: PluginGrantKey) = JSONObject()
        .put("package", key.packageName)
        .put("pluginId", key.pluginId)
        .put("signingDigestSha256", key.signingDigestSha256)

    private fun keyOf(json: JSONObject): PluginGrantKey? {
        val packageName = json.optString("package").takeIf(String::isNotBlank) ?: return null
        val pluginId = json.optString("pluginId").takeIf(String::isNotBlank) ?: return null
        val digest = json.optString("signingDigestSha256").takeIf(String::isNotBlank) ?: return null
        return PluginGrantKey(packageName, pluginId, digest)
    }

    companion object {
        private const val PREFERENCES_NAME = "nexus_skill_grants"
        private const val VERSION = 1
        private const val DIGEST_CHARS = 64
    }
}
