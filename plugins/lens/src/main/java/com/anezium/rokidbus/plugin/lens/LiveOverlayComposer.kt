package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.CameraOverlayContract
import kotlin.math.abs

internal data class LiveOverlayBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class LiveOverlayTrack(
    val id: String,
    val stableId: Long,
    val sourceText: String,
    val box: LiveOverlayBox,
)

internal data class PendingLiveOverlay(
    val script: OcrScript,
    val tracks: List<LiveOverlayTrack>,
)

internal data class ComposedLiveOverlayItem(
    val id: String,
    val text: String,
    val box: LiveOverlayBox,
    val role: String,
)

/**
 * Serialized phone-side live overlay state. OCR geometry is segmented into paragraphs, reconciled
 * into stable identities, damped, and finally compared with the last emitted semantic snapshot.
 */
internal class LiveOverlayComposer {
    private data class CachedDisplay(
        val sourceText: String,
        val text: String,
        val role: String,
    )

    private var reconciliationState = LiveParagraphReconciliationState()
    private var lastScript: OcrScript? = null
    private val dampedBounds = mutableMapOf<Long, LiveParagraphRect>()
    private val displayCache = mutableMapOf<Long, CachedDisplay>()
    private var lastEmitted: Map<String, ComposedLiveOverlayItem> = emptyMap()

    fun observe(
        blocks: List<LiveFrameParagraphBlock>,
        script: OcrScript,
        frameWidth: Int,
        frameHeight: Int,
        nowMs: Long,
    ): PendingLiveOverlay {
        require(frameWidth > 0 && frameHeight > 0)
        if (lastScript != script) {
            resetTracking()
            lastScript = script
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
        dampedBounds.keys.retainAll(activeIds)
        displayCache.keys.retainAll(activeIds)

        val tracks = reconciled.visibleAnchors.mapNotNull { anchor ->
            val previous = dampedBounds[anchor.stableId]
            val damped = if (previous == null) anchor.bounds else previous.dampToward(anchor.bounds)
            dampedBounds[anchor.stableId] = damped
            val normalized = damped.normalize(frameWidth, frameHeight) ?: return@mapNotNull null
            LiveOverlayTrack(
                id = "lens-live-${anchor.stableId}",
                stableId = anchor.stableId,
                sourceText = anchor.sourceText,
                box = normalized,
            )
        }
        return PendingLiveOverlay(script = script, tracks = tracks)
    }

    /**
     * Returns null when the glasses already have an equivalent overlay. An empty list is a
     * meaningful result when it clears a previously emitted overlay.
     */
    fun complete(
        pending: PendingLiveOverlay,
        translations: Map<String, TranslationResult>,
    ): List<ComposedLiveOverlayItem>? {
        val items = pending.tracks.map { track ->
            val cached = displayCache[track.stableId]
            val translated = translations[track.sourceText]
            val display = when {
                cached?.sourceText == track.sourceText && cached.role == "translation" -> cached
                translated != null -> {
                    val output = translated.dst.takeIf(String::isNotBlank) ?: track.sourceText
                    val role = if (translated.fallback || output == track.sourceText) "source" else "translation"
                    CachedDisplay(
                        sourceText = track.sourceText,
                        text = output.take(CameraOverlayContract.MAX_TEXT_CHARS),
                        role = role,
                    ).also { displayCache[track.stableId] = it }
                }
                cached?.sourceText == track.sourceText -> cached
                else -> CachedDisplay(
                    sourceText = track.sourceText,
                    text = track.sourceText.take(CameraOverlayContract.MAX_TEXT_CHARS),
                    role = "source",
                ).also { displayCache[track.stableId] = it }
            }
            ComposedLiveOverlayItem(track.id, display.text, track.box, display.role)
        }
        val next = items.associateBy(ComposedLiveOverlayItem::id)
        if (!meaningfullyChanged(lastEmitted, next)) return null
        lastEmitted = next
        return items
    }

    fun reset() {
        resetTracking()
        lastScript = null
        lastEmitted = emptyMap()
    }

    private fun resetTracking() {
        reconciliationState = LiveParagraphReconciliationState()
        dampedBounds.clear()
        displayCache.clear()
    }

    private fun meaningfullyChanged(
        previous: Map<String, ComposedLiveOverlayItem>,
        next: Map<String, ComposedLiveOverlayItem>,
    ): Boolean {
        if (previous.keys != next.keys) return true
        return next.any { (id, item) ->
            val old = previous.getValue(id)
            old.text != item.text || old.role != item.role ||
                abs(old.box.left - item.box.left) > BOX_EMISSION_EPSILON ||
                abs(old.box.top - item.box.top) > BOX_EMISSION_EPSILON ||
                abs(old.box.right - item.box.right) > BOX_EMISSION_EPSILON ||
                abs(old.box.bottom - item.box.bottom) > BOX_EMISSION_EPSILON
        }
    }

    private fun LiveParagraphRect.dampToward(next: LiveParagraphRect): LiveParagraphRect =
        LiveParagraphRect(
            left = left + (next.left - left) * BOX_DAMPING_ALPHA,
            top = top + (next.top - top) * BOX_DAMPING_ALPHA,
            right = right + (next.right - right) * BOX_DAMPING_ALPHA,
            bottom = bottom + (next.bottom - bottom) * BOX_DAMPING_ALPHA,
        )

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

    private companion object {
        const val BOX_DAMPING_ALPHA = 0.35f
        const val BOX_EMISSION_EPSILON = 0.005f
    }
}
