package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.LensWireContract
import java.util.concurrent.atomic.AtomicReference

internal const val MAX_TRANSLATED_TEXT_CHARS = 4_096

data class TranslationRequest(
    val id: String,
    val targetLang: String,
    val mode: LensRecognizerMode,
    val strings: List<String>,
)

data class TranslationResult(
    val src: String,
    val dst: String,
    val srcLang: String,
    val fallback: Boolean = false,
    val failure: TranslationErrorCode? = null,
    /** Engine that actually served this result; the router stamps it on success. */
    val engine: TranslationEngine? = null,
)

data class TranslationDownloadStatus(
    val srcLang: String,
    val targetLang: String,
)

enum class TranslationErrorCode(val wireValue: String) {
    INVALID_REQUEST("INVALID_REQUEST"),
    REQUEST_TOO_LARGE("REQUEST_TOO_LARGE"),
    DUPLICATE_REQUEST("DUPLICATE_REQUEST"),
    BUSY("BUSY"),
    UNSUPPORTED_TARGET_LANGUAGE("UNSUPPORTED_TARGET_LANGUAGE"),
    MODEL_UNAVAILABLE("MODEL_UNAVAILABLE"),
    TRANSLATION_FAILED("TRANSLATION_FAILED"),
    RESPONSE_TOO_LARGE("RESPONSE_TOO_LARGE"),
    TIMEOUT("TIMEOUT"),
    PROVIDER_CLOSED("PROVIDER_CLOSED"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
}

enum class LensRecognizerMode(val wireValue: String) {
    LATIN(LensWireContract.RECOGNIZER_MODE_LATIN),
    JAPANESE(LensWireContract.RECOGNIZER_MODE_JAPANESE),
    CHINESE(LensWireContract.RECOGNIZER_MODE_CHINESE),
    KOREAN(LensWireContract.RECOGNIZER_MODE_KOREAN),
    DEVANAGARI(LensWireContract.RECOGNIZER_MODE_DEVANAGARI),
}

interface TranslationProvider : AutoCloseable {
    interface Callback {
        fun onDownloading(status: TranslationDownloadStatus)
        fun onSuccess(translations: List<TranslationResult>)
        fun onError(error: TranslationErrorCode)
    }

    fun translate(request: TranslationRequest, callback: Callback): TranslationCall
}

fun interface TranslationCall {
    fun cancel()

    companion object {
        val NONE = TranslationCall { }
    }
}

internal enum class TranslationTerminalState {
    ACTIVE,
    COMPLETED,
    CANCELED,
}

/**
 * Linearizes completion and cancellation. The callback runs while cancellation is excluded, so
 * once [cancel] returns no final callback can start afterward.
 */
internal class TranslationTerminal {
    private val state = AtomicReference(TranslationTerminalState.ACTIVE)
    private val callbackLock = Any()

    fun isActive(): Boolean = state.get() == TranslationTerminalState.ACTIVE

    fun complete(callback: () -> Unit): Boolean = synchronized(callbackLock) {
        val completed = state.compareAndSet(
            TranslationTerminalState.ACTIVE,
            TranslationTerminalState.COMPLETED,
        )
        if (!completed) {
            false
        } else {
            callback()
            true
        }
    }

    fun cancel(): Boolean = synchronized(callbackLock) {
        state.compareAndSet(
            TranslationTerminalState.ACTIVE,
            TranslationTerminalState.CANCELED,
        )
    }

    internal fun currentState(): TranslationTerminalState = state.get()
}

