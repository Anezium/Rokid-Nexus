package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * Why a notice stopped being visible. Delivered to its owner exactly once.
 */
enum class NoticeCloseReason(val wireValue: String) {
    /** The wearer pressed BACK. */
    USER("user"),

    /** The TTL or the absolute lifetime ran out. */
    TIMEOUT("timeout"),

    /** The owner called hide. */
    OWNER("owner"),

    /** Another plugin took the slot. */
    REPLACED("replaced"),

    /** The owner lost the bus. Best-effort: not delivered if the owner is what vanished. */
    DISCONNECT("disconnect"),
    ;

    companion object {
        fun fromWireValue(value: String): NoticeCloseReason? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One answer the wearer can pick from a notice's platform-rendered action row.
 *
 * Deliberately the same three fields as [ActivityAction], because it is the
 * same affordance in a different tier: the wearer moves along a row of glyphs
 * and confirms one. Two spellings of one idea would be the expensive mistake
 * here, not the duplicated data class.
 */
data class NoticeAction(
    val id: String,
    val glyph: String,
    val label: String,
)

/** One platform-owned, single-line editor rendered below a notice. */
data class NoticeTextInput(
    val id: String,
    val hint: String,
)

/** Text submitted by the wearer. The contents must never appear in logs. */
data class NoticeTextSubmission(
    val surfaceId: String,
    val inputId: String,
    val text: String,
) {
    override fun toString(): String =
        "NoticeTextSubmission(surfaceId=$surfaceId, inputId=$inputId, text=<redacted:${text.length}>)"
}

data class NoticeSurfaceContent(
    val title: String?,
    val body: String?,
    val footer: String?,
    val interactive: Boolean = false,
    val actions: List<NoticeAction> = emptyList(),
    val ttlMs: Long = NoticeSurfaceContract.DEFAULT_TTL_MS,
    val image: ImageSurfaceMetadata? = null,
    /** Whether this new event may ask the glasses hub to wake a dark display. */
    val wakeDisplay: Boolean = false,
    /** Structured alternative to [body]; each entry owns one hard break. */
    val lines: List<String> = emptyList(),
    /** Whether the notice should hide the rest of the glasses display. */
    val backdrop: Boolean = false,
    /** Optional platform-owned editor. Mutually exclusive with gesture replies. */
    val textInput: NoticeTextInput? = null,
) {
    /**
     * Whether the band expects a gesture at all. Actions are an interaction by
     * construction, so offering them is enough: a plugin that ships a choice
     * does not also have to remember to set [interactive], and the glasses have
     * one question to ask before they claim a key.
     */
    val expectsInput: Boolean get() = interactive || actions.isNotEmpty()
}

/**
 * One field of an update. Absent from the payload is not the same as sent empty:
 * absent keeps the current value, present-and-empty clears it. A nullable field
 * cannot express both, so presence is carried by the wrapper and the cleared
 * value by its contents.
 */
@JvmInline
value class NoticeField<T>(val value: T)

data class NoticeSurfacePatch(
    val title: NoticeField<String?>? = null,
    val body: NoticeField<String?>? = null,
    val footer: NoticeField<String?>? = null,
    val interactive: NoticeField<Boolean>? = null,
    val actions: NoticeField<List<NoticeAction>>? = null,
    val ttlMs: NoticeField<Long>? = null,
    val lines: NoticeField<List<String>>? = null,
    val textInput: NoticeField<NoticeTextInput?>? = null,
) {
    // Presence is the test, never the value: `?:` here would treat a field sent
    // empty as a field left out, and clearing a footer would silently keep it.
    fun applyTo(content: NoticeSurfaceContent): NoticeSurfaceContent = content.copy(
        title = if (title != null) title.value else content.title,
        body = when {
            body != null -> body.value
            lines != null -> null
            else -> content.body
        },
        lines = when {
            lines != null -> lines.value
            body != null -> emptyList()
            else -> content.lines
        },
        footer = if (footer != null) footer.value else content.footer,
        interactive = if (interactive != null) interactive.value else content.interactive,
        actions = if (actions != null) actions.value else content.actions,
        ttlMs = if (ttlMs != null) ttlMs.value else content.ttlMs,
        textInput = if (textInput != null) textInput.value else content.textInput,
    )
}

sealed interface NoticeSurfaceValidationResult {
    data class Valid(val content: NoticeSurfaceContent) : NoticeSurfaceValidationResult
    data class Invalid(val reason: String) : NoticeSurfaceValidationResult
}

sealed interface NoticeSurfacePatchResult {
    data class Valid(val patch: NoticeSurfacePatch) : NoticeSurfacePatchResult
    data class Invalid(val reason: String) : NoticeSurfacePatchResult
}

/** Pure notice-surface v4 validation and normalization with no Android dependencies. */
object NoticeSurfaceContract {
    const val KIND = "notice"

    /**
     * v4 is the band that grew into a reading surface — same fields, an
     * eight-fold text budget, sized for the grown band rather than the glance.
     * v3 is the band that knows where a message ends: structured `lines`
     * beside the body. v2 was the paged band — a body four times longer than a
     * glance, an image in the envelope, and a patch that replaces a live notice
     * in place.
     *
     * The bump is what keeps an older pair honest, and the reasoning has not
     * changed since v2. Both sides gate the capability on an exact version
     * match, so glasses still speaking v3 decline the capability outright and
     * the plugin hears CAPABILITY_NOT_AVAILABLE. Left at 3 they would accept the
     * handshake and reject an answer past the old text ceiling instead.
     */
    const val VERSION = 4
    const val LOCAL_SURFACE_ID = "notice"

    const val MAX_TITLE_CHARS = 32
    const val MAX_BODY_CHARS = 8192
    const val MAX_LINES = 64
    const val MAX_FOOTER_CHARS = 40

    /**
     * A label the wearer can read at a glance, and three of them side by side.
     *
     * The row ellipsizes what it cannot fit, so this ceiling is not about the
     * drawing: it is about telling the plugin author their label was too long
     * instead of quietly handing them a chip reading "Confir…".
     */
    const val MAX_ACTION_LABEL_CHARS = 16

    /**
     * The same ceiling an activity's action row has, for the same reason: three
     * glyphs is what the wearer can read and step through without the band
     * turning into a menu. Past it the notice is rejected, never truncated.
     */
    const val MAX_ACTIONS = 3
    const val MAX_TEXT_INPUT_ID_CHARS = 64
    const val MAX_TEXT_INPUT_HINT_CHARS = 48
    const val MAX_TEXT_INPUT_CHARS = 1_024
    // Three bytes per UTF-16 code unit covers every BMP character accepted by
    // the editor's character filter, so a visibly accepted value can always be
    // submitted instead of failing only when Enter is pressed.
    const val MAX_TEXT_INPUT_UTF8_BYTES = 3_072

    const val DEFAULT_TTL_MS = 8_000L
    const val MIN_TTL_MS = 2_000L
    const val MAX_TTL_MS = 45_000L
    const val MIN_DERIVED_TTL_MS = 4_000L
    const val DERIVED_TTL_BASE_MS = 2_000L
    const val DERIVED_TTL_PER_CHAR_MS = 45L

    /**
     * Hard ceiling from the first accepted show. The TTL restarts on every update,
     * so without this a plugin could keep a banner in the wearer's eye forever by
     * updating it — which is precisely what a notice must not be. The glasses
     * enforce it until the wearer deliberately enters engaged reading. An ongoing
     * thing is an activity; see plan 012.
     */
    const val MAX_LIFETIME_MS = 90_000L

    /**
     * Accepted messages per second per plugin, shared between show and update.
     * Sized so a transcript can refresh the body a few times a second without a
     * plugin being able to drive the renderer.
     */
    const val MAX_MESSAGES_PER_SECOND = 5

    const val ERROR_INVALID_NOTICE = "INVALID_NOTICE"
    const val ERROR_NOTICE_RATE_LIMITED = "NOTICE_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    fun hasValidInteraction(content: NoticeSurfaceContent): Boolean =
        content.textInput == null || (!content.interactive && content.actions.isEmpty())

    fun validateShow(
        payload: JSONObject,
        binary: ByteArray? = null,
    ): NoticeSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be notice")
        if (payload.has("body") && payload.has("lines")) {
            return invalid("body and lines are mutually exclusive")
        }

        val title = when (val result = readText(payload, "title", MAX_TITLE_CHARS)) {
            is TextResult.Invalid -> return invalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> result.value
        }
        val body = when (val result = readText(payload, "body", MAX_BODY_CHARS)) {
            is TextResult.Invalid -> return invalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> result.value
        }
        val lines = when (val result = readLines(payload, "lines")) {
            is LinesResult.Invalid -> return invalid(result.reason)
            is LinesResult.Absent -> emptyList()
            is LinesResult.Present -> result.value
        }
        val footer = when (val result = readText(payload, "footer", MAX_FOOTER_CHARS)) {
            is TextResult.Invalid -> return invalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> result.value
        }
        if (title.isNullOrEmpty() && body.isNullOrEmpty() && lines.isEmpty()) {
            return invalid("title, body, or lines must contain text")
        }

        val interactive = when (val value = payload.opt("interactive")) {
            null -> false
            is Boolean -> value
            else -> return invalid("interactive must be a boolean")
        }

        val wakeDisplay = when (val value = payload.opt("wakeDisplay")) {
            null -> false
            is Boolean -> value
            else -> return invalid("wakeDisplay must be a boolean")
        }

        val backdrop = when (val value = payload.opt("backdrop")) {
            null -> false
            is Boolean -> value
            else -> return invalid("backdrop must be a boolean")
        }

        val actions = when (val result = readActions(payload, "actions")) {
            is ActionsResult.Invalid -> return invalid(result.reason)
            is ActionsResult.Absent -> emptyList()
            is ActionsResult.Present -> result.value.orEmpty()
        }
        val textInput = when (val result = readTextInput(payload, "textInput")) {
            is TextInputResult.Invalid -> return invalid(result.reason)
            is TextInputResult.Absent -> null
            is TextInputResult.Present -> result.value
        }
        if (textInput != null && (interactive || actions.isNotEmpty())) {
            return invalid("textInput is mutually exclusive with interactive and actions")
        }

        val ttlMs = when (val value = payload.opt("ttlMs")) {
            null -> derivedTtlMs(
                title.orEmpty().length +
                    body.orEmpty().length +
                    lines.sumOf { it.length + 1 } +
                    footer.orEmpty().length,
            )
            is Number -> integerLong(value)?.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                ?: return invalid("ttlMs must be an integer")
            else -> return invalid("ttlMs must be an integer")
        }

        val image = if (binary != null || IMAGE_FIELDS.any(payload::has)) {
            val imagePayload = JSONObject(payload.toString()).put("kind", ImageSurfaceContract.KIND)
            when (val result = ImageSurfaceContract.validate(imagePayload, binary)) {
                is ImageSurfaceValidationResult.Valid -> result.metadata
                is ImageSurfaceValidationResult.Invalid -> return invalid(result.reason)
            }
        } else {
            null
        }

        return NoticeSurfaceValidationResult.Valid(
            NoticeSurfaceContent(
                title = title?.takeIf { it.isNotEmpty() },
                body = body?.takeIf { it.isNotEmpty() },
                footer = footer?.takeIf { it.isNotEmpty() },
                lines = lines,
                interactive = interactive,
                actions = actions,
                ttlMs = ttlMs,
                image = image,
                wakeDisplay = wakeDisplay,
                backdrop = backdrop,
                textInput = textInput,
            ),
        )
    }

    fun derivedTtlMs(characterCount: Int): Long =
        (DERIVED_TTL_BASE_MS + characterCount.coerceAtLeast(0) * DERIVED_TTL_PER_CHAR_MS)
            .coerceIn(MIN_DERIVED_TTL_MS, MAX_TTL_MS)

    /**
     * An update carries only what changed. Unlike a show it is not required to
     * leave the notice with any text: the caps still apply, but "did the wearer
     * end up with an empty banner" is checked after the patch is applied, by the
     * caller that owns the current content.
     */
    fun validateUpdate(payload: JSONObject): NoticeSurfacePatchResult {
        if (payload.has("kind") && payload.opt("kind") != KIND) {
            return patchInvalid("kind must be notice")
        }
        if (payload.has("wakeDisplay")) {
            return patchInvalid("wakeDisplay is show-only")
        }
        if (payload.has("backdrop")) {
            return patchInvalid("backdrop is show-only")
        }
        if (payload.has("body") && payload.has("lines")) {
            return patchInvalid("body and lines are mutually exclusive")
        }

        val title = when (val result = readText(payload, "title", MAX_TITLE_CHARS)) {
            is TextResult.Invalid -> return patchInvalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> NoticeField(result.value?.takeIf { it.isNotEmpty() })
        }
        val body = when (val result = readText(payload, "body", MAX_BODY_CHARS)) {
            is TextResult.Invalid -> return patchInvalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> NoticeField(result.value?.takeIf { it.isNotEmpty() })
        }
        val lines = when (val result = readLines(payload, "lines")) {
            is LinesResult.Invalid -> return patchInvalid(result.reason)
            is LinesResult.Absent -> null
            is LinesResult.Present -> NoticeField(result.value)
        }
        val footer = when (val result = readText(payload, "footer", MAX_FOOTER_CHARS)) {
            is TextResult.Invalid -> return patchInvalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> NoticeField(result.value?.takeIf { it.isNotEmpty() })
        }

        val interactive = when (val value = payload.opt("interactive")) {
            null -> null
            is Boolean -> NoticeField(value)
            else -> return patchInvalid("interactive must be a boolean")
        }

        val actions = when (val result = readActions(payload, "actions")) {
            is ActionsResult.Invalid -> return patchInvalid(result.reason)
            is ActionsResult.Absent -> null
            is ActionsResult.Present -> NoticeField(result.value.orEmpty())
        }
        val textInput = when (val result = readTextInput(payload, "textInput")) {
            is TextInputResult.Invalid -> return patchInvalid(result.reason)
            is TextInputResult.Absent -> null
            is TextInputResult.Present -> NoticeField(result.value)
        }

        val ttlMs = when (val value = payload.opt("ttlMs")) {
            null -> null
            is Number -> NoticeField(
                integerLong(value)?.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                    ?: return patchInvalid("ttlMs must be an integer"),
            )
            else -> return patchInvalid("ttlMs must be an integer")
        }

        return NoticeSurfacePatchResult.Valid(
            NoticeSurfacePatch(
                title = title,
                body = body,
                lines = lines,
                footer = footer,
                interactive = interactive,
                actions = actions,
                ttlMs = ttlMs,
                textInput = textInput,
            ),
        )
    }

    /**
     * Full state, for a show. Optional fields that are empty, false, or an
     * empty list are omitted, which is safe here because a show replaces
     * everything: absent means "this notice does not have one".
     */
    fun toPayload(surfaceId: String, content: NoticeSurfaceContent): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("ttlMs", content.ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS))
        .apply {
            content.title?.let { put("title", it) }
            content.body?.let { put("body", it) }
            if (content.lines.isNotEmpty()) put("lines", linesJson(content.lines))
            content.footer?.let { put("footer", it) }
            // Omitted when false so a non-interactive payload stays minimal.
            if (content.interactive) put("interactive", true)
            // Waking is opt-in and show-only; old payloads stay byte-for-byte minimal.
            if (content.wakeDisplay) put("wakeDisplay", true)
            // The blackout is likewise opt-in and show-only.
            if (content.backdrop) put("backdrop", true)
            // Omitted when empty for the same reason, and for one more: a notice
            // that offers no choice must put nothing new on the wire, so every
            // banner written before this feature existed still serialises byte
            // for byte the way it did. An activity always sends its array; a
            // notice cannot, because its payload is the compatibility surface.
            if (content.actions.isNotEmpty()) put("actions", actionsJson(content.actions))
            content.textInput?.let { put("textInput", textInputJson(it)) }
            content.image?.let { image ->
                put("imageVersion", ImageSurfaceContract.VERSION)
                put("contentKey", image.contentKey)
                put("mimeType", image.mimeType)
                put("pixelWidth", image.pixelWidth)
                put("pixelHeight", image.pixelHeight)
                put("sha256", image.sha256)
            }
        }

