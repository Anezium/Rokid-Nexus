package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.CameraOverlayContract
import kotlin.math.abs

internal data class LiveOverlayBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class LiveOverlayParagraphLayout(
    val medianLineHeight: Float,
    val growDown: Float,
    val column: Int?,
)

internal data class LiveOverlayTrack(
    val id: String,
    val stableId: Long,
    val sourceText: String,
    val box: LiveOverlayBox,
    val layout: LiveOverlayParagraphLayout,
)

internal data class PendingLiveOverlay(
    val generation: Long,
    val script: OcrScript,
    val targetLanguage: String,
    val tracks: List<LiveOverlayTrack>,
)

internal data class ComposedLiveOverlayItem(
    val id: String,
    val text: String,
    val box: LiveOverlayBox,
    val role: String,
    val reason: String?,
    val layout: LiveOverlayParagraphLayout,
)

internal data class OverlayDisplay(
    val text: String,
    val role: String,
    val reason: String?,
    val fallback: Boolean,
)

internal data class LiveTranslationApplication(
    val emitted: List<ComposedLiveOverlayItem>?,
    val applied: Int,
    val rejected: Int,
    val fallback: Int,
)

/**
 * Serialized phone-side live overlay state. OCR geometry is segmented into paragraphs, reconciled
 * into stable identities, damped, and compared with the last emitted semantic snapshot. All public
 * operations are synchronized because OCR and translation callbacks can arrive on different pools.
 */
internal class LiveOverlayComposer {
    private data class CachedDisplay(
        val sourceText: String,
        val display: OverlayDisplay,
    )

    private data class ContinuousGeometry(
        val bounds: LiveParagraphRect,
        val medianLineHeight: Float,
        val growDown: Float,
    )

    private var reconciliationState = LiveParagraphReconciliationState()
    private var generation = 0L
    private var lastScript: OcrScript? = null
    private var lastTargetLanguage: String? = null
    private val dampedGeometry = mutableMapOf<Long, ContinuousGeometry>()
    private val displayCache = mutableMapOf<Long, CachedDisplay>()
    private var current: PendingLiveOverlay? = null
    private var lastEmitted: Map<String, ComposedLiveOverlayItem> = emptyMap()

    @Synchronized
    fun observe(
        blocks: List<LiveFrameParagraphBlock>,
        script: OcrScript,
        frameWidth: Int,
        frameHeight: Int,
        nowMs: Long,
        targetLanguage: String = "",
    ): PendingLiveOverlay {
        require(frameWidth > 0 && frameHeight > 0)
        if (lastScript != script || lastTargetLanguage != targetLanguage) {
            resetTracking()
            generation += 1
            lastScript = script
            lastTargetLanguage = targetLanguage
        }
        val observations = segmentLiveFrameParagraphs(blocks).map { paragraph ->
            LiveParagraphObservation(
                sourceText = paragraph.source,
                bounds = paragraph.bounds.toLiveParagraphRect(),
                memberBounds = paragraph.lineBounds.map { it.toLiveParagraphRect() },
                columnIndex = paragraph.columnIndex,
                gapBelow = paragraph.gapBelow,
            )
        }
        val reconciled = LiveParagraphReconciler.reconcile(reconciliationState, observations, nowMs)
        reconciliationState = reconciled.state
        val activeIds = reconciled.state.anchors.mapTo(mutableSetOf()) { it.stableId }
        dampedGeometry.keys.retainAll(activeIds)
        displayCache.keys.retainAll(activeIds)

        val tracks = reconciled.visibleAnchors.mapNotNull { anchor ->
            val target = anchor.toContinuousGeometry()
            val previous = dampedGeometry[anchor.stableId]
            val damped = previous?.dampToward(target) ?: target
            dampedGeometry[anchor.stableId] = damped
            val normalizedBox = damped.bounds.normalize(frameWidth, frameHeight) ?: return@mapNotNull null
            val normalizedLayout = damped.normalizeLayout(frameHeight, anchor.columnIndex)
                ?: return@mapNotNull null
            LiveOverlayTrack(
                id = "lens-live-${anchor.stableId}",
                stableId = anchor.stableId,
                sourceText = anchor.sourceText,
                box = normalizedBox,
                layout = normalizedLayout,
            )
        }
        return PendingLiveOverlay(generation, script, targetLanguage, tracks).also { current = it }
    }

    @Synchronized
    fun translationCandidates(
        pending: PendingLiveOverlay,
        sessionGeneration: Long,
    ): List<LiveTranslationCandidate> {
        if (!isCurrent(pending)) return emptyList()
        return pending.tracks.mapNotNull { track ->
            val cached = displayCache[track.stableId]
            if (cached?.sourceText == track.sourceText && cached.display.role != ROLE_PENDING) {
                null
            } else {
                LiveTranslationCandidate(
                    sessionGeneration = sessionGeneration,
                    composerGeneration = pending.generation,
                    script = pending.script,
                    targetLanguage = pending.targetLanguage,
                    stableId = track.stableId,
                    sourceText = track.sourceText,
                )
            }
        }
    }

