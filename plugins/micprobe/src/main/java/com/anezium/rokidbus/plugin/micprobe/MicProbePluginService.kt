package com.anezium.rokidbus.plugin.micprobe

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class MicProbePluginService : NexusPluginService() {
    private data class Capture(
        val leaseId: String,
        val startedAtMs: Long,
        val pcm: FileOutputStream,
        var frames: Long = 0L,
        var bytes: Long = 0L,
        var gaps: Long = 0L,
        var firstSeq: Long? = null,
        var lastSeq: Long? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private var registrationApproved = false
    private var captureRequested = false
    private var acquireRequestId: String? = null
    private var releaseRequestId: String? = null
    private var capture: Capture? = null
    private var registrationTimeout: Runnable? = null
    private var acquireTimeout: Runnable? = null
    private var captureTimeout: Runnable? = null
    private var releaseTimeout: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) requestCapture()
        return START_NOT_STICKY
    }

    override fun onNexusOpen() {
        status("Unexpected glasses open; this diagnostic is settings-only")
    }

    override fun onNexusClose() = Unit

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusLinkState(state: Int) {
        status("Hub linkState=$state")
    }

    override fun onNexusRegistrationState(result: Int) {
        registrationApproved = result == PluginRegistrationResult.APPROVED
        status("Plugin registration: ${registrationLabel(result)}")
        if (registrationApproved) {
            if (captureRequested) acquireLease()
        } else {
            val reason = "plugin registration ${registrationLabel(result)}"
            when {
                capture != null -> finishCapture(reason = reason, release = false)
                captureRequested || acquireRequestId != null -> failBeforeGrant(reason)
                releaseRequestId != null -> {
                    releaseRequestId = null
                    releaseTimeout?.let(main::removeCallbacks)
                    releaseTimeout = null
                    status("Audio lease release interrupted: $reason")
                    stopSelf()
                }
            }
        }
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        when (path) {
            PATH_ACQUIRE_REPLY -> handleAcquireReply(id, payload)
            PATH_RELEASE_REPLY -> handleReleaseReply(id, payload)
            PATH_REVOKED -> handleRevocation(payload)
            else -> status("Ignoring JSON path=$path id=${id.take(8)}")
        }
    }

    override fun onNexusBinaryMessage(
        path: String,
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ) {
        if (path != PATH_FRAMES) {
            status("Ignoring binary path=$path id=${id.take(8)} bytes=${data.size}")
            return
        }
        val current = capture ?: return
        if (payload.optString("leaseId") != current.leaseId) return

        val seq = if (payload.has("seq")) payload.optLong("seq") else null
        if (seq == null) {
            current.gaps += 1L
        } else {
            val previous = current.lastSeq
            if (previous == null) {
                current.firstSeq = seq
            } else {
                val expected = previous + 1L
                if (seq != expected) {
                    current.gaps += if (seq > expected) seq - expected else 1L
                }
            }
            current.lastSeq = seq
        }
        current.frames += 1L
        current.bytes += data.size.toLong()
        runCatching { current.pcm.write(data) }
            .onFailure { failure ->
                finishCapture(
                    reason = "PCM write failed: ${failure.message ?: failure.javaClass.simpleName}",
                    release = true,
                )
            }
    }

    override fun onDestroy() {
        removeAllCallbacks()
        capture?.let { current ->
            runCatching { current.pcm.close() }
            nexusClient?.send(
                PATH_RELEASE,
                UUID.randomUUID().toString(),
                JSONObject().put("leaseId", current.leaseId),
            )
            writeSummary(
                granted = true,
                reason = "service destroyed",
                current = current,
                durationMs = SystemClock.elapsedRealtime() - current.startedAtMs,
            )
            capture = null
        }
        super.onDestroy()
    }

    private fun requestCapture() {
        if (captureRequested || acquireRequestId != null || capture != null || releaseRequestId != null) {
            status("Capture already in progress")
            return
        }
        if (!truncatePcm()) {
            val reason = "could not truncate PCM file"
            status("Capture failed: $reason")
            writeSummary(granted = false, reason = reason)
            stopSelf()
            return
        }
        captureRequested = true
        status("Waiting for approved phone-hub registration")
        if (registrationApproved || nexusClient?.isApproved == true) {
            registrationApproved = true
            acquireLease()
            return
        }
        registrationTimeout = Runnable {
            if (!captureRequested) return@Runnable
            captureRequested = false
            status("Hub not connected: plugin registration timed out")
            writeSummary(granted = false, reason = "hub not connected")
            stopSelf()
        }.also { main.postDelayed(it, REGISTRATION_TIMEOUT_MS) }
    }

    private fun acquireLease() {
        captureRequested = false
        registrationTimeout?.let(main::removeCallbacks)
        registrationTimeout = null
        val requestId = UUID.randomUUID().toString()
        acquireRequestId = requestId
        status("Sending $PATH_ACQUIRE")
        val accepted = nexusClient?.send(PATH_ACQUIRE, requestId, JSONObject()) == true
        if (!accepted) {
            acquireRequestId = null
            failBeforeGrant("hub not connected or plugin not approved")
            return
        }
        acquireTimeout = Runnable {
            if (acquireRequestId != requestId) return@Runnable
            acquireRequestId = null
            failBeforeGrant("audio lease acquire timed out")
        }.also { main.postDelayed(it, REQUEST_TIMEOUT_MS) }
    }

    private fun handleAcquireReply(id: String, payload: JSONObject) {
        if (id != acquireRequestId) {
            status("Ignoring stale acquire reply id=${id.take(8)}")
            return
        }
        acquireRequestId = null
        acquireTimeout?.let(main::removeCallbacks)
        acquireTimeout = null
        if (!payload.optBoolean("granted", false)) {
            val reason = payload.optString("reason", "unspecified")
            status("Audio lease denied: $reason")
            writeSummary(granted = false, reason = reason)
            stopSelf()
            return
        }

        val leaseId = payload.optString("leaseId")
        if (leaseId.isBlank()) {
            failBeforeGrant("granted reply omitted leaseId")
            return
        }
        val output = runCatching {
            FileOutputStream(File(filesDir, PCM_FILE_NAME), true)
        }.getOrElse { failure ->
            val reason = "could not open PCM file: ${failure.message ?: failure.javaClass.simpleName}"
            status(reason)
            writeSummary(granted = true, reason = reason)
            sendRelease(leaseId)
            return
        }
        capture = Capture(
            leaseId = leaseId,
            startedAtMs = SystemClock.elapsedRealtime(),
            pcm = output,
        )
        status(
            "Audio lease granted id=${leaseId.take(8)} " +
                "rate=${payload.optInt("sampleRate")} channels=${payload.optInt("channels")} " +
                "encoding=${payload.optString("encoding")}; capturing 10 s",
        )
        captureTimeout = Runnable {
            if (capture?.leaseId == leaseId) finishCapture(reason = null, release = true)
        }.also { main.postDelayed(it, CAPTURE_DURATION_MS) }
    }

    private fun handleReleaseReply(id: String, payload: JSONObject) {
        if (id != releaseRequestId) {
            status("Ignoring stale release reply id=${id.take(8)}")
            return
        }
        releaseRequestId = null
        releaseTimeout?.let(main::removeCallbacks)
        releaseTimeout = null
        if (payload.optBoolean("released", false)) {
            status("Audio lease released")
        } else {
            status("Audio lease release rejected: ${payload.optString("reason", "unspecified")}")
        }
        stopSelf()
    }

    private fun handleRevocation(payload: JSONObject) {
        val current = capture ?: return
        if (payload.optString("leaseId") != current.leaseId) return
        val reason = payload.optString("reason", "unspecified")
        status("Audio lease revoked: $reason")
        finishCapture(reason = "revoked: $reason", release = false)
    }

    private fun finishCapture(reason: String?, release: Boolean) {
        val current = capture ?: return
        capture = null
        captureTimeout?.let(main::removeCallbacks)
        captureTimeout = null
        val closeFailure = runCatching {
            current.pcm.flush()
            current.pcm.close()
        }.exceptionOrNull()
        val finalReason = reason ?: closeFailure?.let {
            "PCM close failed: ${it.message ?: it.javaClass.simpleName}"
        }
        val durationMs = SystemClock.elapsedRealtime() - current.startedAtMs
        writeSummary(
            granted = true,
            reason = finalReason,
            current = current,
            durationMs = durationMs,
        )
        status(
            "Capture finished frames=${current.frames} bytes=${current.bytes} gaps=${current.gaps} " +
                "firstSeq=${current.firstSeq} lastSeq=${current.lastSeq} durationMs=$durationMs" +
                (finalReason?.let { " reason=$it" } ?: ""),
        )
        if (release) sendRelease(current.leaseId) else stopSelf()
    }

    private fun sendRelease(leaseId: String) {
        val requestId = UUID.randomUUID().toString()
        releaseRequestId = requestId
        status("Sending $PATH_RELEASE")
        val accepted = nexusClient?.send(
            PATH_RELEASE,
            requestId,
            JSONObject().put("leaseId", leaseId),
        ) == true
        if (!accepted) {
            releaseRequestId = null
            status("Hub not connected; audio lease release could not be sent")
            stopSelf()
            return
        }
        releaseTimeout = Runnable {
            if (releaseRequestId != requestId) return@Runnable
            releaseRequestId = null
            status("Audio lease release reply timed out")
            stopSelf()
        }.also { main.postDelayed(it, REQUEST_TIMEOUT_MS) }
    }

    private fun failBeforeGrant(reason: String) {
        captureRequested = false
        acquireRequestId = null
        registrationTimeout?.let(main::removeCallbacks)
        acquireTimeout?.let(main::removeCallbacks)
        registrationTimeout = null
        acquireTimeout = null
        status("Capture failed: $reason")
        writeSummary(granted = false, reason = reason)
        stopSelf()
    }

    private fun truncatePcm(): Boolean =
        runCatching {
            FileOutputStream(File(filesDir, PCM_FILE_NAME), false).use { }
            true
        }.getOrElse {
            status("Could not truncate PCM file: ${it.message ?: it.javaClass.simpleName}")
            false
        }

    private fun writeSummary(
        granted: Boolean,
        reason: String? = null,
        current: Capture? = null,
        durationMs: Long = 0L,
    ) {
        val json = JSONObject()
            .put("granted", granted)
            .put("frames", current?.frames ?: 0L)
            .put("bytes", current?.bytes ?: 0L)
            .put("gaps", current?.gaps ?: 0L)
            .put("firstSeq", current?.firstSeq ?: JSONObject.NULL)
            .put("lastSeq", current?.lastSeq ?: JSONObject.NULL)
            .put("durationMs", durationMs)
        if (!reason.isNullOrBlank()) json.put("reason", reason)
        runCatching {
            File(filesDir, SUMMARY_FILE_NAME).writeText(json.toString())
        }.onFailure {
            status("Could not write summary: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun status(message: String) {
        Log.i(TAG, message)
        val line = String.format(
            Locale.US,
            "[%8.3f] %s",
            SystemClock.elapsedRealtime() / 1000.0,
            message,
        )
        runCatching {
            File(filesDir, STATUS_FILE_NAME).appendText(line + "\n")
        }.onFailure {
            Log.w(TAG, "Could not persist status log", it)
        }
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, line),
        )
    }

    private fun removeAllCallbacks() {
        registrationTimeout?.let(main::removeCallbacks)
        acquireTimeout?.let(main::removeCallbacks)
        captureTimeout?.let(main::removeCallbacks)
        releaseTimeout?.let(main::removeCallbacks)
        registrationTimeout = null
        acquireTimeout = null
        captureTimeout = null
        releaseTimeout = null
    }

    private fun registrationLabel(result: Int): String = when (result) {
        PluginRegistrationResult.APPROVED -> "approved"
        PluginRegistrationResult.PENDING_USER_APPROVAL -> "pending user approval"
        PluginRegistrationResult.DENIED -> "denied"
        PluginRegistrationResult.INVALID_DESCRIPTOR -> "invalid descriptor"
        PluginRegistrationResult.IDENTITY_MISMATCH -> "identity mismatch"
        PluginRegistrationResult.UNSUPPORTED_API -> "unsupported API"
        PluginRegistrationResult.REGISTRATION_FAILED -> "hub registration failed"
        else -> "unknown result $result"
    }

    companion object {
        const val ACTION_CAPTURE = "com.anezium.rokidbus.plugin.micprobe.CAPTURE"
        const val ACTION_STATUS = "com.anezium.rokidbus.plugin.micprobe.STATUS"
        const val EXTRA_STATUS = "status"
        const val STATUS_FILE_NAME = "micprobe-status.log"

        private const val TAG = "MicProbe"
        private const val PCM_FILE_NAME = "micprobe.pcm"
        private const val SUMMARY_FILE_NAME = "micprobe-summary.json"
        private const val PATH_ACQUIRE = "/audio/lease/acquire"
        private const val PATH_ACQUIRE_REPLY = "/audio/lease/acquire/reply"
        private const val PATH_RELEASE = "/audio/lease/release"
        private const val PATH_RELEASE_REPLY = "/audio/lease/release/reply"
        private const val PATH_REVOKED = "/audio/lease/revoked"
        private const val PATH_FRAMES = "/audio/frames"
        private const val CAPTURE_DURATION_MS = 25_000L
        private const val REGISTRATION_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
