package com.anezium.rokidbus.glasses.page

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.NoticeController
import com.anezium.rokidbus.glasses.log
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Gate
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Reason
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Verdict
import org.json.JSONObject
import java.util.UUID

/** A render heartbeat, scoped to the attached map view. A timer alone never renews demand. */
internal class RenderedMapLease(
    private val view: View,
    private val supported: () -> Boolean = GlassesHub::renderedMapsActive,
    private val deliver: (JSONObject) -> Boolean = {
        GlassesHub.sendToPhone(RenderedMapContract.PATH, it, binary = ByteArray(0))
    },
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private var context: RenderedMapContext? = null
    private val diagnostics = MapFeedbackDiagnostics(clock, ::log)
    private var boundAtMs = 0L
    private var mapView = MapView.FOLLOW
    private var token = ""
    private var sentAt = Long.MIN_VALUE
    private var active = false
    private var reportPending = false
    private var covered = false
    private var unobserveNotice: (() -> Unit)? = null
    private val rect = Rect()
    private val redraw = Runnable {
        if (visible()) view.invalidate() else clear()
    }

    fun bind(context: RenderedMapContext?, mapView: MapView) {
        if (this.context == context && this.mapView == mapView) return
        if (context != null && this.context?.copy(revision = context.revision) == context && this.mapView == mapView) {
            // A content refresh keeps the same attached presentation. Replace its acknowledgment
            // on the next draw without manufacturing a hide between the two rendered revisions.
            this.context = context
            boundAtMs = clock()
            reportPending = true
            return
        }
        clear()
        this.context = context
        boundAtMs = clock()
        this.mapView = mapView
        token = UUID.randomUUID().toString()
        sentAt = Long.MIN_VALUE
        if (context == null) detach()
    }

    fun drawn() {
        if (context != null && view.isAttachedToWindow && unobserveNotice == null) {
            unobserveNotice = NoticeController.observe { notice ->
                covered = notice != null
                if (covered) clear() else view.invalidate()
            }
        }
        val reason = when {
            context == null -> Reason.NO_CONTEXT
            !supported() -> Reason.CAPABILITY
            else -> visibilityReason()
        }
        if (reason != null) {
            diagnostics.record(Gate.GLASSES_DRAW, Verdict.CLEAR, reason, sample(false))
            clear()
            return
        }
        diagnostics.record(Gate.GLASSES_DRAW, Verdict.PASS, Reason.CURRENT, sample(true))
        val now = clock()
        if (reportPending || !active || now - sentAt >= RenderedMapContract.RENEW_MS) {
            if (send(true)) {
                active = true
                reportPending = false
                sentAt = now
            }
        }
        view.removeCallbacks(redraw)
        view.postDelayed(redraw, RenderedMapContract.RENEW_MS)
    }

    fun clear() {
        view.removeCallbacks(redraw)
        if (active) {
            diagnostics.record(Gate.GLASSES_DRAW, Verdict.CLEAR, visibilityReason() ?: Reason.RESET, sample(false))
            send(false)
        }
        active = false
    }

    fun detach() {
        clear()
        unobserveNotice?.invoke()
        unobserveNotice = null
    }

    private fun visible(): Boolean = visibilityReason() == null

    private fun visibilityReason(): Reason? = when {
        covered -> Reason.COVERED
        !view.isAttachedToWindow -> Reason.DETACHED
        !view.isShown || view.windowVisibility != View.VISIBLE || !(view.alpha > 0f) ||
            !view.getLocalVisibleRect(rect) || rect.left > 0 || rect.top > 0 ||
            rect.right < view.width || rect.bottom < view.height || view.width <= 0 || view.height <= 0 -> Reason.NOT_VISIBLE
        else -> null
    }

    private fun send(visible: Boolean): Boolean {
        val authority = context
        if (authority == null || !supported()) {
            diagnostics.record(Gate.GLASSES_SEND, Verdict.DROP,
                if (authority == null) Reason.NO_CONTEXT else Reason.CAPABILITY, sample(visible))
            return false
        }
        val sent = deliver(authority.toPayload()
            .put("token", token).put("view", if (mapView == MapView.FOLLOW) RenderedMapContract.FOLLOW else RenderedMapContract.OVERVIEW)
            .put("visible", visible))
        diagnostics.record(Gate.GLASSES_SEND, if (sent) Verdict.PASS else Verdict.DROP,
            if (!sent) Reason.DELIVERY_FAILED else if (visible) Reason.CURRENT else Reason.HIDDEN, sample(visible))
        return sent
    }

    private fun sample(visible: Boolean) = MapFeedbackDiagnostics.Sample(
        kind = when (context?.kind) {
            RenderedMapContract.AMBIENT -> MapFeedbackDiagnostics.Kind.AMBIENT
            RenderedMapContract.PAGE -> MapFeedbackDiagnostics.Kind.PAGE
            else -> MapFeedbackDiagnostics.Kind.UNKNOWN
        },
        view = when (mapView) {
            MapView.FOLLOW -> MapFeedbackDiagnostics.View.FOLLOW
            MapView.OVERVIEW -> MapFeedbackDiagnostics.View.OVERVIEW
            else -> MapFeedbackDiagnostics.View.UNKNOWN
        },
        revision = context?.revision ?: -1, seq = context?.seq ?: -1,
        ageMs = clock() - boundAtMs, visible = visible,
    )
}
