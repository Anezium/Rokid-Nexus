package com.anezium.rokidbus.phone.lens

import org.json.JSONArray
import org.json.JSONObject

internal data class FittedLensTranslationResponse(
    val payload: JSONObject,
    val degradedCount: Int,
)

internal fun fitLensTranslationResponse(
    requestId: String,
    translations: List<TranslationResult>,
    protocolVersion: Int,
    maxPayloadBytes: Int,
): FittedLensTranslationResponse? {
    require(maxPayloadBytes > 0)

    val fitted = translations.toMutableList()
    var payload = lensTranslationPayload(requestId, fitted, protocolVersion)
    if (payload.utf8Size() <= maxPayloadBytes) {
        return FittedLensTranslationResponse(payload, degradedCount = 0)
    }

    var degradedCount = 0
    translations.indices
        .filter { !translations[it].fallback }
        .sortedByDescending { translations[it].dst.toByteArray(Charsets.UTF_8).size }
        .forEach { index ->
            val translation = fitted[index]
            fitted[index] = translation.copy(
                dst = translation.src,
                fallback = true,
                failure = TranslationErrorCode.RESPONSE_TOO_LARGE,
            )
            degradedCount += 1
            payload = lensTranslationPayload(requestId, fitted, protocolVersion)
            if (payload.utf8Size() <= maxPayloadBytes) {
                return FittedLensTranslationResponse(payload, degradedCount)
            }
        }

    return null
}

private fun lensTranslationPayload(
    requestId: String,
    translations: List<TranslationResult>,
    protocolVersion: Int,
): JSONObject =
    JSONObject()
        .put("version", protocolVersion)
        .put("id", requestId)
        .put("translations", JSONArray().also { array ->
            translations.forEach { translation ->
                array.put(
                    JSONObject()
                        .put("src", translation.src)
                        .put("dst", translation.dst)
                        .put("srcLang", translation.srcLang)
                        .put("fallback", translation.fallback)
                        .also { item ->
                            translation.failure?.let { item.put("error", it.wireValue) }
                        },
                )
            }
        })

private fun JSONObject.utf8Size(): Int =
    toString().toByteArray(Charsets.UTF_8).size
