package com.anezium.rokidbus.client.plugin

import android.content.Context
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.ActivityCommandResult
import com.anezium.rokidbus.shared.ActivityGeometryUpdate
import com.anezium.rokidbus.shared.ActivityHeading
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.AssistantTakeoverContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.InkSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceValidationResult
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.CapabilityParseResult
import com.anezium.rokidbus.shared.plugin.PluginCloseTypes
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import com.anezium.rokidbus.shared.skills.SkillCatalog
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillError
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import com.anezium.rokidbus.shared.skills.SkillLimits
import com.anezium.rokidbus.shared.skills.SkillProviderResult
import com.anezium.rokidbus.shared.skills.SkillResultEnvelope
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillStatus
import com.anezium.rokidbus.shared.skills.SkillValidation
import com.anezium.rokidbus.shared.skills.SkillsContract
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class NexusPluginClient internal constructor(
    private val pluginId: String,
    private val callbacks: NexusPluginCallbacks,
    private val transport: NexusPluginTransport,
    private val monotonicClockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : NexusPluginTransport.Listener, AutoCloseable {
    private val renderedMaps = NexusRenderedMaps(monotonicClockMs)

    /** Both hubs preserve map credit and support authenticated rendered-view feedback over SPP. */
    val supportsRenderedMaps: Boolean
        get() = supportsLauncherInteractive() && RenderedMapContract.supported(hubCapabilities) &&
            currentLinkState and LinkStateBits.SPP_DATA_UP != 0

    /** Empty for unknown, hidden, unsupported or expired views. Read again before requesting data. */
    val renderedMapViews: Set<NexusRenderedMapView>
        get() = if (supportsRenderedMaps) renderedMaps.views() else emptySet()

    /** The actually drawn owner publications. Correlate these before requesting a map footprint. */
    val renderedMapFrames: Set<NexusRenderedMap>
        get() = if (supportsRenderedMaps) renderedMaps.frames() else emptySet()

    /** Next active slot's expiry, for one lifecycle cancellation timer; null without demand. */
    val renderedMapLeaseRemainingMs: Long?
        get() = if (supportsRenderedMaps) renderedMaps.nextExpiryDelayMs() else null

    /** Discard this plugin's feedback, for example when its journey is replaced. */
    fun clearRenderedMapDemand() = renderedMaps.clear()
    // A page answer is current or it is nothing: it never waits in the reconnect queue.
    private val pages = NexusPageChannel(pluginId, monotonicClockMs) { path, id, payload ->
        !closed && isApproved && transport.sendImmediate(path, id, payload)
    }
    private val seenEventIds = ArrayDeque<String>()
    private val seenEventIdSet = linkedSetOf<String>()
    // Activity command invocations already delivered, and those already answered: each runs once
    // and is answered at most once, whatever the transport repeats.
    private val activityCommandLock = Any()
    private val seenActivityInvocations = linkedSetOf<String>()
    private val answeredActivityCommands = linkedSetOf<String>()
    // Correlates a surface request's id to the callback that wants to know if
    // the hub rejects it after accepting the Binder call — see watchForSurfaceError.
    private val pendingSurfaceErrors = mutableMapOf<String, (String) -> Unit>()
    private val audioSessionLock = Any()
    private val speechSessionLock = Any()
    private val ttsSessionLock = Any()
    private val snapshotSessionLock = Any()
    @Volatile private var registrationState = PluginRegistrationResult.REGISTRATION_FAILED
    private var opened = false
    private var backgrounded = false
    @Volatile private var closed = false
    @Volatile private var approvedCapabilities: Set<PluginCapability> = emptySet()
    private var noticeInteractionVersion = 0
    private var noticeApprovalAwaitingMetadata = false
    private var currentNoticeToken: String? = null
    private var noticeHidePending = false
    private var registeredAudioSession: NexusAudioSession? = null
    private var audioSessionApiUsed = false
    private var registeredSpeechSession: NexusSpeechSession? = null
    private var speechSessionApiUsed = false
    private var registeredTtsSession: NexusTtsSession? = null
    private var ttsSessionApiUsed = false
    private var registeredSnapshotSession: NexusSnapshotSession? = null
    private var snapshotSessionApiUsed = false
    @Volatile private var currentLinkState = 0
    @Volatile private var hubCapabilities = 0
    @Volatile private var skillsVersion = 0
    private val skillLock = Any()
    private val liveInvocations = linkedMapOf<String, NexusSkillInvocation>()

    /**
     * This provider's own parsed catalog, when it declares one. Answers are checked against it
     * locally so a contract mistake fails in the provider's process, not only at the hub.
     */
    @Volatile
    internal var ownSkillCatalog: SkillCatalog? = null

    val isApproved: Boolean
        get() = registrationState == PluginRegistrationResult.APPROVED

    /**
     * How many times this client has registered with the hub. Approval is
     * reported twice for one registration (at once, then again with its
     * capability metadata) and again after every reconnect, and a reconnect is
     * not reported otherwise. A plugin that keeps state on the hub, such as a
     * started activity, compares this number to tell a new registration, which
     * holds nothing yet, from the repeated report of the current one.
     */
    @Volatile
    var registrationGeneration: Int = 0
        private set

    fun hasCapability(capability: PluginCapability): Boolean =
        isApproved && capability in approvedCapabilities

    val supportsImageSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.IMAGE_SURFACE != 0

    val supportsInkSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.INK_SURFACE != 0

    /**
     * Whether the glasses hub understands the `editable` field on a card, and
     * can be reached right now. An older hub build ignores the field silently —
     * it shows the card but never commits it — so a plugin that wants to type
     * needs a way to know that ahead of time, not just wait to find out.
     */
    val supportsEditableSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.EDITABLE_SURFACE != 0

    /**
     * Whether these glasses can show a pin — not whether one would appear this instant.
     * Unlike [supportsImageSurface] this ignores the link: a pin pushed while the glasses
     * are asleep is held by the hub and delivered when they come back, so refusing here
     * would strand exactly the background plugins pins exist for.
     */
    val supportsPinSurface: Boolean
        get() = hubCapabilities and BusCapabilityBits.PIN_SURFACE != 0

    /**
     * Whether pages can be served right now: approved with the `surfaces` grant, both hubs on
     * session/page v1 for this plugin, and a live glasses link. A hub too old to say so reads
     * as unsupported.
     */
    val supportsPageSession: Boolean
        get() = !closed && hasCapability(PluginCapability.SURFACES) &&
            pages.sessionVersion == PageSurfaceContract.VERSION && hasUsableLink(currentLinkState)

    /**
     * Sends a live revision of the page [request] asked for. Only for the frame's current request,
     * after a `live` answer, while the page is visible under an unexpired lease, and at a revision
     * above every one already sent for it. The first answer is the callback's return value.
     */
    fun publishPage(request: NexusPageRequest, page: NexusPage): NexusSdkResult = when {
        !isApproved -> NexusSdkResult.NOT_REGISTERED
        !hasCapability(PluginCapability.SURFACES) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        !supportsPageSession -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> pages.publish(request, page)
    }

    fun showPin(pin: NexusPin): NexusSdkResult {
        pinPreflight()?.let { return it }
        val payload = pin.toPayload()
        if (PinSurfaceContract.validateShow(payload) !is PinSurfaceValidationResult.Valid) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.PIN_SHOW, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun hidePin(): NexusSdkResult {
        pinPreflight()?.let { return it }
        return if (
            send(
                BusPaths.PIN_HIDE,
                UUID.randomUUID().toString(),
                JSONObject().put("surfaceId", PinSurfaceContract.LOCAL_SURFACE_ID),
            )
        ) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * Whether these glasses can show a notice, and whether they can be reached
     * right now. Unlike a pin this does take the link into account: a notice is
     * a moment, so the hub never holds one for glasses that are asleep.
     */
    val supportsNoticeSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.NOTICE_SURFACE != 0

    fun showNotice(notice: NexusNotice): NexusSdkResult {
        noticePreflight()?.let { return it }
        if (notice.image != null) return NexusSdkResult.INVALID_PAYLOAD
        val payload = notice.toPayload()
        val validation = NoticeSurfaceContract.validateShow(payload)
        if (
            validation !is NoticeSurfaceValidationResult.Valid ||
            !NoticeSurfaceContract.destinationAllowed(validation.content, pluginId)
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.NOTICE_SHOW, UUID.randomUUID().toString(), forNoticePeers(payload))) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun showNotice(notice: NexusNotice, imageBytes: ByteArray): NexusSdkResult {
        noticePreflight()?.let { return it }
        if (!supportsImageSurface) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (notice.image == null) return NexusSdkResult.INVALID_PAYLOAD
        val payload = notice.toPayload(imageBytes)
        val validation = NoticeSurfaceContract.validateShow(payload, imageBytes)
        if (
            validation !is NoticeSurfaceValidationResult.Valid ||
            !NoticeSurfaceContract.destinationAllowed(validation.content, pluginId)
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (
            sendBinary(BusPaths.NOTICE_SHOW, UUID.randomUUID().toString(), forNoticePeers(payload), imageBytes)
        ) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
    }

    fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult {
        noticePreflight()?.let { return it }
        if (update.rearm == false && noticeInteractionVersion < 1) {
            return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        val payload = update.toPayload()
        return if (send(BusPaths.NOTICE_UPDATE, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun hideNotice(): NexusSdkResult {
        noticePreflight()?.let { return it }
        val payload = JSONObject().put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        return if (
            send(
                BusPaths.NOTICE_HIDE,
                UUID.randomUUID().toString(),
                payload,
            )
        ) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * Whether these glasses support the platform-owned activity tier.
     *
     * This intentionally ignores the current link. Like a pin, an activity is
     * canonical phone-side state and is resent when capable glasses reconnect.
     */
    val supportsActivitySurface: Boolean
        get() = hubCapabilities and BusCapabilityBits.ACTIVITY_SURFACE != 0

    /**
     * Whether both hubs understand activity extras: [NexusActivity.badge],
     * [NexusActivity.track], and the urgent tone. Without it those fields are
     * dropped and the activity renders as v1; nothing is refused.
     */
    val supportsActivityExtras: Boolean
        get() = supportsActivitySurface &&
            hubCapabilities and BusCapabilityBits.ACTIVITY_EXTRAS != 0

    /**
     * Whether both hubs negotiated the launcher bundle for this approved plugin: activity
     * destinations, commands and offline persistence, typed and quick actions, the conversation,
     * media and route page bodies, notice destinations and [withdrawNotice], and activity route
     * geometry. Read from the hub's feature bit, or from the page capabilities it sends a page
     * provider. When false, the SDK drops every launcher field before sending, and a page must
     * use the four base templates.
     */
    fun supportsLauncherInteractive(): Boolean =
        !closed && hasCapability(PluginCapability.SURFACES) && (
            hubCapabilities and BusCapabilityBits.LAUNCHER_INTERACTIVE != 0 ||
                LauncherInteractiveContract.isActive(pages.launcherInteractiveVersion)
            )

    /** Whether the current glasses/link announced TTS protocol v1. */
    val supportsTts: Boolean
        get() = currentLinkState and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0 &&
            hubCapabilities and BusCapabilityBits.TTS != 0

    /**
     * Whether the phone hub routes skills (it announced skills v1 at registration). Every
     * skills call returns [NexusSdkResult.CAPABILITY_NOT_AVAILABLE] without sending when false,
     * so a plugin built with this SDK keeps working with an older hub.
     */
    val supportsSkills: Boolean
        get() = isApproved && skillsVersion >= SkillsContract.VERSION

    /**
     * Asks the hub for the skill operations the wearer approved for this caller; the answer
     * arrives on [NexusPluginCallbacks.onSkillCatalog]. Needs the `skills_client` grant.
     */
    fun requestSkillCatalog(): NexusSdkResult {
        skillsClientPreflight()?.let { return it }
        val id = UUID.randomUUID().toString()
        watchForSurfaceError(id) { code -> callbacks.onSkillCatalog(emptyList(), code) }
        return sendOrForget(id, BusPaths.SKILLS_CATALOG_REQUEST, SkillsContract.catalogRequest())
    }

    /**
     * Invokes an approved operation by its hub alias. Exactly one
     * [NexusPluginCallbacks.onSkillResult] follows for [SkillInvokeRequest.requestKey], including
     * when the hub refuses the call: a route rejection arrives as a failed result too.
     */
    fun invokeSkill(request: SkillInvokeRequest): NexusSdkResult {
        skillsClientPreflight()?.let { return it }
        val payload = SkillsContract.invokeRequest(request)
        if (SkillsContract.parseInvokeRequest(payload) == null ||
            SkillsContract.utf8Size(request.arguments) > SkillLimits.MAX_ARGUMENTS_BYTES
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        val id = UUID.randomUUID().toString()
        watchForSurfaceError(id) { code ->
            callbacks.onSkillResult(
                SkillResultEnvelope(
                    session = request.session,
                    requestKey = request.requestKey,
                    invocationId = LOCAL_INVOCATION_ID,
                    providerId = "",
                    operationId = "",
                    alias = request.alias,
                    contractVersion = 0,
                    status = SkillStatus.FAILED,
                    error = SkillError(routeErrorCode(code), SkillDispatch.NONE),
                    observedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        return sendOrForget(id, BusPaths.SKILLS_INVOKE, payload)
    }

    /** Cancels a pending invocation; its result still arrives, as `cancelled` with its dispatch state. */
    fun cancelSkill(session: String, requestKey: String): NexusSdkResult {
        skillsClientPreflight()?.let { return it }
        val payload = SkillsContract.cancelRequest(session, requestKey)
        if (SkillsContract.parseCancelRequest(payload) == null) return NexusSdkResult.INVALID_PAYLOAD
        return sendOrForget(UUID.randomUUID().toString(), BusPaths.SKILLS_CANCEL, payload)
    }

    /** Ends a caller session: its invocations are cancelled and its references and ledger dropped. */
    fun closeSkillSession(session: String): NexusSdkResult {
        skillsClientPreflight()?.let { return it }
        val payload = SkillsContract.sessionClose(session)
        if (SkillsContract.parseSessionClose(payload) == null) return NexusSdkResult.INVALID_PAYLOAD
        return sendOrForget(UUID.randomUUID().toString(), BusPaths.SKILLS_SESSION_CLOSE, payload)
    }

    private fun sendOrForget(id: String, path: String, payload: JSONObject): NexusSdkResult =
        if (send(path, id, payload)) {
            NexusSdkResult.SENT
        } else {
            pendingSurfaceErrors.remove(id)
            NexusSdkResult.NOT_REGISTERED
        }

    private fun skillsClientPreflight(): NexusSdkResult? = when {
        !isApproved -> NexusSdkResult.NOT_REGISTERED
        !hasCapability(PluginCapability.SKILLS_CLIENT) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        skillsVersion < SkillsContract.VERSION -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> null
    }

    private fun routeErrorCode(code: String): String = when {
        code.startsWith("CAPABILITY_REQUIRED") || code == "REVOKED" || code == "PENDING_APPROVAL" ->
            SkillErrorCodes.PERMISSION_REQUIRED
        else -> SkillErrorCodes.UNAVAILABLE
    }

    /**
     * Sends a provider's one answer. Refused locally, with the invocation left open for a
     * correct answer, when the result breaks the status rules, the size limit, or the
     * operation's declared output schema.
     */
    internal fun sendSkillResult(invocation: NexusSkillInvocation, result: SkillProviderResult): NexusSdkResult {
        if (closed || !isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!hasCapability(PluginCapability.SKILLS_PROVIDER)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        val payload = SkillsContract.providerResult(result)
        if (SkillsContract.parseProviderResult(payload) == null ||
            SkillsContract.utf8Size(payload) > SkillLimits.MAX_RESULT_BYTES
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        val operation = ownSkillCatalog?.operation(invocation.operationId)
        val data = result.data
        if (operation != null && data != null &&
            SkillSchemaValidator.validate(data, operation.output) is SkillValidation.Invalid
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        synchronized(skillLock) {
            if (liveInvocations[invocation.invocationId] !== invocation) return NexusSdkResult.INVALID_PAYLOAD
            liveInvocations.remove(invocation.invocationId)
        }
        return if (transport.send(BusPaths.SKILLS_PROVIDER_RESULT, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    private fun routeSkillMessage(path: String, payload: JSONObject): Boolean {
        when (path) {
            BusPaths.SKILLS_PROVIDER_INVOKE -> {
                if (!isApproved || !hasCapability(PluginCapability.SKILLS_PROVIDER)) return true
                val parsed = SkillsContract.parseProviderInvoke(payload) ?: return true
                val invocation = NexusSkillInvocation(
                    client = this,
                    invocationId = parsed.invocationId,
                    operationId = parsed.operationId,
                    contractVersion = parsed.contractVersion,
                    arguments = parsed.arguments,
                    deadlineAtMs = monotonicClockMs() + parsed.deadlineMs,
                    clock = monotonicClockMs,
                )
                synchronized(skillLock) {
                    if (liveInvocations.containsKey(parsed.invocationId)) return true
                    liveInvocations[parsed.invocationId] = invocation
                    while (liveInvocations.size > MAX_LIVE_PROVIDER_INVOCATIONS) {
                        liveInvocations.remove(liveInvocations.keys.first())
                    }
                }
                callbacks.onSkillInvoked(invocation)
            }
            BusPaths.SKILLS_PROVIDER_CANCEL -> {
                val invocationId = SkillsContract.parseProviderCancel(payload) ?: return true
                val invocation = synchronized(skillLock) { liveInvocations[invocationId] } ?: return true
                invocation.isCancelled = true
                callbacks.onSkillCancelled(invocation)
            }
            BusPaths.SKILLS_RESULT -> if (isApproved) {
                SkillsContract.parseResult(payload)?.let(callbacks::onSkillResult)
            }
            BusPaths.SKILLS_CATALOG_REPLY -> if (isApproved) {
                SkillsContract.parseCatalogReply(payload)?.let { callbacks.onSkillCatalog(it, null) }
            }
            else -> return false
        }
        return true
    }

    private fun cancelLiveInvocations() {
        val cancelled = synchronized(skillLock) {
            liveInvocations.values.toList().also { liveInvocations.clear() }
        }
        cancelled.forEach { it.isCancelled = true }
    }

    fun startActivity(activity: NexusActivity): NexusSdkResult {
        activityPreflight()?.let { return it }
        val payload = activity.toStartPayload()
        val validation = ActivitySurfaceContract.validateStart(payload)
        if (
            validation !is ActivitySurfaceValidationResult.Valid ||
            !ActivitySurfaceContract.destinationAllowed(validation.content, pluginId)
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.ACTIVITY_START, UUID.randomUUID().toString(), forActivityPeers(payload))) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * [urgent] marks a time-critical transition ("get off at the next stop")
     * and is only valid together with [significant]. The platform decides how
     * it looks and how often it is honoured.
     */
    fun updateActivity(
        activity: NexusActivity,
        significant: Boolean = false,
        urgent: Boolean = false,
    ): NexusSdkResult {
        activityPreflight()?.let { return it }
        val payload = activity.toUpdatePayload(significant, urgent)
        val validation = ActivitySurfaceContract.validateUpdate(payload)
        if (
            validation !is ActivitySurfacePatchResult.Valid ||
            !ActivitySurfaceContract.destinationAllowed(validation.patch, pluginId)
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.ACTIVITY_UPDATE, UUID.randomUUID().toString(), forActivityPeers(payload))) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * Sends the result of a [NexusActivityCommand] outside the callback, for a plugin that
     * answered null and finished the work later. Same rules: once per invocation, never retried,
     * and dropped by the hub after its deadline, when the glasses already show it unconfirmed.
     */
    fun sendActivityCommandResult(command: NexusActivityCommand, result: NexusActivityCommandResult): NexusSdkResult {
        if (!isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!hasCapability(PluginCapability.SURFACES)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        if (!supportsLauncherInteractive()) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (command.activityId != ownActivityId) return NexusSdkResult.INVALID_PAYLOAD
        if (!rememberOnce(answeredActivityCommands, command.invocationId)) return NexusSdkResult.INVALID_PAYLOAD
        return if (transport.sendImmediate(BusPaths.ACTIVITY_RESULT, UUID.randomUUID().toString(), activityResultPayload(command, result))) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * Replaces the route geometry drawn with this plugin's activity (navigation). Latest wins:
     * the hub keeps the highest [revision] and drops older ones. Geometry rides only the SPP data
     * plane: with only the narrow control link up this returns
     * [NexusSdkResult.CAPABILITY_NOT_AVAILABLE] and nothing is queued, so text guidance must never
     * depend on it. It is cleared when the activity ends or goes offline.
     */
    fun publishActivityGeometry(revision: Long, geometry: RouteGeometry): NexusSdkResult {
        activityPreflight()?.let { return it }
        if (geometry.attribution != null && !supportsRenderedMaps) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (!supportsLauncherInteractive()) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (currentLinkState and LinkStateBits.SPP_DATA_UP == 0) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (revision < 0) return NexusSdkResult.INVALID_PAYLOAD
        val payload = runCatching {
            ActivitySurfaceContract.geometryPayload(ActivityGeometryUpdate(revision, geometry))
        }.getOrNull() ?: return NexusSdkResult.INVALID_PAYLOAD
        if (ActivitySurfaceContract.parseGeometry(payload) == null) return NexusSdkResult.INVALID_PAYLOAD
        payload.put("surfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        return if (transport.sendImmediate(BusPaths.ACTIVITY_GEOMETRY, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * The wearer's compass heading for this plugin's activity map, [deg] in `0 until 360`, with
     * an optional accuracy. The hub keeps at most five a second and treats one older than three
     * seconds as absent; nothing is queued.
     */
    fun publishActivityHeading(deg: Double, accuracyDeg: Double? = null): NexusSdkResult {
        activityPreflight()?.let { return it }
        if (!supportsLauncherInteractive()) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        // Like geometry, a heading travels over the data plane only; the phone drops it otherwise.
        if (currentLinkState and LinkStateBits.SPP_DATA_UP == 0) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        val payload = runCatching {
            ActivitySurfaceContract.headingPayload(ActivityHeading(deg, System.currentTimeMillis(), accuracyDeg))
        }.getOrNull() ?: return NexusSdkResult.INVALID_PAYLOAD
        if (ActivitySurfaceContract.parseHeading(payload) == null) return NexusSdkResult.INVALID_PAYLOAD
        payload.put("surfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        return if (transport.sendImmediate(BusPaths.ACTIVITY_HEADING, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * Says that the event this plugin announced with [eventKey] is no longer on the phone (its
     * notification was removed). The glasses mark the matching history entries removed and stop
     * offering a reply for them. It is not a read or delivery receipt.
     */
    fun withdrawNotice(eventKey: String): NexusSdkResult {
        if (!isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!hasCapability(PluginCapability.SURFACES)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        if (!supportsLauncherInteractive()) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (!NoticeSurfaceContract.isValidEventKey(eventKey)) return NexusSdkResult.INVALID_PAYLOAD
        return if (send(BusPaths.NOTICE_WITHDRAW, UUID.randomUUID().toString(), NoticeSurfaceContract.withdrawPayload(eventKey))) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    private val ownActivityId: String
        get() = "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}"

    /** Peers without the launcher bundle get a plain activity, whatever this plugin set. */
    private fun forActivityPeers(payload: JSONObject): JSONObject =
        if (supportsLauncherInteractive()) payload else ActivitySurfaceContract.stripLauncherFields(payload)

    private fun forNoticePeers(payload: JSONObject): JSONObject =
        if (supportsLauncherInteractive()) payload else NoticeSurfaceContract.stripLauncherFields(payload)

    private fun activityResultPayload(command: NexusActivityCommand, result: NexusActivityCommandResult): JSONObject =
        ActivitySurfaceContract.resultPayload(
            ActivityCommandResult(command.activityId, command.invocationId, result.status.contract, result.message),
        ).put("surfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)

    /** Adds [id] to a bounded record, true only the first time; callers on any thread. */
    private fun rememberOnce(ids: LinkedHashSet<String>, id: String): Boolean = synchronized(activityCommandLock) {
        if (!ids.add(id)) return@synchronized false
        while (ids.size > MAX_SEEN_EVENTS) ids.remove(ids.first())
        true
    }

    fun endActivity(): NexusSdkResult {
        renderedMaps.clear(RenderedMapContract.AMBIENT)
        callbacks.onRenderedMapsChanged()
        activityPreflight()?.let { return it }
        return if (
            send(
                BusPaths.ACTIVITY_END,
                UUID.randomUUID().toString(),
                JSONObject().put(
                    "surfaceId",
                    ActivitySurfaceContract.LOCAL_SURFACE_ID,
                ),
            )
        ) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun connect() {
        check(!closed) { "NexusPluginClient is closed" }
        transport.connect(this)
    }

    fun send(path: String, id: String, payload: JSONObject): Boolean {
        if (closed || !isApproved) return false
        if (PathRules.normalizeAbsolute(path) == RenderedMapContract.PATH) return false
        val outgoingPath = canonicalNoticePath(path)
        if (!supportsNoticePatch(outgoingPath, payload)) return false
        val sent = transport.send(outgoingPath, id, prepareNoticePayload(outgoingPath, payload))
        if (sent && outgoingPath == BusPaths.NOTICE_HIDE) noticeHidePending = true
        return sent
    }

    internal fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
        if (closed || !isApproved) return false
        val outgoingPath = canonicalNoticePath(path)
        if (!supportsNoticePatch(outgoingPath, payload)) return false
        val sent = transport.sendBinary(outgoingPath, id, prepareNoticePayload(outgoingPath, payload), data)
        if (sent && outgoingPath == BusPaths.NOTICE_HIDE) noticeHidePending = true
        if (!sent) {
            currentLinkState = currentLinkState and LinkStateBits.SPP_DATA_UP.inv()
            hubCapabilities = transport.capabilities()
        }
        return sent
    }

    internal fun isApprovedForAudio(): Boolean = !closed && isApproved

    internal fun isApprovedForSpeech(): Boolean = !closed && isApproved

    internal fun isApprovedForTts(): Boolean = !closed && isApproved
    internal fun isApprovedForSnapshot(): Boolean = !closed && isApproved

    internal fun reportInkError(surfaceId: String, problems: List<NexusInkProblem>) {
        callbacks.onInkError(surfaceId, problems)
    }

    internal fun registerAudioSession(session: NexusAudioSession): Boolean =
        synchronized(audioSessionLock) {
            if (closed || registeredAudioSession?.let { it !== session } == true) {
                false
            } else {
                registeredAudioSession = session
                audioSessionApiUsed = true
                true
            }
        }

    internal fun unregisterAudioSession(session: NexusAudioSession) {
        synchronized(audioSessionLock) {
            if (registeredAudioSession === session) registeredAudioSession = null
        }
    }

    internal fun sendAudioAcquire(session: NexusAudioSession, id: String): Boolean {
        if (synchronized(audioSessionLock) { registeredAudioSession !== session }) return false
        return send(NEXUS_AUDIO_LEASE_ACQUIRE_PATH, id, JSONObject())
    }

    internal fun sendAudioRelease(
        session: NexusAudioSession,
        id: String,
        leaseId: String,
    ): Boolean {
        if (synchronized(audioSessionLock) { registeredAudioSession !== session }) return false
        return send(
            NEXUS_AUDIO_LEASE_RELEASE_PATH,
            id,
            JSONObject().put("leaseId", leaseId),
        )
    }

    internal fun releaseAudioSession() {
        currentAudioSession()?.terminate(
            reason = NexusAudioStopReason.RELEASED,
            releaseActiveLease = true,
        )
    }

    internal fun registerSpeechSession(session: NexusSpeechSession): Boolean =
        synchronized(speechSessionLock) {
            if (closed || registeredSpeechSession?.let { it !== session } == true) {
                false
            } else {
                registeredSpeechSession = session
                speechSessionApiUsed = true
                true
            }
        }

    internal fun unregisterSpeechSession(session: NexusSpeechSession) {
        synchronized(speechSessionLock) {
            if (registeredSpeechSession === session) registeredSpeechSession = null
        }
    }

    internal fun sendSpeechStart(
        session: NexusSpeechSession,
        id: String,
        language: String?,
    ): Boolean {
        if (synchronized(speechSessionLock) { registeredSpeechSession !== session }) return false
        val payload = JSONObject()
            .put("version", 1)
            .put("mode", "utterance")
        language?.trim()?.takeIf(String::isNotEmpty)?.let { payload.put("language", it) }
        return send(NEXUS_STT_SESSION_START_PATH, id, payload)
    }

    internal fun sendSpeechStop(
        session: NexusSpeechSession,
        id: String,
        sessionId: String,
    ): Boolean {
        if (synchronized(speechSessionLock) { registeredSpeechSession !== session }) return false
        return send(
            NEXUS_STT_SESSION_STOP_PATH,
            id,
            JSONObject().put("sessionId", sessionId),
        )
    }

    internal fun releaseSpeechSession() {
        currentSpeechSession()?.terminate(
            reason = NexusSpeechStopReason.CANCELLED,
            stopActiveSession = true,
        )
    }

    internal fun registerTtsSession(session: NexusTtsSession): Boolean =
        synchronized(ttsSessionLock) {
            if (closed || registeredTtsSession?.let { it !== session } == true) {
                false
            } else {
                registeredTtsSession = session
                ttsSessionApiUsed = true
                true
            }
        }

    internal fun registerSnapshotSession(session: NexusSnapshotSession): Boolean =
        synchronized(snapshotSessionLock) {
            if (closed || registeredSnapshotSession?.let { it !== session } == true) {
                false
            } else {
                registeredSnapshotSession = session
                snapshotSessionApiUsed = true
                true
            }
        }

    internal fun unregisterTtsSession(session: NexusTtsSession) {
        synchronized(ttsSessionLock) {
            if (registeredTtsSession === session) registeredTtsSession = null
        }
    }

    internal fun sendTtsSpeak(
        session: NexusTtsSession,
        id: String,
        payload: JSONObject,
    ): Boolean {
        if (synchronized(ttsSessionLock) { registeredTtsSession !== session }) return false
        return send(BusPaths.TTS_SPEAK, id, payload)
    }

    internal fun sendTtsStop(
        session: NexusTtsSession,
        id: String,
        utteranceId: String,
    ): Boolean {
        if (synchronized(ttsSessionLock) { registeredTtsSession !== session }) return false
        return send(BusPaths.TTS_STOP, id, JSONObject().put("utteranceId", utteranceId))
    }

    internal fun releaseTtsSession() {
        currentTtsSession()?.terminate(
            reason = NexusTtsDoneReason.STOPPED,
            stopCurrent = true,
        )
    }

    internal fun unregisterSnapshotSession(session: NexusSnapshotSession) {
        synchronized(snapshotSessionLock) {
            if (registeredSnapshotSession === session) registeredSnapshotSession = null
        }
    }

    internal fun sendSnapshotRequest(session: NexusSnapshotSession, id: String): Boolean {
        if (synchronized(snapshotSessionLock) { registeredSnapshotSession !== session }) return false
        return send(
            NEXUS_SNAPSHOT_REQUEST_PATH,
            id,
            JSONObject().put("version", 1).put("requestId", id),
        )
    }

    internal fun releaseSnapshotSession() {
        currentSnapshotSession()?.terminate(NexusSnapshotError.CANCELLED)
    }

    override fun onRegistrationState(result: Int) {
        if (closed) return
        renderedMaps.clear()
        // A new registration holds no page work; the hub announces its page support afresh.
        pages.onRegistrationReset()
        registrationGeneration++
        clearNoticeContext()
        noticeApprovalAwaitingMetadata = result == PluginRegistrationResult.APPROVED
        applyRegistrationState(result)
    }

    private fun applyRegistrationState(result: Int) {
        registrationState = result
        // Approval is the moment a fire-and-forget plugin acts on — connect, push a pin,
        // disconnect — so capabilities must be true by then. Leaving this to the first
        // onLinkState is a race, and the loser reads every capability as absent.
        if (result == PluginRegistrationResult.APPROVED) {
            hubCapabilities = transport.capabilities()
            // The plugin's *own* grants travel behind this on a different wire:
            // `registerPlugin` answers APPROVED synchronously while the capability list
            // follows as a `/plugin/registration` message, measured on hardware at 16 ms
            // apart. A plugin that pushes the instant it is approved — the shape the SDK
            // recommends for pins and notices — would read an empty grant set and be
            // refused a capability the wearer did approve. So ask the hub outright and
            // make approval and grants one moment again.
            //
            // Null means the hub predates the call: fall back to what we already have,
            // and the registration message fills it in a few milliseconds later.
            transport.approvedCapabilities()?.let { serialized ->
                val parsed = PluginCapability.parseList(serialized)
                if (parsed is CapabilityParseResult.Valid) approvedCapabilities = parsed.capabilities
            }
        }
        if (result != PluginRegistrationResult.APPROVED) {
            renderedMaps.clear()
            approvedCapabilities = emptySet()
            skillsVersion = 0
            cancelLiveInvocations()
            pages.onRegistrationReset()
            clearNoticeContext()
            terminateAudioSession(
                reason = NexusAudioStopReason.ERROR,
                releaseActiveLease = false,
            )
            terminateSpeechSession(
                reason = NexusSpeechStopReason.ERROR,
                stopActiveSession = false,
            )
            terminateTtsSession(
                reason = NexusTtsDoneReason.UNAVAILABLE,
                stopCurrent = false,
            )
            terminateSnapshotSession(NexusSnapshotError.ERROR)
        }
        callbacks.onRegistrationState(result)
        if (result != PluginRegistrationResult.APPROVED && (opened || backgrounded)) {
            opened = false
            backgrounded = false
            callbacks.onClose()
        }
    }

    override fun onLinkState(state: Int) {
        if (closed) return
        // Without a usable transport every page lease is void, and support waits for the hub's
        // next owner-scoped capability update; worn or bonded alone carries no page.
        if (!hasUsableLink(state)) pages.onLinkUnusable()
        currentLinkState = state
        hubCapabilities = transport.capabilities()
        if (!supportsRenderedMaps) {
            renderedMaps.clear()
            callbacks.onRenderedMapsChanged()
        }
        callbacks.onLinkState(state)
    }

    override fun onGlassesAiButton(active: Boolean) {
        if (closed) return
        callbacks.onGlassesAiButton(active)
    }

    /**
     * A surface call that returned [NexusSdkResult.SENT] only means the hub
     * accepted the Binder call — it can still reject the surface itself (e.g.
     * SURFACE_BUSY) in a reply that arrives after the call already returned.
     * [onError] fires once if that happens; capped so a caller that never sees
     * an error can't leak an entry per call forever.
     */
    internal fun watchForSurfaceError(requestId: String, onError: (String) -> Unit) {
        if (pendingSurfaceErrors.size > 32) pendingSurfaceErrors.clear()
        pendingSurfaceErrors[requestId] = onError
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) {
        if (closed) return
        if (path == BusPaths.ERROR) {
            if (!rememberEvent(id)) return
            val handler = pendingSurfaceErrors.remove(payload.optString("forId"))
            if (handler != null) {
                handler(payload.optString("code"))
            } else if (isApproved) {
                callbacks.onMessage(path, id, payload)
            }
            return
        }
        if (path == BusPaths.TTS_STARTED || path == BusPaths.TTS_DONE) {
            if (!rememberEvent(id)) return
            if (!routeTtsMessage(path, payload) && isApproved) {
                callbacks.onMessage(path, id, payload)
            }
            return
        }
        if (payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (path == RenderedMapContract.PATH) {
            if (supportsRenderedMaps) {
                if (!renderedMaps.receive(payload)) renderedMaps.clear()
                callbacks.onRenderedMapsChanged()
            }
            return
        }
        if (path == BusPaths.PAGE_CLOSED || (path == BusPaths.PAGE_VISIBILITY && !payload.optBoolean("visible"))) {
            renderedMaps.clear(RenderedMapContract.PAGE)
            callbacks.onRenderedMapsChanged()
        }
        if (routeSkillMessage(path, payload)) return
        if (routeSnapshotMessage(path, id, payload)) return
        if (routeSpeechMessage(path, payload)) return
        if (routeAudioMessage(path, payload)) return
        if (path == BusPaths.INK_EVENT) {
            routeInkMessage(payload)
            return
        }
        if (path == BusPaths.HUB_CAPABILITIES) {
            if (isApproved) pages.onCapabilities(payload)
            return
        }
        if (routePageMessage(path, payload)) return
        if (path == BusPaths.NOTICE_INPUT) {
            if (isApproved && !noticeHidePending && acceptsNoticeCallback(payload)) {
                callbacks.onNoticeInput(
                    NexusInputEvent(
                        surfaceId = NoticeSurfaceContract.LOCAL_SURFACE_ID,
                        keyCode = payload.optInt("keyCode"),
                        action = payload.optInt("action"),
                    ),
                )
            }
            return
        }
        if (path == BusPaths.NOTICE_ACTION) {
            val noticeId = payload.optString("noticeId")
            val actionId = payload.optString("id")
            if (
                isApproved &&
                !noticeHidePending &&
                acceptsNoticeCallback(payload) &&
                noticeId == "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}" &&
                actionId.isNotBlank()
            ) {
                callbacks.onNoticeAction(actionId)
            }
            return
        }
        if (path == BusPaths.SURFACE_TEXT_COMMITTED) {
            if (isApproved) {
                EditableSurfaceContract.parseCommitted(payload)?.let { committed ->
                    callbacks.onSurfaceTextCommitted(
                        committed.surfaceId,
                        committed.text,
                        committed.cancelled,
                    )
                }
            }
            return
        }
        if (path == BusPaths.NOTICE_CLOSED) {
            if (isApproved && acceptsNoticeCallback(payload)) {
                NexusNoticeCloseReason.fromWire(payload.optString("reason"))?.let { reason ->
                    clearNoticeContext()
                    callbacks.onNoticeClosed(reason)
                }
            }
            return
        }
        if (path == BusPaths.ACTIVITY_ACTION) {
            if (ActivitySurfaceContract.isCommandPayload(payload)) {
                routeActivityCommand(payload)
                return
            }
            val activityId = payload.optString("activityId")
            val actionId = payload.optString("id")
            if (
                isApproved &&
                activityId == "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}" &&
                actionId.isNotBlank()
            ) {
                callbacks.onActivityAction(actionId)
            }
            return
        }
        if (path == BusPaths.ACTIVITY_CLOSED) {
            renderedMaps.clear(RenderedMapContract.AMBIENT)
            callbacks.onRenderedMapsChanged()
            val activityId = payload.optString("activityId")
            val reason = payload.optString("reason")
            if (
                isApproved &&
                activityId == "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}" &&
                reason.isNotBlank()
            ) {
                callbacks.onActivityClosed(reason)
            }
            return
        }
        when (path) {
            // A duplicate PLUGIN_OPEN (fresh event id) is the hub asking an already-open
            // plugin to re-present itself — e.g. the glasses fell back to the launcher
            // while the hub still considers the session open. onOpen implementations
            // reset and re-show, which also acknowledges the hub's open watchdog.
            BusPaths.PLUGIN_OPEN -> if (isApproved) {
                opened = true
                backgrounded = false
                callbacks.onOpen(payload.optString("type").ifBlank { PluginOpenTypes.OPEN })
            }
            BusPaths.PLUGIN_CLOSE -> {
                val closeType = payload.optString("type")
                if (closeType == PluginCloseTypes.BACKGROUND) {
                    if (opened) {
                        opened = false
                        backgrounded = true
                        releaseSpeechSession()
                        releaseTtsSession()
                        releaseSnapshotSession()
                        callbacks.onBackground()
                    }
                } else {
                    if (opened || backgrounded) {
                        opened = false
                        backgrounded = false
                        releaseAudioSession()
                        releaseSpeechSession()
                        releaseTtsSession()
                        releaseSnapshotSession()
                        callbacks.onClose()
                    }
                }
            }
            BusPaths.PLUGIN_INPUT -> if (opened && isApproved) {
                callbacks.onInput(
                    NexusInputEvent(
                        surfaceId = payload.optString("localSurfaceId", payload.optString("surfaceId")),
                        keyCode = payload.optInt("keyCode"),
                        action = payload.optInt("action"),
                    ),
                )
            }
            BusPaths.PLUGIN_REGISTRATION -> {
                renderedMaps.clear()
                callbacks.onRenderedMapsChanged()
                // Metadata follows synchronous approval, whose callback may already
                // have shown a notice, including after a reconnect.
                if (!noticeApprovalAwaitingMetadata) clearNoticeContext()
                noticeApprovalAwaitingMetadata = false
                noticeInteractionVersion = payload.optInt("noticeInteractionVersion", 0)
                skillsVersion = payload.optInt(SkillsContract.REGISTRATION_FIELD, 0)
                // A fresh registration means the hub has no open session with us (it just
                // (re)accepted this client), so a stale `opened` from a previous hub life
                // must not swallow the next PLUGIN_OPEN.
                if (opened || backgrounded) {
                    opened = false
                    backgrounded = false
                    releaseAudioSession()
                    releaseSpeechSession()
                    releaseTtsSession()
                    releaseSnapshotSession()
                    callbacks.onClose()
                }
                val result = payload.optInt("result", PluginRegistrationResult.REGISTRATION_FAILED)
                val parsed = PluginCapability.parseList(payload.optString("capabilities"))
                approvedCapabilities = if (parsed is CapabilityParseResult.Valid) {
                    parsed.capabilities
                } else {
                    emptySet()
                }
                applyRegistrationState(result)
            }
            BusPaths.ASSISTANT_TAKEOVER_REPLY -> if (isApproved) {
                AssistantTakeoverContract.replyEnabled(payload)?.let(callbacks::onAssistantTakeover)
            }
            else -> if (isApproved) callbacks.onMessage(path, id, payload)
        }
    }

    /**
     * Asks the phone hub which way the assist button goes right now; the answer arrives on
     * [NexusPluginCallbacks.onAssistantTakeover], a rejection on
     * [NexusPluginCallbacks.onAssistantTakeoverError]. Needs the `assistant` grant.
     */
    fun requestAssistantTakeover(): NexusSdkResult =
        sendAssistantTakeover(AssistantTakeoverContract.statusRequest())

    /**
     * Hands the assist button to the approved assistant plugin (`true`) or back to Rokid's own
     * (`false`). The hub confirms the new position on [NexusPluginCallbacks.onAssistantTakeover].
     * This never touches the grant itself: a plugin can step back and step in again, but only
     * the wearer, on the phone, decides whether it may replace the assistant at all.
     */
    fun setAssistantTakeover(enabled: Boolean): NexusSdkResult =
        sendAssistantTakeover(AssistantTakeoverContract.setRequest(enabled))

    private fun sendAssistantTakeover(payload: JSONObject): NexusSdkResult {
        if (!isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!hasCapability(PluginCapability.ASSISTANT)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        val id = UUID.randomUUID().toString()
        watchForSurfaceError(id) { code -> callbacks.onAssistantTakeoverError(code) }
        return if (send(BusPaths.ASSISTANT_TAKEOVER_REQUEST, id, payload)) {
            NexusSdkResult.SENT
        } else {
            pendingSurfaceErrors.remove(id)
            NexusSdkResult.NOT_REGISTERED
        }
    }

    override fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        if (closed || !isApproved || payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (routeSnapshotBinary(path, id, payload, data)) return
        if (routeSpeechBinary(path)) return
        if (routeAudioBinary(path, payload, data)) return
        callbacks.onBinary(path, id, payload, data)
    }

    override fun onError(message: String) = Unit

    override fun close() {
        if (closed) return
        renderedMaps.clear()
        closed = true
        clearNoticeContext()
        terminateAudioSession(
            reason = NexusAudioStopReason.ERROR,
            releaseActiveLease = false,
        )
        terminateSpeechSession(
            reason = NexusSpeechStopReason.ERROR,
            stopActiveSession = false,
        )
        terminateTtsSession(
            reason = NexusTtsDoneReason.UNAVAILABLE,
            stopCurrent = false,
        )
        terminateSnapshotSession(NexusSnapshotError.ERROR)
        cancelLiveInvocations()
        pages.onRegistrationReset()
        if (opened || backgrounded) {
            opened = false
            backgrounded = false
            callbacks.onClose()
        }
        transport.close()
        currentLinkState = 0
        hubCapabilities = 0
        seenEventIds.clear()
        seenEventIdSet.clear()
    }

    /**
     * A launcher-bundle command: delivered once per invocation to this plugin's own current
     * activity, and answered with what the callback returns. Null sends nothing, so the glasses
     * show the command unconfirmed; a callback that throws is answered as rejected.
     */
    private fun routeActivityCommand(payload: JSONObject) {
        if (!isApproved || !hasCapability(PluginCapability.SURFACES)) return
        val parsed = ActivitySurfaceContract.parseCommand(payload) ?: return
        if (parsed.activityId != ownActivityId) return
        if (!rememberOnce(seenActivityInvocations, parsed.invocationId)) return
        val command = NexusActivityCommand(
            activityId = parsed.activityId,
            actionId = parsed.actionId,
            invocationId = parsed.invocationId,
            seq = parsed.seq,
            typed = parsed.typed?.toNexus(),
        )
        val result = runCatching { callbacks.onActivityCommand(command) }
            .getOrElse { NexusActivityCommandResult.rejected() } ?: return
        if (!rememberOnce(answeredActivityCommands, command.invocationId)) return
        transport.sendImmediate(BusPaths.ACTIVITY_RESULT, UUID.randomUUID().toString(), activityResultPayload(command, result))
    }

    /** Page callbacks need no open: approval, the `surfaces` grant, announced support and a usable link do. */
    private fun routePageMessage(path: String, payload: JSONObject): Boolean {
        val handler: (JSONObject, NexusPluginCallbacks) -> Unit = when (path) {
            BusPaths.PAGE_REQUEST -> pages::onRequest
            BusPaths.PAGE_ACTION -> pages::onAction
            BusPaths.PAGE_VISIBILITY -> pages::onVisibility
            BusPaths.PAGE_CLOSED -> pages::onClosed
            BusPaths.PAGE_RESPONSE, BusPaths.PAGE_RESULT -> return true
            else -> return false
        }
        if (supportsPageSession) handler(payload, callbacks)
        return true
    }

    private fun routeAudioMessage(path: String, payload: JSONObject): Boolean {
        if (path != NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH &&
            path != NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH &&
            path != NEXUS_AUDIO_LEASE_REVOKED_PATH
        ) {
            return false
        }
        val (session, consume) = synchronized(audioSessionLock) {
            registeredAudioSession to audioSessionApiUsed
        }
        when (path) {
            NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH -> session?.onAcquireReply(payload)
            NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH -> session?.onReleaseReply(payload)
            NEXUS_AUDIO_LEASE_REVOKED_PATH -> session?.onRevoked(payload)
        }
        return consume
    }

    private fun routeSnapshotMessage(path: String, id: String, payload: JSONObject): Boolean {
        if (path != NEXUS_SNAPSHOT_ERROR_PATH) return false
        val (session, consume) = synchronized(snapshotSessionLock) {
            registeredSnapshotSession to snapshotSessionApiUsed
        }
        session?.onSnapshotError(id, payload)
        return consume
    }

    private fun routeSnapshotBinary(
        path: String,
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ): Boolean {
        if (path != NEXUS_SNAPSHOT_RESULT_PATH) return false
        val (session, consume) = synchronized(snapshotSessionLock) {
            registeredSnapshotSession to snapshotSessionApiUsed
        }
        session?.onSnapshotResult(id, payload, data)
        return consume
    }

    private fun routeSpeechMessage(path: String, payload: JSONObject): Boolean {
        if (!isSpeechPath(path)) return false
        val (session, consume) = synchronized(speechSessionLock) {
            registeredSpeechSession to speechSessionApiUsed
        }
        when (path) {
            NEXUS_STT_SESSION_START_REPLY_PATH -> session?.onStartReply(payload)
            NEXUS_STT_SESSION_STOP_REPLY_PATH -> session?.onStopReply(payload)
            NEXUS_STT_STATE_PATH -> session?.onState(payload)
            NEXUS_STT_PARTIAL_PATH -> session?.onPartial(payload)
            NEXUS_STT_FINAL_PATH -> session?.onFinal(payload)
            NEXUS_STT_SESSION_ENDED_PATH -> session?.onEnded(payload)
        }
        return consume
    }

    private fun routeTtsMessage(path: String, payload: JSONObject): Boolean {
        if (path != BusPaths.TTS_STARTED && path != BusPaths.TTS_DONE) return false
        val (session, consume) = synchronized(ttsSessionLock) {
            registeredTtsSession to ttsSessionApiUsed
        }
        when (path) {
            BusPaths.TTS_STARTED -> session?.onStarted(payload)
            BusPaths.TTS_DONE -> session?.onDone(payload)
        }
        return consume
    }

    private fun routeInkMessage(payload: JSONObject) {
        if (!isApproved) return
        val surfaceId = payload.optString("surfaceId")
        if (surfaceId.isBlank()) return
        when (payload.optString("type")) {
            InkSurfaceContract.EVENT_READY -> callbacks.onInkReady(surfaceId)
            InkSurfaceContract.EVENT_ACTION -> {
                val actionId = payload.optString("actionId")
                if (actionId.isNotBlank()) {
                    callbacks.onInkAction(
                        surfaceId,
                        actionId,
                        payload.optJSONObject("dataset")?.let { JSONObject(it.toString()) } ?: JSONObject(),
                    )
                }
            }
            InkSurfaceContract.EVENT_CLOSED ->
                NexusInkCloseReason.fromWire(payload.optString("reason"))
                    ?.let { reason -> callbacks.onInkClosed(surfaceId, reason) }
            InkSurfaceContract.EVENT_ERROR -> {
                val array = payload.optJSONArray("problems") ?: return
                val problems = buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let(NexusInkProblem::fromJson)?.let(::add)
                    }
                }
                if (problems.isNotEmpty()) callbacks.onInkError(surfaceId, problems)
            }
        }
    }

    private fun routeSpeechBinary(path: String): Boolean {
        if (!isSpeechPath(path)) return false
        return synchronized(speechSessionLock) { speechSessionApiUsed }
    }

    private fun routeAudioBinary(path: String, payload: JSONObject, data: ByteArray): Boolean {
        if (path != NEXUS_AUDIO_FRAMES_PATH) return false
        val (session, consume) = synchronized(audioSessionLock) {
            registeredAudioSession to audioSessionApiUsed
        }
        session?.onAudioFrame(payload, data)
        return consume
    }

    private fun currentAudioSession(): NexusAudioSession? =
        synchronized(audioSessionLock) { registeredAudioSession }

    private fun currentSpeechSession(): NexusSpeechSession? =
        synchronized(speechSessionLock) { registeredSpeechSession }

    private fun currentTtsSession(): NexusTtsSession? =
        synchronized(ttsSessionLock) { registeredTtsSession }
    private fun currentSnapshotSession(): NexusSnapshotSession? =
        synchronized(snapshotSessionLock) { registeredSnapshotSession }

    private fun terminateAudioSession(
        reason: NexusAudioStopReason,
        releaseActiveLease: Boolean,
    ) {
        currentAudioSession()?.terminate(reason, releaseActiveLease)
    }

    private fun terminateSpeechSession(
        reason: NexusSpeechStopReason,
        stopActiveSession: Boolean,
    ) {
        currentSpeechSession()?.terminate(reason, stopActiveSession)
    }

    private fun terminateTtsSession(
        reason: NexusTtsDoneReason,
        stopCurrent: Boolean,
    ) {
        currentTtsSession()?.terminate(reason, stopCurrent)
    }

    private fun terminateSnapshotSession(error: NexusSnapshotError) {
        currentSnapshotSession()?.terminate(error)
    }

    private fun isSpeechPath(path: String): Boolean =
        path == "/stt" || path.startsWith("/stt/")

    private fun rememberEvent(id: String): Boolean {
        if (id.isBlank() || !seenEventIdSet.add(id)) return false
        seenEventIds += id
        while (seenEventIds.size > MAX_SEEN_EVENTS) {
            seenEventIdSet.remove(seenEventIds.removeFirst())
        }
        return true
    }

    private fun canonicalNoticePath(path: String): String = when (val normalized = PathRules.normalizeAbsolute(path)) {
        BusPaths.NOTICE_SHOW, BusPaths.NOTICE_UPDATE, BusPaths.NOTICE_HIDE -> normalized
        else -> path
    }

    private fun supportsNoticePatch(path: String, payload: JSONObject): Boolean =
        path != BusPaths.NOTICE_UPDATE || payload.opt("rearm") != false || noticeInteractionVersion >= 1

    private fun prepareNoticePayload(path: String, payload: JSONObject): JSONObject {
        if (path != BusPaths.NOTICE_SHOW && path != BusPaths.NOTICE_UPDATE && path != BusPaths.NOTICE_HIDE) {
            return payload
        }
        val outgoing = JSONObject(payload.toString())
        if (path == BusPaths.NOTICE_HIDE) {
            outgoing.remove(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)
            currentNoticeToken?.let { outgoing.put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, it) }
            return outgoing
        }
        val freshQuestion = path == BusPaths.NOTICE_SHOW ||
            ((outgoing.has("interactive") || outgoing.has("actions")) && outgoing.opt("rearm") != false)
        if (freshQuestion || currentNoticeToken == null) {
            currentNoticeToken = UUID.randomUUID().toString()
            noticeHidePending = false
        }
        return outgoing.put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, currentNoticeToken)
    }

    private fun acceptsNoticeCallback(payload: JSONObject): Boolean {
        val token = NoticeSurfaceContract.clientToken(payload)
        if (payload.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN) || noticeInteractionVersion >= 1) {
            return payload.optString("noticeId") == "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}" &&
                token != null && token == currentNoticeToken
        }
        // Older hubs do not echo correlations; their callbacks retain the legacy contract.
        return true
    }

    private fun clearNoticeContext() {
        currentNoticeToken = null
        noticeHidePending = false
    }

    private fun noticePreflight(): NexusSdkResult? = when {
        !isApproved -> NexusSdkResult.NOT_REGISTERED
        !hasCapability(PluginCapability.SURFACES) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        !supportsNoticeSurface -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> null
    }

    private fun pinPreflight(): NexusSdkResult? = when {
        !isApproved -> NexusSdkResult.NOT_REGISTERED
        !hasCapability(PluginCapability.SURFACES) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        !supportsPinSurface -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> null
    }

    private fun activityPreflight(): NexusSdkResult? = when {
        !isApproved -> NexusSdkResult.NOT_REGISTERED
        !hasCapability(PluginCapability.SURFACES) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        !supportsActivitySurface -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> null
    }

    companion object {
        private const val MAX_SEEN_EVENTS = 128
        private const val MAX_LIVE_PROVIDER_INVOCATIONS = 2 * SkillLimits.MAX_LIVE_INVOCATIONS

        /** Stands in for the hub's invocation id on a result the SDK builds from a route rejection. */
        const val LOCAL_INVOCATION_ID = "local-rejected"

        /** A transport that can carry a page: CXR control or the SPP data plane, not merely a bond. */
        private fun hasUsableLink(state: Int): Boolean =
            state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0

        fun create(
            context: Context,
            pluginId: String,
            callbacks: NexusPluginCallbacks,
            hubTarget: HubTarget = HubTarget.PHONE,
        ): NexusPluginClient = NexusPluginClient(
            pluginId = pluginId,
            callbacks = callbacks,
            transport = AndroidNexusPluginTransport(context, pluginId, hubTarget),
        )
    }
}
