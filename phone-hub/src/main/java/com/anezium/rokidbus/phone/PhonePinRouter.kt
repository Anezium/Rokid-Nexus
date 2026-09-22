package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceValidationResult

/**
 * Pin-slot routing: local show/hide, TTL expiry, revoke, and reconnect resend.
 *
 * Canonical state stays in [PhonePinState]. Transport and timers are injected
 * so [BusHubService] can keep the Binder and the glasses link.
 */
internal class PhonePinRouter(
    private val state: PhonePinState,
    private val sink: PhoneHudRouteSink,
    private val nowMs: () -> Long,
    private val postExpiry: (delayMs: Long) -> Unit,
    private val cancelExpiry: () -> Unit,
) {
    fun matches(path: String): Boolean =
        path == BusPaths.PIN_SHOW || path == BusPaths.PIN_HIDE

    fun ownerPluginId(): String? = state.ownerPluginId()

    fun invalidLocalReason(envelope: BusEnvelope): String? {
        val invalid = envelope.binary != null ||
            envelope.payload.optString("surfaceId") != PinSurfaceContract.LOCAL_SURFACE_ID ||
            (
                envelope.path == BusPaths.PIN_SHOW &&
                    PinSurfaceContract.validateShow(envelope.payload) !is PinSurfaceValidationResult.Valid
                )
        return if (invalid) PinSurfaceContract.ERROR_INVALID_PIN else null
    }

    fun handleLocal(envelope: BusEnvelope, sender: PhoneHudRouteSender) {
        val pluginId = sender.pluginId
        if (pluginId == null || envelope.binary != null) {
            sink.recordLocalRoute(
                envelope,
                sender.uid,
                pluginId,
                PluginBusJournal.Verdict.REJECTED,
                PinSurfaceContract.ERROR_INVALID_PIN,
            )
            sink.deliverError(sender.replyBinder, envelope.id, PinSurfaceContract.ERROR_INVALID_PIN)
            return
        }
        if (sink.capabilities() and BusCapabilityBits.PIN_SURFACE == 0) {
            sink.recordLocalRoute(
                envelope,
                sender.uid,
                pluginId,
                PluginBusJournal.Verdict.REJECTED,
                PinSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE,
            )
            sink.deliverError(
                sender.replyBinder,
                envelope.id,
                PinSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE,
            )
            return
        }

        when (envelope.path) {
            BusPaths.PIN_SHOW -> when (val result = state.show(pluginId, envelope.payload)) {
                is PhonePinShowResult.Rejected -> {
                    sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.REJECTED,
                        result.code,
                    )
                    sink.deliverError(sender.replyBinder, envelope.id, result.code)
                }
                is PhonePinShowResult.Accepted -> {
                    scheduleExpiry()
                    result.replacedOwnerPluginId?.let { previous ->
                        sink.log("pin replaced owner=$previous by=$pluginId")
                    }
                    val forwarded = envelope.copy(payload = result.pin.payload)
                    sink.recordLocalRoute(
                        forwarded,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                    )
                    // Glasses asleep: hold it rather than fail the plugin. It is already
                    // canonical state, and the announce resend delivers it on link-up. The
                    // TTL still runs from now, so a stale pin never surfaces late.
                    if (sink.pinLinkUp()) {
                        sink.sendRemote(forwarded)?.let {
                            sink.deliverError(sender.replyBinder, envelope.id, it)
                        }
                    } else {
                        sink.log("pin held owner=$pluginId reason=link_down")
                    }
                }
            }
            BusPaths.PIN_HIDE -> {
                val expectedId = "$pluginId:${PinSurfaceContract.LOCAL_SURFACE_ID}"
                if (envelope.payload.optString("surfaceId") != expectedId ||
                    envelope.payload.optString("localSurfaceId") != PinSurfaceContract.LOCAL_SURFACE_ID
                ) {
                    sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.REJECTED,
                        PinSurfaceContract.ERROR_INVALID_PIN,
                    )
                    sink.deliverError(
                        sender.replyBinder,
                        envelope.id,
                        PinSurfaceContract.ERROR_INVALID_PIN,
                    )
                    return
                }
                when (val result = state.hide(pluginId)) {
                    PhonePinClearResult.Ignored -> {
                        sink.recordLocalRoute(
                            envelope,
                            sender.uid,
                            pluginId,
                            PluginBusJournal.Verdict.OK,
                            "PIN_HIDE_IGNORED_NOT_OWNER",
                        )
                        sink.log("pin hide ignored plugin=$pluginId reason=not_owner")
                    }
                    is PhonePinClearResult.Cleared -> {
                        scheduleExpiry()
                        val forwarded = envelope.copy(payload = result.payload)
                        sink.recordLocalRoute(
                            forwarded,
                            sender.uid,
                            pluginId,
                            PluginBusJournal.Verdict.OK,
                        )
                        // Same as show: the slot is already empty phone-side, and the
                        // empty-slot assert on reconnect stops the glasses keeping a ghost.
                        if (sink.pinLinkUp()) {
                            sink.sendRemote(forwarded)?.let {
                                sink.deliverError(sender.replyBinder, envelope.id, it)
                            }
                        } else {
                            sink.log("pin hide held owner=$pluginId reason=link_down")
                        }
                    }
                }
            }
        }
    }

    fun scheduleExpiry() {
        cancelExpiry()
        val deadline = state.expiryDeadlineMs() ?: return
        postExpiry((deadline - nowMs()).coerceAtLeast(0L))
    }

    fun expireCanonical() {
        when (val result = state.expireIfDue()) {
            PhonePinClearResult.Ignored -> scheduleExpiry()
            is PhonePinClearResult.Cleared -> {
                cancelExpiry()
                sink.log("pin expired owner=${result.payload.optString("ownerPluginId")}")
                if (sink.pinLinkUp()) {
                    sink.sendRemote(BusEnvelope(path = BusPaths.PIN_HIDE, payload = result.payload))
                }
            }
        }
    }

    fun clearForRevokedOwner(pluginId: String, reason: String) {
        val result = state.ownerLostAccess(pluginId)
        if (result !is PhonePinClearResult.Cleared) return
        cancelExpiry()
        sink.log("pin cleared owner=$pluginId reason=$reason")
        if (sink.pinLinkUp()) {
            sink.sendRemote(BusEnvelope(path = BusPaths.PIN_HIDE, payload = result.payload))
        }
    }

    fun resendIfAvailable() {
        if (sink.capabilities() and BusCapabilityBits.PIN_SURFACE == 0) return
        expireCanonical()
        val payload = state.payloadForResend()
        if (payload == null) {
            // Assert the empty slot too: a pin cleared while the links were down
            // never produced a delivered hide, and the glasses would keep it forever.
            state.emptySlotHidePayload()?.let { hide ->
                sink.sendRemote(BusEnvelope(path = BusPaths.PIN_HIDE, payload = hide))
            }
            return
        }
        val error = sink.sendRemote(BusEnvelope(path = BusPaths.PIN_SHOW, payload = payload))
        if (error == null) {
            sink.log(
                "pin resent owner=${payload.optString("ownerPluginId")} seq=${payload.optLong("seq")}",
            )
        } else {
            sink.log("pin resend failed code=$error")
        }
    }
}
