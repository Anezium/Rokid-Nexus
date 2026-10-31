package com.anezium.rokidbus.glasses

import android.os.Handler
import android.view.KeyEvent
import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.ink.InkWireValidator
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderPatch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal data class InkResyncRequest(
    val currentDocumentId: String,
    val currentRevision: Int,
    val patchDocumentId: String,
    val patchBaseRevision: Int,
)

/** Controller-owned Ink state; attached overlay/activity views are disposable projections. */
internal class InkRendererLayer(
    private val main: Handler,
    private val onResyncNeeded: (InkResyncRequest) -> Unit,
    private val onAction: (String, Map<String, Any?>) -> Unit,
    private val onRendererError: (NexusSurface, List<InkProblem>) -> Unit,
) {
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusInkDecode").apply { isDaemon = true }
    }
    private val decodeJobs = mutableListOf<DecodeJob>()
    private var generation = 0L
    private var store: InkNodeStore? = null
    private var attachedView: InkHudView? = null

    /** Hears every store handed to an attached view, at the moment it is handed over. */
    internal var projectionObserver: ((InkHudView, InkNodeStore) -> Unit)? = null

    /**
     * Decodes [surface]'s document or patch off the main thread. A valid result is only staged:
     * [onStaged] receives it, and nothing becomes this layer's state or reaches a view until the
     * caller commits it, after deciding the surface may still be presented.
     */
    fun submit(
        surface: NexusSurface,
        onStaged: (StagedInk) -> Unit,
    ) {
        val payload = surface.ink ?: return
        val documentJson = payload.documentJson
        val patchJson = payload.patchJson
        if ((documentJson == null) == (patchJson == null)) {
            log("Ink payload rejected id=${surface.surfaceId}: expected exactly one document or patch")
            onRendererError(
                surface,
                listOf(
                    InkProblem(
                        InkProblemCodes.WIRE_INVALID,
                        "Expected exactly one Ink document or patch",
                    ),
                ),
            )
            return
        }
        // A new full document replaces all queued work. Patches retain the
        // generation so a show immediately followed by patches commits in
        // document/revision order on the main Handler.
        val requestGeneration = if (documentJson != null) nextGeneration() else generation
        val job = DecodeJob()
        decodeJobs += job
        job.future = decoder.submit {
            val decoded = if (documentJson != null) {
                decodeDocument(documentJson)
            } else {
                decodePatch(requireNotNull(patchJson))
            }
            if (job.cancelled || Thread.currentThread().isInterrupted) return@submit
            main.post {
                decodeJobs.remove(job)
                if (job.cancelled || requestGeneration != generation) return@post
                when (decoded) {
                    is Decoded.Document -> {
                        val current = store
                        if (current?.documentId == decoded.value.documentId && decoded.value.revision <= current.revision) {
                            log(
                                "Ink stale document drop doc=${decoded.value.documentId} " +
                                    "rev=${decoded.value.revision} current=${current.revision}",
                            )
                            return@post
                        }
                        val next = InkNodeStore.from(decoded.value)
                        onStaged(
                            StagedInk(requestGeneration, current, next) { view ->
                                view.show(next, payload.debugActions)
                            },
                        )
                    }
                    is Decoded.Patch -> {
                        val current = store
                        if (current == null) {
                            onResyncNeeded(
                                InkResyncRequest("", -1, decoded.value.documentId, decoded.value.baseRevision),
                            )
                            return@post
                        }
                        when (val result = InkNodeStore.Executor.apply(current, decoded.value)) {
                            is InkPatchApplyResult.Applied -> onStaged(
                                StagedInk(requestGeneration, current, result.store) { view ->
                                    view.applyPatch(result.store, decoded.value.changes, payload.debugActions)
                                },
                            )
                            is InkPatchApplyResult.Invalid -> {
                                logProblem("Ink patch rejected", result.problem)
                                onRendererError(surface, listOf(result.problem))
                            }
                            is InkPatchApplyResult.ResyncNeeded -> onResyncNeeded(
                                InkResyncRequest(
                                    result.currentDocumentId,
                                    result.currentRevision,
                                    result.patchDocumentId,
                                    result.patchBaseRevision,
                                ),
                            )
                        }
                    }
                    is Decoded.Invalid -> {
                        decoded.problems.forEach { logProblem("Ink wire rejected", it) }
                        onRendererError(surface, decoded.problems)
                    }
                }
            }
        }
    }

    fun attach(view: InkHudView, debugActions: Boolean) {
        if (attachedView !== view) {
            attachedView?.clearProjection()
            attachedView?.onAction = null
            attachedView = view
        }
        view.onAction = onAction
        store?.let { accepted ->
            projectionObserver?.invoke(view, accepted)
            view.show(accepted, debugActions)
        }
    }

    fun detach(view: InkHudView) {
        if (attachedView !== view) return
        cancelPendingDecodes()
        view.onAction = null
        view.clearProjection()
        attachedView = null
    }

    fun handleKeyEvent(event: KeyEvent): Boolean = attachedView?.handleInkKeyEvent(event) == true

    fun invalidateLayoutMetrics() {
        attachedView?.invalidateLayoutMetrics()
    }

    fun clear() {
        nextGeneration()
        store = null
        attachedView?.clearProjection()
        attachedView?.onAction = null
    }

    fun destroy() {
        clear()
        attachedView = null
        decoder.shutdownNow()
    }

    fun identity(): Pair<String, Int>? = store?.let { it.documentId to it.revision }

    private fun nextGeneration(): Long {
        generation += 1
        cancelDecodeJobs()
        return generation
    }

    private fun cancelPendingDecodes() {
        generation += 1
        cancelDecodeJobs()
    }

    private fun cancelDecodeJobs() {
        decodeJobs.forEach { job ->
            job.cancelled = true
            job.future?.cancel(true)
        }
        decodeJobs.clear()
    }

    private fun decodeDocument(raw: String): Decoded {
        val decoded = RenderDocument.fromWireJson(raw)
        val value = decoded.value ?: return Decoded.Invalid(decoded.problems)
        val validation = InkWireValidator.validateDocument(
            value,
            raw.toByteArray(StandardCharsets.UTF_8).size,
        )
        return if (validation.isEmpty()) Decoded.Document(value) else Decoded.Invalid(validation)
    }

    private fun decodePatch(raw: String): Decoded {
        val decoded = RenderPatch.fromWireJson(raw)
        val value = decoded.value ?: return Decoded.Invalid(decoded.problems)
        val validation = InkWireValidator.validatePatch(
            value,
            raw.toByteArray(StandardCharsets.UTF_8).size,
        )
        return if (validation.isEmpty()) Decoded.Patch(value) else Decoded.Invalid(validation)
    }

    private fun logProblem(prefix: String, problem: InkProblem) {
        log("$prefix code=${problem.code} feature=${problem.feature.orEmpty()} message=${problem.message}")
    }

    /**
     * A validated document, or a patch applied to [base], that is not this layer's state yet.
     * [commit] makes it the state and projects it into the attached view, once, and only while
     * the layer is still where the candidate was built: no clear, detach or newer document since,
     * and [base] still the accepted store. Until then a reentrant [attach] sees [base].
     */
    internal inner class StagedInk(
        private val stagedGeneration: Long,
        private val base: InkNodeStore?,
        private val next: InkNodeStore,
        private val project: (InkHudView) -> Unit,
    ) {
        private var settled = false

        fun commit(): Boolean {
            if (settled) return false
            settled = true
            if (stagedGeneration != generation || store !== base) return false
            store = next
            attachedView?.let { view ->
                projectionObserver?.invoke(view, next)
                project(view)
            }
            return true
        }
    }

    private sealed interface Decoded {
        data class Document(val value: RenderDocument) : Decoded
        data class Patch(val value: RenderPatch) : Decoded
        data class Invalid(val problems: List<InkProblem>) : Decoded
    }

    private class DecodeJob {
        @Volatile var cancelled = false
        var future: Future<*>? = null
    }
}
