package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.AmbientSizeEdit
import com.anezium.rokidbus.shared.AmbientSizes
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Where the glasses keep their mirror of the phone's ambient preferences across restarts. */
internal interface AmbientPreferencesStorage {
    fun load(): AmbientPreferencesSnapshot

    fun save(snapshot: AmbientPreferencesSnapshot)

    companion object {
        /** Nothing survives the process: what a test or a hub without a context gets. */
        fun memory(initial: AmbientPreferencesSnapshot = AmbientPreferencesSnapshot()): AmbientPreferencesStorage =
            object : AmbientPreferencesStorage {
                private var held = initial

                override fun load(): AmbientPreferencesSnapshot = held

                override fun save(snapshot: AmbientPreferencesSnapshot) {
                    held = snapshot
                }
            }
    }
}

/**
 * What the glasses hold: the phone's [sizes], per-app [modes] and [hiddenOwners] as last received,
 * with the glasses' own size edits applied on top, and the edits not yet sent ([queued], latest
 * per owner; a null size returns an owner to automatic).
 */
internal data class AmbientPreferencesSnapshot(
    val sizes: Map<String, AmbientSize> = emptyMap(),
    val modes: Map<String, ForegroundAmbientMode> = emptyMap(),
    val queued: Map<String, AmbientSize?> = emptyMap(),
    val hiddenOwners: Set<String> = emptySet(),
)

/**
 * The glasses' mirror of the phone's ambient preferences (plan 028a §8.1, §8.2). Pure: sending is
 * a function and storage an interface.
 *
 * The phone is authoritative. A glasses edit applies here at once and is sent with
 * `/core/ambient/sizes/set`; one that cannot be sent is queued, latest per owner, and the queue is
 * sent before the next phone snapshot is applied. That snapshot predates the queued edits, so they
 * stay applied over it until the phone's answer, which always carries the full map, replaces it.
 *
 * The phone numbers sizes and policy from one rising counter, so each kind keeps its own last
 * revision: a policy never makes a sizes snapshot look old. Both are forgotten when the link goes,
 * so a phone that restarted on an earlier clock is still heard.
 */
internal class AmbientPreferencesMirror(
    private val storage: AmbientPreferencesStorage,
    /** Sends one edit to the phone; false when it could not leave. */
    private val send: (AmbientSizeEdit) -> Boolean,
) {
    private var snapshot = storage.load()
    private var sizesRevision: Long? = null
    private var policyRevision: Long? = null

    val sizes: Map<String, AmbientSize> get() = snapshot.sizes

    val modes: Map<String, ForegroundAmbientMode> get() = snapshot.modes

    val queued: Map<String, AmbientSize?> get() = snapshot.queued

    val hiddenOwners: Set<String> get() = snapshot.hiddenOwners

    fun size(owner: String): AmbientSize? = snapshot.sizes[owner]

    /** The mode over [packageName]; home is always residents, an app nobody configured signals. */
    fun modeFor(packageName: String?, isHome: Boolean): ForegroundAmbientMode =
        if (isHome || packageName == null) {
            AmbientPreferencesContract.defaultMode(isHome = true)
        } else {
            snapshot.modes[packageName] ?: AmbientPreferencesContract.defaultMode(isHome = false)
        }

    /**
     * The wearer chose [size] for [owner] on the glasses. It applies now; it is sent when [online],
     * queued otherwise or when the send fails. True when what is applied changed.
     */
    fun edit(owner: String, size: AmbientSize?, online: Boolean): Boolean {
        val before = snapshot.sizes[owner]
        val sizes = if (size == null) snapshot.sizes - owner else snapshot.sizes + (owner to size)
        val sent = online && send(AmbientSizeEdit(owner, size))
        val queued = if (sent) snapshot.queued - owner else snapshot.queued + (owner to size)
        commit(snapshot.copy(sizes = sizes, queued = queued))
        return before != size
    }

    /** `/core/ambient/sizes`: true when what is applied changed. An old revision is dropped. */
    fun onSizes(incoming: AmbientSizes): Boolean {
        val last = sizesRevision
        if (last != null && incoming.revision <= last) return false
        sizesRevision = incoming.revision
        val pending = snapshot.queued
        val unsent = pending.filterNot { (owner, size) -> send(AmbientSizeEdit(owner, size)) }
        val sizes = incoming.sizes.toMutableMap()
        // What was just sent is newer than this snapshot; the phone's answer will carry it.
        pending.forEach { (owner, size) -> if (size == null) sizes.remove(owner) else sizes[owner] = size }
        val before = snapshot.sizes
        commit(snapshot.copy(sizes = sizes, queued = unsent))
        return before != sizes
    }

    /** `/core/ambient/policy`: true when the modes or the hidden owners changed. An old revision is dropped. */
    fun onPolicy(incoming: AmbientPolicy): Boolean {
        val last = policyRevision
        if (last != null && incoming.revision <= last) return false
        policyRevision = incoming.revision
        val before = snapshot
        commit(snapshot.copy(modes = incoming.modes, hiddenOwners = incoming.hiddenOwners))
        return before.modes != incoming.modes || before.hiddenOwners != incoming.hiddenOwners
    }

    /** The link went: the next phone snapshots are taken whatever their revision. */
    fun onLinkLost() {
        sizesRevision = null
        policyRevision = null
    }

    private fun commit(next: AmbientPreferencesSnapshot) {
        if (next == snapshot) return
        snapshot = next
        storage.save(next)
    }
}

