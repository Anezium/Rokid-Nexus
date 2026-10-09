package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONObject

/** One live plugin registration, named by the epoch the hub generated for it. */
data class PageRegistration(val epoch: String, val pluginId: String, val grantKey: PluginGrantKey)

/** What the broker needs from the hub around it; every call is made outside the broker's lock. */
interface PhonePageHost {
    fun nowMs(): Long

    /** The approved catalog principal providing pages under [pluginId], with `surfaces` granted, or null. */
    fun pageProvider(pluginId: String): PhonePluginPrincipal?
    fun registrations(principal: PhonePluginPrincipal): List<PageRegistration>

    /** Live registrations of every opted-in provider, for page version announcements. */
    fun providerRegistrations(): List<PageRegistration>
    fun deliver(registration: PageRegistration, path: String, payload: JSONObject): Boolean
    fun sendToGlasses(path: String, payload: JSONObject): String?
    fun bind(principal: PhonePluginPrincipal): Boolean
    fun unbind(principal: PhonePluginPrincipal)
    fun scheduleTick(atMs: Long?)
    fun log(message: String)
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
        var live: Boolean = false,
        var actions: Set<String> = emptySet(),
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
        val actions: Set<String>,
        val dataPlane: Boolean,
        /** An answer to the current request, which makes its actions available. */
        val answersRequest: Boolean,
    )

    private val lock = Any()
    private val frames = linkedMapOf<FrameKey, Frame>()
    private val bound = linkedMapOf<String, PhonePluginPrincipal>()
    private var generation = -1L
    private var remoteVersion = 0
    private var announcedToProviders = 0

    val negotiatedVersion: Int
        get() = synchronized(lock) { PageSurfaceContract.negotiatedSessionVersion(localVersion, remoteVersion) }

    /** The glasses announced their capabilities; a restart or replacement connection resets all page work. */
    fun onGlassesCapabilities(version: Int) = mutate {
        val previous = remoteVersion
        remoteVersion = version
        if (previous != version) dropAll()
        announceIfChanged()
    }

    /** The glasses link went away entirely: support and every page with it. */
    fun onLinkLost() = mutate {
        remoteVersion = 0
        dropAll()
        announceIfChanged()
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
            host.registrations(principal).forEach { registration -> later { deliverVersion(registration, version) } }
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
            frame.invocationDeadline?.takeIf { now >= it }?.let {
                frame.invocationId = null
                frame.invocationDeadline = null
            }
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
        frame.invocationId = null
        frame.invocationDeadline = null
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
                        if (rollback || !bodyAccepted(value, frame.owner)) {
                            refuseAnswer(frame)
                            answerError(frame, PageSurfaceContract.ERROR_INVALID_PAGE)
                            return PageSurfaceContract.ERROR_INVALID_PAGE
                        }
                        frame.requestDeadline = null
                        frame.displayDeadline = now + PageSurfaceContract.PAGE_TIMEOUT_MS
                        frame.reservedRevision = maxOf(frame.reservedRevision ?: value.revision, value.revision)
                        val candidate = Candidate(
                            value.revision, value.live, value.actions.map { it.id }.toSet(),
                            PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESPONSE, stamped), answersRequest = true,
                        )
                        forwardPage(BusPaths.PAGE_RESPONSE, stamped, frame, candidate)
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
        if (!bodyAccepted(page, frame.owner)) return PageSurfaceContract.ERROR_INVALID_PAGE
        frame.reservedRevision = page.revision
        val candidate = Candidate(
            page.revision, page.live, page.actions.map { it.id }.toSet(),
            PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESPONSE, stamped), answersRequest = false,
        )
        forwardPage(BusPaths.PAGE_RESPONSE, stamped, frame, candidate)
        return null
    }

    private fun result(payload: JSONObject, sender: PageRegistration): String? {
        val invocationId = payload.opt("invocationId") as? String ?: return PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST
        val frame = frames.values.firstOrNull { it.invocationId == invocationId }
            ?.takeIf { it.owner == sender.pluginId && it.epoch == sender.epoch }
            ?: return ERROR_PAGE_NOT_REQUESTED
        val now = host.nowMs()
        if (now >= (frame.invocationDeadline ?: Long.MIN_VALUE)) {
            frame.invocationId = null
            frame.invocationDeadline = null
            return ERROR_PAGE_NOT_REQUESTED
        }
        val stamped = stamped(payload, sender)
        val result = when (val validated = PageSurfaceContract.validateBoundedResult(stamped)) {
            is PageSurfaceValidationResult.Invalid -> return validated.error
            is PageSurfaceValidationResult.Valid -> validated.value
        }
        val replacement = result.replacement
        val candidate = replacement?.let {
            val sameFrame = it.correlation.sessionGeneration == frame.key.generation &&
                it.correlation.frameIndex == frame.key.frameIndex &&
                it.correlation.pageId == frame.pageId
            if (!sameFrame || it.revision <= frame.highestRevision() || !bodyAccepted(it, frame.owner)) {
                return PageSurfaceContract.ERROR_INVALID_PAGE
            }
            frame.reservedRevision = it.revision
            Candidate(
                it.revision, it.live, it.actions.map { action -> action.id }.toSet(),
                PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESULT, stamped), answersRequest = false,
            )
        }
        frame.invocationId = null
        frame.invocationDeadline = null
        if (candidate != null) forwardPage(BusPaths.PAGE_RESULT, stamped, frame, candidate) else forward(BusPaths.PAGE_RESULT, stamped)
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
        val accepted = frame.actionable && frame.shownRevision == action.revision && action.actionId in frame.actions &&
            frame.invocationId == null && !frame.pending && lease != null && now < lease
        if (!accepted) return host.log("page drop path=action id=${action.invocationId} reason=NOT_ACTIONABLE")
        val principal = host.pageProvider(frame.owner) ?: return invalidate(frame)
        val registration = host.registrations(principal).singleOrNull()?.takeIf { it.epoch == frame.epoch }
            ?: return invalidate(frame)
        frame.invocationId = action.invocationId
        frame.invocationDeadline = now + PageSurfaceContract.PAGE_TIMEOUT_MS
        val outgoing = stamped(payload, registration)
        later {
            // Queued is not delivered: a cover, a cancellation or the lease end may have come first.
            val current = synchronized(lock) {
                val lease = frame.leaseUntil
                val live = frames[frame.key] === frame && frame.invocationId == action.invocationId &&
                    frame.epoch == registration.epoch && frame.actionable && lease != null && host.nowMs() < lease
                // An invocation that never reached its provider is released; the glasses show it unconfirmed.
                if (!live && frame.invocationId == action.invocationId) {
                    frame.invocationId = null
                    frame.invocationDeadline = null
                }
                live
            }
            if (current) {
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
        if (!visibility.visible) {
            frame.invocationId = null
            frame.invocationDeadline = null
        }
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
        frame.actions = emptySet()
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
     * frame, its request and its epoch are still the same. A page that could not be sent ends the
     * frame on both sides: the glasses get a small, honest answer and the provider a close.
     */
    private fun forwardPage(path: String, payload: JSONObject, frame: Frame, candidate: Candidate) {
        val requestId = frame.requestId
        val epoch = frame.epoch
        later {
            val error = host.sendToGlasses(path, payload)
            if (error != null) {
                host.log("page send failed path=$path code=$error bytes=${PageSurfaceContract.serializedBytes(payload)}")
            }
            mutate {
                if (frames[frame.key] === frame && frame.requestId == requestId && frame.epoch == epoch) {
                    if (error == null) commit(frame, candidate) else failClosed(frame)
                }
            }
        }
    }

    private fun commit(frame: Frame, candidate: Candidate) {
        frame.shownRevision = maxOf(frame.shownRevision ?: candidate.revision, candidate.revision)
        frame.live = candidate.live
        frame.actions = candidate.actions
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

    private fun bodyAccepted(page: PageSurfaceResponse.Page, owner: String): Boolean =
        !PageTemplateContract.isSupported(page.template) ||
            PageTemplateContract.decode(page, owner) is PageSurfaceValidationResult.Valid

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

    private fun deliverVersion(registration: PageRegistration, version: Int) {
        host.deliver(
            registration,
            BusPaths.HUB_CAPABILITIES,
            JSONObject()
                .put(PageSurfaceContract.FIELD_PLUGIN_ID, registration.pluginId)
                .put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, version),
        )
    }

    /** Tells opted-in providers when the effective version changed. */
    private fun announceIfChanged() {
        val version = negotiated()
        if (version == announcedToProviders) return
        announcedToProviders = version
        later { host.providerRegistrations().forEach { deliverVersion(it, version) } }
    }

    /** Holds or drops the page-only binding of each owner by its current demand. */
    private fun reconcileBindings() {
        val now = host.nowMs()
        val demand = frames.values.filter { frame ->
            frame.pending || listOfNotNull(frame.leaseUntil, frame.displayDeadline).any { now < it }
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

        fun isPagePath(path: String): Boolean = PathRules.isPagePath(path)

        /** A page answer is refused on its raw size, before it is ever parsed. */
        fun rawAnswerTooLarge(path: String, bytes: Int): Boolean =
            isPagePath(PathRules.normalizeAbsolute(path) ?: path) && bytes > PageSurfaceContract.MAX_PAGE_BYTES
    }
}
