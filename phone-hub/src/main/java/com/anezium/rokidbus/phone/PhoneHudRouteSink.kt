package com.anezium.rokidbus.phone

import android.os.IBinder
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.RenderedMapContract

/**
 * Side effects a HUD-tier router needs from [BusHubService].
 *
 * The service keeps the Binder and the glasses link; extracted routers call
 * back here so transport, journaling, and local delivery stay in one place.
 */
internal interface PhoneHudRouteSink {
    fun capabilities(): Int
    /** Negotiated activity support, retained across link loss, without unrelated discovery. */
    fun activitiesSupported(): Boolean = capabilities() and BusCapabilityBits.ACTIVITY_SURFACE != 0
    fun renderedMapsSupported(): Boolean = RenderedMapContract.supported(capabilities())
    fun pinLinkUp(): Boolean
    fun log(message: String)
    fun sendRemote(envelope: BusEnvelope): String?
    fun deliverLocal(envelope: BusEnvelope, targetBinder: IBinder? = null): Boolean
    fun deliverError(targetBinder: IBinder?, id: String, code: String)
    fun recordLocalRoute(
        envelope: BusEnvelope,
        senderUid: Int,
        pluginId: String?,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    )
    fun recordRemoteRoute(
        envelope: BusEnvelope,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    )
}

internal data class PhoneHudRouteSender(
    val pluginId: String?,
    val replyBinder: IBinder?,
    val uid: Int,
)
