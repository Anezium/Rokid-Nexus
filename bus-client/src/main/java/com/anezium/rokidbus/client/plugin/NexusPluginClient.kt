package com.anezium.rokidbus.client.plugin

import android.content.Context
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.InkSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceValidationResult
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.CapabilityParseResult
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class NexusPluginClient internal constructor(
    private val pluginId: String,
    private val callbacks: NexusPluginCallbacks,
    private val transport: NexusPluginTransport,
) : NexusPluginTransport.Listener, AutoCloseable {
    private val seenEventIds = ArrayDeque<String>()
    private val seenEventIdSet = linkedSetOf<String>()
    private val audioSessionLock = Any()
    private val speechSessionLock = Any()
    private val ttsSessionLock = Any()
    private val snapshotSessionLock = Any()
    private var registrationState = PluginRegistrationResult.REGISTRATION_FAILED
    private var opened = false
    private var closed = false
    private var approvedCapabilities: Set<PluginCapability> = emptySet()
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

    val isApproved: Boolean
        get() = registrationState == PluginRegistrationResult.APPROVED

    fun hasCapability(capability: PluginCapability): Boolean =
        isApproved && capability in approvedCapabilities

    val supportsImageSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.IMAGE_SURFACE != 0

    val supportsInkSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.INK_SURFACE != 0

    /**
     * Whether these glasses can show a pin — not whether one would appear this instant.
     * Unlike [supportsImageSurface] this ignores the link: a pin pushed while the glasses
     * are asleep is held by the hub and delivered when they come back, so refusing here
     * would strand exactly the background plugins pins exist for.
     */
    val supportsPinSurface: Boolean
        get() = hubCapabilities and BusCapabilityBits.PIN_SURFACE != 0

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

    /** Whether the current glasses can host a notice editor backed by the phone keyboard. */
    val supportsNoticeTextInput: Boolean
        get() = supportsNoticeSurface &&
            hubCapabilities and BusCapabilityBits.NOTICE_TEXT_INPUT != 0

    fun showNotice(notice: NexusNotice): NexusSdkResult {
        noticePreflight()?.let { return it }
        if (notice.textInput != null && !supportsNoticeTextInput) {
            return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        if (notice.image != null) return NexusSdkResult.INVALID_PAYLOAD
        val payload = notice.toPayload()
        if (NoticeSurfaceContract.validateShow(payload) !is NoticeSurfaceValidationResult.Valid) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.NOTICE_SHOW, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun showNotice(notice: NexusNotice, imageBytes: ByteArray): NexusSdkResult {
        noticePreflight()?.let { return it }
        if (notice.textInput != null && !supportsNoticeTextInput) {
            return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        if (!supportsImageSurface) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (notice.image == null) return NexusSdkResult.INVALID_PAYLOAD
        val payload = notice.toPayload(imageBytes)
        if (
            NoticeSurfaceContract.validateShow(payload, imageBytes) !is
            NoticeSurfaceValidationResult.Valid
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (
            sendBinary(BusPaths.NOTICE_SHOW, UUID.randomUUID().toString(), payload, imageBytes)
        ) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
    }

    fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult {
        noticePreflight()?.let { return it }
        if (update.textInput != null && !supportsNoticeTextInput) {
            return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        return if (send(BusPaths.NOTICE_UPDATE, UUID.randomUUID().toString(), update.toPayload())) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun hideNotice(): NexusSdkResult {
        noticePreflight()?.let { return it }
        return if (
            send(
                BusPaths.NOTICE_HIDE,
                UUID.randomUUID().toString(),
                JSONObject().put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID),
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

    /** Whether the current glasses/link announced TTS protocol v1. */
    val supportsTts: Boolean
        get() = currentLinkState and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0 &&
            hubCapabilities and BusCapabilityBits.TTS != 0

    fun startActivity(activity: NexusActivity): NexusSdkResult {
        activityPreflight()?.let { return it }
        val payload = activity.toStartPayload()
        if (
            ActivitySurfaceContract.validateStart(payload) !is
            ActivitySurfaceValidationResult.Valid
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.ACTIVITY_START, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun updateActivity(
        activity: NexusActivity,
        significant: Boolean = false,
    ): NexusSdkResult {
        activityPreflight()?.let { return it }
        val payload = activity.toUpdatePayload(significant)
        if (
            ActivitySurfaceContract.validateUpdate(payload) !is
            ActivitySurfacePatchResult.Valid
        ) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.ACTIVITY_UPDATE, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun endActivity(): NexusSdkResult {
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
        return transport.send(path, id, payload)
    }

    internal fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
        if (closed || !isApproved) return false
        val sent = transport.sendBinary(path, id, payload, data)
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
            approvedCapabilities = emptySet()
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
        if (result != PluginRegistrationResult.APPROVED && opened) {
            opened = false
            callbacks.onClose()
        }
    }

    override fun onLinkState(state: Int) {
        if (closed) return
        currentLinkState = state
        hubCapabilities = transport.capabilities()
        callbacks.onLinkState(state)
    }

    override fun onGlassesAiButton(active: Boolean) {
        if (closed) return
        callbacks.onGlassesAiButton(active)
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) {
        if (closed) return
        if (path == BusPaths.TTS_STARTED || path == BusPaths.TTS_DONE) {
            if (!rememberEvent(id)) return
            if (!routeTtsMessage(path, payload) && isApproved) {
                callbacks.onMessage(path, id, payload)
            }
            return
        }
        if (payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (routeSnapshotMessage(path, id, payload)) return
        if (routeSpeechMessage(path, payload)) return
        if (routeAudioMessage(path, payload)) return
        if (path == BusPaths.INK_EVENT) {
            routeInkMessage(payload)
            return
        }
        if (path == BusPaths.NOTICE_INPUT) {
            if (isApproved) {
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
                noticeId == "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}" &&
                actionId.isNotBlank()
            ) {
                callbacks.onNoticeAction(actionId)
            }
            return
        }
        if (path == BusPaths.NOTICE_TEXT_SUBMIT) {
            val submission = NoticeSurfaceContract.parseTextSubmission(payload)
            if (
                isApproved &&
                submission?.surfaceId == "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
            ) {
                callbacks.onNoticeTextSubmitted(submission.inputId, submission.text)
            }
            return
        }
        if (path == BusPaths.NOTICE_CLOSED) {
            NexusNoticeCloseReason.fromWire(payload.optString("reason"))
                ?.let(callbacks::onNoticeClosed)
            return
        }
        if (path == BusPaths.ACTIVITY_ACTION) {
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
                callbacks.onOpen()
            }
            BusPaths.PLUGIN_CLOSE -> if (opened) {
                opened = false
                releaseAudioSession()
                releaseSpeechSession()
                releaseTtsSession()
                releaseSnapshotSession()
                callbacks.onClose()
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
                // A fresh registration means the hub has no open session with us (it just
                // (re)accepted this client), so a stale `opened` from a previous hub life
                // must not swallow the next PLUGIN_OPEN.
                if (opened) {
                    opened = false
                    callbacks.onClose()
                }
                val result = payload.optInt("result", PluginRegistrationResult.REGISTRATION_FAILED)
                val parsed = PluginCapability.parseList(payload.optString("capabilities"))
                approvedCapabilities = if (parsed is CapabilityParseResult.Valid) {
                    parsed.capabilities
                } else {
                    emptySet()
                }
                onRegistrationState(result)
            }
            else -> if (isApproved) callbacks.onMessage(path, id, payload)
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
        closed = true
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
        if (opened) {
            opened = false
            callbacks.onClose()
        }
        transport.close()
        currentLinkState = 0
        hubCapabilities = 0
        seenEventIds.clear()
        seenEventIdSet.clear()
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
