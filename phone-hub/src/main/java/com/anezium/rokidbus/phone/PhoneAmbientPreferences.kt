package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.AmbientSizes
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * The user's ambient presentation choices, stored on the phone, which is authoritative for both:
 * the island size per owner plugin (absent = the glasses' automatic choice) and the mode per
 * native app (absent = the default: residents at home, signals elsewhere). Edited on the phone's
 * display and native-app pages, and for sizes also on the glasses.
 */
class PhoneAmbientPreferencesStore(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        PreferenceStringStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            KEY_ENTRIES,
        ),
    )

    private var cache: Pair<Map<String, AmbientSize>, Map<String, ForegroundAmbientMode>>? = null

    @Synchronized
    fun sizes(): Map<String, AmbientSize> = entries().first

    @Synchronized
    fun modes(): Map<String, ForegroundAmbientMode> = entries().second

    @Synchronized
    fun sizeFor(ownerPluginId: String): AmbientSize? = sizes()[ownerPluginId]

    @Synchronized
    fun modeFor(packageName: String): ForegroundAmbientMode? = modes()[packageName]

    /**
     * Sets or clears ([size] null) one owner's size. Refused for an invalid id, and for a new owner
     * once the map is full, so the map always fits the wire. True when it changed.
     */
    @Synchronized
    fun setSize(ownerPluginId: String, size: AmbientSize?): Boolean {
        if (!PluginDescriptor.isValidId(ownerPluginId)) return false
        val (sizes, modes) = entries()
        if (sizes[ownerPluginId] == size) return false
        if (size != null && ownerPluginId !in sizes && sizes.size >= AmbientPreferencesContract.MAX_SIZE_ENTRIES) {
            return false
        }
        write(if (size == null) sizes - ownerPluginId else sizes + (ownerPluginId to size), modes)
        return true
    }

    /** Like [setSize], for one native app's mode; [mode] null returns it to the default. */
    @Synchronized
    fun setMode(packageName: String, mode: ForegroundAmbientMode?): Boolean {
        if (!AmbientPreferencesContract.isValidPackageName(packageName)) return false
        val (sizes, modes) = entries()
        if (modes[packageName] == mode) return false
        if (mode != null && packageName !in modes && modes.size >= AmbientPreferencesContract.MAX_POLICY_ENTRIES) {
            return false
        }
        write(sizes, if (mode == null) modes - packageName else modes + (packageName to mode))
        return true
    }

    private fun entries(): Pair<Map<String, AmbientSize>, Map<String, ForegroundAmbientMode>> =
        cache ?: decode(storage.read()).also { cache = it }

    private fun write(sizes: Map<String, AmbientSize>, modes: Map<String, ForegroundAmbientMode>) {
        storage.write(encode(sizes, modes))
        cache = sizes to modes
    }

    companion object {
        @Volatile private var instance: PhoneAmbientPreferencesStore? = null

        /** The process's one instance, so the hub and the settings pages never read stale caches. */
        fun shared(context: Context): PhoneAmbientPreferencesStore =
            instance ?: synchronized(this) { instance ?: PhoneAmbientPreferencesStore(context).also { instance = it } }

        private const val PREFERENCES_NAME = "nexus_ambient_preferences"
        private const val KEY_ENTRIES = "preferences_v1"
        private const val VERSION = 1

        internal fun encode(sizes: Map<String, AmbientSize>, modes: Map<String, ForegroundAmbientMode>): String =
            JSONObject()
                .put("version", VERSION)
                .put("sizes", JSONObject().apply { sizes.toSortedMap().forEach { (owner, size) -> put(owner, size.wireValue) } })
                .put("modes", JSONObject().apply { modes.toSortedMap().forEach { (app, mode) -> put(app, mode.wireValue) } })
                .toString()

        /** Anything unreadable or unknown reads as absent, which is the default for that entry. */
        internal fun decode(raw: String?): Pair<Map<String, AmbientSize>, Map<String, ForegroundAmbientMode>> {
            val root = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optInt("version") == VERSION }
                ?: return emptyMap<String, AmbientSize>() to emptyMap()
            val sizes = linkedMapOf<String, AmbientSize>()
            root.optJSONObject("sizes")?.let { map ->
                map.keys().forEach { owner ->
                    val size = (map.opt(owner) as? String)?.let(AmbientSize::fromWireValue)
                    if (size != null && PluginDescriptor.isValidId(owner)) sizes[owner] = size
                }
            }
            val modes = linkedMapOf<String, ForegroundAmbientMode>()
            root.optJSONObject("modes")?.let { map ->
                map.keys().forEach { app ->
                    val mode = (map.opt(app) as? String)?.let(ForegroundAmbientMode::fromWireValue)
                    if (mode != null && AmbientPreferencesContract.isValidPackageName(app)) modes[app] = mode
                }
            }
            return sizes.entries.take(AmbientPreferencesContract.MAX_SIZE_ENTRIES).associate { it.key to it.value } to
                modes.entries.take(AmbientPreferencesContract.MAX_POLICY_ENTRIES).associate { it.key to it.value }
        }
    }
}

