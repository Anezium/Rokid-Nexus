package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.plugin.PluginCloseTypes
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONObject
import java.util.UUID

interface ExternalPluginRuntime {
    fun bind(principal: PhonePluginPrincipal): Boolean
    fun isRegistered(principal: PhonePluginPrincipal): Boolean
    fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean
    fun hideOwnedSurfaces(pluginId: String)
    fun unbind(principal: PhonePluginPrincipal)
}

interface ExternalPluginScheduler {
    fun schedule(key: String, delayMs: Long, action: () -> Unit)
    fun cancel(key: String)
}

data class ExternalPluginOpenFollowUp(
    val path: String,
    val type: String,
    val extra: () -> JSONObject = { JSONObject() },
)

data class ExternalPluginOpenRequest(
    val type: String = PluginOpenTypes.OPEN,
    val followUp: ExternalPluginOpenFollowUp? = null,
)

class ExternalPluginController(
    private val runtime: ExternalPluginRuntime,
    private val scheduler: ExternalPluginScheduler,
    private val logger: (String) -> Unit = {},
    private val onRegisteredPrincipal: (PhonePluginPrincipal) -> Unit = {},
    private val onForegroundChanged: () -> Unit = {},
    private val onBackgroundChanged: (String?) -> Unit = {},
    private val journal: PluginBusJournal? = null,
) {
    private var pending: PhonePluginPrincipal? = null
    private var active: PhonePluginPrincipal? = null
        set(value) {
            val changed = field?.grantKey() != value?.grantKey()
            field = value
            if (changed) onForegroundChanged()
        }
    private var background: PhonePluginPrincipal? = null
        set(value) {
            val changed = field?.grantKey() != value?.grantKey()
            field = value
            if (changed) onBackgroundChanged(value?.descriptor?.id)
        }
    private var openGeneration = 0L
    private var automaticRebindAttempted = false
    private var openRequest = ExternalPluginOpenRequest()

    fun open(
        principal: PhonePluginPrincipal,
        request: ExternalPluginOpenRequest = ExternalPluginOpenRequest(),
    ): Boolean {
        active?.takeIf { it.grantKey() != principal.grantKey() }?.let { closePrincipal(it, "switch") }
        pending?.takeIf { it.grantKey() != principal.grantKey() }?.let { previous ->
            cancelWatchdogs(previous)
            runtime.unbind(previous)
        }
        cancelWatchdogs(principal)
        openGeneration += 1
        automaticRebindAttempted = false
        val backgroundPrincipal = background?.takeIf { it.grantKey() == principal.grantKey() }
        if (backgroundPrincipal != null) {
            background = null
            if (!runtime.isRegistered(backgroundPrincipal)) {
                runtime.unbind(backgroundPrincipal)
                openRequest = request
                return beginColdOpen(principal, openGeneration)
            }
            openRequest = if (request.type == PluginOpenTypes.OPEN) {
                request.copy(type = PluginOpenTypes.RESUME)
            } else {
                request
            }
            active = backgroundPrincipal
            if (!deliverOpen(backgroundPrincipal, openRequest, openGeneration)) {
                closePrincipal(backgroundPrincipal, "resume_failed")
                return false
            }
            logger("external plugin resumed plugin=${principal.descriptor.id}")
            return true
        }
        openRequest = request
        return beginColdOpen(principal, openGeneration)
    }

    private fun beginColdOpen(principal: PhonePluginPrincipal, generation: Long): Boolean {
        pending = principal
        if (!runtime.bind(principal)) {
            pending = null
            record(
                principal,
                PluginBusJournal.Category.LIFECYCLE,
                PluginBusJournal.Direction.HUB_TO_PLUGIN,
                BusPaths.PLUGIN_OPEN,
                PluginBusJournal.Verdict.REJECTED,
                "BIND_FAILED",
            )
            return false
        }
        val timeoutKey = registrationTimeoutKey(principal)
        scheduler.cancel(timeoutKey)
        scheduler.schedule(timeoutKey, REGISTRATION_TIMEOUT_MS) {
            if (generation == openGeneration && pending?.grantKey() == principal.grantKey()) {
                pending = null
                record(
                    principal,
                    PluginBusJournal.Category.REGISTRATION,
                    PluginBusJournal.Direction.PLUGIN_TO_HUB,
                    BusPaths.PLUGIN_REGISTRATION,
                    PluginBusJournal.Verdict.REJECTED,
                    "REGISTRATION_TIMEOUT",
                )
                if (automaticRebindAttempted) {
                    giveUpOpen(principal, generation)
                } else {
                    runtime.hideOwnedSurfaces(principal.descriptor.id)
                    runtime.unbind(principal)
                    logger("external plugin registration timed out plugin=${principal.descriptor.id}")
                }
            }
        }
        if (runtime.isRegistered(principal)) onRegistered(principal)
        return true
    }

    fun onRegistered(principal: PhonePluginPrincipal) {
        onPluginActivity(principal.descriptor.id)
        val pendingPrincipal = pending?.takeIf { it.grantKey() == principal.grantKey() }
        if (pendingPrincipal == null) {
            onRegisteredPrincipal(principal)
            return
        }
        scheduler.cancel(registrationTimeoutKey(pendingPrincipal))
        pending = null
        active = pendingPrincipal
        if (!deliverOpen(pendingPrincipal, openRequest, openGeneration)) {
            if (automaticRebindAttempted) {
                giveUpOpen(pendingPrincipal, openGeneration)
            } else {
                closePrincipal(pendingPrincipal, "open_failed")
            }
        } else {
            onRegisteredPrincipal(pendingPrincipal)
        }
    }

    fun input(
        ownerPluginId: String,
        localSurfaceId: String,
        keyCode: Int,
        action: Int,
    ): Boolean {
        val principal = active?.takeIf { it.descriptor.id == ownerPluginId } ?: return false
        return deliver(
            principal,
            BusPaths.PLUGIN_INPUT,
            "input",
            JSONObject()
                .put("localSurfaceId", localSurfaceId)
                .put("keyCode", keyCode)
                .put("action", action),
        )
    }

    /**
     * The wearer submitted or cancelled the editable field on [ownerPluginId]'s
     * foreground card. Stamps `pluginId` the same way notice/ink delivery does,
     * so [NexusPluginClient]'s existing per-plugin gate on that field is enough
     * on the receiving side — no separate decode path needed.
     */
    fun textCommitted(
        ownerPluginId: String,
        surfaceId: String,
        text: String,
        cancelled: Boolean,
    ): Boolean {
        val principal = active?.takeIf { it.descriptor.id == ownerPluginId } ?: return false
        return deliver(
            principal,
            BusPaths.SURFACE_TEXT_COMMITTED,
            "text-committed",
            EditableSurfaceContract.committedPayload(surfaceId, text, cancelled)
                .put("pluginId", ownerPluginId),
        )
    }

    fun closeActive(reason: String = "close") {
        active?.let { closePrincipal(it, reason) }
        pending?.let { principal ->
            cancelWatchdogs(principal)
            runtime.unbind(principal)
        }
        pending = null
    }

    fun closeAll(reason: String = "close") {
        closeActive(reason)
        background?.let { closePrincipal(it, reason) }
    }

    fun activeId(): String? = active?.descriptor?.id

    fun activeDisplayName(): String? = active?.descriptor?.displayName

    fun backgroundId(): String? = background?.descriptor?.id

    fun backgroundDisplayName(): String? = background?.descriptor?.displayName

    /**
     * A plugin that shows a surface while the HUD is idle becomes the foreground
     * plugin. This remains a containment path for third-party plugins that violate
     * the hub-initiated lifecycle: PLUGIN_OPEN lets inputs through the SDK gate and
     * keeps the open/close lifecycle balanced.
     */
    fun adopt(principal: PhonePluginPrincipal): Boolean {
        if (active?.grantKey() == principal.grantKey()) return true
        if (background?.grantKey() == principal.grantKey()) return false
        if (active != null) return false
        if (!runtime.isRegistered(principal)) return false
        cancelWatchdogs(principal)
        openGeneration += 1
        automaticRebindAttempted = false
        openRequest = ExternalPluginOpenRequest(type = PluginOpenTypes.ADOPTED)
        active = principal
        if (!deliverOpen(principal, openRequest, openGeneration)) {
            closePrincipal(principal, "adopt_failed")
            return false
        }
        logger("external plugin adopted as foreground plugin=${principal.descriptor.id}")
        return true
    }

    /** Any valid surface traffic, plus registration, acknowledges the latest PLUGIN_OPEN. */
    fun onPluginActivity(pluginId: String) {
        active?.takeIf { it.descriptor.id == pluginId }?.let { principal ->
            scheduler.cancel(openAckTimeoutKey(principal))
        }
    }

    /**
     * A plugin that hides its own last surface (BACK on the HUD) is normally closed. A detach
     * request backed by its active audio lease gets a balanced background close instead, so the
     * next launcher open is delivered as a resume rather than swallowed by SDK lifecycle state.
     */
    fun onPluginSelfHid(
        pluginId: String,
        detach: Boolean = false,
        hasActiveAudioLease: Boolean = false,
    ) {
        val principal = active?.takeIf { it.descriptor.id == pluginId } ?: return
        cancelWatchdogs(principal)
        active = null
        if (detach && hasActiveAudioLease) {
            background?.takeIf { it.grantKey() != principal.grantKey() }?.let { previous ->
                closePrincipal(previous, PluginCloseTypes.CLOSED)
            }
            if (deliver(principal, BusPaths.PLUGIN_CLOSE, PluginCloseTypes.BACKGROUND)) {
                background = principal
                record(
                    principal,
                    PluginBusJournal.Category.LIFECYCLE,
                    PluginBusJournal.Direction.PLUGIN_TO_HUB,
                    BusPaths.PLUGIN_CLOSE,
                    PluginBusJournal.Verdict.OK,
                    "BACKGROUND",
                )
                logger("external plugin backgrounded plugin=$pluginId")
                return
            }
            runtime.unbind(principal)
            logger("external plugin background delivery failed plugin=$pluginId")
            return
        }
        deliver(principal, BusPaths.PLUGIN_CLOSE, "self_hidden")
        runtime.unbind(principal)
        record(
            principal,
            PluginBusJournal.Category.LIFECYCLE,
            PluginBusJournal.Direction.PLUGIN_TO_HUB,
            BusPaths.PLUGIN_CLOSE,
            PluginBusJournal.Verdict.OK,
            "SELF_CLOSE",
        )
        logger("external plugin self-closed plugin=$pluginId")
    }

    fun onAudioLeaseEnded(pluginId: String, reason: String) {
        val principal = background?.takeIf { it.descriptor.id == pluginId } ?: return
        closePrincipal(principal, PluginCloseTypes.CLOSED)
        logger("external plugin background finalized plugin=$pluginId reason=$reason")
    }

    fun onRevoked(key: PluginGrantKey) {
        pending?.takeIf { it.grantKey() == key }?.let { principal ->
            cancelWatchdogs(principal)
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
            pending = null
        }
        active?.takeIf { it.grantKey() == key }?.let { closePrincipal(it, "revoked") }
        background?.takeIf { it.grantKey() == key }?.let { closePrincipal(it, "revoked") }
    }

    fun onBinderDied(key: PluginGrantKey) {
        pending?.takeIf { it.grantKey() == key }?.let { principal ->
            cancelWatchdogs(principal)
            pending = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        active?.takeIf { it.grantKey() == key }?.let { principal ->
            cancelWatchdogs(principal)
            active = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        background?.takeIf { it.grantKey() == key }?.let { principal ->
            background = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
    }

    fun onPackageUnavailable(packageName: String) {
        pending?.takeIf { it.packageName == packageName }?.let { principal ->
            cancelWatchdogs(principal)
            pending = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        active?.takeIf { it.packageName == packageName }?.let { principal ->
            closePrincipal(principal, "package_unavailable")
        }
        background?.takeIf { it.packageName == packageName }?.let { principal ->
            closePrincipal(principal, "package_unavailable")
        }
    }

    private fun closePrincipal(principal: PhonePluginPrincipal, reason: String) {
        cancelWatchdogs(principal)
        if (active?.grantKey() == principal.grantKey()) active = null
        if (pending?.grantKey() == principal.grantKey()) pending = null
        if (background?.grantKey() == principal.grantKey()) background = null
        deliver(principal, BusPaths.PLUGIN_CLOSE, reason)
        runtime.hideOwnedSurfaces(principal.descriptor.id)
        runtime.unbind(principal)
    }

    private fun deliverOpen(
        principal: PhonePluginPrincipal,
        request: ExternalPluginOpenRequest,
        generation: Long,
    ): Boolean {
        if (!deliver(principal, BusPaths.PLUGIN_OPEN, request.type)) return false
        request.followUp?.let { followUp ->
            if (!deliver(principal, followUp.path, followUp.type, followUp.extra())) return false
        }
        val timeoutKey = openAckTimeoutKey(principal)
        scheduler.cancel(timeoutKey)
        scheduler.schedule(timeoutKey, OPEN_ACK_TIMEOUT_MS) {
            if (generation == openGeneration && active?.grantKey() == principal.grantKey()) {
                onOpenAckTimedOut(principal, generation)
            }
        }
        return true
    }

    private fun onOpenAckTimedOut(principal: PhonePluginPrincipal, generation: Long) {
        record(
            principal,
            PluginBusJournal.Category.LIFECYCLE,
            PluginBusJournal.Direction.HUB_TO_PLUGIN,
            BusPaths.PLUGIN_OPEN,
            PluginBusJournal.Verdict.REJECTED,
            "OPEN_ACK_TIMEOUT",
        )
        if (automaticRebindAttempted) {
            giveUpOpen(principal, generation)
            return
        }
        automaticRebindAttempted = true
        record(
            principal,
            PluginBusJournal.Category.LIFECYCLE,
            PluginBusJournal.Direction.HUB_TO_PLUGIN,
            BusPaths.PLUGIN_OPEN,
            PluginBusJournal.Verdict.OK,
            "REBIND_ATTEMPT",
        )
        logger("external plugin open unacknowledged plugin=${principal.descriptor.id}; rebinding")
        active = null
        runtime.unbind(principal)
        if (!beginColdOpen(principal, generation)) giveUpOpen(principal, generation)
    }

    private fun giveUpOpen(principal: PhonePluginPrincipal, generation: Long) {
        if (generation != openGeneration) return
        cancelWatchdogs(principal)
        pending = null
        active = null
        runtime.hideOwnedSurfaces(principal.descriptor.id)
        runtime.unbind(principal)
        record(
            principal,
            PluginBusJournal.Category.LIFECYCLE,
            PluginBusJournal.Direction.HUB_TO_PLUGIN,
            BusPaths.PLUGIN_OPEN,
            PluginBusJournal.Verdict.REJECTED,
            "OPEN_FAILED",
        )
        logger("external plugin open failed plugin=${principal.descriptor.id}")
    }

    private fun record(
        principal: PhonePluginPrincipal,
        category: PluginBusJournal.Category,
        direction: PluginBusJournal.Direction,
        path: String,
        verdict: PluginBusJournal.Verdict,
        reason: String,
    ) {
        val target = journal ?: return
        if (!target.enabled.get()) return
        try {
            target.record(
                pluginId = principal.descriptor.id,
                category = category,
                direction = direction,
                path = path,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect plugin lifecycle.
        }
    }

    private fun deliver(
        principal: PhonePluginPrincipal,
        path: String,
        type: String,
        extra: JSONObject = JSONObject(),
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("version", 1)
            .put("type", type)
            .put("id", id)
            .put("pluginId", principal.descriptor.id)
        extra.keys().forEach { key -> payload.put(key, extra.get(key)) }
        return runtime.deliver(
            principal,
            path,
            id,
            payload,
        )
    }

    private fun cancelWatchdogs(principal: PhonePluginPrincipal) {
        scheduler.cancel(registrationTimeoutKey(principal))
        scheduler.cancel(openAckTimeoutKey(principal))
    }

    private fun registrationTimeoutKey(principal: PhonePluginPrincipal): String =
        "registration:${principal.packageName}:${principal.descriptor.id}"

    private fun openAckTimeoutKey(principal: PhonePluginPrincipal): String =
        "open-ack:${principal.packageName}:${principal.descriptor.id}"

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 5_000L
        const val OPEN_ACK_TIMEOUT_MS = 4_000L
    }
}