/** The mirror in the hub's private preferences, as JSON; an unreadable store starts empty. */
internal class SharedPreferencesAmbientStorage(context: Context) : AmbientPreferencesStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): AmbientPreferencesSnapshot = runCatching {
        val sizes = prefs.getString(SIZES, null)?.let(::JSONObject)?.let { json ->
            json.keys().asSequence().mapNotNull { owner -> AmbientSize.fromWireValue(json.optString(owner))?.let { owner to it } }.toMap()
        }.orEmpty()
        val modes = prefs.getString(MODES, null)?.let(::JSONObject)?.let { json ->
            json.keys().asSequence().mapNotNull { app -> ForegroundAmbientMode.fromWireValue(json.optString(app))?.let { app to it } }.toMap()
        }.orEmpty()
        val queued = prefs.getString(QUEUED, null)?.let(::JSONObject)?.let { json ->
            json.keys().asSequence().map { owner ->
                owner to (if (json.isNull(owner)) null else AmbientSize.fromWireValue(json.optString(owner)))
            }.toMap()
        }.orEmpty()
        val hidden = prefs.getString(HIDDEN, null)?.let(::JSONArray)?.let { json ->
            (0 until json.length()).mapTo(linkedSetOf()) { json.getString(it) }
        }.orEmpty()
        AmbientPreferencesSnapshot(sizes, modes, queued, hidden)
    }.getOrElse { AmbientPreferencesSnapshot() }

    override fun save(snapshot: AmbientPreferencesSnapshot) {
        prefs.edit()
            .putString(SIZES, JSONObject().apply { snapshot.sizes.forEach { (owner, size) -> put(owner, size.wireValue) } }.toString())
            .putString(MODES, JSONObject().apply { snapshot.modes.forEach { (app, mode) -> put(app, mode.wireValue) } }.toString())
            .putString(
                QUEUED,
                JSONObject().apply { snapshot.queued.forEach { (owner, size) -> put(owner, size?.wireValue ?: JSONObject.NULL) } }.toString(),
            )
            .putString(HIDDEN, JSONArray(snapshot.hiddenOwners.sorted()).toString())
            .apply()
    }

    private companion object {
        const val PREFS = "ambient_preferences"
        const val SIZES = "sizes"
        const val MODES = "modes"
        const val QUEUED = "queued"
        const val HIDDEN = "hidden_owners"
    }
}

/**
 * The hub's ambient preferences: `/core/ambient/sizes` and `/core/ambient/policy` in, the display
 * page's edits out. Main thread only. A trusted hub-to-hub route: no plugin reaches it.
 */
internal object AmbientPreferences {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var mirror = AmbientPreferencesMirror(AmbientPreferencesStorage.memory(), ::sendEdit)
    private var started = false
    private var preview: Pair<String, AmbientSize>? = null

    fun start(context: Context) {
        runOnMain {
            if (started) return@runOnMain
            started = true
            mirror = AmbientPreferencesMirror(SharedPreferencesAmbientStorage(context), ::sendEdit)
            notifyChanged()
        }
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    /** [owner]'s saved size, null while automatic. The display page's preview never shows here. */
    fun size(owner: String): AmbientSize? = mirror.size(owner)

    /** The size [owner]'s island is drawn at: the display page's preview over the saved size. */
    fun effectiveSize(owner: String): AmbientSize? = preview?.takeIf { it.first == owner }?.second ?: mirror.size(owner)

    /**
     * The display page's provisional size, or null when no display page previews one. It is never
     * stored nor sent: only [edit] writes the preference.
     */
    fun preview(next: Pair<String, AmbientSize>?) {
        runOnMain {
            if (next == preview) return@runOnMain
            preview = next
            notifyChanged()
        }
    }

    fun modeFor(packageName: String?, isHome: Boolean): ForegroundAmbientMode = mirror.modeFor(packageName, isHome)

    /** True when the user keeps [owner]'s island and pin off the glasses, as the phone last said. */
    fun keptOffGlasses(owner: String): Boolean = owner in mirror.hiddenOwners

    /** The wearer saved [size] for [owner] on the display page. */
    fun edit(owner: String, size: AmbientSize?) {
        runOnMain {
            val online = GlassesHub.isPhoneConnected() && GlassesHub.launcherInteractiveActive()
            if (mirror.edit(owner, size, online)) notifyChanged()
            log("ambient size edit owner=$owner size=${size?.wireValue ?: "auto"} queued=${owner in mirror.queued}")
        }
    }

    fun onSizes(payload: JSONObject) {
        runOnMain {
            val sizes = AmbientPreferencesContract.parseSizes(payload) ?: return@runOnMain log("ambient sizes dropped reason=MALFORMED")
            if (mirror.onSizes(sizes)) notifyChanged()
        }
    }

    fun onPolicy(payload: JSONObject) {
        runOnMain {
            val policy = AmbientPreferencesContract.parsePolicy(payload) ?: return@runOnMain log("ambient policy dropped reason=MALFORMED")
            if (mirror.onPolicy(policy)) notifyChanged()
        }
    }

    fun onLinkLost() {
        runOnMain { mirror.onLinkLost() }
    }

    private fun sendEdit(edit: AmbientSizeEdit): Boolean =
        GlassesHub.launcherInteractiveActive() &&
            GlassesHub.sendToPhone(AmbientPreferencesContract.SIZES_SET_PATH, AmbientPreferencesContract.sizeEditPayload(edit))

    private fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
