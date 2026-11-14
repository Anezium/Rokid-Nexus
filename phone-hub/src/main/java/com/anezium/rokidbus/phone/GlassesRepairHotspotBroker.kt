package com.anezium.rokidbus.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesHotspotCredentials
import com.anezium.rokidbus.shared.GlassesRepairHotspotContract as Wire

/** Uses the file controller's reservation; never starts a second hotspot. */
internal class GlassesRepairHotspotBroker(
    private val context: Context,
    private val acquire: (String, Long, (GlassesFilesHotspotCredentials) -> Unit, () -> Unit) -> Boolean,
    private val release: (String) -> Unit,
    private val send: (BusEnvelope) -> String?,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var activeId: String? = null
    private var activeOffer: GlassesFilesHotspotCredentials? = null
    private val timeout = Runnable { close() }

    fun handle(envelope: BusEnvelope) {
        if (envelope.binary != null || !Wire.validId(envelope.id)) return
        val kind = Wire.kind(envelope.payload) ?: return
        main.post {
            if (kind == "release") {
                if (activeId == envelope.id) close()
                return@post
            }
            if (kind != "request") return@post
            val automatic = envelope.payload.opt("automatic") as? Boolean ?: return@post
            if (!GlassesFilesHotspotHost.permitted(context)) {
                android.util.Log.i("RokidNexus", "Hotspot recovery unavailable reason=permission")
                unavailable(envelope.id)
                return@post
            }
            if (automatic && !GlassesRepairSettingsStore(context).isAutoRepairEnabled()) {
                unavailable(envelope.id)
                return@post
            }
            if (activeId == envelope.id) {
                activeOffer?.let { offer(envelope.id, it) }
                return@post
            }
            if (activeId != null) { unavailable(envelope.id); return@post }
            activeId = envelope.id
            main.postDelayed(timeout, Wire.TIMEOUT_MS)
            val accepted = acquire(envelope.id,
                SystemClock.elapsedRealtime() + Wire.TIMEOUT_MS,
                { credentials -> main.post {
                    if (activeId == envelope.id) {
                        activeOffer = credentials
                        offer(envelope.id, credentials)
                    }
                } },
                { main.post {
                    if (activeId == envelope.id) {
                        activeId = null
                        activeOffer = null
                        main.removeCallbacks(timeout)
                        unavailable(envelope.id)
                    }
                } },
            )
            if (!accepted) {
                android.util.Log.i("RokidNexus", "Hotspot recovery unavailable reason=busy_or_disconnected")
                activeId = null
                activeOffer = null
                main.removeCallbacks(timeout)
                unavailable(envelope.id)
            }
        }
    }

    private fun offer(id: String, credentials: GlassesFilesHotspotCredentials) {
        if (send(BusEnvelope(path = Wire.PATH, id = id, payload = Wire.offer(credentials))) != null) close()
    }

    private fun unavailable(id: String) {
        send(BusEnvelope(path = Wire.PATH, id = id, payload = Wire.message("unavailable")))
    }

    override fun close() {
        val id = activeId ?: return
        activeId = null
        activeOffer = null
        main.removeCallbacks(timeout)
        release(id)
        unavailable(id)
    }
}
