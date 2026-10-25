package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PinSurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceValidationResult
import java.util.concurrent.CopyOnWriteArrayList

internal data class NexusPinSurface(
    val surfaceId: String,
    val seq: Long,
    val content: PinSurfaceContent,
    val expiresAtMs: Long?,
)

internal sealed interface PinStateDecision {
    data class Applied(val pin: NexusPinSurface) : PinStateDecision
    data object Hidden : PinStateDecision
    data object DroppedStale : PinStateDecision
}

/** Pure single-slot sequence and TTL state used by the glasses controller. */
internal class PinStateMachine {
    private var latestSeq = Long.MIN_VALUE
    private var active: NexusPinSurface? = null

    fun activePin(): NexusPinSurface? = active

    fun show(
        surfaceId: String,
        seq: Long,
        content: PinSurfaceContent,
        nowMs: Long,
    ): PinStateDecision {
        if (seq <= latestSeq) return PinStateDecision.DroppedStale
        latestSeq = seq
        val pin = NexusPinSurface(
            surfaceId = surfaceId,
            seq = seq,
            content = content,
            expiresAtMs = content.ttlMs?.let { nowMs + it },
        )
        active = pin
        return PinStateDecision.Applied(pin)
    }

    fun hide(seq: Long): PinStateDecision {
        if (seq <= latestSeq) return PinStateDecision.DroppedStale
        latestSeq = seq
        active = null
        return PinStateDecision.Hidden
    }

    fun expire(nowMs: Long, expectedSeq: Long): Boolean {
        val pin = active ?: return false
        val deadline = pin.expiresAtMs ?: return false
        if (pin.seq != expectedSeq || nowMs < deadline) return false
        active = null
        return true
    }
}

internal object PinController {
    private val main = Handler(Looper.getMainLooper())
    private val state = PinStateMachine()
    private val listeners = CopyOnWriteArrayList<(NexusPinSurface?) -> Unit>()
    private var expiry: Runnable? = null
    private var cameraOverlayActive = false
    private var notified: NexusPinSurface? = null

    init {
        // The user's choice on the phone hides or brings back the held pin: no new pin is needed.
        AmbientPreferences.observe(::refreshKeptOff)
    }

    fun activePin(): NexusPinSurface? = state.activePin()

    /**
     * The held pin unless the user keeps its owner off the glasses. It stays held, with its own
     * TTL, so letting the owner back shows it again at once.
     */
    fun presentablePin(): NexusPinSurface? =
        state.activePin()?.takeUnless { ActivityController.keptOffGlasses(ownerOf(it)) }

    fun visiblePin(): NexusPinSurface? =
        presentablePin().takeUnless { cameraOverlayActive }

    fun observe(listener: (NexusPinSurface?) -> Unit): () -> Unit {
        listeners += listener
        listener(visiblePin())
        return { listeners.remove(listener) }
    }

    fun handlePinEnvelope(envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.PIN_SHOW -> {
            runOnMain { show(envelope) }
            true
        }
        BusPaths.PIN_HIDE -> {
            runOnMain { hide(envelope) }
            true
        }
        else -> false
    }

    /** Whether the user keeps the pin's owner off the glasses may have changed; redraws only if it did. */
    fun refreshKeptOff() {
        runOnMain { if (visiblePin() != notified) notifyChanged() }
    }

    fun setCameraOverlayActive(active: Boolean) {
        runOnMain {
            if (cameraOverlayActive == active) return@runOnMain
            cameraOverlayActive = active
            notifyChanged()
        }
    }

    private fun show(envelope: BusEnvelope) {
        val validation = PinSurfaceContract.validateShow(envelope.payload)
        if (validation !is PinSurfaceValidationResult.Valid) {
            log("pin rejected code=${PinSurfaceContract.ERROR_INVALID_PIN}")
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        if (surfaceId.isBlank()) {
            log("pin rejected code=${PinSurfaceContract.ERROR_INVALID_PIN}")
            return
        }
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        when (
            val decision = state.show(
                surfaceId = surfaceId,
                seq = seq,
                content = validation.content,
                nowMs = SystemClock.elapsedRealtime(),
            )
        ) {
            PinStateDecision.DroppedStale ->
                log("pin dropped stale id=$surfaceId seq=$seq")
            is PinStateDecision.Applied -> {
                scheduleExpiry(decision.pin)
                notifyChanged()
            }
            PinStateDecision.Hidden -> Unit
        }
    }

    private fun hide(envelope: BusEnvelope) {
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        when (state.hide(seq)) {
            PinStateDecision.DroppedStale ->
                log("pin hide dropped stale seq=$seq")
            PinStateDecision.Hidden -> {
                cancelExpiry()
                notifyChanged()
            }
            is PinStateDecision.Applied -> Unit
        }
    }

    private fun scheduleExpiry(pin: NexusPinSurface) {
        cancelExpiry()
        val deadline = pin.expiresAtMs ?: return
        val task = Runnable {
            if (state.expire(SystemClock.elapsedRealtime(), pin.seq)) {
                expiry = null
                notifyChanged()
            }
        }
        expiry = task
        main.postDelayed(task, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun cancelExpiry() {
        expiry?.let(main::removeCallbacks)
        expiry = null
    }

    private fun notifyChanged() {
        val visible = visiblePin()
        notified = visible
        listeners.forEach { listener -> runCatching { listener(visible) } }
    }

    private fun ownerOf(pin: NexusPinSurface): String =
        pin.surfaceId.removeSuffix(":${PinSurfaceContract.LOCAL_SURFACE_ID}")

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