    /**
     * The wire form of a validated update: exactly the fields the owner sent,
     * with a present-but-empty value carrying the clear.
     *
     * An update is a patch all the way down, so the hub relays this rather than
     * re-serialising its canonical state with [toPayload]. That serialisation
     * omits an empty footer, a false flag, and an empty row -- and on a patch an
     * absent key means "leave it alone", so every clear an owner sent used to
     * arrive at the glasses as a no-op.
     *
     * Round-trips through [validateUpdate]: an empty string clears text the same
     * way JSON null does, and both are how the patch spells a cleared field.
     */
    fun toUpdatePayload(surfaceId: String, patch: NoticeSurfacePatch): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .apply {
            patch.title?.let { put("title", it.value.orEmpty()) }
            patch.body?.let { put("body", it.value.orEmpty()) }
            patch.lines?.let { put("lines", linesJson(it.value)) }
            patch.footer?.let { put("footer", it.value.orEmpty()) }
            patch.interactive?.let { put("interactive", it.value) }
            patch.actions?.let { put("actions", actionsJson(it.value)) }
            patch.ttlMs?.let { put("ttlMs", it.value.coerceIn(MIN_TTL_MS, MAX_TTL_MS)) }
            patch.textInput?.let { field ->
                put("textInput", field.value?.let(::textInputJson) ?: JSONObject.NULL)
            }
        }

