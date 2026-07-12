package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.LensWireContract
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface LensTranslationParseResult {
    data class Success(val request: TranslationRequest) : LensTranslationParseResult

    data class Failure(
        val replyId: String?,
        val error: TranslationErrorCode,
    ) : LensTranslationParseResult
}

internal object LensTranslationRequestParser {
    internal const val MAX_REQUEST_PAYLOAD_BYTES = 48 * 1024
    private const val MAX_REQUEST_ID_CHARS = 128
    private const val MAX_LANGUAGE_TAG_CHARS = 16
    private val requestIdRegex = Regex("[A-Za-z0-9._:-]+")
    private val languageTagRegex = Regex("[A-Za-z]{2,3}(?:-[A-Za-z]{2,8})?")

    fun parse(envelopeId: String, payload: JSONObject): LensTranslationParseResult {
        val safeEnvelopeId = envelopeId.takeIf(::isSafeRequestId)
        val rawPayloadId = payload.opt("id").takeUnless { it == null || it === JSONObject.NULL }
        if (safeEnvelopeId == null ||
            (rawPayloadId != null && rawPayloadId !is String) ||
            (rawPayloadId is String && rawPayloadId.isNotBlank() && rawPayloadId != envelopeId)
        ) {
            return LensTranslationParseResult.Failure(
                replyId = safeEnvelopeId,
                error = TranslationErrorCode.INVALID_REQUEST,
            )
        }
        val requestId = safeEnvelopeId

        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_REQUEST_PAYLOAD_BYTES) {
            return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.REQUEST_TOO_LARGE)
        }

        val targetLang = parseTargetLanguage(payload)
            ?: return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.INVALID_REQUEST)
        val mode = parseMode(payload)
            ?: return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.INVALID_REQUEST)
        val rawStrings = payload.opt("strings")
        if (rawStrings !is JSONArray) {
            return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.INVALID_REQUEST)
        }
        if (rawStrings.length() > LensWireContract.MAX_STRING_COUNT) {
            return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.REQUEST_TOO_LARGE)
        }

        val strings = ArrayList<String>(rawStrings.length())
        val seen = linkedSetOf<String>()
        var totalSourceBytes = 0
        for (index in 0 until rawStrings.length()) {
            val raw = rawStrings.opt(index)
            if (raw !is String) {
                return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.INVALID_REQUEST)
            }
            val normalized = LensWireContract.normalizeText(raw)
            if (normalized.isBlank() || !seen.add(normalized)) continue
            if (normalized.length > LensWireContract.MAX_STRING_CHARS) {
                return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.REQUEST_TOO_LARGE)
            }
            totalSourceBytes += normalized.toByteArray(Charsets.UTF_8).size
            if (totalSourceBytes > LensWireContract.MAX_TOTAL_SOURCE_BYTES) {
                return LensTranslationParseResult.Failure(requestId, TranslationErrorCode.REQUEST_TOO_LARGE)
            }
            strings += normalized
        }

        return LensTranslationParseResult.Success(
            TranslationRequest(
                id = requestId,
                targetLang = targetLang,
                mode = mode,
                strings = strings,
            ),
        )
    }

    private fun parseTargetLanguage(payload: JSONObject): String? {
        val raw = payload.opt("targetLang").takeUnless { it == null || it === JSONObject.NULL }
        val value = when (raw) {
            null -> LensWireContract.DEFAULT_TARGET_LANG
            is String -> raw.trim().ifBlank { LensWireContract.DEFAULT_TARGET_LANG }
            else -> return null
        }
        return value
            .takeIf { it.length <= MAX_LANGUAGE_TAG_CHARS && languageTagRegex.matches(it) }
            ?.lowercase()
    }

    private fun parseMode(payload: JSONObject): LensRecognizerMode? {
        val raw = payload.opt("mode").takeUnless { it == null || it === JSONObject.NULL }
            ?: return LensRecognizerMode.LATIN
        if (raw !is String) return null
        return LensRecognizerMode.entries.firstOrNull { it.wireValue.equals(raw, ignoreCase = true) }
            ?: LensRecognizerMode.LATIN
    }

    private fun isSafeRequestId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_REQUEST_ID_CHARS &&
            requestIdRegex.matches(value)
}