/**
 * Keeps glasses that negotiated the launcher bundle in step with [store]: the full size map and
 * the full app policy on every announce and every change, never a delta. Each message carries a
 * revision that rises with every send, across hub restarts too ([initialRevision] is wall time),
 * so the glasses can always tell the newest map from an older one that arrived late. The policy
 * also carries [hiddenOwners], the approved plugins the user keeps off the glasses.
 */
internal class PhoneAmbientSync(
    private val store: PhoneAmbientPreferencesStore,
    private val send: (path: String, payload: JSONObject) -> String?,
    private val launcherActive: () -> Boolean,
    private val log: (String) -> Unit,
    initialRevision: Long = System.currentTimeMillis(),
    private val hiddenOwners: () -> Set<String> = { emptySet() },
    private val activityDisplays: () -> Map<String, com.anezium.rokidbus.shared.ActivityDisplayPreference> = { emptyMap() },
    private val supportsActivityEvents: () -> Boolean = { true },
) {
    private val revision = AtomicLong(initialRevision)

    fun onGlassesAnnounced() {
        pushSizes()
        pushPolicy()
    }

    /**
     * `/core/ambient/sizes/set` from the glasses. Every edit is answered with the full map, changed
     * or not: the glasses replace their local guess with it, so a refused edit is undone there too.
     */
    fun handleSizeSet(payload: JSONObject) {
        val edit = AmbientPreferencesContract.parseSizeEdit(payload)
        if (edit == null) {
            log("ambient size edit ignored reason=invalid")
            return
        }
        val changed = store.setSize(edit.ownerPluginId, edit.size)
        log("ambient size edit owner=${edit.ownerPluginId} size=${edit.size?.wireValue ?: "auto"} changed=$changed")
        pushSizes()
    }

    @Synchronized
    fun pushSizes() {
        if (!launcherActive()) return
        val sizes = AmbientSizes(revision.incrementAndGet(), store.sizes())
        val error = send(AmbientPreferencesContract.SIZES_PATH, AmbientPreferencesContract.sizesPayload(sizes))
        log("ambient sizes ${if (error == null) "sent" else "not sent code=$error"} revision=${sizes.revision} owners=${sizes.sizes.size}")
    }

    @Synchronized
    fun pushPolicy() {
        if (!launcherActive()) return
        // Within the wire's bound: PhoneActivityPreferences refuses to keep one more plugin off.
        val displays = activityDisplays()
        // Old glasses cannot time or scope an island: keep these choices quiet until updated.
        val unsupported = if (supportsActivityEvents()) emptySet() else displays.filterValues {
            it.mode == com.anezium.rokidbus.shared.ActivityDisplayMode.EVENTS ||
                it.mode == com.anezium.rokidbus.shared.ActivityDisplayMode.HOME
        }.keys
        val hidden = hiddenOwners() + unsupported
        val policy = AmbientPolicy(revision.incrementAndGet(), store.modes(), hidden,
            if (supportsActivityEvents()) displays else emptyMap())
        val error = send(AmbientPreferencesContract.POLICY_PATH, AmbientPreferencesContract.policyPayload(policy))
        log(
            "ambient policy ${if (error == null) "sent" else "not sent code=$error"} revision=${policy.revision} " +
                "apps=${policy.modes.size} hidden=${hidden.size}",
        )
    }
}
