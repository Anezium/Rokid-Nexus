package com.anezium.rokidbus.plugin.lens

internal const val GEMINI_VISION_SETTING_LABEL = "Read frozen photos with Gemini"

private fun ocrScriptDisplayName(script: OcrScript): String = when (script) {
    OcrScript.LATIN -> "Latin-alphabet languages"
    OcrScript.JAPANESE -> "Japanese"
    OcrScript.CHINESE -> "Chinese"
    OcrScript.KOREAN -> "Korean"
    OcrScript.DEVANAGARI -> "Devanagari"
}

/**
 * Built from [OcrScript] so a new on-device recognizer cannot ship while the settings screen
 * still tells users their script is unreadable.
 */
internal fun lensReadableScriptsCaption(): String {
    val names = OcrScript.entries.map(::ocrScriptDisplayName)
    val readable = names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "On the phone, Lens reads $readable. To read Thai, Arabic, Cyrillic, Greek or Hebrew, " +
        "turn on “$GEMINI_VISION_SETTING_LABEL” below (frozen photos only)."
}