    /**
     * The wearer picked one of the band's actions. Keyed like every other
     * notice reply, so the owner reads `noticeId` here exactly as it does on
     * `/notice/input` and `/notice/closed`.
     */
    fun actionPayload(surfaceId: String, actionId: String): JSONObject = JSONObject()
        .put("noticeId", surfaceId)
        .put("id", actionId)

    fun textSubmissionPayload(
        surfaceId: String,
        inputId: String,
        text: String,
    ): JSONObject {
        val submission = validateTextSubmission(surfaceId, inputId, text)
            ?: throw IllegalArgumentException("Invalid notice text submission")
        return JSONObject()
            .put("noticeId", submission.surfaceId)
            .put("inputId", submission.inputId)
            .put("text", submission.text)
    }

    fun parseTextSubmission(payload: JSONObject): NoticeTextSubmission? {
        val surfaceId = payload.opt("noticeId") as? String ?: return null
        val inputId = payload.opt("inputId") as? String ?: return null
        val text = payload.opt("text") as? String ?: return null
        return validateTextSubmission(surfaceId, inputId, text)
    }

    fun closedPayload(surfaceId: String, reason: NoticeCloseReason): JSONObject = JSONObject()
        .put("noticeId", surfaceId)
        .put("reason", reason.wireValue)

