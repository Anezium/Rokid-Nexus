package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject

object CxrBusBridge {
    private val main = Handler(Looper.getMainLooper())
    private var bridge: CXRServiceBridge? = null
    @Volatile private var connected = false

    fun start(context: Context) {
        if (bridge != null) {
            requestStateProbe()
            return
        }
        val next = CXRServiceBridge()
        bridge = next
        next.setStatusListener(statusListener)
        val result = next.subscribe(BusConstants.CXR_KEY, msgCallback)
        log("CXR-S subscribe key=${BusConstants.CXR_KEY} result=$result")
        requestStateProbe()
    }

    fun isUp(): Boolean = connected

    fun send(envelope: BusEnvelope): Boolean =
        runCatching {
            val json = FrameProtocol.toJson(envelope).toString()
            val result = bridge?.sendMessage(
                BusConstants.CXR_KEY,
                Caps().apply { write(json) },
            )
            log("CXR-S TX ${envelope.path} id=${envelope.id} result=$result")
            if (result != null && result >= 0) {
                markConnected()
                true
            } else {
                false
            }
        }.getOrElse {
            logError("CXR-S TX failed", it)
            false
        }

    private fun requestStateProbe() {
        val probe = BusEnvelope(
            path = "/hub/probe",
            payload = JSONObject().put("source", "glasses"),
        )
        send(probe)
    }

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, mac: String?, deviceType: Int) {
            main.post {
                markConnected()
                requestStateProbe()
            }
        }

        override fun onDisconnected() {
            main.post {
                connected = false
                GlassesHub.onCxrState(false)
            }
        }

        override fun onConnecting(name: String?, mac: String?, deviceType: Int) = Unit

        override fun onARTCStatus(latency: Float, connected: Boolean) {
            if (connected) main.post { markConnected() }
        }

        override fun onAudioNoise(noise: Float) = Unit

        override fun onRokidAccountChanged(account: String?) = Unit
    }

    private val msgCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
            if (msgType != BusConstants.CXR_KEY) return
            val payload = decodePayload(caps, data)
            if (payload.isBlank()) return
            val envelope = runCatching { FrameProtocol.fromJson(JSONObject(payload)) }
                .onFailure { logError("CXR-S JSON parse failed", it) }
                .getOrNull() ?: return
            main.post {
                markConnected()
                GlassesHub.onRemoteEnvelope(envelope)
            }
        }
    }

    private fun decodePayload(caps: Caps?, data: ByteArray?): String {
        if (data != null && data.isNotEmpty()) {
            val raw = String(data, Charsets.UTF_8).trim()
            if (raw.startsWith("{")) return raw
            val serializedCapsPayload = runCatching {
                val parsed = Caps.fromBytes(data)
                if (parsed.size() > 0) parsed.at(0).string else ""
            }.getOrDefault("")
            if (serializedCapsPayload.isNotBlank()) return serializedCapsPayload
            if (raw.isNotBlank()) return raw
        }
        return if (caps != null && caps.size() > 0) {
            runCatching { caps.at(0).string }.getOrDefault("")
        } else {
            ""
        }
    }

    private fun markConnected() {
        if (!connected) {
            connected = true
            GlassesHub.onCxrState(true)
        }
    }
}
