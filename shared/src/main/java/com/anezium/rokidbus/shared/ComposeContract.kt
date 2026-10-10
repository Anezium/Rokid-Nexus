package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Why a dictation capture ended. Anything but [COMPLETED] sends the wearer to Type. */
enum class ComposeDictationReason(val wireValue: String) {
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    NO_SPEECH("no_speech"),
    BUSY("busy"),
    MIC_UNAVAILABLE("mic_unavailable"),
    LINK_LOST("link_lost"),
    ERROR("error"),
    ;

    companion object {
        fun fromWireValue(value: String): ComposeDictationReason? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Glasses to phone: start dictating on the glasses microphone, in [language] when given. */
data class ComposeDictateStart(val captureId: String, val language: String? = null)

/** Phone to glasses: the recognizer's running text. */
data class ComposeDictatePartial(val captureId: String, val text: String)

/** Phone to glasses: the capture is over. [text] is the draft only when [reason] is completed. */
data class ComposeDictateFinal(val captureId: String, val reason: ComposeDictationReason, val text: String? = null)

/** Glasses to phone: finish the capture ([cancel] false) or drop it ([cancel] true). */
data class ComposeDictateStop(val captureId: String, val cancel: Boolean)

/** Glasses to phone: open the phone keyboard on [initialText], bounded by [maxChars]. */
data class ComposeTypeStart(val captureId: String, val initialText: String, val maxChars: Int)

/** Phone to glasses: the phone field as it is now. */
data class ComposeTypeText(val captureId: String, val text: String)

/**
 * Phone to glasses: typing ended. [committed] carries [text], which may be empty: an empty commit
 * is the wearer's one explicit way to discard the draft. Uncommitted, nothing changes.
 */
data class ComposeTypeDone(val captureId: String, val committed: Boolean, val text: String? = null)

/**
 * The hub composer's capture engines, between the two hubs only (`/core/compose/...`): no plugin
 * capability unlocks them, no plugin sees them. Dictation uses the glasses microphone and the hub
 * speech engine, never a phone microphone; typing uses the phone keyboard and never starts the
 * glasses microphone. Each capture has a fresh [isValidCaptureId] id; a message for any other id
 * is stale and ignored. A link loss during a capture cancels it and keeps the previous draft.
 */
object ComposeContract {
    const val VERSION = 1
    const val MAX_TEXT_CHARS = PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS
    const val MAX_LANGUAGE_CHARS = 35

    const val DICTATE_START_PATH = BusPaths.CORE_COMPOSE_DICTATE_START
    const val DICTATE_PARTIAL_PATH = BusPaths.CORE_COMPOSE_DICTATE_PARTIAL
    const val DICTATE_FINAL_PATH = BusPaths.CORE_COMPOSE_DICTATE_FINAL
    const val DICTATE_STOP_PATH = BusPaths.CORE_COMPOSE_DICTATE_STOP
    const val TYPE_START_PATH = BusPaths.CORE_COMPOSE_TYPE_START
    const val TYPE_TEXT_PATH = BusPaths.CORE_COMPOSE_TYPE_TEXT
    const val TYPE_DONE_PATH = BusPaths.CORE_COMPOSE_TYPE_DONE
    const val TYPE_FINISH_PATH = BusPaths.CORE_COMPOSE_TYPE_FINISH
    const val TYPE_CANCEL_PATH = BusPaths.CORE_COMPOSE_TYPE_CANCEL

    /** Glasses to phone. */
    val glassesToPhonePaths: Set<String> = setOf(
        DICTATE_START_PATH, DICTATE_STOP_PATH, TYPE_START_PATH, TYPE_FINISH_PATH, TYPE_CANCEL_PATH,
    )

    /** Phone to glasses. */
    val phoneToGlassesPaths: Set<String> = setOf(
        DICTATE_PARTIAL_PATH, DICTATE_FINAL_PATH, TYPE_TEXT_PATH, TYPE_DONE_PATH,
    )

    private val captureIdPattern = Regex("[A-Za-z0-9_-]{8,64}")
    private val languagePattern = Regex("[A-Za-z0-9-]{1,$MAX_LANGUAGE_CHARS}")

    fun isValidCaptureId(value: String): Boolean = captureIdPattern.matches(value)

    /** Recognizer output past the composer's ceiling is cut, never sent whole. */
    fun clip(text: String): String = if (text.length <= MAX_TEXT_CHARS) text else text.substring(0, MAX_TEXT_CHARS)

    fun dictateStart(value: ComposeDictateStart): JSONObject = base(value.captureId).apply {
        value.language?.let {
            require(languagePattern.matches(it)) { "Invalid language" }
            put("language", it)
        }
    }

    fun parseDictateStart(payload: JSONObject): ComposeDictateStart? {
        val captureId = captureId(payload) ?: return null
        val language = if (payload.has("language") && payload.opt("language") != JSONObject.NULL) {
            (payload.opt("language") as? String)?.takeIf(languagePattern::matches) ?: return null
        } else {
            null
        }
        return ComposeDictateStart(captureId, language)
    }

    fun dictatePartial(value: ComposeDictatePartial): JSONObject = base(value.captureId).put("text", checkedText(value.text))

    fun parseDictatePartial(payload: JSONObject): ComposeDictatePartial? {
        val captureId = captureId(payload) ?: return null
        return ComposeDictatePartial(captureId, readText(payload, "text") ?: return null)
    }

    fun dictateFinal(value: ComposeDictateFinal): JSONObject {
        require(value.reason != ComposeDictationReason.COMPLETED || !value.text.isNullOrBlank()) {
            "A completed dictation carries its text"
        }
        return base(value.captureId).put("reason", value.reason.wireValue).apply {
            value.text?.let { put("text", checkedText(it)) }
        }
    }

    /** A completed dictation needs non-blank text; any other reason's text is dropped. */
    fun parseDictateFinal(payload: JSONObject): ComposeDictateFinal? {
        val captureId = captureId(payload) ?: return null
        val reason = (payload.opt("reason") as? String)?.let(ComposeDictationReason::fromWireValue) ?: return null
        val text = if (payload.has("text") && payload.opt("text") != JSONObject.NULL) {
            readText(payload, "text") ?: return null
        } else {
            null
        }
        if (reason == ComposeDictationReason.COMPLETED) {
            if (text.isNullOrBlank()) return null
            return ComposeDictateFinal(captureId, reason, text)
        }
        return ComposeDictateFinal(captureId, reason, null)
    }

    fun dictateStop(value: ComposeDictateStop): JSONObject = base(value.captureId).put("cancel", value.cancel)

    fun parseDictateStop(payload: JSONObject): ComposeDictateStop? {
        val captureId = captureId(payload) ?: return null
        val cancel = payload.opt("cancel") as? Boolean ?: return null
        return ComposeDictateStop(captureId, cancel)
    }

    fun typeStart(value: ComposeTypeStart): JSONObject {
        require(value.maxChars in 1..MAX_TEXT_CHARS) { "maxChars out of range" }
        require(value.initialText.length <= value.maxChars) { "initialText exceeds maxChars" }
        return base(value.captureId).put("initialText", value.initialText).put("maxChars", value.maxChars)
    }

    fun parseTypeStart(payload: JSONObject): ComposeTypeStart? {
        val captureId = captureId(payload) ?: return null
        val maxChars = (payload.opt("maxChars") as? Int)?.takeIf { it in 1..MAX_TEXT_CHARS } ?: return null
        val initialText = payload.opt("initialText") as? String ?: return null
        if (initialText.length > maxChars) return null
        return ComposeTypeStart(captureId, initialText, maxChars)
    }

    fun typeText(value: ComposeTypeText): JSONObject = base(value.captureId).put("text", checkedText(value.text))

    fun parseTypeText(payload: JSONObject): ComposeTypeText? {
        val captureId = captureId(payload) ?: return null
        return ComposeTypeText(captureId, readText(payload, "text") ?: return null)
    }

    fun typeDone(value: ComposeTypeDone): JSONObject {
        require(!value.committed || value.text != null) { "A committed typing carries its text" }
        return base(value.captureId).put("committed", value.committed).apply {
            if (value.committed) put("text", checkedText(value.text.orEmpty()))
        }
    }

    /** A commit needs its text (possibly empty); an uncommitted end carries none. */
    fun parseTypeDone(payload: JSONObject): ComposeTypeDone? {
        val captureId = captureId(payload) ?: return null
        val committed = payload.opt("committed") as? Boolean ?: return null
        if (!committed) return ComposeTypeDone(captureId, committed = false)
        return ComposeTypeDone(captureId, committed = true, text = readText(payload, "text") ?: return null)
    }

    /** `/core/compose/type/finish` and `/core/compose/type/cancel` carry the capture id alone. */
    fun typeEnd(captureId: String): JSONObject = base(captureId)

    fun parseTypeEnd(payload: JSONObject): String? = captureId(payload)

    private fun base(captureId: String): JSONObject {
        require(isValidCaptureId(captureId)) { "Invalid capture id" }
        return JSONObject().put("captureId", captureId)
    }

    private fun checkedText(text: String): String {
        require(text.length <= MAX_TEXT_CHARS) { "Text exceeds $MAX_TEXT_CHARS characters" }
        return text
    }

    private fun captureId(payload: JSONObject): String? = (payload.opt("captureId") as? String)?.takeIf(::isValidCaptureId)

    private fun readText(payload: JSONObject, key: String): String? =
        (payload.opt(key) as? String)?.takeIf { it.length <= MAX_TEXT_CHARS }
}
