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
) {
    val active get() = status == PatchJobStatus.PREPARING || status == PatchJobStatus.RUNNING
}

/** Activity and service share this store in :patcher; disk is the process-death boundary. */
class PatchJobStore(private val directory: File) {
    private val file = File(directory, "patch-job.json")
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<PatchJobState> = mutable

    init {
        if (mutable.value.active) update(mutable.value.copy(status = PatchJobStatus.INTERRUPTED,
            message = "The last patch was interrupted. Retry when you are ready.", result = null))
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
        return PatchJobState(UUID.randomUUID().toString(), PatchJobStatus.PREPARING,
            "Reading and validating the selected APK", System.currentTimeMillis(), targetId = targetId).also(::update)
    }

    @Synchronized fun patch(hash: String, selected: List<String>): PatchJobState {
        val current = state.value
        check(!current.active && current.stock != null) { "Choose and validate a stock APK first." }
        require(selected.isNotEmpty()) { "Select at least one patch." }
        return current.copy(id = UUID.randomUUID().toString(), status = PatchJobStatus.RUNNING, message = "Loading the patch bundle",
            startedAt = System.currentTimeMillis(), result = null, bundleHash = hash, selected = selected,
            progress = PatchProgress(PatchPhase.BUNDLE_LOAD), elapsedMs = 0).also(::update)
    }

    @Synchronized fun change(id: String, block: (PatchJobState) -> PatchJobState) {
        if (state.value.id == id && state.value.active) update(block(state.value))
    }

    @Synchronized fun progress(id: String, progress: PatchProgress, elapsedMs: Long) {
        val current = state.value
        if (current.id != id || !current.active) return
        require(progress.phase.ordinal >= current.progress.phase.ordinal) { "Patch phases cannot move backwards." }
        val next = current.copy(progress = progress, elapsedMs = elapsedMs.coerceAtLeast(current.elapsedMs), message = progress.display())
        // Persist phase boundaries; patch callbacks are observable immediately without an fsync each.
        if (progress.phase != current.progress.phase) update(next) else mutable.value = next
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
        File(work(state.workId), name).takeIf { it.isFile && it.canonicalFile.toPath().startsWith(work(state.workId).canonicalFile.toPath()) }
    }

    fun result(state: PatchJobState = this.state.value): File? = state.result?.let { name ->
        File(directory, "results/$name").takeIf { PatchPolicy.isResult(name) && it.isFile && it.parentFile!!.canonicalFile == File(directory, "results").canonicalFile }
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
        @Volatile private var instance: PatchJobStore? = null
        fun get(context: Context): PatchJobStore = instance ?: synchronized(this) {
            instance ?: PatchJobStore(context.applicationContext.filesDir).also { instance = it }
        }

        internal fun encode(s: PatchJobState) = buildJsonObject {
            put("id", s.id); put("status", s.status.name); put("message", s.message); put("started", s.startedAt)
            s.stock?.let { put("stock", it) }; s.result?.let { put("result", it) }
            s.bundleHash?.let { put("bundle", it) }; put("selected", JsonArray(s.selected.map(::JsonPrimitive)))
            put("phase", s.progress.phase.name); s.progress.fraction?.let { put("fraction", it) }
            s.progress.patchName?.let { put("patch_name", it) }; put("patch_index", s.progress.patchIndex)
            put("patch_total", s.progress.patchTotal); put("elapsed", s.elapsedMs)
            put("target", s.targetId)
            put("work", s.workId)
        }.toString()

        internal fun decode(text: String): PatchJobState {
            val json = Json.parseToJsonElement(text).jsonObject
            return PatchJobState(json.getValue("id").jsonPrimitive.content,
                PatchJobStatus.valueOf(json.getValue("status").jsonPrimitive.content),
                json.getValue("message").jsonPrimitive.content, json.getValue("started").jsonPrimitive.long,
                json["stock"]?.jsonPrimitive?.content, json["result"]?.jsonPrimitive?.content,
                json["bundle"]?.jsonPrimitive?.content, json.getValue("selected").jsonArray.map { it.jsonPrimitive.content },
                PatchProgress(json["phase"]?.jsonPrimitive?.content?.let(PatchPhase::valueOf) ?: PatchPhase.READ_INPUT,
                    json["fraction"]?.jsonPrimitive?.double, json["patch_name"]?.jsonPrimitive?.content,
                    json["patch_index"]?.jsonPrimitive?.int ?: 0, json["patch_total"]?.jsonPrimitive?.int ?: 0),
                json["elapsed"]?.jsonPrimitive?.long ?: 0,
                json["target"]?.jsonPrimitive?.content ?: PatchTargets.default.id,
                json["work"]?.jsonPrimitive?.content ?: json.getValue("id").jsonPrimitive.content)
        }
    }
}
