package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import org.json.JSONArray
import org.json.JSONObject

/** One offline activity entry as the phone keeps it across a hub restart. */
data class PhoneActivitySnapshot(
    val key: PluginGrantKey,
    /** The full offline start payload, identity and hub stamps included. */
    val activity: JSONObject,
) {
    val knownAtWallMs: Long get() = activity.optLong("knownAtWallMs", 0L)
}

/**
 * The bounded store of offline activity snapshots ([ActivitySurfaceContract.MAX_OFFLINE_SNAPSHOTS]
 * at most), in its own preferences file. It only mirrors what the phone's activity state holds
 * offline: written whenever that set changes, read once at hub start, where only snapshots whose
 * grant is still approved with `surfaces` come back. Nothing here keeps a plugin alive.
 */
class PhoneActivitySnapshotStore(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        PreferenceStringStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            KEY_SNAPSHOTS,
        ),
    )

    @Synchronized
    fun load(): List<PhoneActivitySnapshot> = decode(storage.read().orEmpty())

    /** Replaces the stored set; past the bound the newest [PhoneActivitySnapshot.knownAtWallMs] win. */
    @Synchronized
    fun write(snapshots: List<PhoneActivitySnapshot>) {
        val encoded = encode(snapshots)
        if (encoded != storage.read()) storage.write(encoded)
    }

    companion object {
        private const val PREFERENCES_NAME = "nexus_activity_snapshots"
        private const val KEY_SNAPSHOTS = "snapshots_v1"
        private const val VERSION = 1

        internal fun encode(snapshots: List<PhoneActivitySnapshot>): String {
            val kept = snapshots
                .distinctBy { it.key.pluginId }
                .sortedWith(compareByDescending<PhoneActivitySnapshot> { it.knownAtWallMs }.thenBy { it.key.pluginId })
                .take(ActivitySurfaceContract.MAX_OFFLINE_SNAPSHOTS)
                .sortedBy { it.key.pluginId }
            val items = JSONArray()
            kept.forEach { snapshot ->
                items.put(GrantKeyJson.encode(snapshot.key).put("activity", JSONObject(snapshot.activity.toString())))
            }
            return JSONObject().put("version", VERSION).put("snapshots", items).toString()
        }

        internal fun decode(value: String): List<PhoneActivitySnapshot> {
            if (value.isBlank()) return emptyList()
            return runCatching {
                val root = JSONObject(value)
                if (root.optInt("version") != VERSION) return@runCatching emptyList()
                val items = root.optJSONArray("snapshots") ?: return@runCatching emptyList()
                buildList {
                    for (index in 0 until minOf(items.length(), ActivitySurfaceContract.MAX_OFFLINE_SNAPSHOTS)) {
                        val item = items.optJSONObject(index) ?: continue
                        val key = GrantKeyJson.decode(item) ?: continue
                        val activity = item.optJSONObject("activity") ?: continue
                        add(PhoneActivitySnapshot(key, activity))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
