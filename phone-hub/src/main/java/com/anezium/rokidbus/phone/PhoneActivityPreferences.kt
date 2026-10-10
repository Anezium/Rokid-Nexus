package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The user's choice for one plugin's activity: shown at all, and pinned. */
data class PhoneActivityPreference(
    val enabled: Boolean = true,
    val pinned: Boolean = false,
) {
    companion object {
        val DEFAULT = PhoneActivityPreference()
    }
}

/**
 * Per-plugin activity preferences on the phone, keyed by the plugin's grant key.
 *
 * Kept apart from the grant and from live activity state on purpose: the grant says a plugin may
 * draw on the glasses, this says whether its activity shows and whether the user pinned it.
 * Editing it never goes through the grant teardown, and it is purged with the grant, so a
 * re-approved or re-signed plugin starts from the defaults.
 */
class PhoneActivityPreferences(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        PreferenceStringStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            KEY_ENTRIES,
        ),
    )

    private var cache: Map<PluginGrantKey, PhoneActivityPreference>? = null

    @Synchronized
    fun preferenceFor(key: PluginGrantKey): PhoneActivityPreference =
        entries()[key] ?: PhoneActivityPreference.DEFAULT

    @Synchronized
    fun setEnabled(key: PluginGrantKey, enabled: Boolean) = edit(key) { it.copy(enabled = enabled) }

    @Synchronized
    fun setPinned(key: PluginGrantKey, pinned: Boolean) = edit(key) { it.copy(pinned = pinned) }

    @Synchronized
    fun purge(key: PluginGrantKey) {
        val current = entries()
        if (key in current) write(current - key)
    }

    /** Drops every key that no longer has an approved grant; returns the keys it dropped. */
    @Synchronized
    fun retainOnly(approved: Set<PluginGrantKey>): Set<PluginGrantKey> {
        val current = entries()
        val dropped = current.keys - approved
        if (dropped.isNotEmpty()) write(current - dropped)
        return dropped
    }

    private fun edit(key: PluginGrantKey, change: (PhoneActivityPreference) -> PhoneActivityPreference) {
        val current = entries()
        val next = change(current[key] ?: PhoneActivityPreference.DEFAULT)
        // Defaults are not stored, so an untouched plugin leaves nothing behind.
        write(if (next == PhoneActivityPreference.DEFAULT) current - key else current + (key to next))
    }

    private fun entries(): Map<PluginGrantKey, PhoneActivityPreference> =
        cache ?: decode(storage.read().orEmpty()).also { cache = it }

    private fun write(entries: Map<PluginGrantKey, PhoneActivityPreference>) {
        cache = entries
        storage.write(encode(entries))
    }

    companion object {
        @Volatile private var instance: PhoneActivityPreferences? = null

        /** The process's one instance, so the hub and the plugin page never read stale caches. */
        fun shared(context: Context): PhoneActivityPreferences =
            instance ?: synchronized(this) { instance ?: PhoneActivityPreferences(context).also { instance = it } }

        private const val PREFERENCES_NAME = "nexus_activity_preferences"
        private const val KEY_ENTRIES = "preferences_v1"
        private const val VERSION = 1

        internal fun encode(entries: Map<PluginGrantKey, PhoneActivityPreference>): String {
            val items = JSONArray()
            entries.entries.sortedWith(compareBy({ it.key.packageName }, { it.key.pluginId }, { it.key.signingDigestSha256 }))
                .forEach { (key, preference) ->
                    items.put(
                        GrantKeyJson.encode(key)
                            .put("enabled", preference.enabled)
                            .put("pinned", preference.pinned),
                    )
                }
            return JSONObject().put("version", VERSION).put("entries", items).toString()
        }

        internal fun decode(value: String): Map<PluginGrantKey, PhoneActivityPreference> {
            if (value.isBlank()) return emptyMap()
            return runCatching {
                val root = JSONObject(value)
                if (root.optInt("version") != VERSION) return@runCatching emptyMap()
                val items = root.optJSONArray("entries") ?: return@runCatching emptyMap()
                buildMap {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val key = GrantKeyJson.decode(item) ?: continue
                        val enabled = item.opt("enabled") as? Boolean ?: continue
                        val pinned = item.opt("pinned") as? Boolean ?: continue
                        put(key, PhoneActivityPreference(enabled = enabled, pinned = pinned))
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }
}

/** A plugin grant key as stored beside the hub's own per-plugin choices. */
internal object GrantKeyJson {
    fun encode(key: PluginGrantKey): JSONObject = JSONObject()
        .put("package", key.packageName)
        .put("pluginId", key.pluginId)
        .put("signingDigestSha256", key.signingDigestSha256)

    fun decode(item: JSONObject): PluginGrantKey? {
        val packageName = item.opt("package") as? String ?: return null
        val pluginId = item.opt("pluginId") as? String ?: return null
        val digest = item.opt("signingDigestSha256") as? String ?: return null
        if (packageName.isBlank() || pluginId.isBlank() || digest.isBlank()) return null
        return PluginGrantKey(packageName, pluginId, digest)
    }
}

/** One string value in a private preferences file, written synchronously like the grants. */
internal class PreferenceStringStorage(
    private val preferences: SharedPreferences,
    private val key: String,
) : PluginGrantStorage {
    override fun read(): String? = preferences.getString(key, null)

    override fun write(value: String) {
        preferences.edit().putString(key, value).commit()
    }
}