    /**
     * Compatibility entry point used by focused composer tests and synchronous callers. It still
     * applies the same generation/script/language/stable-id/exact-source guards as async results.
     */
    @Synchronized
    fun complete(
        pending: PendingLiveOverlay,
        translations: Map<String, TranslationResult>,
    ): List<ComposedLiveOverlayItem>? {
        if (!isCurrent(pending)) return null
        if (translations.isNotEmpty()) {
            val updates = pending.tracks.mapNotNull { track ->
                translations[track.sourceText]?.let { result ->
                    LiveTranslationUpdate(
                        LiveTranslationCandidate(
                            sessionGeneration = 0L,
                            composerGeneration = pending.generation,
                            script = pending.script,
                            targetLanguage = pending.targetLanguage,
                            stableId = track.stableId,
                            sourceText = track.sourceText,
                        ),
                        result,
                    )
                }
            }
            applyUpdatesLocked(updates)
        }
        return composeLocked(pending)
    }

    @Synchronized
    fun applyTranslations(
        updates: List<LiveTranslationUpdate>,
        sessionGeneration: Long? = null,
    ): LiveTranslationApplication {
        val pending = current
        if (pending == null) {
            return LiveTranslationApplication(null, 0, updates.size, 0)
        }
        val counts = applyUpdatesLocked(updates, sessionGeneration)
        return LiveTranslationApplication(
            emitted = composeLocked(pending),
            applied = counts.first,
            rejected = updates.size - counts.first,
            fallback = counts.second,
        )
    }

    @Synchronized
    fun reset() {
        resetTracking()
        generation += 1
        lastScript = null
        lastTargetLanguage = null
        current = null
        lastEmitted = emptyMap()
    }

    private fun applyUpdatesLocked(
        updates: List<LiveTranslationUpdate>,
        sessionGeneration: Long? = null,
    ): Pair<Int, Int> {
        val pending = current ?: return 0 to 0
        val activeById = pending.tracks.associateBy(LiveOverlayTrack::stableId)
        var applied = 0
        var fallback = 0
        updates.forEach { update ->
            val candidate = update.candidate
            val track = activeById[candidate.stableId] ?: return@forEach
            if ((sessionGeneration != null && candidate.sessionGeneration != sessionGeneration) ||
                candidate.composerGeneration != pending.generation ||
                candidate.script != pending.script ||
                candidate.targetLanguage != pending.targetLanguage ||
                candidate.sourceText != track.sourceText ||
                update.result.src != candidate.sourceText
            ) return@forEach
            val existing = displayCache[candidate.stableId]
            if (existing?.sourceText == candidate.sourceText && existing.display.role != ROLE_PENDING) {
                return@forEach
            }
            val display = overlayDisplay(candidate.sourceText, update.result)
            displayCache[candidate.stableId] = CachedDisplay(candidate.sourceText, display)
            applied += 1
            if (display.fallback) fallback += 1
        }
        return applied to fallback
    }

    private fun composeLocked(pending: PendingLiveOverlay): List<ComposedLiveOverlayItem>? {
        val items = pending.tracks.map { track ->
            val cached = displayCache[track.stableId]
                ?.takeIf { it.sourceText == track.sourceText }
                ?: CachedDisplay(track.sourceText, overlayDisplay(track.sourceText, null)).also {
                    displayCache[track.stableId] = it
                }
            ComposedLiveOverlayItem(
                id = track.id,
                text = cached.display.text,
                box = track.box,
                role = cached.display.role,
                reason = cached.display.reason,
                layout = track.layout,
            )
        }
        val next = items.associateBy(ComposedLiveOverlayItem::id)
        if (!meaningfullyChanged(lastEmitted, next)) return null
        lastEmitted = next
        return items
    }

    private fun isCurrent(pending: PendingLiveOverlay): Boolean =
        current === pending || current == pending

    private fun resetTracking() {
        reconciliationState = LiveParagraphReconciliationState()
        dampedGeometry.clear()
        displayCache.clear()
    }

    private fun meaningfullyChanged(
        previous: Map<String, ComposedLiveOverlayItem>,
        next: Map<String, ComposedLiveOverlayItem>,
    ): Boolean {
        if (previous.keys != next.keys) return true
        return next.any { (id, item) ->
            val old = previous.getValue(id)
            old.text != item.text || old.role != item.role || old.reason != item.reason ||
                abs(old.box.left - item.box.left) > BOX_EMISSION_EPSILON ||
                abs(old.box.top - item.box.top) > BOX_EMISSION_EPSILON ||
                abs(old.box.right - item.box.right) > BOX_EMISSION_EPSILON ||
                abs(old.box.bottom - item.box.bottom) > BOX_EMISSION_EPSILON ||
                abs(old.layout.medianLineHeight - item.layout.medianLineHeight) >
                    LAYOUT_EMISSION_EPSILON ||
                abs(old.layout.growDown - item.layout.growDown) > LAYOUT_EMISSION_EPSILON ||
                old.layout.column != item.layout.column
        }
    }

