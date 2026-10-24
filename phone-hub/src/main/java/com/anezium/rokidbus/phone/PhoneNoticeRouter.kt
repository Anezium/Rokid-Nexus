package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeWithdrawal
import org.json.JSONObject

/**
 * Canonical notice routing; Android delivery and transport stay in the hub.
 *
 * [launcherVersion] is the launcher bundle version negotiated with the glasses, or null before
 * they announce. With the bundle the glasses keep a notification history: the router forwards
 * an owner's withdrawals to it and tells it to forget an owner that lost its grant. Both outlive
 * a link loss, because the glasses keep their history across one; a withdrawal or purge that
 * cannot be sent now waits for the next announce.
 */
internal class PhoneNoticeRouter(
    private val state: PhoneNoticeState,
    private val sink: PhoneHudRouteSink,
    private val launcherVersion: () -> Int? = { 0 },
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit = { _, _ -> },
) {
    private val historyLock = Any()

    /** Owners whose notices reached glasses that keep a history, so a purge knows whom to name. */
    private val historyOwners = linkedSetOf<String>()
    private val pendingPurges = linkedSetOf<String>()
    private val pendingWithdrawals = linkedSetOf<NoticeWithdrawal>()

    /** Owners whose budget-deferred forget already has a drain scheduled. */
    private val scheduledDrains = mutableSetOf<String>()

    /**
     * Where [historyOwners] and [pendingPurges] outlive this process: the glasses keep their
     * history across a phone hub restart, so a purge owed must survive it too. Owner ids only.
     */
    private var ledger: PhoneNoticeOwnerLedger? = null

    /**
     * Takes back what a previous process of the hub knew it owed the glasses. The caller then
     * reconciles every restored owner against the current grants, as after a package change.
     */
    fun restore(ledger: PhoneNoticeOwnerLedger) {
        synchronized(historyLock) {
            val saved = ledger.load()
            historyOwners += saved.historyOwners
            pendingPurges += saved.pendingPurges
            this.ledger = ledger
            persist()
        }
    }

    /** Called with [historyLock] held after either set changed. */
    private fun persist() {
        ledger?.save(PhoneNoticeOwners(historyOwners.toSet(), pendingPurges.toSet()))
    }

    fun matches(path: String): Boolean =
        path == BusPaths.NOTICE_SHOW ||
            path == BusPaths.NOTICE_UPDATE ||
            path == BusPaths.NOTICE_HIDE ||
            path == BusPaths.NOTICE_WITHDRAW ||
            path == BusPaths.NOTICE_FORGET

    private fun bundleNegotiated(): Boolean = LauncherInteractiveContract.isActive(launcherVersion() ?: 0)

    /** Owners the glasses may still hold history entries for. */
    fun historyOwnerIds(): Set<String> = synchronized(historyLock) { historyOwners.toSet() }

    fun invalidLocalReason(envelope: BusEnvelope): String? {
        if (isValidLocalNoticeEnvelope(envelope)) return null
        if (envelope.path == BusPaths.NOTICE_UPDATE && envelope.payload.has("wakeDisplay")) {
            sink.log("notice update rejected field=wakeDisplay reason=show_only")
        }
        if (envelope.path == BusPaths.NOTICE_UPDATE && envelope.payload.has("backdrop")) {
            sink.log("notice update rejected field=backdrop reason=show_only")
        }
        return NoticeSurfaceContract.ERROR_INVALID_NOTICE
    }

    fun handleLocal(
        envelope: BusEnvelope,
        sender: PhoneHudRouteSender,
    ) {
        val pluginId = sender.pluginId
        if (pluginId == null) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_INVALID_NOTICE)
            return
        }
        // Not gated on the link like a show: the history it corrects outlives a link loss.
        if (envelope.path == BusPaths.NOTICE_WITHDRAW) {
            handleWithdraw(envelope, sender, pluginId)
            return
        }
        if (envelope.path == BusPaths.NOTICE_FORGET) {
            sink.recordLocalRoute(envelope, sender.uid, pluginId, PluginBusJournal.Verdict.OK)
            // The same durable, coalesced obligation as a revoked owner's: one per owner, kept
            // across a link loss and a restart of this hub. Past the notice budget it is still
            // kept, and goes once the budget's window has passed, so nothing floods the link and
            // nothing waits for a reconnection.
            if (state.admitWithdraw(pluginId)) purgeOwner(pluginId, "owner_forget") else queuePurge(pluginId)
            // Only now may the plugin stop keeping the request: this hub owns it on disk.
            sink.deliverLocal(
                BusEnvelope(
                    path = BusPaths.NOTICE_FORGET_ACCEPTED,
                    payload = JSONObject().put("pluginId", pluginId).put("forId", envelope.id),
                ),
                sender.replyBinder,
            )
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
            BusPaths.NOTICE_SHOW -> {
                val launcherBundle = bundleNegotiated()
                when (
                    val result = state.show(
                        pluginId,
                        envelope.payload,
                        envelope.binary,
                        launcherBundle,
                    )
                ) {
                    is PhoneNoticeShowResult.Rejected ->
                        reject(envelope, sender, result.code)
                    is PhoneNoticeShowResult.Accepted -> {
                        // A forget the owner asked for earlier goes first, or it would land
                        // after this newer event and erase it.
                        if (launcherBundle && synchronized(historyLock) { pluginId in pendingPurges }) {
                            purgeOwner(pluginId, "owner_forget_before_show")
                        }
                        if (launcherBundle) {
                            val eventKey = result.notice.payload.optString(NoticeSurfaceContract.FIELD_EVENT_KEY)
                            synchronized(historyLock) {
                                if (historyOwners.add(pluginId)) persist()
                                // A withdrawal queued while the link was down is older than this
                                // event; flushed after it, it would mark the new notice removed.
                                if (eventKey.isNotEmpty()) {
                                    pendingWithdrawals.removeAll { it.ownerPluginId == pluginId && it.eventKey == eventKey }
                                }
                            }
                        }
                        result.replacedOwnerPluginId?.let { previous ->
                            sink.log("notice replaced owner=$previous by=$pluginId")
                            result.replacedNotice?.let { replaced ->
                                deliverClosed(
                                    previous, NoticeCloseReason.REPLACED,
                                    replaced.identity, replaced.clientToken,
                                )
                            }
                        }
                        forward(envelope, result.notice.payload, sender)
                    }
                }
            }
            BusPaths.NOTICE_UPDATE ->
                when (val result = state.update(pluginId, envelope.payload)) {
                    PhoneNoticeUpdateResult.Ignored -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        sender.pluginId,
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
                        sender.pluginId,
                        PluginBusJournal.Verdict.OK,
                        "NOTICE_HIDE_IGNORED_NOT_OWNER",
                    )
                    is PhoneNoticeClearResult.Cleared -> {
                        sink.recordLocalRoute(envelope, sender.uid, sender.pluginId, PluginBusJournal.Verdict.OK)
                        sink.sendRemote(BusEnvelope(path = BusPaths.NOTICE_HIDE, payload = result.payload))
                        deliverClosed(result.ownerPluginId, result.reason, result.identity, result.clientToken)
                    }
                }
        }
    }

    /**
     * `/notice/withdraw`: the event is no longer on the phone. The owner is the authenticated
     * sender, never a field of the payload, and the glasses get only that owner and the key.
     * It is not proof of read or delivery; the plugin hears it was accepted, not delivered.
     */
    private fun handleWithdraw(
        envelope: BusEnvelope,
        sender: PhoneHudRouteSender,
        pluginId: String,
    ) {
        if (!bundleNegotiated()) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE)
            return
        }
        val eventKey = NoticeSurfaceContract.parseWithdraw(envelope.payload)
        if (eventKey == null) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_INVALID_NOTICE)
            return
        }
        if (!state.admitWithdraw(pluginId)) {
            reject(envelope, sender, NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED)
            return
        }
        val withdrawal = NoticeWithdrawal(pluginId, eventKey)
        val outgoing = BusEnvelope(
            path = BusPaths.NOTICE_WITHDRAW,
            id = envelope.id,
            payload = NoticeSurfaceContract.glassesWithdrawPayload(withdrawal),
        )
        sink.recordLocalRoute(outgoing, sender.uid, pluginId, PluginBusJournal.Verdict.OK)
        if (!sink.pinLinkUp() || sink.sendRemote(outgoing) != null) {
            synchronized(historyLock) { queueWithdrawal(withdrawal) }
            sink.log("notice withdraw queued owner=$pluginId")
        }
    }

    private fun queueWithdrawal(withdrawal: NoticeWithdrawal) {
        pendingWithdrawals.remove(withdrawal)
        pendingWithdrawals.add(withdrawal)
        while (pendingWithdrawals.size > NoticeSurfaceContract.MAX_HISTORY_ENTRIES) {
            pendingWithdrawals.remove(pendingWithdrawals.first())
        }
    }

    /**
     * The owner lost its grant, was disabled, uninstalled, or came back under another signer or
     * id: the glasses forget every history entry it left. Sent even for an owner this process
     * never saw notify, since the glasses' history may predate a phone hub restart.
     */
    fun purgeOwner(pluginId: String, reason: String) {
        val version = launcherVersion()
        if (version != null && !LauncherInteractiveContract.isActive(version)) return
        val purge = BusEnvelope(
            path = BusPaths.CORE_NOTICE_PURGE,
            payload = NoticeSurfaceContract.purgePayload(pluginId),
        )
        synchronized(historyLock) {
            historyOwners.remove(pluginId)
            pendingWithdrawals.removeAll { it.ownerPluginId == pluginId }
            if (version != null && sink.pinLinkUp() && sink.sendRemote(purge) == null) {
                pendingPurges.remove(pluginId)
                persist()
                sink.log("notice history purged owner=$pluginId reason=$reason")
                return
            }
            pendingPurges.add(pluginId)
            persist()
        }
        sink.log("notice history purge queued owner=$pluginId reason=$reason")
    }

    private fun queuePurge(pluginId: String) {
        val drain = synchronized(historyLock) {
            pendingPurges.add(pluginId)
            persist()
            scheduledDrains.add(pluginId)
        }
        sink.log("notice history purge queued owner=$pluginId reason=owner_forget_budget")
        if (drain) schedule(PURGE_DRAIN_DELAY_MS) { drainPurge(pluginId) }
    }

    /** One coalesced purge per owner and window, whatever else the owner sent meanwhile. */
    private fun drainPurge(pluginId: String) {
        val owed = synchronized(historyLock) {
            scheduledDrains.remove(pluginId)
            pluginId in pendingPurges
        }
        if (owed) purgeOwner(pluginId, "owner_forget")
    }

    /**
     * After a grant or package change (revoke, disable, uninstall, a new signer or id), purges
     * every owner the glasses may hold history for that is no longer an approved surfaces owner,
     * and [changedOwner] on the same rule even if this process never saw it notify.
     */
    fun purgeOwnersWithoutAccess(isSurfaceOwner: (String) -> Boolean, reason: String, changedOwner: String? = null) {
        (historyOwnerIds() + listOfNotNull(changedOwner))
            .filterNot(isSurfaceOwner)
            .forEach { purgeOwner(it, reason) }
    }

    /**
     * The glasses announced. With the bundle they get what waited for them, purges first so a
     * withdrawal never names an owner the glasses are about to forget; without it nothing is
     * owed, since glasses without the bundle keep no history.
     */
    fun onGlassesAnnounced() {
        synchronized(historyLock) {
            if (!bundleNegotiated()) {
                pendingPurges.clear()
                pendingWithdrawals.clear()
                persist()
                return
            }
            for (owner in pendingPurges.toList()) {
                val purge = BusEnvelope(
                    path = BusPaths.CORE_NOTICE_PURGE,
                    payload = NoticeSurfaceContract.purgePayload(owner),
                )
                if (sink.sendRemote(purge) != null) return
                pendingPurges.remove(owner)
                persist()
                sink.log("notice history purged owner=$owner reason=announce")
            }
            for (withdrawal in pendingWithdrawals.toList()) {
                val outgoing = BusEnvelope(
                    path = BusPaths.NOTICE_WITHDRAW,
                    payload = NoticeSurfaceContract.glassesWithdrawPayload(withdrawal),
                )
                if (sink.sendRemote(outgoing) != null) return
                pendingWithdrawals.remove(withdrawal)
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
        sink.sendRemote(forwarded)?.let { sink.deliverError(sender.replyBinder, envelope.id, it) }
    }

    private fun reject(
        envelope: BusEnvelope,
        sender: PhoneHudRouteSender,
        code: String,
    ) {
        sink.recordLocalRoute(envelope, sender.uid, sender.pluginId, PluginBusJournal.Verdict.REJECTED, code)
        sink.deliverError(sender.replyBinder, envelope.id, code)
    }

    /**
     * Tells the owner its notice is gone, and why. `pluginId` is what scopes the
     * delivery: notice traffic is owner-scoped, so no other plugin subscribed to
     * the path learns that this one had a banner dismissed.
     */
    private fun deliverClosed(
        pluginId: String,
        reason: NoticeCloseReason,
        identity: NoticeInteractionIdentity,
        clientToken: String?,
    ) {
        val payload = NoticeSurfaceContract
            .closedPayload("$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}", reason, identity)
            .put("pluginId", pluginId)
            .apply { clientToken?.let { put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, it) } }
        sink.deliverLocal(BusEnvelope(path = BusPaths.NOTICE_CLOSED, payload = payload))
    }

    /**
     * The wearer answered a banner that offered no row. Only the plugin that
     * raised it hears about it, only while it still owns the slot, and only
     * once: input goes through the same one-answer gate an action does, because
     * the duplicate tap that made that rule does not care which kind of reply
     * the band was offering.
     */
    fun handleGlassesInput(envelope: BusEnvelope) {
        val noticeId = envelope.payload.optString("noticeId")
        val identity = NoticeSurfaceContract.interactionIdentity(envelope.payload)
        val owner = when (val result = state.takeInputAnswer(noticeId, identity)) {
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
            is PhoneNoticeActionResult.Owner -> result
        }
        if (!sink.deliverLocal(noticeOwnerReply(envelope, owner))) {
            sink.log("notice input undelivered owner=${owner.ownerPluginId}; no live registration")
        }
    }

    /**
     * The wearer picked one of the band's actions. Routed exactly like a notice
     * input -- to the plugin that owns the canonical slot, and to nobody else --
     * with one extra check the input path cannot make: the action must be one
     * this notice actually offers, so a stale pick from a band that has since
     * been replaced does not reach the new owner.
     */
    fun handleGlassesAction(envelope: BusEnvelope) {
        val noticeId = envelope.payload.optString("noticeId")
        val actionId = envelope.payload.optString("id")
        val identity = NoticeSurfaceContract.interactionIdentity(envelope.payload)
        val owner = when (val result = state.takeAnswer(noticeId, actionId, identity)) {
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
            is PhoneNoticeActionResult.Owner -> result
        }
        if (!sink.deliverLocal(noticeOwnerReply(envelope, owner))) {
            sink.log("notice action undelivered owner=${owner.ownerPluginId}; no live registration")
        }
    }

    fun handleGlassesClosed(envelope: BusEnvelope) {
        val surfaceId = envelope.payload.optString("noticeId")
        val reason = NoticeCloseReason.fromWireValue(envelope.payload.optString("reason"))
        if (reason == null) {
            sink.log("notice close ignored id=${surfaceId.take(80)} reason=invalid")
            return
        }
        val identity = NoticeSurfaceContract.interactionIdentity(envelope.payload)
        when (val result = state.closedByGlasses(surfaceId, reason, identity)) {
            PhoneNoticeClearResult.Ignored ->
                sink.log("notice close ignored id=$surfaceId reason=${reason.wireValue}")
            is PhoneNoticeClearResult.Cleared -> {
                sink.log("notice closed owner=${result.ownerPluginId} reason=${reason.wireValue}")
                deliverClosed(result.ownerPluginId, result.reason, result.identity, result.clientToken)
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

    private companion object {
        /** The notice budget's window: a deferred forget fits in the next one. */
        const val PURGE_DRAIN_DELAY_MS = 1_000L
    }
}
