package com.anezium.rokidbus.glasses

import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WidgetAnchor
import com.anezium.rokidbus.shared.WidgetSurfaceContract
import com.anezium.rokidbus.shared.WidgetSurfaceValidationResult
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single-slot ambient widget state on the glasses. A plugin pushes full timed lines + an anchor
 * once; this holds a [LyricsWidgetClock] and forwards anchor-only updates (seek, drift, pause)
 * to it. Sequence numbers from the phone hub keep an older show from clobbering a newer one,
 * exactly like the pin slot. Android-free so the show/update/hide confusion test stays fast.
 */
internal object WidgetStateMachine {
    private var latestSeq = Long.MIN_VALUE
    var clock: LyricsWidgetClock? = null
        private set
    var holdDisplay: Boolean = false
        private set
    private var contentKey: String? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun currentContentKey(): String? = contentKey

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun handleWidgetEnvelope(envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.WIDGET_SHOW -> {
            show(envelope)
            true
        }
        BusPaths.WIDGET_UPDATE -> {
            updateAnchor(envelope)
            true
        }
        BusPaths.WIDGET_HIDE -> {
            hide(envelope)
            true
        }
        else -> false
    }

    fun clear() {
        if (clock != null || holdDisplay) {
            clock = null
            holdDisplay = false
            contentKey = null
            notifyChanged()
        }
    }

    /** Test hook: wipe singleton sequence state so a new test starts clean. */
    internal fun resetSequencesForTest() {
        latestSeq = Long.MIN_VALUE
        clock = null
        holdDisplay = false
        contentKey = null
        listeners.clear()
    }

    private fun show(envelope: BusEnvelope) {
        val validation = WidgetSurfaceContract.validateShow(envelope.payload)
        if (validation !is WidgetSurfaceValidationResult.Valid) {
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        if (surfaceId.isBlank()) {
            return
        }
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        if (seq <= latestSeq) {
            return
        }
        latestSeq = seq
        val anchor = validation.content.anchor
        contentKey = validation.content.contentKey
        clock = LyricsWidgetClock(
            timedLines = validation.content.lines,
            anchor = WidgetAnchor(anchor.positionMs, anchor.playing, anchor.sentAtElapsedRealtime),
        )
        holdDisplay = validation.content.holdDisplay
        notifyChanged()
    }

    private fun updateAnchor(envelope: BusEnvelope) {
        val validation = WidgetSurfaceContract.validateAnchorUpdate(envelope.payload)
        if (validation !is WidgetSurfaceValidationResult.Valid) {
            return
        }
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        if (seq <= latestSeq) {
            return
        }
        latestSeq = seq
        val next = clock ?: return
        val anchor = validation.content.anchor
        // Anchor-only updates carry no line content and no mode; keep the last show's holdDisplay.
        contentKey = validation.content.contentKey
        next.updateAnchor(WidgetAnchor(anchor.positionMs, anchor.playing, anchor.sentAtElapsedRealtime))
        notifyChanged()
    }

    private fun hide(envelope: BusEnvelope) {
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        if (seq <= latestSeq) {
            return
        }
        latestSeq = seq
        if (clock == null) return
        clock = null
        holdDisplay = false
        contentKey = null
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}