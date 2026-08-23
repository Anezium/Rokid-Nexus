package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject

/**
 * Notice-slot routing: local show/update/hide, glasses input/action/close, and revoke.
 *
 * Canonical state stays in [PhoneNoticeState]. Transport stays on [PhoneHudRouteSink]
 * so [BusHubService] can keep the Binder and the glasses link.
 */
internal class PhoneNoticeRouter(
    private val state: PhoneNoticeState,
    private val sink: PhoneHudRouteSink,
) {
    fun matches(path: String): Boolean =
        path == BusPaths.NOTICE_SHOW ||
            path == BusPaths.NOTICE_UPDATE ||
            path == BusPaths.NOTICE_HIDE

    fun handleLocal(envelope: BusEnvelope, sender: PhoneHudRouteSender) {
        val pluginId = sender.pluginId
        if (pluginId == null) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_INVALID_NOTICE)
            return
        }
        if (sink.capabilities() and BusCapabilityBits.NOTICE_SURFACE == 0) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE)
            return
        }
        // Unlike a pin, nothing here is worth holding for glasses that cannot be
        // reached: a banner delivered after the moment has passed is worse than
        // no banner. The plugin is told and decides for itself.
        if (!sink.pinLinkUp()) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE)
            return
        }

        when (envelope.path) {
            BusPaths.NOTICE_SHOW ->
                when (
                    val result = state.show(
                        pluginId,
                        DisplayArbiter.applyNoticeWake(sender.displayPolicy, envelope.payload),
                        envelope.binary,
                    )
                ) {
                    is PhoneNoticeShowResult.Rejected ->
                        reject(envelope, sender, result.code)
                    is PhoneNoticeShowResult.Accepted -> {
                        result.replacedOwnerPluginId?.let { previous ->
                            sink.log("notice replaced owner=$previous by=$pluginId")
                            deliverClosed(previous, NoticeCloseReason.REPLACED)
                        }
                        forward(envelope, result.notice.payload, sender)
                    }
                }
            BusPaths.NOTICE_UPDATE ->
                when (val result = state.update(pluginId, envelope.payload)) {
                    PhoneNoticeUpdateResult.Ignored -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                        "NOTICE_UPDATE_IGNORED",
                    )
                    is PhoneNoticeUpdateResult.Rejected ->
                        reject(envelope, sender, result.code)
                    is PhoneNoticeUpdateResult.Accepted -> {
                        forward(envelope, result.notice.payload, sender)
                    }
                }
            BusPaths.NOTICE_HIDE ->
                when (val result = state.hide(pluginId)) {
                    PhoneNoticeClearResult.Ignored -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                        "NOTICE_HIDE_IGNORED_NOT_OWNER",
                    )
                    is PhoneNoticeClearResult.Cleared -> {
                        sink.recordLocalRoute(
                            envelope,
                            sender.uid,
                            pluginId,
                            PluginBusJournal.Verdict.OK,
                        )
                        sink.sendRemote(BusEnvelope(path = BusPaths.NOTICE_HIDE, payload = result.payload))
                        deliverClosed(result.ownerPluginId, result.reason)
                    }
                }
        }
    }

    fun handleGlassesInput(envelope: BusEnvelope) {
        val noticeId = envelope.payload.optString("noticeId")
        val owner = when (val result = state.takeInputAnswer(noticeId)) {
            // Silent drops here cost an evening once: the glasses claimed the key
            // and sent it, and nothing downstream said why it went nowhere.
            PhoneNoticeActionResult.NotCurrent -> {
                sink.log("notice input ignored id=${noticeId.take(80)} reason=not_current")
                return
            }
            PhoneNoticeActionResult.AlreadyAnswered -> {
                sink.log("notice input ignored id=${noticeId.take(80)} reason=already_answered")
                return
            }
            is PhoneNoticeActionResult.Owner -> result.ownerPluginId
        }
        val payload = JSONObject(envelope.payload.toString()).put("pluginId", owner)
        if (!sink.deliverLocal(envelope.copy(payload = payload))) {
            sink.log("notice input undelivered owner=$owner; no live registration")
        }
    }

    fun handleGlassesAction(envelope: BusEnvelope) {
        val noticeId = envelope.payload.optString("noticeId")
        val actionId = envelope.payload.optString("id")
        val owner = when (val result = state.takeAnswer(noticeId, actionId)) {
            PhoneNoticeActionResult.NotCurrent -> {
                sink.log(
                    "notice action ignored id=${noticeId.take(80)} " +
                        "actionPresent=${actionId.isNotBlank()} reason=not_current",
                )
                return
            }
            // Distinct from not_current on purpose: this one means the wearer
            // did pick a real action on the real notice, and it is the second
            // time. Two temple taps 188 ms apart is what that looks like.
            PhoneNoticeActionResult.AlreadyAnswered -> {
                sink.log(
                    "notice action ignored id=${noticeId.take(80)} " +
                        "actionPresent=${actionId.isNotBlank()} reason=already_answered",
                )
                return
            }
            is PhoneNoticeActionResult.Owner -> result.ownerPluginId
        }
        val payload = JSONObject(envelope.payload.toString()).put("pluginId", owner)
        if (!sink.deliverLocal(envelope.copy(payload = payload))) {
            sink.log("notice action undelivered owner=$owner; no live registration")
        }
    }

    fun handleGlassesClosed(envelope: BusEnvelope) {
        val surfaceId = envelope.payload.optString("noticeId")
        val reason = NoticeCloseReason.fromWireValue(envelope.payload.optString("reason"))
            ?: NoticeCloseReason.USER
        when (val result = state.closedByGlasses(surfaceId, reason)) {
            PhoneNoticeClearResult.Ignored ->
                sink.log("notice close ignored id=$surfaceId reason=${reason.wireValue}")
            is PhoneNoticeClearResult.Cleared -> {
                sink.log("notice closed owner=${result.ownerPluginId} reason=${reason.wireValue}")
                deliverClosed(result.ownerPluginId, result.reason)
            }
        }
    }

    /**
     * The owner lost the right to hold a notice. Nothing is delivered back: the
     * plugin is normally being uninstalled or revoked, and there is no one left
     * to tell.
     */
    fun clearForRevokedOwner(pluginId: String, reason: String) {
        val result = state.ownerLostAccess(pluginId)
        if (result !is PhoneNoticeClearResult.Cleared) return
        sink.log("notice cleared owner=$pluginId reason=$reason")
        if (sink.pinLinkUp()) {
            sink.sendRemote(BusEnvelope(path = BusPaths.NOTICE_HIDE, payload = result.payload))
        }
    }

    private fun forward(
        envelope: BusEnvelope,
        payload: JSONObject,
        sender: PhoneHudRouteSender,
    ) {
        val forwarded = envelope.copy(payload = payload)
        sink.recordLocalRoute(forwarded, sender.uid, sender.pluginId, PluginBusJournal.Verdict.OK)
        sink.sendRemote(forwarded)?.let { sink.deliverError(sender.replyBinder, envelope.id, it) }
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

    /**
     * Tells the owner its notice is gone, and why. `pluginId` is what scopes the
     * delivery: notice traffic is owner-scoped, so no other plugin subscribed to
     * the path learns that this one had a banner dismissed.
     */
    private fun deliverClosed(pluginId: String, reason: NoticeCloseReason) {
        val payload = NoticeSurfaceContract
            .closedPayload("$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}", reason)
            .put("pluginId", pluginId)
        sink.deliverLocal(BusEnvelope(path = BusPaths.NOTICE_CLOSED, payload = payload))
    }
}
