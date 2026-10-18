package com.anezium.rokidbus.glasses.page

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.NoticeController
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RenderedMapContract
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
    private var mapView = MapView.FOLLOW
    private var token = ""
    private var sentAt = Long.MIN_VALUE
    private var active = false
    private var covered = false
    private var unobserveNotice: (() -> Unit)? = null
    private val rect = Rect()
    private val redraw = Runnable {
        if (visible()) view.invalidate() else clear()
    }

    fun bind(context: RenderedMapContext?, mapView: MapView) {
        if (this.context == context && this.mapView == mapView) return
        clear()
        this.context = context
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
        if (context == null || !supported() || !visible()) {
            clear()
            return
        }
        val now = clock()
        if (!active || now - sentAt >= RenderedMapContract.RENEW_MS) {
            if (send(true)) {
                active = true
                sentAt = now
            }
        }
        view.removeCallbacks(redraw)
        view.postDelayed(redraw, RenderedMapContract.RENEW_MS)
    }

    fun clear() {
        view.removeCallbacks(redraw)
        if (active) send(false)
        active = false
    }

    fun detach() {
        clear()
        unobserveNotice?.invoke()
        unobserveNotice = null
    }

    private fun visible(): Boolean = !covered && view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
        view.alpha > 0f && view.getLocalVisibleRect(rect) && rect.left <= 0 && rect.top <= 0 &&
        rect.right >= view.width && rect.bottom >= view.height && view.width > 0 && view.height > 0

    private fun send(visible: Boolean): Boolean {
        val authority = context ?: return false
        if (!supported()) return false
        return deliver(authority.toPayload()
            .put("token", token).put("view", if (mapView == MapView.FOLLOW) RenderedMapContract.FOLLOW else RenderedMapContract.OVERVIEW)
            .put("visible", visible))
    }
}
