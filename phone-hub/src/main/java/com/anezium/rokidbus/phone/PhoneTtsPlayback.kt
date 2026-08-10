package com.anezium.rokidbus.phone

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneEvent
import com.anezium.rokidbus.shared.TtsDoneReason
import com.anezium.rokidbus.shared.TtsPlaybackState
import com.anezium.rokidbus.shared.TtsSpeakRequest
import com.anezium.rokidbus.shared.TtsStopRequest
import com.anezium.rokidbus.shared.TtsValidationResult
import java.util.Locale
import java.util.UUID

internal class PhoneTtsPlayback(
    private val output: PhoneTtsOutput,
    private val storedLanguageTag: () -> String? = { null },
    private val systemLocale: () -> Locale = Locale::getDefault,
    private val engineId: () -> String = { UUID.randomUUID().toString() },
    private val emitStarted: (ownerPluginId: String, utteranceId: String) -> Unit,
    private val emitDone: (TtsDoneEvent) -> Unit,
) : PhoneTtsOutput.Listener {
    private val lock = Any()
    private val state = TtsPlaybackState()
    private var reportUnexpectedFailure: (TtsDoneEvent) -> Unit = {}

    init {
        output.setListener(this)
    }

    val isReady: Boolean
        get() = output.isReady

    fun initialize() = output.initialize()

    fun prewarm(): Boolean = synchronized(lock) {
        if (!output.isReady || state.current() != null) return@synchronized false
        output.prewarm()
    }

    fun availableVoices(locale: Locale? = null): List<PhoneTtsVoiceOption> =
        output.availableVoices(locale)

    fun speakSample(text: String, locale: Locale): Boolean = synchronized(lock) {
        if (!output.isReady || state.current() != null) return@synchronized false
        output.speakSample(text, locale)
    }

    fun setUnexpectedFailureHandler(handler: (TtsDoneEvent) -> Unit) = synchronized(lock) {
        reportUnexpectedFailure = handler
    }

    fun speak(request: TtsSpeakRequest): Boolean = synchronized(lock) {
        if (!output.isReady) return@synchronized false
        val ownerPluginId = request.ownerPluginId ?: return@synchronized false
        val accepted =
            state.accept(ownerPluginId, request.utteranceId, engineId(), request.text)
        accepted.preempted?.let(emitDone)
        val locale = resolvePhoneTtsLocale(request.lang, storedLanguageTag(), systemLocale())
        val result = output.speak(
            accepted.active.engineId,
            accepted.active.text,
            locale,
        )
        if (result != PhoneTtsSpeakResult.ACCEPTED) {
            output.stop()
            state.unavailable(accepted.active.engineId)?.let(::emitUnexpectedFailure)
        }
        true
    }

    fun stop(request: TtsStopRequest): Boolean = synchronized(lock) {
        val ownerPluginId = request.ownerPluginId ?: return@synchronized false
        val stopping =
            state.requestStop(ownerPluginId, request.utteranceId)
                ?: return@synchronized false
        val event = state.stopped(stopping.engineId)
        output.stop()
        event?.let(emitDone)
        true
    }

    fun cancel(reason: TtsDoneReason) = synchronized(lock) {
        val stopping = state.cancelCurrent(reason) ?: return@synchronized
        val event = state.stopped(stopping.engineId)
        output.stop()
        event?.let(emitDone)
    }

    fun shutdown() = synchronized(lock) {
        val stopping = state.cancelCurrent(TtsDoneReason.CANCELLED)
        val event = stopping?.let { state.stopped(it.engineId) }
        if (stopping != null) output.stop()
        event?.let(emitDone)
        output.shutdown()
    }

    override fun onStart(utteranceId: String) {
        synchronized(lock) { state.started(utteranceId) }
            ?.let { emitStarted(it.ownerPluginId, it.utteranceId) }
    }

    override fun onDone(utteranceId: String) {
        synchronized(lock) { state.stopped(utteranceId) }?.let(emitDone)
    }

    override fun onUnavailable(utteranceId: String) {
        handleUnexpectedFailure(utteranceId)
    }

    override fun onStopped(utteranceId: String) {
        handleUnexpectedFailure(utteranceId)
    }

    private fun handleUnexpectedFailure(utteranceId: String) {
        synchronized(lock) { state.unavailable(utteranceId) }
            ?.let(::emitUnexpectedFailure)
    }

    private fun emitUnexpectedFailure(event: TtsDoneEvent) {
        runCatching { reportUnexpectedFailure(event) }
        emitDone(event)
    }
}