    private sealed interface TextResult {
        data object Absent : TextResult
        data class Present(val value: String?) : TextResult
        data class Invalid(val reason: String) : TextResult
    }

    private sealed interface ActionsResult {
        data object Absent : ActionsResult
        data class Present(val value: List<NoticeAction>?) : ActionsResult
        data class Invalid(val reason: String) : ActionsResult
    }

    private sealed interface LinesResult {
        data object Absent : LinesResult
        data class Present(val value: List<String>) : LinesResult
        data class Invalid(val reason: String) : LinesResult
    }

    private sealed interface TextInputResult {
        data object Absent : TextInputResult
        data class Present(val value: NoticeTextInput?) : TextInputResult
        data class Invalid(val reason: String) : TextInputResult
    }

    private fun readTextInput(payload: JSONObject, key: String): TextInputResult {
        if (!payload.has(key)) return TextInputResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return TextInputResult.Present(null)
        val value = raw as? JSONObject ?: return TextInputResult.Invalid("$key must be an object")
        val id = (value.opt("id") as? String)?.trim()
            ?: return TextInputResult.Invalid("text input id must be a string")
        if (!TEXT_INPUT_ID.matches(id)) {
            return TextInputResult.Invalid("text input id is invalid")
        }
        val hint = (value.opt("hint") as? String)?.let(::normalizeText)
            ?: return TextInputResult.Invalid("text input hint must be a string")
        if (hint.isEmpty()) return TextInputResult.Invalid("text input hint must contain text")
        if (hint.length > MAX_TEXT_INPUT_HINT_CHARS) {
            return TextInputResult.Invalid(
                "text input hint exceeds $MAX_TEXT_INPUT_HINT_CHARS characters",
            )
        }
        return TextInputResult.Present(NoticeTextInput(id, hint))
    }

