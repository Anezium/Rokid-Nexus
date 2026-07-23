package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class NexusAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val encoding: String,
)

enum class NexusAudioStopReason {
    RELEASED,
    REVOKED,
    DENIED_BUSY,
    DENIED_NO_LINK,
    DENIED_START_FAILED,
    DENIED_NOT_GRANTED,
    ERROR,
}

interface NexusAudioCallbacks {
    fun onAudioStarted(format: NexusAudioFormat)
    fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long)
    fun onAudioStopped(reason: NexusAudioStopReason)
}

class NexusAudioSession internal constructor(
    private val client: NexusPluginClient,
    private val callbacks: NexusAudioCallbacks,
) {
    private enum class State {
        IDLE,
        PENDING,
        ACTIVE,
    }

    private val stateLock = Any()
    private var state = State.IDLE
    private var leaseId: String? = null

    val isActive: Boolean
        get() = synchronized(stateLock) { state == State.ACTIVE }

    fun start(): NexusSdkResult {
        return synchronized(stateLock) {
            if (!client.isApprovedForAudio()) return@synchronized NexusSdkResult.NOT_REGISTERED
            if (!client.hasCapability(PluginCapability.MICROPHONE)) {
                return@synchronized NexusSdkResult.CAPABILITY_NOT_GRANTED
            }
            if (state != State.IDLE) return@synchronized NexusSdkResult.SENT
            if (!client.registerAudioSession(this)) {
                return@synchronized NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            }

            val requestId = UUID.randomUUID().toString()
            state = State.PENDING
            if (client.sendAudioAcquire(this, requestId)) {
                NexusSdkResult.SENT
            } else {
                clearState()
                client.unregisterAudioSession(this)
                NexusSdkResult.NOT_REGISTERED
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            if (state != State.ACTIVE) return
            val currentLeaseId = leaseId ?: return
            val requestId = UUID.randomUUID().toString()
            client.sendAudioRelease(this, requestId, currentLeaseId)
            if (state == State.ACTIVE && leaseId == currentLeaseId) {
                finish(NexusAudioStopReason.RELEASED)
            }
        }
    }

    internal fun onAcquireReply(payload: JSONObject) {
        synchronized(stateLock) {
            if (state != State.PENDING) return
            if (!payload.optBoolean("granted", false)) {
                finish(mapDeniedReason(payload.optString("reason")))
                return
            }

            val grantedLeaseId = payload.optString("leaseId")
            val format = NexusAudioFormat(
                sampleRate = payload.optInt("sampleRate"),
                channels = payload.optInt("channels"),
                encoding = payload.optString("encoding"),
            )
            if (grantedLeaseId.isBlank() ||
                format.sampleRate <= 0 ||
                format.channels <= 0 ||
                format.encoding.isBlank()
            ) {
                finish(NexusAudioStopReason.ERROR)
                return
            }

            leaseId = grantedLeaseId
            state = State.ACTIVE
            callbacks.onAudioStarted(format)
        }
    }

    internal fun onAudioFrame(payload: JSONObject, data: ByteArray) {
        synchronized(stateLock) {
            if (state != State.ACTIVE || payload.optString("leaseId") != leaseId) return
            callbacks.onAudioFrame(
                pcm = data,
                seq = payload.optLong("seq"),
                elapsedRealtimeMs = payload.optLong("elapsedRealtime"),
            )
        }
    }

    internal fun onReleaseReply(payload: JSONObject) {
        synchronized(stateLock) {
            if (state != State.ACTIVE) return
            if (payload.optBoolean("released", false)) {
                finish(NexusAudioStopReason.RELEASED)
            } else {
                finish(NexusAudioStopReason.ERROR)
            }
        }
    }

    internal fun onRevoked(payload: JSONObject) {
        synchronized(stateLock) {
            if (state != State.ACTIVE || payload.optString("leaseId") != leaseId) return
            finish(NexusAudioStopReason.REVOKED)
        }
    }

    internal fun terminate(reason: NexusAudioStopReason, releaseActiveLease: Boolean) {
        synchronized(stateLock) {
            if (state == State.IDLE) {
                client.unregisterAudioSession(this)
                return
            }
            if (releaseActiveLease && state == State.ACTIVE) {
                leaseId?.let { currentLeaseId ->
                    client.sendAudioRelease(this, UUID.randomUUID().toString(), currentLeaseId)
                }
            }
            if (state != State.IDLE) finish(reason)
        }
    }

    private fun finish(reason: NexusAudioStopReason) {
        if (state == State.IDLE) return
        clearState()
        client.unregisterAudioSession(this)
        callbacks.onAudioStopped(reason)
    }

    private fun clearState() {
        state = State.IDLE
        leaseId = null
    }

    private fun mapDeniedReason(reason: String): NexusAudioStopReason = when (
        reason.trim().uppercase(Locale.US)
    ) {
        "BUSY" -> NexusAudioStopReason.DENIED_BUSY
        "NO_CXR" -> NexusAudioStopReason.DENIED_NO_LINK
        "START_FAILED" -> NexusAudioStopReason.DENIED_START_FAILED
        else -> NexusAudioStopReason.ERROR
    }
}

fun NexusPluginClient.audioSession(callbacks: NexusAudioCallbacks): NexusAudioSession =
    NexusAudioSession(this, callbacks)

internal const val NEXUS_AUDIO_LEASE_ACQUIRE_PATH = "/audio/lease/acquire"
internal const val NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH = "/audio/lease/acquire/reply"
internal const val NEXUS_AUDIO_FRAMES_PATH = "/audio/frames"
internal const val NEXUS_AUDIO_LEASE_RELEASE_PATH = "/audio/lease/release"
internal const val NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH = "/audio/lease/release/reply"
internal const val NEXUS_AUDIO_LEASE_REVOKED_PATH = "/audio/lease/revoked"
