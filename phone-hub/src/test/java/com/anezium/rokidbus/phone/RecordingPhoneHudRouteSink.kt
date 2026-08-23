package com.anezium.rokidbus.phone

import android.os.IBinder
import com.anezium.rokidbus.shared.BusEnvelope

internal class RecordingPhoneHudRouteSink(
    var capabilityBits: Int = 0,
    var linkUp: Boolean = true,
    var sendRemoteError: String? = null,
) : PhoneHudRouteSink {
    data class RouteRecord(
        val path: String,
        val pluginId: String?,
        val verdict: PluginBusJournal.Verdict,
        val reason: String?,
    )

    val remote = mutableListOf<BusEnvelope>()
    val local = mutableListOf<BusEnvelope>()
    val errors = mutableListOf<Pair<String, String>>()
    val localRoutes = mutableListOf<RouteRecord>()
    val remoteRoutes = mutableListOf<RouteRecord>()
    val logs = mutableListOf<String>()

    override fun capabilities(): Int = capabilityBits
    override fun pinLinkUp(): Boolean = linkUp
    override fun log(message: String) {
        logs += message
    }

    override fun sendRemote(envelope: BusEnvelope): String? {
        remote += envelope
        return sendRemoteError
    }

    override fun deliverLocal(envelope: BusEnvelope, targetBinder: IBinder?): Boolean {
        local += envelope
        return true
    }

    override fun deliverError(targetBinder: IBinder?, id: String, code: String) {
        errors += id to code
    }

    override fun recordLocalRoute(
        envelope: BusEnvelope,
        senderUid: Int,
        pluginId: String?,
        verdict: PluginBusJournal.Verdict,
        reason: String?,
    ) {
        localRoutes += RouteRecord(envelope.path, pluginId, verdict, reason)
    }

    override fun recordRemoteRoute(
        envelope: BusEnvelope,
        verdict: PluginBusJournal.Verdict,
        reason: String?,
    ) {
        remoteRoutes += RouteRecord(envelope.path, null, verdict, reason)
    }
}

internal fun hudSender(pluginId: String?, uid: Int = 42) =
    PhoneHudRouteSender(pluginId = pluginId, replyBinder = null, uid = uid)
