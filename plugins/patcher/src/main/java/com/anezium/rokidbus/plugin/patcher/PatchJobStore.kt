package com.anezium.rokidbus.plugin.patcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class PatchJobStatus { IDLE, PREPARING, READY, RUNNING, SUCCESS, FAILURE, CANCELLED, INTERRUPTED }

data class PatchJobState(
    val id: String = "",
    val status: PatchJobStatus = PatchJobStatus.IDLE,
    val message: String = "",
    val startedAt: Long = 0,
    val stock: String? = null,
    val result: String? = null,
    val bundleHash: String? = null,
    val selected: List<String> = emptyList(),
    val progress: PatchProgress = PatchProgress(),
    val elapsedMs: Long = 0,
    val targetId: String = PatchTargets.default.id,
    val workId: String = id,
    val delivered: Boolean = false,
) {
    val active get() = status == PatchJobStatus.PREPARING || status == PatchJobStatus.RUNNING
}

/** Activity and service share this store in :patcher; disk is the process-death boundary. */
class PatchJobStore(private val directory: File,
                    private val diagnostic: (String) -> Unit = { android.util.Log.w(PatchTimings.TAG, it) }) {
    private val storage = PatchStorage(directory)
    private val file = File(directory, "patch-job.json")
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<PatchJobState> = mutable
    private var backwardsMilestoneId: String? = null
    // Memory only: process death ends a key backup/import, so no lease can outlive it.
    private var keyLease: Any? = null
    private val keyBusy = MutableStateFlow(false)
    val keyMaintenance: StateFlow<Boolean> = keyBusy

    init {
        if (mutable.value.active) update(mutable.value.copy(status = PatchJobStatus.INTERRUPTED,
            message = "The last patch was interrupted. Retry when you are ready.", result = null))
        cleanStorage()
    }

    @Synchronized fun selectTarget(targetId: String) {
        PatchTargets.require(targetId)
        if (state.value.targetId == targetId) return
        check(!state.value.active) { "Another target is already being patched." }
        update(PatchJobState(targetId = targetId))
    }

    @Synchronized fun prepare(targetId: String = state.value.targetId): PatchJobState {
        PatchTargets.require(targetId)
        check(!state.value.active) { "A patch job is already running." }
        check(keyLease == null) { KEY_BUSY }
        return PatchJobState(UUID.randomUUID().toString(), PatchJobStatus.PREPARING,
            "Reading and validating the selected APK", System.currentTimeMillis(), targetId = targetId).also(::update)
    }

    @Synchronized fun patch(hash: String, selected: List<String>): PatchJobState {
        val current = state.value
        check(!current.active && current.stock != null) { "Choose and validate a stock APK first." }
        require(selected.isNotEmpty()) { "Select at least one patch." }
        check(keyLease == null) { KEY_BUSY }
        return current.copy(id = UUID.randomUUID().toString(), status = PatchJobStatus.RUNNING, message = "Loading the patch bundle",
            startedAt = System.currentTimeMillis(), result = null, bundleHash = hash, selected = selected,
            progress = PatchProgress(PatchPhase.BUNDLE_LOAD), elapsedMs = 0, delivered = false).also(::update)
    }

    /** The job signs with the key, so a key export/import and a job never overlap in :patcher. */
    @Synchronized fun beginKeyMaintenance(): Any {
        check(!state.value.active) { "Wait for the running patch to finish, then try again." }
        check(keyLease == null) { "The signing key is already being backed up or imported." }
        return Any().also { keyLease = it; keyBusy.value = true }
    }

    @Synchronized fun endKeyMaintenance(lease: Any) {
        if (keyLease === lease) { keyLease = null; keyBusy.value = false }
    }

    @Synchronized fun change(id: String, block: (PatchJobState) -> PatchJobState) {
        if (state.value.id == id && state.value.active) update(block(state.value))
    }

    @Synchronized fun progress(id: String, progress: PatchProgress, elapsedMs: Long): Boolean {
        val current = state.value
        if (current.id != id || !current.active) return false
        if (progress.phase.ordinal < current.progress.phase.ordinal) {
            if (backwardsMilestoneId != id) {
                backwardsMilestoneId = id
                diagnostic("ignored_backwards_milestone_class=${progress.javaClass.name}")
            }
            return false
        }
        val next = current.copy(progress = progress, elapsedMs = elapsedMs.coerceAtLeast(current.elapsedMs), message = progress.display())
        // Persist phase boundaries; patch callbacks are observable immediately without an fsync each.
        if (progress.phase != current.progress.phase) update(next) else mutable.value = next
        return true
    }

    @Synchronized fun tick(id: String, elapsedMs: Long) {
        val current = state.value
        if (current.id == id && current.active) mutable.value = current.copy(elapsedMs = elapsedMs.coerceAtLeast(current.elapsedMs))
    }

    fun work(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "jobs/$id").apply { mkdirs() }
    }

    fun stock(state: PatchJobState = this.state.value): File? = state.stock?.let { name ->
        if (name == "retry/stock.apk") storage.retry.takeIf {
            it.isFile && System.currentTimeMillis() - it.lastModified() in 0 until PatchStorage.RETRY_MAX_AGE_MS
        } else {
            if (!state.workId.matches(Regex("[a-f0-9-]{36}"))) return@let null
            val work = File(directory, "jobs/${state.workId}")
            File(work, name).takeIf { it.isFile && it.canonicalFile.toPath().startsWith(work.canonicalFile.toPath()) }
        }
    }

    @Synchronized fun cleanStorage() {
        val current = state.value
        if (current.active) return
        val retained = stock(current)?.let(storage::retain)
        if (current.stock != retained) update(current.copy(stock = retained))
        if (retained == null) storage.discardRetry()
        storage.sweep()
    }

    fun result(state: PatchJobState = this.state.value): File? = state.result?.let { name ->
        File(directory, "results/$name").takeIf { PatchPolicy.isResult(name) && it.isFile && it.parentFile!!.canonicalFile == File(directory, "results").canonicalFile }
    }

    @Synchronized fun markDelivered(id: String) {
        val current = state.value
        check(current.id == id && current.status == PatchJobStatus.SUCCESS && result(current) != null) {
            "The saved result is no longer available."
        }
        update(current.copy(delivered = true))
    }

    @Synchronized fun reconcileResult(now: Long = System.currentTimeMillis()) {
        val current = state.value
        if (current.status != PatchJobStatus.SUCCESS) return
        val result = result(current)
        if (result == null || kotlin.math.abs(now - result.lastModified()) >= PatchPolicy.RESULT_MAX_AGE_MS) {
            result?.delete()
            update(current.copy(status = PatchJobStatus.FAILURE, result = null,
                message = "The saved result expired or is missing. Retry patching to create a new APK."))
        }
    }

    private fun update(value: PatchJobState) {
        directory.mkdirs()
        val pending = File(directory, "patch-job.partial")
        try {
            pending.outputStream().use { it.write(encode(value).toByteArray()); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = value
        } finally { pending.delete() }
    }

    private fun read(): PatchJobState = if (!file.exists()) PatchJobState() else try {
        decode(file.readText())
    } catch (_: Exception) {
        PatchJobState(status = PatchJobStatus.INTERRUPTED, message = "The last patch state could not be recovered. Choose your APK to retry.")
    }

    companion object {
        const val KEY_BUSY = "Wait for the signing key backup or import to finish, then try again."
        @Volatile private var instance: PatchJobStore? = null
        fun get(context: Context): PatchJobStore = instance ?: synchronized(this) {
            instance ?: PatchJobStore(context.applicationContext.filesDir).also { instance = it }
        }

        internal fun encode(s: PatchJobState) = buildJsonObject {
            put("safe_failures", true); put("id", s.id); put("status", s.status.name); put("message", s.message); put("started", s.startedAt)
            s.stock?.let { put("stock", it) }; s.result?.let { put("result", it) }
            s.bundleHash?.let { put("bundle", it) }; put("selected", JsonArray(s.selected.map(::JsonPrimitive)))
            put("phase", s.progress.phase.name); s.progress.fraction?.let { put("fraction", it) }
            s.progress.patchName?.let { put("patch_name", it) }; put("patch_index", s.progress.patchIndex)
            s.progress.substep?.let { put("substep", it.name) }
            s.progress.completedBytes?.let { put("written_bytes", it) }
            s.progress.workTotal?.let { put("work_total", it) }
            put("patch_total", s.progress.patchTotal); put("elapsed", s.elapsedMs)
            put("target", s.targetId)
            put("work", s.workId); put("delivered", s.delivered)
        }.toString()

        internal fun decode(text: String): PatchJobState {
            val json = Json.parseToJsonElement(text).jsonObject
            return PatchJobState(json.getValue("id").jsonPrimitive.content,
                PatchJobStatus.valueOf(json.getValue("status").jsonPrimitive.content),
                if (json["safe_failures"]?.jsonPrimitive?.boolean != true &&
                    json.getValue("status").jsonPrimitive.content == "FAILURE") "Patching failed. Try again with the stock APK."
                else json.getValue("message").jsonPrimitive.content, json.getValue("started").jsonPrimitive.long,
                json["stock"]?.jsonPrimitive?.content, json["result"]?.jsonPrimitive?.content,
                json["bundle"]?.jsonPrimitive?.content, json.getValue("selected").jsonArray.map { it.jsonPrimitive.content },
                PatchProgress(json["phase"]?.jsonPrimitive?.content?.let(PatchPhase::valueOf) ?: PatchPhase.READ_INPUT,
                    json["fraction"]?.jsonPrimitive?.double, json["patch_name"]?.jsonPrimitive?.content,
                    json["patch_index"]?.jsonPrimitive?.int ?: 0, json["patch_total"]?.jsonPrimitive?.int ?: 0,
                    json["substep"]?.jsonPrimitive?.content?.let(PatchSubstep::valueOf),
                    json["written_bytes"]?.jsonPrimitive?.long, json["work_total"]?.jsonPrimitive?.long),
                json["elapsed"]?.jsonPrimitive?.long ?: 0,
                json["target"]?.jsonPrimitive?.content ?: PatchTargets.default.id,
                json["work"]?.jsonPrimitive?.content ?: json.getValue("id").jsonPrimitive.content,
                json["delivered"]?.jsonPrimitive?.boolean ?: false)
        }
    }
}