    private fun textInputJson(input: NoticeTextInput): JSONObject = JSONObject()
        .put("id", input.id)
        .put("hint", input.hint)

    private fun validateTextSubmission(
        surfaceId: String,
        inputId: String,
        text: String,
    ): NoticeTextSubmission? {
        if (surfaceId.isBlank() || surfaceId.length > 160) return null
        val normalizedId = inputId.trim()
        if (!TEXT_INPUT_ID.matches(normalizedId)) return null
        val normalizedText = text.trim()
        if (normalizedText.isEmpty() || normalizedText.length > MAX_TEXT_INPUT_CHARS) return null
        if (normalizedText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_INPUT_UTF8_BYTES) return null
        return NoticeTextSubmission(surfaceId, normalizedId, normalizedText)
    }

    /**
     * Field-for-field the activity reader, including its refusals: past the cap
     * the whole notice is rejected rather than quietly losing its third choice,
     * and a glyph outside this build's set is accepted as long as the name is
     * well formed, because the renderer degrades it to a dot.
     */
    private fun readActions(payload: JSONObject, key: String): ActionsResult {
        if (!payload.has(key)) return ActionsResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return ActionsResult.Present(null)
        val array = raw as? JSONArray ?: return ActionsResult.Invalid("$key must be an array")
        if (array.length() > MAX_ACTIONS) {
            return ActionsResult.Invalid("$key exceeds $MAX_ACTIONS entries")
        }
        val actions = buildList {
            for (index in 0 until array.length()) {
                val entry = array.opt(index) as? JSONObject
                    ?: return ActionsResult.Invalid("$key must contain objects")
                val id = (entry.opt("id") as? String)?.trim()
                    ?: return ActionsResult.Invalid("action id must be a string")
                if (id.isEmpty()) return ActionsResult.Invalid("action id must contain text")
                val glyph = (entry.opt("glyph") as? String)?.trim()
                    ?: return ActionsResult.Invalid("action glyph must be a string")
                if (!GlyphContract.isWellFormedName(glyph)) {
                    return ActionsResult.Invalid("action glyph is invalid")
                }
                val label = (entry.opt("label") as? String)?.trim()
                    ?: return ActionsResult.Invalid("action label must be a string")
                if (label.isEmpty()) return ActionsResult.Invalid("action label must contain text")
                if (label.length > MAX_ACTION_LABEL_CHARS) {
                    return ActionsResult.Invalid(
                        "action label exceeds $MAX_ACTION_LABEL_CHARS characters",
                    )
                }
                add(NoticeAction(id = id, glyph = glyph, label = label))
            }
        }
        return ActionsResult.Present(actions)
    }

