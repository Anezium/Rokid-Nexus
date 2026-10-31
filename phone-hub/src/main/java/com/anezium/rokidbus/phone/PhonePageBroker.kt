package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceAction
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceInvocation
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.QuickActionPolicyContract
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Gate
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Reason
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Verdict
import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONObject

/** One live plugin registration, named by the epoch the hub generated for it. */
data class PageRegistration(val epoch: String, val pluginId: String, val grantKey: PluginGrantKey)

/** What the broker needs from the hub around it; every call is made outside the broker's lock. */
interface PhonePageHost {
    fun renderedMapsSupported(): Boolean = false
    fun nowMs(): Long

    /** The approved catalog principal providing pages under [pluginId], with `surfaces` granted, or null. */
    fun pageProvider(pluginId: String): PhonePluginPrincipal?
    fun registrations(principal: PhonePluginPrincipal): List<PageRegistration>

    /** Live registrations of every opted-in provider, for page version announcements. */
    fun providerRegistrations(): List<PageRegistration>
    fun deliver(registration: PageRegistration, path: String, payload: JSONObject): Boolean
    fun sendToGlasses(path: String, payload: JSONObject): String?
    fun bind(principal: PhonePluginPrincipal): Boolean
    fun rebind(principal: PhonePluginPrincipal): Boolean
    fun unbind(principal: PhonePluginPrincipal)
    fun scheduleTick(atMs: Long?)
    fun log(message: String)

    /**
     * The quick-action families the user approved for [principal] and it still declares, read
     * at the moment of each check: an approval withdrawn a moment ago no longer counts.
     */
    fun quickFamilies(principal: PhonePluginPrincipal): Set<QuickActionFamily> = emptySet()
}

/**
 * The phone hub's side of session/page v1: routes the glasses' page traffic to the one
 * authenticated provider that owns each page, and only that provider's answers to work it
 * recorded back. A provider binding made here is separate from any immersion: it never opens,
 * adopts, closes or unbinds a plugin's surface, and it is held only while a page needs it.
 *
 * State is bounded to the frames of the one glasses session and each frame's pending action.
 * Logs carry ids, sizes and codes, never page content.
 */
