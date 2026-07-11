package com.anezium.rokidbus.shared

object LensWireContract {
    const val MAX_STRING_COUNT = 24
    const val MAX_STRING_CHARS = 1_024
    const val MAX_TOTAL_SOURCE_BYTES = 16 * 1_024
    const val DEFAULT_TARGET_LANG = "fr"

    const val PHONE_REQUEST_TIMEOUT_MS = 8_000L
    const val PHONE_MODEL_DOWNLOAD_TIMEOUT_MS = 130_000L
    const val GLASSES_TIMEOUT_GRACE_MS = 5_000L
    const val GLASSES_REQUEST_TIMEOUT_MS = PHONE_REQUEST_TIMEOUT_MS + GLASSES_TIMEOUT_GRACE_MS
    const val GLASSES_MODEL_DOWNLOAD_TIMEOUT_MS =
        PHONE_MODEL_DOWNLOAD_TIMEOUT_MS + GLASSES_TIMEOUT_GRACE_MS

    private val whitespaceRegex = Regex("\\s+")

    fun normalizeText(text: String): String =
        text.trim().replace(whitespaceRegex, " ")
}
