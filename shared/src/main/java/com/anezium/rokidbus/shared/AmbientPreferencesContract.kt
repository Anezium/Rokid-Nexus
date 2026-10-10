package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject

/** How large one owner's ambient island is drawn. Absent means the hub's automatic choice. */
enum class AmbientSize(val wireValue: String) {
    TEXT("text"),
    MINIMAP("minimap"),
    PANEL("panel"),
    ;

    companion object {
        fun fromWireValue(value: String): AmbientSize? = entries.firstOrNull { it.wireValue == value }
    }
}

/** What ambient islands may show over one foreground app. */
enum class ForegroundAmbientMode(val wireValue: String) {
    /** Activity islands and signals, as on the home screen. */
    RESIDENTS("residents"),

    /** Signals only; the default over an app the user never configured. */
    SIGNALS("signals"),
    NOTHING("nothing"),
    ;

    companion object {
        fun fromWireValue(value: String): ForegroundAmbientMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Glasses to phone: the wearer set one owner's size on the glasses; null returns it to automatic. */
data class AmbientSizeEdit(val ownerPluginId: String, val size: AmbientSize?)

/** Phone to glasses: every owner's stored size, authoritative. */
data class AmbientSizes(val revision: Long, val sizes: Map<String, AmbientSize>)

/** Phone to glasses: per-app modes; an app absent from [modes] gets [AmbientPreferencesContract.defaultMode]. */
data class AmbientPolicy(val revision: Long, val modes: Map<String, ForegroundAmbientMode>)

/**
 * Ambient presentation preferences, between the two hubs only (`/core/ambient/...`). The phone
 * stores both and is authoritative: glasses edits apply locally at once and are sent with
 * `sizes/set` (queued while offline, latest per owner, sent before the next phone snapshot is
 * applied); the phone answers every link-up and change with the full map. The full-map excursion
 * never writes a size. Entries with a value this build does not know are dropped, which leaves
 * that owner or app on its default.
 */
object AmbientPreferencesContract {
    const val SIZES_SET_PATH = BusPaths.CORE_AMBIENT_SIZES_SET
    const val SIZES_PATH = BusPaths.CORE_AMBIENT_SIZES
    const val POLICY_PATH = BusPaths.CORE_AMBIENT_POLICY

    const val MAX_SIZE_ENTRIES = 64
    const val MAX_POLICY_ENTRIES = 128
    const val MAX_PACKAGE_CHARS = 255

    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun isValidPackageName(value: String): Boolean = value.length <= MAX_PACKAGE_CHARS && packagePattern.matches(value)

    /** The mode over an app the policy does not name: residents at home, signals elsewhere. */
    fun defaultMode(isHome: Boolean): ForegroundAmbientMode =
        if (isHome) ForegroundAmbientMode.RESIDENTS else ForegroundAmbientMode.SIGNALS

    fun modeFor(policy: AmbientPolicy, packageName: String, isHome: Boolean): ForegroundAmbientMode =
        policy.modes[packageName] ?: defaultMode(isHome)

    fun sizeEditPayload(edit: AmbientSizeEdit): JSONObject {
        require(PluginDescriptor.isValidId(edit.ownerPluginId)) { "Invalid plugin id" }
        return JSONObject()
            .put("ownerPluginId", edit.ownerPluginId)
            .put("size", edit.size?.wireValue ?: JSONObject.NULL)
    }

    /** An edit, or null when it is malformed; an unknown size is malformed here, since it is a write. */
    fun parseSizeEdit(payload: JSONObject): AmbientSizeEdit? {
        val owner = (payload.opt("ownerPluginId") as? String)?.takeIf(PluginDescriptor::isValidId) ?: return null
        if (!payload.has("size")) return null
        val raw = payload.opt("size")
        val size = if (raw == JSONObject.NULL) null else (raw as? String)?.let(AmbientSize::fromWireValue) ?: return null
        return AmbientSizeEdit(owner, size)
    }

    fun sizesPayload(sizes: AmbientSizes): JSONObject = JSONObject()
        .put("revision", sizes.revision)
        .put("sizes", JSONObject().apply { sizes.sizes.toSortedMap().forEach { (owner, size) -> put(owner, size.wireValue) } })

    fun parseSizes(payload: JSONObject): AmbientSizes? {
        val revision = nonnegativeLong(payload.opt("revision")) ?: return null
        val map = payload.opt("sizes") as? JSONObject ?: return null
        if (map.length() > MAX_SIZE_ENTRIES) return null
        val sizes = linkedMapOf<String, AmbientSize>()
        for (owner in map.keys()) {
            if (!PluginDescriptor.isValidId(owner)) return null
            val wire = map.opt(owner) as? String ?: return null
            AmbientSize.fromWireValue(wire)?.let { sizes[owner] = it }
        }
        return AmbientSizes(revision, sizes)
    }

    fun policyPayload(policy: AmbientPolicy): JSONObject = JSONObject()
        .put("revision", policy.revision)
        .put("modes", JSONObject().apply { policy.modes.toSortedMap().forEach { (app, mode) -> put(app, mode.wireValue) } })

    fun parsePolicy(payload: JSONObject): AmbientPolicy? {
        val revision = nonnegativeLong(payload.opt("revision")) ?: return null
        val map = payload.opt("modes") as? JSONObject ?: return null
        if (map.length() > MAX_POLICY_ENTRIES) return null
        val modes = linkedMapOf<String, ForegroundAmbientMode>()
        for (app in map.keys()) {
            if (!isValidPackageName(app)) return null
            val wire = map.opt(app) as? String ?: return null
            ForegroundAmbientMode.fromWireValue(wire)?.let { modes[app] = it }
        }
        return AmbientPolicy(revision, modes)
    }

    private fun nonnegativeLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }
}