    private fun actionsJson(actions: List<NoticeAction>): JSONArray = JSONArray().apply {
        actions.forEach { action ->
            put(
                JSONObject()
                    .put("id", action.id)
                    .put("glyph", action.glyph)
                    .put("label", action.label),
            )
        }
    }

    private fun readLines(payload: JSONObject, key: String): LinesResult {
        if (!payload.has(key)) return LinesResult.Absent
        val array = payload.opt(key) as? JSONArray
            ?: return LinesResult.Invalid("$key must be an array")
        if (array.length() > MAX_LINES) {
            return LinesResult.Invalid("$key exceeds $MAX_LINES entries")
        }
        val lines = buildList {
            for (index in 0 until array.length()) {
                val raw = array.opt(index) as? String
                    ?: return LinesResult.Invalid("$key must contain strings")
                val normalized = normalizeText(raw)
                if (normalized.isNotEmpty()) add(normalized)
            }
        }
        if (lines.sumOf { it.length.toLong() + 1L } > MAX_BODY_CHARS) {
            return LinesResult.Invalid("$key exceeds $MAX_BODY_CHARS character budget")
        }
        return LinesResult.Present(lines)
    }

    private fun linesJson(lines: List<String>): JSONArray = JSONArray().apply {
        lines.forEach { line -> put(line) }
    }

    private fun readText(payload: JSONObject, key: String, maxChars: Int): TextResult {
        if (!payload.has(key)) return TextResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return TextResult.Present(null)
        val text = raw as? String ?: return TextResult.Invalid("$key must be a string")
        // Newlines collapse to spaces: the renderer owns wrapping and paging,
        // and a plugin cannot be allowed to lay the band out by hand.
        val normalized = normalizeText(text)
        if (normalized.length > maxChars) return TextResult.Invalid("$key exceeds $maxChars characters")
        return TextResult.Present(normalized)
    }

    private fun normalizeText(text: String): String = text.replace(NEWLINES, " ").trim()

    private fun integerLong(number: Number): Long? {
        val double = number.toDouble()
        val long = number.toLong()
        return long.takeIf { double.isFinite() && double == long.toDouble() }
    }

    private fun invalid(reason: String) = NoticeSurfaceValidationResult.Invalid(reason)

    private fun patchInvalid(reason: String) = NoticeSurfacePatchResult.Invalid(reason)

    private val NEWLINES = Regex("[\\r\\n]+")
    private val TEXT_INPUT_ID =
        Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,${MAX_TEXT_INPUT_ID_CHARS - 1}}")
    private val IMAGE_FIELDS = listOf(
        "imageVersion",
        "contentKey",
        "mimeType",
        "pixelWidth",
        "pixelHeight",
        "sha256",
    )
}
