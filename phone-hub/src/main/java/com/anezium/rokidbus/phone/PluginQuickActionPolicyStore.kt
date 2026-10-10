package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.QuickActionFamily
import org.json.JSONArray
import org.json.JSONObject

/**
 * The quick-action families the user approved per plugin, keyed by the plugin's grant key.
 *
 * An approval is the user's trust in that plugin: one tap then runs its typed actions of that
 * family without a confirmation. The hubs check owner, revision and type; nothing can check what
 * the plugin's callback does. So nothing is approved by default, an approval only ever names a
 * family the plugin declares right now (a family it drops and declares again starts unapproved),
 * and revoke, disable, uninstall or a signer change purge it with the grant. Toggling a family
 * never goes through the grant teardown.
 */
class PluginQuickActionPolicyStore(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        PreferenceStringStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            KEY_ENTRIES,
        ),
    )

    private var cache: Map<PluginGrantKey, Set<QuickActionFamily>>? = null

    /** What the user approved for [key], whether or not the plugin still declares it. */
    @Synchronized
    fun approvedFamilies(key: PluginGrantKey): Set<QuickActionFamily> = entries()[key].orEmpty()

    /** The families that may run quick for [principal] now: approved and still declared. */
    @Synchronized
    fun effectiveFamilies(principal: PhonePluginPrincipal): Set<QuickActionFamily> =
        approvedFamilies(principal.grantKey()).intersect(principal.descriptor.quickActionFamilies)

    /**
     * Approves or withdraws one family. Approving a family [principal] does not declare is
     * refused: the user can only ever trust what the plugin asked for. True when it changed.
     */
    @Synchronized
    fun setApproved(principal: PhonePluginPrincipal, family: QuickActionFamily, approved: Boolean): Boolean {
        if (approved && family !in principal.descriptor.quickActionFamilies) return false
        val key = principal.grantKey()
        val current = entries()
        val before = current[key].orEmpty()
        val after = if (approved) before + family else before - family
        if (after == before) return false
        write(if (after.isEmpty()) current - key else current + (key to after))
        return true
    }

    @Synchronized
    fun purge(key: PluginGrantKey): Boolean {
        val current = entries()
        if (key !in current) return false
        write(current - key)
        return true
    }

    /**
     * Keeps approvals only for [approved] principals, and for each only the families it still
     * declares. True when anything was dropped.
     */
    @Synchronized
    fun retainOnly(approved: Collection<PhonePluginPrincipal>): Boolean {
        val declared = approved.associate { it.grantKey() to it.descriptor.quickActionFamilies }
        val current = entries()
        val next = current.mapNotNull { (key, families) ->
            val kept = families.intersect(declared[key].orEmpty())
            if (kept.isEmpty()) null else key to kept
        }.toMap()
        if (next == current) return false
        write(next)
        return true
    }

    private fun entries(): Map<PluginGrantKey, Set<QuickActionFamily>> =
        cache ?: decode(storage.read().orEmpty()).also { cache = it }

    private fun write(entries: Map<PluginGrantKey, Set<QuickActionFamily>>) {
        cache = entries
        storage.write(encode(entries))
    }

    companion object {
        @Volatile private var instance: PluginQuickActionPolicyStore? = null

        /** The process's one instance, so the hub and the plugin page never read stale caches. */
        fun shared(context: Context): PluginQuickActionPolicyStore =
            instance ?: synchronized(this) { instance ?: PluginQuickActionPolicyStore(context).also { instance = it } }

        private const val PREFERENCES_NAME = "nexus_quick_action_policy"
        private const val KEY_ENTRIES = "approvals_v1"
        private const val VERSION = 1

        internal fun encode(entries: Map<PluginGrantKey, Set<QuickActionFamily>>): String {
            val items = JSONArray()
            entries.entries.sortedWith(compareBy({ it.key.packageName }, { it.key.pluginId }, { it.key.signingDigestSha256 }))
                .forEach { (key, families) ->
                    items.put(
                        GrantKeyJson.encode(key).put(
                            "families",
                            JSONArray().apply { families.map { it.wireValue }.sorted().forEach(::put) },
                        ),
                    )
                }
            return JSONObject().put("version", VERSION).put("entries", items).toString()
        }

        /** Unknown families are dropped: a family this build cannot name was never approved here. */
        internal fun decode(value: String): Map<PluginGrantKey, Set<QuickActionFamily>> {
            if (value.isBlank()) return emptyMap()
            return runCatching {
                val root = JSONObject(value)
                if (root.optInt("version") != VERSION) return@runCatching emptyMap()
                val items = root.optJSONArray("entries") ?: return@runCatching emptyMap()
                buildMap {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val key = GrantKeyJson.decode(item) ?: continue
                        val array = item.optJSONArray("families") ?: continue
                        val families = (0 until array.length())
                            .mapNotNull { (array.opt(it) as? String)?.let(QuickActionFamily::fromWireValue) }
                            .toSet()
                        if (families.isNotEmpty()) put(key, families)
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }
}