class PhonePageBroker(
    private val host: PhonePageHost,
    private val localVersion: Int,
    /** The launcher bundle version this phone runs; 0 keeps every page exactly as in v1. */
    private val localLauncherVersion: Int = 0,
) {
    private data class FrameKey(val generation: Long, val frameIndex: Int)

    private class Frame(
        val key: FrameKey,
        val pageId: String,
        val owner: String,
        var requestId: String,
        var epoch: String? = null,
        var requestDeadline: Long? = null,
        var bindDeadline: Long? = null,
        var waitingRequest: JSONObject? = null,
        var shownRevision: Long? = null,
        var shownAtMs: Long? = null,
        var live: Boolean = false,
        /** The shown revision's actions with their kinds and types, so every mode is checked here. */
        var actions: Map<String, PageSurfaceAction> = emptyMap(),
        /** The shown revision's composer, when it is a conversation page that offers one. */
        var composer: ConversationComposer? = null,
        var routeMap: Boolean = false,
        /**
         * The glasses hold an accepted answer of the current request, so its advertised actions
         * may be forwarded. Separate from [shownRevision], which only ever rises.
         */
        var actionable: Boolean = false,
        /** The revision floor the glasses sent with the current request. */
        var floor: Long? = null,
        /** The last page the glasses were sent for this frame needed SPP, so it is lost when SPP is. */
        var dataPlane: Boolean = false,
        /** The highest revision accepted to be sent; later ones must exceed it even before it went out. */
        var reservedRevision: Long? = null,
        var leaseUntil: Long? = null,
        /** A page just forwarded is about to be displayed; its first visibility follows. */
        var displayDeadline: Long? = null,
        var invocationId: String? = null,
        var invocationDeadline: Long? = null,
        /** The family a pending quick invocation runs under; null for any other mode. */
        var invocationQuickFamily: QuickActionFamily? = null,
    ) {
        val pending: Boolean get() = requestDeadline != null
    }

    /**
     * What a page on its way to the glasses changes for its frame. It is applied only once the
     * send succeeded, and only to the same frame, request and epoch; until then the frame keeps
     * describing what the glasses hold.
     */
    private class Candidate(
        val revision: Long,
        val live: Boolean,
        val actions: Map<String, PageSurfaceAction>,
        val composer: ConversationComposer?,
        val dataPlane: Boolean,
        /** An answer to the current request, which makes its actions available. */
        val answersRequest: Boolean,
        val routeMap: Boolean = false,
    )

    private val lock = Any()
    private val mapDiagnostics = MapFeedbackDiagnostics(host::nowMs, host::log)
    private val frames = linkedMapOf<FrameKey, Frame>()

    /** Counts SPP losses, so a page whose send straddled one can be judged against it. */
    private var dataPlaneLosses = 0L
    private val bound = linkedMapOf<String, PhonePluginPrincipal>()
    private var generation = -1L
    private var remoteVersion = 0
    private var remoteLauncherVersion = 0
    private var announcedToProviders = 0
    private var announcedLauncherToProviders = 0

    val negotiatedVersion: Int
        get() = synchronized(lock) { PageSurfaceContract.negotiatedSessionVersion(localVersion, remoteVersion) }

    /** The launcher bundle version pages run with these glasses; 0 without pages or without the bundle. */
    val negotiatedLauncherVersion: Int
        get() = synchronized(lock) { launcherVersion() }

    /**
     * The glasses announced their capabilities; a restart or replacement connection resets all
     * page work. [launcherVersion] is the bundle version the glasses announced.
     */
    fun onGlassesCapabilities(version: Int, launcherVersion: Int = 0) = mutate {
        val previous = remoteVersion
        val previousLauncher = remoteLauncherVersion
        remoteVersion = version
        remoteLauncherVersion = launcherVersion
        if (previous != version || previousLauncher != launcherVersion) dropAll()
        announceIfChanged()
    }

    /** The glasses link went away entirely: support and every page with it. */
    fun onLinkLost() = mutate {
        remoteVersion = 0
        remoteLauncherVersion = 0
        dropAll()
        announceIfChanged()
    }

    /**
     * The user changed [pluginId]'s quick-action approvals. A pending quick invocation whose
     * family is no longer approved is rejected at once: its result will not reach the glasses,
     * which show it unconfirmed, and a queued delivery checks the policy again before it goes.
     */
    fun onQuickPolicyChanged(pluginId: String) = mutate {
        val approved = host.pageProvider(pluginId)?.let(host::quickFamilies).orEmpty()
        frames.values.filter { it.owner == pluginId }.forEach { frame ->
            val family = frame.invocationQuickFamily ?: return@forEach
            if (family !in approved) {
                host.log("page quick invocation rejected plugin=$pluginId reason=POLICY_WITHDRAWN")
                releaseInvocation(frame)
            }
        }
    }

    /**
     * The glasses transports changed. Losing both is [onLinkLost]. Losing SPP while CXR stays
     * ends only the frames whose last page needed SPP; the small ones go on over CXR.
     */
    fun onLinkState(previous: Int, state: Int) {
        val transport = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        when {
            state and transport == 0 -> onLinkLost()
            previous and LinkStateBits.SPP_DATA_UP != 0 && state and LinkStateBits.SPP_DATA_UP == 0 -> onDataPlaneLost()
        }
    }

    /**
     * SPP went away with CXR still up. A frame whose page needed SPP can no longer be revised or
     * acted on: it ends here and its provider hears `link_lost`; the glasses keep it inert by the
     * same rule. Nothing is replayed when SPP returns.
     */
    private fun onDataPlaneLost() = mutate {
        dataPlaneLosses += 1
        frames.values.filter { it.dataPlane }.toList().forEach { frame ->
            val registration = currentRegistration(frame)
            frames.remove(frame.key)
            registration?.let { closeProvider(frame, it) }
        }
    }

    /** Tells [registration] that [frame] is gone for transport reasons. */
    private fun closeProvider(frame: Frame, registration: PageRegistration) {
        val closed = JSONObject().put("pageId", frame.pageId).put("reason", CLOSE_LINK_LOST)
            .put("sessionGeneration", frame.key.generation).put("frameIndex", frame.key.frameIndex)
        val outgoing = stamped(closed, registration)
        later { host.deliver(registration, BusPaths.PAGE_CLOSED, outgoing) }
    }

    /** A registration of [principal] was approved: tell it the effective version, then hand over waiting work. */
    fun onRegistered(principal: PhonePluginPrincipal) = mutate {
        if (principal.descriptor.pageSessionVersion == PageSurfaceContract.VERSION) {
            val version = PageSurfaceContract.negotiatedSessionVersion(localVersion, remoteVersion)
            val launcher = launcherVersion()
            host.registrations(principal).forEach { registration -> later { deliverVersion(registration, version, launcher) } }
        }
        frames.values.filter { it.owner == principal.descriptor.id && it.waitingRequest != null }.forEach { frame ->
            deliverRequest(frame, principal)
        }
    }

    /** A registration went away, was replaced or lost its grant: its work ends, visibly. */
    fun onRegistrationRemoved(registration: PageRegistration) = mutate {
        frames.values.filter { it.owner == registration.pluginId && it.epoch == registration.epoch }
            .forEach { invalidate(it) }
    }

    /** The plugin's process died, its package went away, or its grant was revoked. */
    fun onProviderUnavailable(pluginId: String) = mutate {
        frames.values.filter { it.owner == pluginId }.forEach { invalidate(it) }
        bound.remove(pluginId)?.let { principal -> later { host.unbind(principal) } }
    }

    /** Whether the broker holds [pluginId]'s page-only binding because the glasses want its pages. */
    fun holdsProviderBinding(pluginId: String): Boolean = synchronized(lock) { bound.containsKey(pluginId) }

    fun onRenderedMap(payload: JSONObject) = mutate {
        val context = RenderedMapContract.context(payload)
        val frame = context?.let { frames[FrameKey(it.sessionGeneration, it.frameIndex)] }
        val visible = payload.optBoolean("visible")
        val registration = frame?.let(::currentRegistration)
        val now = host.nowMs()
        val sample = MapFeedbackDiagnostics.Sample.from(payload).copy(
            currentRevision = frame?.shownRevision ?: -1,
            ageMs = frame?.shownAtMs?.let { now - it } ?: -1,
            leaseRemainingMs = frame?.leaseUntil?.let { (it - now).coerceAtLeast(0) } ?: -1,
        )
        val reason = when {
            !host.renderedMapsSupported() -> Reason.CAPABILITY
            !RenderedMapContract.validEvent(payload) || context == null -> Reason.MALFORMED
            context.kind != RenderedMapContract.PAGE -> Reason.KIND_VIEW
            frame == null -> Reason.FRAME
            frame.pageId != context.id -> Reason.IDENTITY
            visible && frame.requestId != context.requestId -> Reason.REQUEST
            visible && frame.shownRevision != context.revision -> Reason.REVISION
            visible && (!frame.routeMap || !frame.actionable || frame.pending) -> Reason.NOT_ACTIONABLE
            visible && frame.leaseUntil?.let { now < it } != true -> Reason.PAGE_LEASE
            registration == null -> Reason.REGISTRATION
            else -> null
        }
        if (reason != null) {
            mapDiagnostics.record(Gate.PHONE_PAGE, Verdict.DROP, reason, sample)
            return@mutate
        }
        checkNotNull(frame)
        checkNotNull(context)
        checkNotNull(registration)
        mapDiagnostics.record(Gate.PHONE_PAGE, Verdict.PASS, Reason.CURRENT, sample)
        val outgoing = RenderedMapContract.delivery(payload, frame.owner)
        later {
            val deliveryReason = synchronized(lock) {
                when {
                    frames[frame.key] !== frame -> Reason.FRAME
                    currentRegistration(frame) != registration -> Reason.REGISTRATION
                    visible && frame.requestId != context.requestId -> Reason.REQUEST
                    visible && frame.shownRevision != context.revision -> Reason.REVISION
                    visible && frame.leaseUntil?.let { host.nowMs() < it } != true -> Reason.PAGE_LEASE
                    else -> null
                }
            }
            val delivered = deliveryReason == null && host.deliver(registration, RenderedMapContract.PATH, outgoing)
            mapDiagnostics.record(Gate.PHONE_PAGE_DELIVERY, if (delivered) Verdict.PASS else Verdict.DROP,
                deliveryReason ?: if (delivered) Reason.CURRENT else Reason.DELIVERY_FAILED,
                sample.copy(ageMs = host.nowMs() - now))
        }
    }

    /** Re-checks every owner against the catalog after packages or grants change. */
    fun onCatalogChanged() {
        val owners = synchronized(lock) { frames.values.map { it.owner }.toSet() }
        owners.filter { host.pageProvider(it) == null }.forEach(::onProviderUnavailable)
    }

    /** A page path from the glasses. True when it was a page path, handled or not. */
    fun onGlasses(path: String, payload: JSONObject): Boolean {
        when (path) {
            BusPaths.PAGE_REQUEST -> mutate { request(payload) }
            BusPaths.PAGE_ACTION -> mutate { action(payload) }
            BusPaths.PAGE_VISIBILITY -> mutate { visibility(payload) }
            BusPaths.PAGE_CLOSED -> mutate { closed(payload) }
            BusPaths.PAGE_RESPONSE, BusPaths.PAGE_RESULT -> host.log("page drop path=$path reason=WRONG_DIRECTION")
            else -> if (isPagePath(path)) host.log("page drop path=$path reason=UNKNOWN_PATH") else return false
        }
        return true
    }

    /**
     * A page path from a plugin. [sender] is the single live registration of the calling app,
     * or null when it has none or several. Returns the error code to report to the sender, or
     * null when the answer was accepted.
     */
    fun onProvider(path: String, payload: JSONObject, sender: PageRegistration?): String? {
        if (path != BusPaths.PAGE_RESPONSE && path != BusPaths.PAGE_RESULT) return ERROR_PAGE_ROUTE_DENIED
        if (sender == null) return ERROR_PAGE_ROUTE_DENIED
        if (host.pageProvider(sender.pluginId)?.grantKey() != sender.grantKey) return ERROR_PAGE_ROUTE_DENIED
        var error: String? = null
        mutate {
            error = when {
                negotiated() != PageSurfaceContract.VERSION -> ERROR_PAGE_NOT_REQUESTED
                path == BusPaths.PAGE_RESPONSE -> response(payload, sender)
                else -> result(payload, sender)
            }
        }
        return error
    }

    /** Runs the deadlines that are due: cold binds, requests, leases and actions. */
    fun onTick() = mutate {
        val now = host.nowMs()
        frames.values.toList().forEach { frame ->
            frame.bindDeadline?.takeIf { now >= it && frame.waitingRequest != null }?.let {
                host.log("page cold registration timeout plugin=${frame.owner}")
                frame.waitingRequest = null
                frame.bindDeadline = null
                frame.requestDeadline = null
                answerError(frame, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
            }
            frame.requestDeadline?.takeIf { now >= it }?.let {
                frame.requestDeadline = null
                frame.waitingRequest = null
                frame.bindDeadline = null
            }
            frame.leaseUntil?.takeIf { now >= it }?.let { frame.leaseUntil = null }
            frame.displayDeadline?.takeIf { now >= it }?.let { frame.displayDeadline = null }
            frame.invocationDeadline?.takeIf { now >= it }?.let { releaseInvocation(frame) }
            if (!frame.pending && frame.shownRevision == null) frames.remove(frame.key)
        }
    }

    private fun request(payload: JSONObject) {
        if (negotiated() != PageSurfaceContract.VERSION) return host.log("page drop path=request reason=UNSUPPORTED")
        val request = (PageSurfaceContract.validateRequest(payload) as? PageSurfaceValidationResult.Valid)?.value
            ?: return host.log("page drop path=request reason=INVALID")
        val correlation = request.correlation
        val remaining = (payload.opt(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS) as? Number)?.toLong()
            ?.coerceAtMost(PageSurfaceContract.PAGE_TIMEOUT_MS) ?: PageSurfaceContract.PAGE_TIMEOUT_MS
        if (remaining <= 0) return host.log("page drop path=request id=${correlation.requestId} reason=EXPIRED")
        val key = FrameKey(correlation.sessionGeneration, correlation.frameIndex)
        if (key.generation != generation) {
            // A new glasses session: the frames of the earlier one were all closed by the glasses.
            frames.values.toList().forEach { forget(it) }
            generation = key.generation
        }
        // Opening or refreshing frame k means every deeper frame is gone.
        frames.values.filter { it.key.frameIndex > key.frameIndex }.forEach { forget(it) }
        val owner = PageTemplateContract.namespaceOf(correlation.pageId)
        val existing = frames[key]?.takeIf { it.pageId == correlation.pageId }
        if (existing == null) frames[key]?.let { forget(it) }
        val frame = existing ?: Frame(key, correlation.pageId, owner.orEmpty(), correlation.requestId)
        frame.requestId = correlation.requestId
        releaseInvocation(frame)
        // A refresh suspends the old revision's actions until its own answer reaches the glasses.
        frame.actionable = false
        frame.floor = (payload.opt(PageSurfaceContract.FIELD_MINIMUM_REVISION) as? Number)?.toLong()
        val now = host.nowMs()
        frame.requestDeadline = now + remaining
        frames[key] = frame
        val principal = owner?.takeIf { it != PageTemplateContract.LOCAL_NAMESPACE }?.let(host::pageProvider)
        if (principal == null) {
            frames.remove(key)
            answerError(frame, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
            return
        }
        frame.waitingRequest = JSONObject(payload.toString())
        val registrations = host.registrations(principal)
        if (registrations.isEmpty()) {
            frame.bindDeadline = now + minOf(PageSurfaceContract.COLD_REGISTRATION_BUDGET_MS, remaining)
        }
        retainBinding(principal)
        if (registrations.isNotEmpty()) deliverRequest(frame, principal)
    }

    private fun deliverRequest(frame: Frame, principal: PhonePluginPrincipal) {
        val waiting = frame.waitingRequest ?: return
        val registrations = host.registrations(principal)
        if (registrations.isEmpty()) return
        frame.waitingRequest = null
        frame.bindDeadline = null
        val registration = registrations.singleOrNull()
        val deadline = frame.requestDeadline
        val now = host.nowMs()
        if (registration == null || deadline == null || now >= deadline) {
            // Two live clients for one plugin is ambiguous, and an answer to either could not be told apart.
            frame.requestDeadline = null
            if (registration == null) answerError(frame, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
            return
        }
        frame.epoch = registration.epoch
        val outgoing = stamped(waiting, registration)
            .put(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS, deadline - now)
        later {
            if (!host.deliver(registration, BusPaths.PAGE_REQUEST, outgoing)) {
                mutate {
                    frames[frame.key]?.takeIf { it === frame && it.requestId == outgoing.optString("requestId") }?.let {
                        it.requestDeadline = null
                        answerError(it, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
                    }
                }
            }
        }
    }

    private fun response(payload: JSONObject, sender: PageRegistration): String? {
        val correlation = correlationOf(payload) ?: return PageSurfaceContract.ERROR_INVALID_PAGE
        val frame = frames[FrameKey(correlation.sessionGeneration, correlation.frameIndex)]
            ?.takeIf { it.pageId == correlation.pageId && it.owner == sender.pluginId && it.epoch == sender.epoch }
            ?: return ERROR_PAGE_NOT_REQUESTED
        val now = host.nowMs()
        val stamped = stamped(payload, sender)
        val pending = frame.pending && frame.requestId == correlation.requestId
        if (pending) {
            if (now >= frame.requestDeadline!!) return ERROR_PAGE_NOT_REQUESTED
            val request = PageSurfaceRequest(correlation, "open")
            when (val validated = PageSurfaceContract.validateResponse(stamped, request)) {
                is PageSurfaceValidationResult.Invalid -> {
                    refuseAnswer(frame)
                    answerError(frame, validated.error)
                    return validated.error
                }
                is PageSurfaceValidationResult.Valid -> when (val value = validated.value) {
                    is PageSurfaceResponse.Error -> {
                        refuseAnswer(frame)
                        forward(BusPaths.PAGE_RESPONSE, stamped)
                        if (frame.shownRevision == null) frames.remove(frame.key)
                    }
                    is PageSurfaceResponse.Page -> {
                        // Below the floor the glasses would drop it as a rollback: it is no answer.
                        val rollback = frame.floor?.let { value.revision < it } == true
                        val body = acceptedBody(value, frame.owner)
                        if (rollback || body == null) {
                            refuseAnswer(frame)
                            answerError(frame, PageSurfaceContract.ERROR_INVALID_PAGE)
                            return PageSurfaceContract.ERROR_INVALID_PAGE
                        }
                        frame.requestDeadline = null
                        frame.displayDeadline = now + PageSurfaceContract.PAGE_TIMEOUT_MS
                        frame.reservedRevision = maxOf(frame.reservedRevision ?: value.revision, value.revision)
                        val outgoing = forGlasses(stamped, value, frame.owner)
                        val candidate = Candidate(
                            value.revision, value.live, value.actions.associateBy { it.id }, body.composer,
                            PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESPONSE, outgoing), answersRequest = true,
                            routeMap = body.routeMap,
                        )
                        forwardPage(BusPaths.PAGE_RESPONSE, outgoing, frame, candidate)
                    }
                }
            }
            return null
        }
        // Anything else must be a live revision of the frame's current, visible, leased request.
        val lease = frame.leaseUntil
        if (frame.requestId != correlation.requestId || !frame.live || lease == null || now >= lease) {
            return ERROR_PAGE_NOT_REQUESTED
        }
        val page = (PageSurfaceContract.validateResponse(stamped) as? PageSurfaceValidationResult.Valid)
            ?.value as? PageSurfaceResponse.Page ?: return PageSurfaceContract.ERROR_INVALID_PAGE
        if (page.correlation != PageCorrelation(frame.requestId, frame.key.generation, frame.key.frameIndex, frame.pageId)) {
            return ERROR_PAGE_NOT_REQUESTED
        }
        if (page.revision <= frame.highestRevision()) return PageSurfaceContract.ERROR_STALE_GENERATION
        val body = acceptedBody(page, frame.owner) ?: return PageSurfaceContract.ERROR_INVALID_PAGE
        frame.reservedRevision = page.revision
        val outgoing = forGlasses(stamped, page, frame.owner)
        val candidate = Candidate(
            page.revision, page.live, page.actions.associateBy { it.id }, body.composer,
            PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESPONSE, outgoing), answersRequest = false,
            routeMap = body.routeMap,
        )
        forwardPage(BusPaths.PAGE_RESPONSE, outgoing, frame, candidate)
        return null
    }

    private fun result(payload: JSONObject, sender: PageRegistration): String? {
        val invocationId = payload.opt("invocationId") as? String ?: return PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST
        val frame = frames.values.firstOrNull { it.invocationId == invocationId }
            ?.takeIf { it.owner == sender.pluginId && it.epoch == sender.epoch }
            ?: return ERROR_PAGE_NOT_REQUESTED
        val now = host.nowMs()
        if (now >= (frame.invocationDeadline ?: Long.MIN_VALUE)) {
            releaseInvocation(frame)
            return ERROR_PAGE_NOT_REQUESTED
        }
        val stamped = stamped(payload, sender)
        val result = when (val validated = PageSurfaceContract.validateBoundedResult(stamped)) {
            is PageSurfaceValidationResult.Invalid -> return validated.error
            is PageSurfaceValidationResult.Valid -> validated.value
        }
        val replacement = result.replacement
        // The result's code passes through as the provider sent it; glasses without the bundle get none.
        val outgoing = forGlasses(stamped, replacement, frame.owner).apply {
            if (launcherVersion() < 1) remove(FIELD_RESULT_CODE)
        }
        val candidate = replacement?.let {
            val sameFrame = it.correlation.sessionGeneration == frame.key.generation &&
                it.correlation.frameIndex == frame.key.frameIndex &&
                it.correlation.pageId == frame.pageId
            val body = acceptedBody(it, frame.owner)
            if (!sameFrame || it.revision <= frame.highestRevision() || body == null) {
                return PageSurfaceContract.ERROR_INVALID_PAGE
            }
            frame.reservedRevision = it.revision
            Candidate(
                it.revision, it.live, it.actions.associateBy { action -> action.id }, body.composer,
                PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESULT, outgoing), answersRequest = false,
                routeMap = body.routeMap,
            )
        }
        releaseInvocation(frame)
        if (candidate != null) forwardPage(BusPaths.PAGE_RESULT, outgoing, frame, candidate) else forward(BusPaths.PAGE_RESULT, outgoing)
        return null
    }

    private fun action(payload: JSONObject) {
        if (negotiated() != PageSurfaceContract.VERSION) return
        val action = (PageSurfaceContract.validateAction(payload) as? PageSurfaceValidationResult.Valid)?.value
            ?: return host.log("page drop path=action reason=INVALID")
        val frame = frames[FrameKey(action.sessionGeneration, action.frameIndex)]
            ?.takeIf { it.pageId == action.pageId } ?: return host.log("page drop path=action reason=NO_FRAME")
        val now = host.nowMs()
        val lease = frame.leaseUntil
        val declared = frame.actions[action.actionId]
        val accepted = frame.actionable && frame.shownRevision == action.revision && declared != null &&
            frame.invocationId == null && !frame.pending && lease != null && now < lease
        if (!accepted || declared == null) return host.log("page drop path=action id=${action.invocationId} reason=NOT_ACTIONABLE")
        val principal = host.pageProvider(frame.owner) ?: return invalidate(frame)
        val registration = host.registrations(principal).singleOrNull()?.takeIf { it.epoch == frame.epoch }
            ?: return invalidate(frame)
        modeRefusal(action, declared, frame, principal)?.let { reason ->
            return host.log("page drop path=action id=${action.invocationId} reason=$reason")
        }
        val quickFamily = if (action.mode == PageSurfaceContract.MODE_QUICK) declared.typed?.family else null
        frame.invocationId = action.invocationId
        frame.invocationDeadline = now + PageSurfaceContract.PAGE_TIMEOUT_MS
        frame.invocationQuickFamily = quickFamily
        // The provider gets the phone's own copy of the action's type, never one the glasses sent;
        // without the bundle it gets the v1 invocation exactly.
        val delivered = if (launcherVersion() >= 1) {
            action.copy(typed = declared.typed)
        } else {
            PageSurfaceInvocation(
                action.invocationId, action.sessionGeneration, action.frameIndex, action.pageId,
                action.revision, action.actionId,
            )
        }
        val outgoing = stamped(delivered.toPayload(), registration)
        later {
            // A quick action runs only while the user still approves its family, checked again here.
            val approved = quickFamily == null || quickFamily in host.quickFamilies(principal)
            // Queued is not delivered: a cover, a cancellation, the lease end or the invocation's
            // own eight seconds may have come first, with no tick in between.
            val current = synchronized(lock) {
                val lease = frame.leaseUntil
                val deadline = frame.invocationDeadline
                val now = host.nowMs()
                val live = frames[frame.key] === frame && frame.invocationId == action.invocationId &&
                    frame.epoch == registration.epoch && frame.actionable && lease != null && now < lease &&
                    deadline != null && now < deadline && approved
                // An invocation that never reached its provider is released; the glasses show it unconfirmed.
                if (!live && frame.invocationId == action.invocationId) releaseInvocation(frame)
                live
            }
            if (current) {
                // A fresh bind may acquire the first connection after an earlier refusal.
                if (host.rebind(principal)) {
                    mutate { bound[frame.owner] = principal }
                } else {
                    host.log("page action bind failed plugin=${frame.owner}")
                }
                host.deliver(registration, BusPaths.PAGE_ACTION, outgoing)
            } else {
                host.log("page drop path=action id=${action.invocationId} reason=CANCELLED")
            }
        }
    }

    private fun visibility(payload: JSONObject) {
        val visibility = (PageSurfaceContract.validateVisibility(payload) as? PageSurfaceValidationResult.Valid)?.value
            ?: return host.log("page drop path=visibility reason=INVALID")
        val frame = frameOf(payload)?.takeIf { it.pageId == visibility.pageId }
            ?: return host.log("page drop path=visibility reason=NO_FRAME")
        val lease = (payload.opt(PageSurfaceContract.FIELD_LEASE_DURATION_MS) as? Number)?.toLong()
            ?.takeIf { visibility.visible && it in 1..PageSurfaceContract.LEASE_MS }
        frame.leaseUntil = lease?.let { host.nowMs() + it }
        frame.displayDeadline = null
        if (lease != null) host.pageProvider(frame.owner)?.let(::retainBinding)
        if (!visibility.visible) releaseInvocation(frame)
        val registration = currentRegistration(frame) ?: return
        val outgoing = stamped(payload, registration)
        if (lease == null) outgoing.remove(PageSurfaceContract.FIELD_LEASE_DURATION_MS)
        later { host.deliver(registration, BusPaths.PAGE_VISIBILITY, outgoing) }
    }

    private fun closed(payload: JSONObject) {
        val closed = (PageSurfaceContract.validateClosed(payload) as? PageSurfaceValidationResult.Valid)?.value
            ?: return host.log("page drop path=closed reason=INVALID")
        // A close with no frame identity names no frame: nothing was allocated, nothing is sent.
        val frame = frameOf(payload)?.takeIf { it.pageId == closed.pageId }
            ?: return host.log("page drop path=closed reason=NO_FRAME")
        val registration = currentRegistration(frame)
        frames.remove(frame.key)
        if (registration != null) {
            val outgoing = stamped(payload, registration)
            later { host.deliver(registration, BusPaths.PAGE_CLOSED, outgoing) }
        }
    }

    /** The provider of [frame] is gone or replaced: its work ends and the glasses keep it inert. */
    private fun invalidate(frame: Frame) {
        host.log("page invalidated plugin=${frame.owner} frame=${frame.key.frameIndex}")
        frames.remove(frame.key)
        answerError(frame, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
    }

    /** The current request was refused or answered invalidly: nothing of the old revision stays actionable or live. */
    private fun refuseAnswer(frame: Frame) {
        frame.requestDeadline = null
        frame.actionable = false
        frame.live = false
        frame.actions = emptyMap()
        frame.composer = null
    }

    private fun releaseInvocation(frame: Frame) {
        frame.invocationId = null
        frame.invocationDeadline = null
        frame.invocationQuickFamily = null
    }

    /**
     * Null when [invocation] may reach [declared] the way it came, else why not. Without the
     * bundle only a confirmed invocation exists. With it: quick only for a plugin action whose
     * quick-eligible family the user approves now; compose only for the action the shown
     * conversation's replyable composer names, with text within its limit; and a send never as
     * a confirmed action. The glasses check the same, but they are never the only gate.
     */
    private fun modeRefusal(
        invocation: PageSurfaceInvocation,
        declared: PageSurfaceAction,
        frame: Frame,
        principal: PhonePluginPrincipal,
    ): String? {
        if (launcherVersion() < 1 && invocation.mode != PageSurfaceContract.MODE_CONFIRMED) return "MODE_NOT_NEGOTIATED"
        if (!PageSurfaceContract.modeAllowedFor(invocation.mode, declared.typed)) return "MODE"
        when (invocation.mode) {
            PageSurfaceContract.MODE_QUICK -> {
                if (declared.kind != PageSurfaceContract.KIND_PLUGIN) return "MODE"
                val family = declared.typed?.family ?: return "MODE"
                if (family !in host.quickFamilies(principal)) return "QUICK_NOT_APPROVED"
            }
            PageSurfaceContract.MODE_COMPOSE -> {
                val composer = frame.composer ?: return "NO_COMPOSER"
                if (composer.sendActionId != declared.id || !composer.replyable) return "COMPOSER"
                val text = invocation.text ?: return "TEXT"
                if (text.isBlank() || text.length > minOf(composer.maxChars, PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS)) {
                    return "TEXT"
                }
            }
        }
        return null
    }

    /** Forgets [frame] without telling anyone: the glasses already closed it. */
    private fun forget(frame: Frame) {
        frames.remove(frame.key)
    }

    private fun dropAll() {
        frames.clear()
        generation = -1L
    }

    private fun answerError(frame: Frame, code: String) {
        val payload = PageSurfaceContract.correlationPayload(
            PageCorrelation(frame.requestId, frame.key.generation, frame.key.frameIndex, frame.pageId),
        ).put("error", code).put(PageSurfaceContract.FIELD_PLUGIN_ID, frame.owner)
        later { host.sendToGlasses(BusPaths.PAGE_RESPONSE, payload) }
    }

    /** Sends what carries no page: an error answer, or a result without a replacement. */
    private fun forward(path: String, payload: JSONObject) {
        later {
            val error = host.sendToGlasses(path, payload) ?: return@later
            host.log("page send failed path=$path code=$error bytes=${PageSurfaceContract.serializedBytes(payload)}")
        }
    }

    /**
     * Sends a page to the glasses and applies [candidate] to [frame] only once it was sent, if the
     * frame, its request and its epoch are still the same. A page that could not be sent, or one
     * that needed SPP while SPP was lost between the start of its send and its commit, ends the
     * frame on both sides: the glasses get a small, honest answer and the provider a close.
     */
    private fun forwardPage(path: String, payload: JSONObject, frame: Frame, candidate: Candidate) {
        val requestId = frame.requestId
        val epoch = frame.epoch
        later {
            // Counted when the send starts, not when it was queued: a loss and a reconnect while
            // it waited leave a send over the new session that crosses nothing.
            val losses = synchronized(lock) { dataPlaneLosses }
            val error = host.sendToGlasses(path, payload)
            if (error != null) {
                host.log("page send failed path=$path code=$error bytes=${PageSurfaceContract.serializedBytes(payload)}")
            }
            mutate {
                if (frames[frame.key] === frame && frame.requestId == requestId && frame.epoch == epoch) {
                    // A page that needed SPP and crossed its loss was judged by the old one: the
                    // glasses may hold it, inert or not, so it counts as lost, never as shown.
                    val crossedLoss = candidate.dataPlane && dataPlaneLosses != losses
                    if (error == null && !crossedLoss) commit(frame, candidate) else failClosed(frame)
                }
            }
        }
    }

    private fun commit(frame: Frame, candidate: Candidate) {
        frame.shownRevision = maxOf(frame.shownRevision ?: candidate.revision, candidate.revision)
        frame.shownAtMs = host.nowMs()
        frame.live = candidate.live
        frame.actions = candidate.actions
        frame.composer = candidate.composer
        frame.routeMap = candidate.routeMap
        frame.dataPlane = candidate.dataPlane
        if (candidate.answersRequest) frame.actionable = true
    }

    private fun failClosed(frame: Frame) {
        val registration = currentRegistration(frame)
        frames.remove(frame.key)
        answerError(frame, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
        registration?.let { closeProvider(frame, it) }
    }

    private fun Frame.highestRevision(): Long = maxOf(reservedRevision ?: Long.MIN_VALUE, shownRevision ?: Long.MIN_VALUE)

    private fun stamped(payload: JSONObject, registration: PageRegistration): JSONObject =
        JSONObject(payload.toString())
            .put(PageSurfaceContract.FIELD_PLUGIN_ID, registration.pluginId)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, registration.epoch)

    /** What the broker keeps of a page's body beside its actions. */
    private class AcceptedBody(val composer: ConversationComposer?, val routeMap: Boolean = false)

    /**
     * Null when the body is invalid for its template. A template these glasses cannot draw (the
     * bundle's bodies without the bundle, `ink` always) passes as an envelope and the glasses show
     * that it needs a newer Nexus; with the bundle the conversation, media and route bodies are
     * decoded typed, so a composer or a control can only ever name an action of the right type.
     */
    private fun acceptedBody(page: PageSurfaceResponse.Page, owner: String): AcceptedBody? {
        val extended = launcherVersion() >= 1
        if (!PageTemplateContract.isSupported(page.template, extended)) return AcceptedBody(null)
        val decoded = PageTemplateContract.decode(page, owner, extended) as? PageSurfaceValidationResult.Valid
            ?: return null
        return AcceptedBody((decoded.value as? PageBody.Conversation)?.composer, decoded.value is PageBody.Route)
    }

    /**
     * The glasses' copy of a page response or result. A provider's own `hubQuick` never survives:
     * with the bundle the phone stamps it on the plugin actions whose quick-eligible family the
     * user approves now; without it every bundle field is removed, so the glasses get a v1 page.
     */
    private fun forGlasses(stamped: JSONObject, page: PageSurfaceResponse.Page?, owner: String): JSONObject {
        if (launcherVersion() < 1) return PageSurfaceContract.stripLauncherFields(stamped)
        for (body in listOfNotNull(stamped.optJSONObject("body"), stamped.optJSONObject("replacement")?.optJSONObject("body"))) {
            com.anezium.rokidbus.shared.LauncherArtwork.stripUnsupported(body, launcherVersion())
        }
        if (launcherVersion() < LauncherInteractiveContract.ARTWORK_VERSION) {
            for (body in listOfNotNull(stamped.optJSONObject("body"), stamped.optJSONObject("replacement")?.optJSONObject("body"))) {
                body.remove("artwork")
                body.remove("positionMs")
                body.remove("durationMs")
                body.remove("playbackSpeed")
                body.remove("advancing")
            }
        }
        val approved = host.pageProvider(owner)?.let(host::quickFamilies).orEmpty()
        val quickIds = page?.actions.orEmpty()
            .filter { it.kind == PageSurfaceContract.KIND_PLUGIN && QuickActionPolicyContract.isQuick(it.typed, approved) }
            .mapTo(linkedSetOf()) { it.id }
        return PageSurfaceContract.withHubQuick(stamped, quickIds)
    }

    /** The launcher bundle version pages run with these glasses: never without a page session. */
    private fun launcherVersion(): Int =
        if (negotiated() == PageSurfaceContract.VERSION) {
            LauncherInteractiveContract.negotiated(localLauncherVersion, remoteLauncherVersion)
        } else {
            0
        }

    private fun currentRegistration(frame: Frame): PageRegistration? {
        val principal = host.pageProvider(frame.owner) ?: return null
        return host.registrations(principal).singleOrNull()?.takeIf { it.epoch == frame.epoch }
    }

    private fun frameOf(payload: JSONObject): Frame? {
        val generation = (payload.opt("sessionGeneration") as? Number)?.toLong() ?: return null
        val frameIndex = (payload.opt("frameIndex") as? Number)?.toInt() ?: return null
        return frames[FrameKey(generation, frameIndex)]
    }

    private fun correlationOf(payload: JSONObject): PageCorrelation? = runCatching {
        PageCorrelation(
            payload.get("requestId") as String,
            (payload.get("sessionGeneration") as Number).toLong(),
            (payload.get("frameIndex") as Number).toInt(),
            payload.get("pageId") as String,
        )
    }.getOrNull()

    private fun deliverVersion(registration: PageRegistration, version: Int, launcher: Int) {
        host.deliver(
            registration,
            BusPaths.HUB_CAPABILITIES,
            JSONObject()
                .put(PageSurfaceContract.FIELD_PLUGIN_ID, registration.pluginId)
                .put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, version)
                .put(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION, launcher),
        )
    }

    /** Tells opted-in providers when the effective page or launcher bundle version changed. */
    private fun announceIfChanged() {
        val version = negotiated()
        val launcher = launcherVersion()
        if (version == announcedToProviders && launcher == announcedLauncherToProviders) return
        announcedToProviders = version
        announcedLauncherToProviders = launcher
        later { host.providerRegistrations().forEach { deliverVersion(it, version, launcher) } }
    }

    /** Holds or drops the page-only binding of each owner by its current demand. */
    private fun reconcileBindings() {
        val now = host.nowMs()
        val demand = frames.values.filter { frame ->
            frame.pending || listOfNotNull(frame.leaseUntil, frame.displayDeadline, frame.invocationDeadline).any { now < it }
        }.map { it.owner }.toSet()
        bound.keys.filter { it !in demand }.toList().forEach { owner ->
            bound.remove(owner)?.let { principal -> later { host.unbind(principal) } }
        }
    }

    private fun retainBinding(principal: PhonePluginPrincipal) {
        if (bound.containsKey(principal.descriptor.id)) return
        bound[principal.descriptor.id] = principal
        later {
            if (!host.bind(principal)) {
                host.log("page bind failed plugin=${principal.descriptor.id}")
                mutate {
                    bound.remove(principal.descriptor.id)
                    frames.values.filter { it.owner == principal.descriptor.id && it.waitingRequest != null }.forEach {
                        it.waitingRequest = null
                        it.bindDeadline = null
                        it.requestDeadline = null
                        answerError(it, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
                    }
                }
            }
        }
    }

    private fun negotiated(): Int = PageSurfaceContract.negotiatedSessionVersion(localVersion, remoteVersion)

    // Everything the broker asks of the hub runs after its state settled and outside its lock.
    private val outbox = ArrayDeque<() -> Unit>()
    private var flushing = false

    private fun later(task: () -> Unit) {
        outbox.addLast(task)
    }

    private inline fun mutate(block: () -> Unit) {
        synchronized(lock) {
            block()
            reconcileBindings()
        }
        flush()
    }

    private fun flush() {
        while (true) {
            val task = synchronized(lock) {
                if (flushing) return
                outbox.removeFirstOrNull()?.also { flushing = true }
            } ?: break
            try {
                task()
            } finally {
                synchronized(lock) { flushing = false }
            }
        }
        val next = synchronized(lock) { nextDeadline() }
        host.scheduleTick(next)
    }

    private fun nextDeadline(): Long? = frames.values.flatMap {
        listOfNotNull(it.bindDeadline, it.requestDeadline, it.leaseUntil, it.displayDeadline, it.invocationDeadline)
    }.minOrNull()

    companion object {
        const val ERROR_PAGE_ROUTE_DENIED = "PAGE_ROUTE_DENIED"
        const val ERROR_PAGE_NOT_REQUESTED = "PAGE_NOT_REQUESTED"
        private const val CLOSE_LINK_LOST = "link_lost"
        private const val FIELD_RESULT_CODE = "code"

        fun isPagePath(path: String): Boolean = PathRules.isPagePath(path)

        /** A page answer is refused on its raw size, before it is ever parsed. */
        fun rawAnswerTooLarge(path: String, bytes: Int): Boolean =
            isPagePath(PathRules.normalizeAbsolute(path) ?: path) && bytes > PageSurfaceContract.MAX_PAGE_BYTES
    }
}
