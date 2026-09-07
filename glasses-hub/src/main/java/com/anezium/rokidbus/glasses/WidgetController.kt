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
    private val hiddenThrough = mutableMapOf<String, Long>()
    @Volatile var clock: LyricsWidgetClock? = null
        private set
    @Volatile var holdDisplay: Boolean = false
        private set
    @Volatile private var contentKey: String? = null
    private var ownerSurfaceId: String? = null
    @Volatile var receivedAt: Long = 0L
        private set
    internal var now: () -> Long = SystemClock::elapsedRealtime
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun currentContentKey(): String? = contentKey

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    @Synchronized
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

    @Synchronized
    fun clear() {
        if (clock != null || holdDisplay) {
            clock = null
            holdDisplay = false
            contentKey = null
            ownerSurfaceId = null
            notifyChanged()
        }
    }

    /** Test hook: wipe singleton sequence state so a new test starts clean. */
    internal fun resetSequencesForTest() {
        latestSeq = Long.MIN_VALUE
        hiddenThrough.clear()
        clock = null
        holdDisplay = false
        contentKey = null
        ownerSurfaceId = null
        listeners.clear()
    }

    private fun show(envelope: BusEnvelope) {
        val validation = WidgetSurfaceContract.validateShow(envelope.payload)
        if (validation !is WidgetSurfaceValidationResult.Valid) {
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        val owner = envelope.payload.optString("ownerPluginId")
        if (owner.isBlank() || surfaceId != "$owner:widget") {
            return
        }
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        if (seq <= latestSeq || seq <= (hiddenThrough[surfaceId] ?: Long.MIN_VALUE)) {
            return
        }
        latestSeq = seq
        ownerSurfaceId = surfaceId
        receivedAt = now()
        val anchor = validation.content.anchor
        contentKey = validation.content.contentKey
        clock = LyricsWidgetClock(
            timedLines = validation.content.lines,
            anchor = WidgetAnchor(anchor.positionMs, anchor.playing, receivedAt),
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
        if (envelope.payload.optString("surfaceId") != ownerSurfaceId ||
            validation.content.contentKey != contentKey
        ) return
        latestSeq = seq
        val next = clock ?: return
        receivedAt = now()
        val anchor = validation.content.anchor
        // Anchor-only updates carry no line content and no mode; keep the last show's holdDisplay.
        contentKey = validation.content.contentKey
        next.updateAnchor(WidgetAnchor(anchor.positionMs, anchor.playing, receivedAt))
        notifyChanged()
    }

    private fun hide(envelope: BusEnvelope) {
        val surfaceId = envelope.payload.optString("surfaceId")
        val owner = envelope.payload.optString("ownerPluginId")
        if (owner.isBlank() || surfaceId != "$owner:widget") return
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        // A hide may overtake its show without owning the currently visible slot.
        hiddenThrough[surfaceId] = maxOf(hiddenThrough[surfaceId] ?: Long.MIN_VALUE, seq)
        if (surfaceId != ownerSurfaceId) return
        if (seq <= latestSeq) {
            return
        }
        latestSeq = seq
        if (clock == null) return
        clock = null
        holdDisplay = false
        contentKey = null
        ownerSurfaceId = null
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
