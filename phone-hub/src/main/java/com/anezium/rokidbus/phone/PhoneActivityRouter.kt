package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject

/**
 * Activity-tier routing: local start/update/end, TTL expiry, revoke, and reconnect resend.
 *
 * Canonical state stays in [PhoneActivityState]. The wire lock, transport, and
 * timers are owned here so [BusHubService] can keep the Binder and the glasses link.
 */
internal class PhoneActivityRouter(
    private val state: PhoneActivityState,
    private val sink: PhoneHudRouteSink,
    private val nowMs: () -> Long,
    private val postExpiry: (delayMs: Long) -> Unit,
    private val cancelExpiry: () -> Unit,
) {
    /** Serializes canonical mutation with its wire send, including reconnect batches. */
    private val wireLock = Any()

    fun matches(path: String): Boolean =
        path == BusPaths.ACTIVITY_START ||
            path == BusPaths.ACTIVITY_UPDATE ||
            path == BusPaths.ACTIVITY_END

    fun ownerPluginIds(): Set<String> = state.ownerPluginIds()

    fun handleLocal(envelope: BusEnvelope, sender: PhoneHudRouteSender): Unit = synchronized(wireLock) {
        val pluginId = sender.pluginId
        if (pluginId == null || envelope.binary != null) {
            reject(
                envelope,
                sender,
                ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
            )
            return
        }
        if (sink.capabilities() and BusCapabilityBits.ACTIVITY_SURFACE == 0) {
            reject(
                envelope,
                sender,
                ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE,
            )
            return
        }

        when (envelope.path) {
            BusPaths.ACTIVITY_START ->
                when (val result = state.start(pluginId, envelope.payload)) {
                    is PhoneActivityStartResult.Rejected ->
                        reject(envelope, sender, result.code)
                    is PhoneActivityStartResult.Accepted -> {
                        result.replaced?.let { replaced ->
                            sink.log("activity replaced owner=${replaced.ownerPluginId} by=$pluginId")
                            sendEndIfReachable(replaced, "replaced")
                            deliverClosed(replaced.ownerPluginId, replaced.reason)
                        }
                        scheduleExpiry()
                        forward(envelope, result.payload, sender)
                    }
                }
            BusPaths.ACTIVITY_UPDATE ->
                when (val result = state.update(pluginId, envelope.payload)) {
                    PhoneActivityUpdateResult.Ignored -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                        "ACTIVITY_UPDATE_IGNORED_NO_SESSION",
                    )
                    is PhoneActivityUpdateResult.Rejected ->
                        reject(envelope, sender, result.code)
                    is PhoneActivityUpdateResult.Accepted -> {
                        scheduleExpiry()
                        forward(envelope, result.payload, sender)
                    }
                }
            BusPaths.ACTIVITY_END ->
                when (val result = state.end(pluginId)) {
                    PhoneActivityClearResult.Ignored -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                        "ACTIVITY_END_IGNORED_NO_SESSION",
                    )
                    is PhoneActivityClearResult.Cleared -> {
                        scheduleExpiry()
                        val forwarded = envelope.copy(payload = result.payload)
                        sink.recordLocalRoute(
                            forwarded,
                            sender.uid,
                            pluginId,
                            PluginBusJournal.Verdict.OK,
                        )
                        if (sink.pinLinkUp()) {
                            sink.sendRemote(forwarded)?.let {
                                sink.deliverError(sender.replyBinder, envelope.id, it)
                            }
                        } else {
                            sink.log("activity end held owner=$pluginId reason=link_down")
                        }
                        deliverClosed(result.ownerPluginId, result.reason)
                    }
                }
        }
    }

    fun handleGlassesAction(envelope: BusEnvelope): Unit = synchronized(wireLock) {
        val activityId = envelope.payload.optString("activityId")
        val actionId = envelope.payload.optString("id")
        val owner = state.ownerForAction(activityId, actionId)
        if (owner == null) {
            sink.log(
                "activity action ignored id=${activityId.take(80)} " +
                    "actionPresent=${actionId.isNotBlank()} reason=not_current",
            )
            return
        }
        val payload = JSONObject(envelope.payload.toString()).put("pluginId", owner)
        if (!sink.deliverLocal(envelope.copy(payload = payload))) {
            sink.log("activity action undelivered owner=$owner; no live registration")
        }
    }

    fun handleGlassesClosed(envelope: BusEnvelope): Unit = synchronized(wireLock) {
        val activityId = envelope.payload.optString("activityId")
        val reason = ActivityCloseReason.fromWireValue(envelope.payload.optString("reason"))
        if (reason == null) {
            sink.log("activity close ignored id=${activityId.take(80)} reason=invalid")
            return
        }
        when (val result = state.closedByGlasses(activityId, reason)) {
            PhoneActivityClearResult.Ignored ->
                sink.log(
                    "activity close ignored id=${activityId.take(80)} " +
                        "reason=${reason.wireValue}",
                )
            is PhoneActivityClearResult.Cleared -> {
                scheduleExpiry()
                sink.log("activity closed owner=${result.ownerPluginId} reason=${reason.wireValue}")
                deliverClosed(result.ownerPluginId, result.reason)
            }
        }
    }

    fun expireCanonical(): Unit = synchronized(wireLock) {
        state.expireIfDue().forEach { expired ->
            sink.log("activity expired owner=${expired.ownerPluginId}")
            sendEndIfReachable(expired, "max_duration")
            deliverClosed(expired.ownerPluginId, expired.reason)
        }
        scheduleExpiry()
    }

    fun clearForDisconnectedOwner(pluginId: String, reason: String): Unit = synchronized(wireLock) {
        val result = state.ownerDisconnected(pluginId)
        if (result !is PhoneActivityClearResult.Cleared) return
        scheduleExpiry()
        sink.log("activity cleared owner=$pluginId reason=$reason")
        sendEndIfReachable(result, "disconnect")
    }

    fun clearForRevokedOwner(pluginId: String, reason: String): Unit = synchronized(wireLock) {
        val result = state.ownerLostAccess(pluginId)
        if (result !is PhoneActivityClearResult.Cleared) return
        scheduleExpiry()
        sink.log("activity cleared owner=$pluginId reason=$reason")
        sendEndIfReachable(result, "access_lost")
    }

    fun clearAllForHubStop(): Unit = synchronized(wireLock) {
        val cleared = state.disconnectAll()
        if (cleared.isEmpty()) return@synchronized
        cancelExpiry()
        cleared.forEach { result ->
            deliverClosed(result.ownerPluginId, result.reason)
        }
        if (sink.pinLinkUp()) {
            val sentinel = state.emptySlotAssertPayload()
            sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_END, payload = sentinel))
        }
        sink.log("activity tier cleared reason=hub_stopped count=${cleared.size}")
    }

    fun resendIfAvailable(): Unit = synchronized(wireLock) {
        if (sink.capabilities() and BusCapabilityBits.ACTIVITY_SURFACE == 0) return
        expireCanonical()

        // A phone-hub restart may leave several owner IDs rendered on the glasses.
        // Clear the whole tier first, then mint newer sequences for every canonical resend.
        val emptyAssert = state.emptySlotAssertPayload()
        val clearError = sink.sendRemote(
            BusEnvelope(path = BusPaths.ACTIVITY_END, payload = emptyAssert),
        )
        if (clearError != null) {
            sink.log("activity empty assert failed code=$clearError")
            return
        }

        state.payloadsForResend().forEach { payload ->
            val error = sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_START, payload = payload))
            if (error == null) {
                sink.log(
                    "activity resent owner=${payload.optString("ownerPluginId")} " +
                        "seq=${payload.optLong("seq")}",
                )
            } else {
                sink.log(
                    "activity resend failed owner=${payload.optString("ownerPluginId")} code=$error",
                )
            }
        }
    }

    private fun forward(
        envelope: BusEnvelope,
        payload: JSONObject,
        sender: PhoneHudRouteSender,
    ) {
        val forwarded = envelope.copy(payload = payload)
        sink.recordLocalRoute(forwarded, sender.uid, sender.pluginId, PluginBusJournal.Verdict.OK)
        if (sink.pinLinkUp()) {
            sink.sendRemote(forwarded)?.let { sink.deliverError(sender.replyBinder, envelope.id, it) }
        } else {
            sink.log(
                "activity held owner=${payload.optString("ownerPluginId")} " +
                    "path=${envelope.path} reason=link_down",
            )
        }
    }

    private fun reject(
        envelope: BusEnvelope,
        sender: PhoneHudRouteSender,
        code: String,
    ) {
        sink.recordLocalRoute(
            envelope,
            sender.uid,
            sender.pluginId,
            PluginBusJournal.Verdict.REJECTED,
            code,
        )
        sink.deliverError(sender.replyBinder, envelope.id, code)
    }

    private fun deliverClosed(pluginId: String, reason: ActivityCloseReason) {
        val payload = ActivitySurfaceContract
            .closedPayload("$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}", reason)
            .put("pluginId", pluginId)
        if (!sink.deliverLocal(BusEnvelope(path = BusPaths.ACTIVITY_CLOSED, payload = payload))) {
            sink.log("activity close undelivered owner=$pluginId reason=${reason.wireValue}")
        }
    }

    private fun sendEndIfReachable(
        result: PhoneActivityClearResult.Cleared,
        cause: String,
    ) {
        if (!sink.pinLinkUp()) {
            sink.log("activity end held owner=${result.ownerPluginId} cause=$cause reason=link_down")
            return
        }
        val error = sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_END, payload = result.payload))
        if (error != null) {
            sink.log("activity end failed owner=${result.ownerPluginId} cause=$cause code=$error")
        }
    }

    private fun scheduleExpiry() {
        cancelExpiry()
        val deadline = state.nextExpiryDeadlineMs() ?: return
        postExpiry((deadline - nowMs()).coerceAtLeast(0L))
    }
}
