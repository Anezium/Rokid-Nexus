package com.anezium.rokidbus.phone.speech

import java.util.Locale

/**
 * Forced transcription language. Provider-specific codes are kept because script and dialect
 * selection differ: Cantonese uses ElevenLabs `yue`, Azure `zh-HK`, and an OpenAI prompt.
 * Android tag chains stay available for per-session overrides, but the Android recognizer runs on
 * Auto — like Relay it auto-detects, so the settings screen locks the grid instead of rewriting
 * the stored choice.
 */
enum class TranscriptionLanguage(
    val id: String,
    val label: String,
    val summaryName: String,
    val openAiCode: String? = null,
    val openAiPrompt: String? = null,
    val elevenLabsCode: String? = null,
    val azureLocale: String? = null,
    val androidTag: String? = null,
    val androidFallbackTags: List<String> = emptyList(),
    val uiNote: String? = null,
) {
    AUTO(
        id = "auto",
        label = "Auto",
        summaryName = "Auto",
        uiNote = "Follows the phone language. Engines may still guess the script on their own.",
    ),
    ENGLISH(
        id = "en",
        label = "English",
        summaryName = "English",
        openAiCode = "en",
        elevenLabsCode = "en",
        azureLocale = "en-US",
        androidTag = "en-US",
    ),
    FRENCH(
        id = "fr",
        label = "Français",
        summaryName = "French",
        openAiCode = "fr",
        elevenLabsCode = "fr",
        azureLocale = "fr-FR",
        androidTag = "fr-FR",
    ),
    GERMAN(
        id = "de",
        label = "Deutsch",
        summaryName = "German",
        openAiCode = "de",
        elevenLabsCode = "de",
        azureLocale = "de-DE",
        androidTag = "de-DE",
    ),
    SPANISH(
        id = "es",
        label = "Español",
        summaryName = "Spanish",
        openAiCode = "es",
        elevenLabsCode = "es",
        azureLocale = "es-ES",
        androidTag = "es-ES",
    ),
    ITALIAN(
        id = "it",
        label = "Italiano",
        summaryName = "Italian",
        openAiCode = "it",
        elevenLabsCode = "it",
        azureLocale = "it-IT",
        androidTag = "it-IT",
    ),
    PORTUGUESE(
        id = "pt",
        label = "Português",
        summaryName = "Portuguese",
        openAiCode = "pt",
        elevenLabsCode = "pt",
        azureLocale = "pt-BR",
        androidTag = "pt-BR",
        uiNote = "Azure and Android use the Brazilian locale (pt-BR).",
    ),
    POLISH(
        id = "pl",
        label = "Polski",
        summaryName = "Polish",
        openAiCode = "pl",
        elevenLabsCode = "pl",
        azureLocale = "pl-PL",
        androidTag = "pl-PL",
    ),
    JAPANESE(
        id = "ja",
        label = "日本語",
        summaryName = "Japanese",
        openAiCode = "ja",
        elevenLabsCode = "ja",
        azureLocale = "ja-JP",
        androidTag = "ja-JP",
    ),
    KOREAN(
        id = "ko",
        label = "한국어",
        summaryName = "Korean",
        openAiCode = "ko",
        elevenLabsCode = "ko",
        azureLocale = "ko-KR",
        androidTag = "ko-KR",
    ),
    CANTONESE(
        id = "yue",
        label = "廣東話",
        summaryName = "Cantonese",
        openAiPrompt = "廣東話語音。請用繁體中文轉寫。",
        elevenLabsCode = "yue",
        azureLocale = "zh-HK",
        androidTag = "yue-Hant-HK",
        androidFallbackTags = listOf("yue-HK", "zh-HK"),
        uiNote = "ElevenLabs (yue) and Azure (zh-HK) write Traditional Chinese.",
    ),
    CHINESE_TRADITIONAL(
        id = "zh-hant",
        label = "中文繁體",
        summaryName = "Chinese (Traditional)",
        openAiCode = "zh",
        openAiPrompt = "請使用繁體中文。",
        elevenLabsCode = "zh",
        azureLocale = "zh-TW",
        androidTag = "zh-TW",
        uiNote = "Azure (zh-TW) guarantees Traditional script.",
    ),
    CHINESE_SIMPLIFIED(
        id = "zh-hans",
        label = "中文简体",
        summaryName = "Chinese (Simplified)",
        openAiCode = "zh",
        openAiPrompt = "请使用简体中文。",
        elevenLabsCode = "zh",
        azureLocale = "zh-CN",
        androidTag = "zh-CN",
    ),
    ;

    fun androidTagChain(): List<String> {
        val primary = androidTag?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(primary) + androidFallbackTags.filter { it.isNotBlank() }
    }

    companion object {
        fun fromId(id: String?): TranscriptionLanguage {
            val normalized = id.orEmpty().trim().lowercase(Locale.US)
            return values().firstOrNull { it.id == normalized } ?: AUTO
        }
    }
}