internal fun resolvePhoneTtsLocale(
    wireLanguageTag: String?,
    storedLanguageTag: String?,
    systemLocale: Locale,
): Locale = wireLanguageTag
    ?.let(Locale::forLanguageTag)
    ?: storedLanguageTag?.let(Locale::forLanguageTag)
    ?: systemLocale

internal sealed interface PhoneTtsDispatchResult {
    data object PhoneHandled : PhoneTtsDispatchResult
    data class Invalid(val error: String) : PhoneTtsDispatchResult
}

internal class PhoneTtsDispatcher(
    private val playback: PhoneTtsPlayback,
    private val emitDone: (TtsDoneEvent) -> Unit,
    private val phoneRoute: () -> PhoneTtsRoute = { PhoneTtsRoute.EXTERNAL_SINK },
    private val logger: (String) -> Unit = {},
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val scheduleAfter: (delayMs: Long, action: () -> Unit) -> Boolean = { delay, action ->
        Handler(Looper.getMainLooper()).postDelayed(Runnable(action), delay)
    },
) {
    private data class PendingSpeak(
        val request: TtsSpeakRequest,
        val deadlineMs: Long,
    )

    private val dispatchLock = Any()
    private val pendingSpeaks = ArrayDeque<PendingSpeak>()
    private var routeCheckScheduled = false
    private var routeCheckGeneration = 0L
    private var shutdown = false

    init {
        playback.setUnexpectedFailureHandler { event ->
            logger(
                "phone TTS dispatch classification=ASYNC_PHONE_FAILURE " +
                    "route=dropped reason=phone_unavailable id=${event.utteranceId}",
            )
        }
    }

    fun dispatch(envelope: BusEnvelope): PhoneTtsDispatchResult = synchronized(dispatchLock) {
        when (envelope.path) {
            BusPaths.TTS_SPEAK -> dispatchSpeak(envelope)
            BusPaths.TTS_STOP -> dispatchStop(envelope)
            else -> PhoneTtsDispatchResult.Invalid(TtsContract.ERROR_INVALID_TTS)
        }
    }

    fun initialize() = synchronized(dispatchLock) { playback.initialize() }

    fun prewarm(): Boolean = synchronized(dispatchLock) { playback.prewarm() }

    fun availableVoices(locale: Locale? = null): List<PhoneTtsVoiceOption> =
        synchronized(dispatchLock) { playback.availableVoices(locale) }

    fun speakSample(text: String, locale: Locale): Boolean = synchronized(dispatchLock) {
        if (pendingSpeaks.isNotEmpty()) return@synchronized false
        when (phoneRoute()) {
            PhoneTtsRoute.PHONE_SPEAKER -> {
                playback.prewarm()
                false
            }
            PhoneTtsRoute.GLASSES_LINK -> {
                playback.prewarm()
                playback.speakSample(text, locale)
            }
            PhoneTtsRoute.EXTERNAL_SINK -> playback.speakSample(text, locale)
        }
    }

    fun cancelForMicrophone() = synchronized(dispatchLock) {
        playback.cancel(TtsDoneReason.CANCELLED)
        retirePending(TtsDoneReason.CANCELLED)
    }

    fun shutdown() = synchronized(dispatchLock) {
        shutdown = true
        retirePending(TtsDoneReason.CANCELLED)
        playback.shutdown()
    }

    private fun dispatchSpeak(envelope: BusEnvelope): PhoneTtsDispatchResult {
        val request = when (
            val validation = TtsContract.validateSpeak(envelope.payload, requireOwner = true)
        ) {
            is TtsValidationResult.Valid -> validation.value
            is TtsValidationResult.Invalid ->
                return PhoneTtsDispatchResult.Invalid(validation.reason)
        }
        val classification = phoneRoute()
        if (pendingSpeaks.isNotEmpty()) {
            enqueue(request)
            logger("phone TTS dispatch classification=$classification route=queued reason=ear_wait")
            return PhoneTtsDispatchResult.PhoneHandled
        }
        if (classification != PhoneTtsRoute.PHONE_SPEAKER && playback.isReady) {
            if (classification == PhoneTtsRoute.GLASSES_LINK) playback.prewarm()
            if (playback.speak(request)) {
                logger("phone TTS dispatch classification=$classification route=phone")
                return PhoneTtsDispatchResult.PhoneHandled
            }
        }
        if (!playback.isReady) playback.initialize()
        enqueue(request)
        if (classification == PhoneTtsRoute.PHONE_SPEAKER) playback.prewarm()
        logger("phone TTS dispatch classification=$classification route=waiting")
        return PhoneTtsDispatchResult.PhoneHandled
    }

    private fun dispatchStop(envelope: BusEnvelope): PhoneTtsDispatchResult {
        val request = when (
            val validation = TtsContract.validateStop(envelope.payload, requireOwner = true)
        ) {
            is TtsValidationResult.Valid -> validation.value
            is TtsValidationResult.Invalid ->
                return PhoneTtsDispatchResult.Invalid(validation.reason)
        }
        val pending = pendingSpeaks.firstOrNull { queued ->
            queued.request.ownerPluginId == request.ownerPluginId &&
                queued.request.utteranceId == request.utteranceId
        }
        if (pending != null) {
            pendingSpeaks.remove(pending)
            emitDone(
                TtsDoneEvent(
                    checkNotNull(request.ownerPluginId),
                    request.utteranceId,
                    TtsDoneReason.STOPPED,
                ),
            )
            if (pendingSpeaks.isEmpty()) cancelRouteCheck()
            return PhoneTtsDispatchResult.PhoneHandled
        }
        playback.stop(request)
        return PhoneTtsDispatchResult.PhoneHandled
    }

    private fun enqueue(request: TtsSpeakRequest) {
        pendingSpeaks.addLast(PendingSpeak(request, nowMs() + ROUTE_WAIT_BUDGET_MS))
        scheduleRouteCheck()
    }

    private fun scheduleRouteCheck(delayMs: Long = ROUTE_REPROBE_INTERVAL_MS) {
        if (routeCheckScheduled || pendingSpeaks.isEmpty() || shutdown) return
        routeCheckScheduled = true
        val generation = ++routeCheckGeneration
        val scheduled = scheduleAfter(delayMs) {
            synchronized(dispatchLock) {
                if (!routeCheckScheduled || routeCheckGeneration != generation) {
                    return@synchronized
                }
                routeCheckScheduled = false
                processPendingSpeaks()
            }
        }
        if (!scheduled) {
            routeCheckScheduled = false
            dropAllPending("scheduler_unavailable")
        }
    }

    private fun processPendingSpeaks() {
        while (pendingSpeaks.isNotEmpty()) {
            val pending = pendingSpeaks.first()
            val classification = phoneRoute()
            val now = nowMs()
            if (classification == PhoneTtsRoute.PHONE_SPEAKER) {
                if (now >= pending.deadlineMs) {
                    pendingSpeaks.removeFirst()
                    drop(pending, classification, "no_ear")
                    continue
                }
                scheduleRouteCheck(
                    minOf(ROUTE_REPROBE_INTERVAL_MS, pending.deadlineMs - now),
                )
                return
            }
            if (!playback.isReady) {
                playback.initialize()
                if (now >= pending.deadlineMs) {
                    pendingSpeaks.removeFirst()
                    drop(pending, classification, "phone_unavailable")
                    continue
                }
                scheduleRouteCheck(
                    minOf(ROUTE_REPROBE_INTERVAL_MS, pending.deadlineMs - now),
                )
                return
            }
            pendingSpeaks.removeFirst()
            if (classification == PhoneTtsRoute.GLASSES_LINK) playback.prewarm()
            if (playback.speak(pending.request)) {
                logger("phone TTS dispatch classification=$classification route=phone")
            } else {
                drop(pending, classification, "phone_unavailable")
            }
        }
    }

    private fun drop(
        pending: PendingSpeak,
        classification: PhoneTtsRoute,
        reason: String,
    ) {
        val ownerPluginId = checkNotNull(pending.request.ownerPluginId)
        logger(
            "phone TTS dispatch classification=$classification " +
                "route=dropped reason=$reason id=${pending.request.utteranceId}",
        )
        emitDone(
            TtsDoneEvent(
                ownerPluginId,
                pending.request.utteranceId,
                TtsDoneReason.UNAVAILABLE,
            ),
        )
    }

    private fun dropAllPending(reason: String) {
        while (pendingSpeaks.isNotEmpty()) {
            drop(pendingSpeaks.removeFirst(), PhoneTtsRoute.PHONE_SPEAKER, reason)
        }
    }

    private fun retirePending(reason: TtsDoneReason) {
        cancelRouteCheck()
        while (pendingSpeaks.isNotEmpty()) {
            val request = pendingSpeaks.removeFirst().request
            emitDone(
                TtsDoneEvent(
                    checkNotNull(request.ownerPluginId),
                    request.utteranceId,
                    reason,
                ),
            )
        }
    }

    private fun cancelRouteCheck() {
        routeCheckScheduled = false
        routeCheckGeneration += 1
    }

    internal companion object {
        const val ROUTE_WAIT_BUDGET_MS = 3_500L
        const val ROUTE_REPROBE_INTERVAL_MS = 500L
    }
}