    private fun LiveParagraphAnchor.toContinuousGeometry(): ContinuousGeometry =
        ContinuousGeometry(
            bounds = bounds,
            medianLineHeight = median(memberBounds.map(LiveParagraphRect::height)),
            growDown = gapBelow,
        )

    private fun ContinuousGeometry.dampToward(next: ContinuousGeometry): ContinuousGeometry =
        ContinuousGeometry(
            bounds = bounds.dampToward(next.bounds),
            medianLineHeight = medianLineHeight.dampToward(next.medianLineHeight),
            growDown = growDown.dampToward(next.growDown),
        )

    private fun LiveParagraphRect.dampToward(next: LiveParagraphRect): LiveParagraphRect =
        LiveParagraphRect(
            left = left.dampToward(next.left),
            top = top.dampToward(next.top),
            right = right.dampToward(next.right),
            bottom = bottom.dampToward(next.bottom),
        )

    private fun Float.dampToward(next: Float): Float = this + (next - this) * BOX_DAMPING_ALPHA

    private fun ContinuousGeometry.normalizeLayout(
        height: Int,
        columnIndex: Int,
    ): LiveOverlayParagraphLayout? {
        if (height <= 0) return null
        val normalizedLineHeight = medianLineHeight / height
        val normalizedGrowDown = growDown / height
        if (!normalizedLineHeight.isFinite() || normalizedLineHeight <= 0f ||
            !normalizedGrowDown.isFinite() || normalizedGrowDown < 0f
        ) return null
        return LiveOverlayParagraphLayout(
            medianLineHeight = normalizedLineHeight.coerceAtMost(1f),
            growDown = normalizedGrowDown.coerceAtMost(1f),
            column = columnIndex.takeIf { it in 0..CameraOverlayContract.MAX_LAYOUT_COLUMN },
        )
    }

    private fun LiveParagraphRect.normalize(width: Int, height: Int): LiveOverlayBox? {
        val box = LiveOverlayBox(
            left = (left / width).coerceIn(0f, 1f),
            top = (top / height).coerceIn(0f, 1f),
            right = (right / width).coerceIn(0f, 1f),
            bottom = (bottom / height).coerceIn(0f, 1f),
        )
        return box.takeIf { it.left < it.right && it.top < it.bottom }
    }

    private fun FrozenLayoutRect.toLiveParagraphRect(): LiveParagraphRect =
        LiveParagraphRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private companion object {
        const val BOX_DAMPING_ALPHA = 0.35f
        const val BOX_EMISSION_EPSILON = 0.005f
        const val LAYOUT_EMISSION_EPSILON = 0.001f
    }
}

internal fun overlayDisplay(source: String, translated: TranslationResult?): OverlayDisplay {
    if (source.length > CameraOverlayContract.MAX_TEXT_CHARS) {
        return OverlayDisplay(
            text = SOURCE_LIMIT_MESSAGE,
            role = ROLE_TRANSLATION_FAILED,
            reason = REASON_SOURCE_TOO_LARGE,
            fallback = true,
        )
    }
    if (translated == null) {
        return OverlayDisplay(source, ROLE_PENDING, REASON_TRANSLATION_PENDING, fallback = true)
    }
    if (translated.failure != null) {
        return OverlayDisplay(
            source,
            ROLE_TRANSLATION_FAILED,
            translated.failure.wireValue.lowercase(),
            fallback = true,
        )
    }
    if (translated.fallback || translated.dst.isBlank() || translated.dst == source) {
        return OverlayDisplay(source, ROLE_TRANSLATION_FALLBACK, REASON_PROVIDER_FALLBACK, fallback = true)
    }
    if (translated.dst.length > CameraOverlayContract.MAX_TEXT_CHARS) {
        return OverlayDisplay(
            source,
            ROLE_TRANSLATION_FALLBACK,
            REASON_TRANSLATION_TOO_LARGE,
            fallback = true,
        )
    }
    return OverlayDisplay(translated.dst, ROLE_TRANSLATION, null, fallback = false)
}

internal const val ROLE_PENDING = "pending"
internal const val ROLE_TRANSLATION = "translation"
internal const val ROLE_TRANSLATION_FALLBACK = "translation-fallback"
internal const val ROLE_TRANSLATION_FAILED = "translation-failed"
private const val REASON_TRANSLATION_PENDING = "translation_pending"
private const val REASON_PROVIDER_FALLBACK = "provider_fallback"
private const val REASON_TRANSLATION_TOO_LARGE = "translation_too_large"
private const val REASON_SOURCE_TOO_LARGE = "source_too_large"
private const val SOURCE_LIMIT_MESSAGE = "Source unavailable: display limit exceeded"
