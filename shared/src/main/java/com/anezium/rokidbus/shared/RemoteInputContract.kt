package com.anezium.rokidbus.shared

import org.json.JSONObject

enum class RemoteEditorAction(val wireValue: String) {
    UNSPECIFIED("unspecified"),
    NONE("none"),
    GO("go"),
    SEARCH("search"),
    SEND("send"),
    NEXT("next"),
    DONE("done"),
    PREVIOUS("previous"),
    ;

    companion object {
        fun fromWireValue(value: String): RemoteEditorAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RemoteInputCloseReason(val wireValue: String) {
    USER_DISMISSED("user_dismissed"),
    EDITOR_FINISHED("editor_finished"),
    FOCUS_LOST("focus_lost"),
    SUPERSEDED("superseded"),
    TRANSPORT_LOST("transport_lost"),
    ;

    companion object {
        fun fromWireValue(value: String): RemoteInputCloseReason? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RemoteInputStatusCode(val wireValue: String) {
    READY("ready"),
    APPLIED("applied"),
    REJECTED("rejected"),
    CLOSED("closed"),
    ;

    companion object {
        fun fromWireValue(value: String): RemoteInputStatusCode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RemoteInputErrorCode(val wireValue: String) {
    NO_ACTIVE_SESSION("no_active_session"),
    SESSION_MISMATCH("session_mismatch"),
    DUPLICATE_SEQUENCE("duplicate_sequence"),
    OUT_OF_ORDER_SEQUENCE("out_of_order_sequence"),
    UNSUPPORTED("unsupported"),
    INVALID_COMMAND("invalid_command"),
    INPUT_CONNECTION_UNAVAILABLE("input_connection_unavailable"),
    INPUT_CONNECTION_REJECTED("input_connection_rejected"),
    INTERNAL("internal"),
    ;

    companion object {
        fun fromWireValue(value: String): RemoteInputErrorCode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class RemoteInputSessionOpen(
    val sessionId: String,
    val packageName: String?,
    val inputType: Int,
    val imeOptions: Int,
    val sensitive: Boolean,
    val nextSequence: Long = 1L,
    /** Trusted hub-owned field explicitly asks the phone UI to come forward. */
    val autoOpenPhoneKeyboard: Boolean = false,
)

data class RemoteInputSessionClosed(
    val sessionId: String,
    val reason: RemoteInputCloseReason,
    val lastAppliedSequence: Long,
)

data class RemoteInputStatus(
    val sessionId: String,
    val status: RemoteInputStatusCode,
    val acknowledgedSequence: Long,
    val expectedSequence: Long? = null,
    val errorCode: RemoteInputErrorCode? = null,
)

/**
 * One transient operation sent to the active glasses InputConnection.
 *
 * Commands intentionally carry deltas, never a document snapshot. Callers must not persist or
 * log encoded command JSON. Text-bearing implementations redact [toString] as a final guard.
 */
sealed class RemoteInputCommand {
    abstract val sessionId: String
    abstract val sequence: Long

    data class CommitText(
        override val sessionId: String,
        override val sequence: Long,
        val text: String,
        val newCursorPosition: Int = 1,
    ) : RemoteInputCommand() {
        override fun toString(): String =
            "CommitText(sessionId=$sessionId, sequence=$sequence, text=<redacted:${text.length}>, " +
                "newCursorPosition=$newCursorPosition)"
    }

    data class SetComposingText(
        override val sessionId: String,
        override val sequence: Long,
        val text: String,
        val newCursorPosition: Int = 1,
    ) : RemoteInputCommand() {
        override fun toString(): String =
            "SetComposingText(sessionId=$sessionId, sequence=$sequence, " +
                "text=<redacted:${text.length}>, newCursorPosition=$newCursorPosition)"
    }

    data class FinishComposingText(
        override val sessionId: String,
        override val sequence: Long,
    ) : RemoteInputCommand()

    data class DeleteSurroundingText(
        override val sessionId: String,
        override val sequence: Long,
        val beforeLength: Int,
        val afterLength: Int,
    ) : RemoteInputCommand()

    data class PerformEditorAction(
        override val sessionId: String,
        override val sequence: Long,
        val action: RemoteEditorAction,
    ) : RemoteInputCommand()

    data class Close(
        override val sessionId: String,
        override val sequence: Long,
        val reason: RemoteInputCloseReason,
    ) : RemoteInputCommand()

}

object RemoteInputContract {
    const val VERSION = 1

    /** Glasses IME -> phone: lifecycle of the editor-owned session. */
    const val SESSION_PATH = "/core/remote-input/session"

    /** Phone -> glasses: immediate input/edit/navigation deltas. */
    const val COMMAND_PATH = "/core/remote-input/command"

    /** Glasses -> phone: readiness, cumulative acknowledgement, rejection, and closure. */
    const val STATUS_PATH = "/core/remote-input/status"

    const val MAX_SESSION_ID_LENGTH = 128
    const val MAX_TEXT_UTF16_LENGTH = 256
    const val MAX_TEXT_UTF8_BYTES = 512
    const val MAX_DELETE_LENGTH = 2048
    const val MAX_CURSOR_OFFSET = 2048
    const val MAX_MESSAGE_CHARS = 4096
    const val MAX_MESSAGE_BYTES = BusConstants.CXR_CONTROL_MAX_BYTES
    const val MAX_SEQUENCE = 9_007_199_254_740_991L

    private const val TYPE_SESSION_OPEN = "session_open"
    private const val TYPE_SESSION_CLOSED = "session_closed"
    private const val TYPE_COMMIT_TEXT = "commit_text"
    private const val TYPE_SET_COMPOSING_TEXT = "set_composing_text"
    private const val TYPE_FINISH_COMPOSING_TEXT = "finish_composing_text"
    private const val TYPE_DELETE_SURROUNDING_TEXT = "delete_surrounding_text"
    private const val TYPE_PERFORM_EDITOR_ACTION = "perform_editor_action"
    private const val TYPE_CLOSE = "close"
    private const val TYPE_STATUS = "status"

    private val sessionIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{15,127}")
    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun encodeSessionOpen(value: RemoteInputSessionOpen): JSONObject {
        require(isValidSessionId(value.sessionId)) { "Invalid remote-input session id" }
        require(value.packageName == null || isValidPackageName(value.packageName)) {
            "Invalid target package"
        }
        require(isValidCommandSequence(value.nextSequence)) { "Invalid next sequence" }
        return base(TYPE_SESSION_OPEN, value.sessionId, 0L)
            .putOpt("packageName", value.packageName)
            .put("inputType", value.inputType)
            .put("imeOptions", value.imeOptions)
            .put("sensitive", value.sensitive)
            .put("nextSequence", value.nextSequence)
            .apply {
                if (value.autoOpenPhoneKeyboard) put("autoOpenPhoneKeyboard", true)
            }
    }

    fun decodeSessionOpen(payload: JSONObject): RemoteInputSessionOpen? {
        if (!hasValidEnvelope(payload, TYPE_SESSION_OPEN, expectedSequence = 0L)) return null
        val packageName = if (payload.has("packageName")) {
            payload.strictString("packageName")?.takeIf(::isValidPackageName) ?: return null
        } else {
            null
        }
        val inputType = payload.strictInt("inputType") ?: return null
        val imeOptions = payload.strictInt("imeOptions") ?: return null
        val sensitive = payload.strictBoolean("sensitive") ?: return null
        val nextSequence = payload.strictLong("nextSequence")
            ?.takeIf(::isValidCommandSequence) ?: return null
        val autoOpenPhoneKeyboard = when (val raw = payload.opt("autoOpenPhoneKeyboard")) {
            null -> false
            is Boolean -> raw
            else -> return null
        }
        return RemoteInputSessionOpen(
            sessionId = payload.getString("sessionId"),
            packageName = packageName,
            inputType = inputType,
            imeOptions = imeOptions,
            sensitive = sensitive,
            nextSequence = nextSequence,
            autoOpenPhoneKeyboard = autoOpenPhoneKeyboard,
        )
    }

    fun encodeSessionClosed(value: RemoteInputSessionClosed): JSONObject {
        require(isValidSessionId(value.sessionId)) { "Invalid remote-input session id" }
        require(isValidAcknowledgedSequence(value.lastAppliedSequence)) { "Invalid sequence" }
        return base(TYPE_SESSION_CLOSED, value.sessionId, value.lastAppliedSequence)
            .put("reason", value.reason.wireValue)
    }

    fun decodeSessionClosed(payload: JSONObject): RemoteInputSessionClosed? {
        if (!hasValidEnvelope(payload, TYPE_SESSION_CLOSED)) return null
        val sequence = payload.strictLong("sequence")
            ?.takeIf(::isValidAcknowledgedSequence) ?: return null
        val reason = RemoteInputCloseReason.fromWireValue(
            payload.strictString("reason") ?: return null,
        ) ?: return null
        return RemoteInputSessionClosed(payload.getString("sessionId"), reason, sequence)
    }

    fun encodeCommand(command: RemoteInputCommand): JSONObject {
        require(isValidSessionId(command.sessionId)) { "Invalid remote-input session id" }
        require(isValidCommandSequence(command.sequence)) { "Invalid sequence" }
        val payload = when (command) {
            is RemoteInputCommand.CommitText -> {
                require(isValidTextDelta(command.text, allowEmpty = false)) { "Invalid text delta" }
                require(command.newCursorPosition in -MAX_CURSOR_OFFSET..MAX_CURSOR_OFFSET) {
                    "Invalid cursor offset"
                }
                base(TYPE_COMMIT_TEXT, command.sessionId, command.sequence)
                    .put("text", command.text)
                    .put("newCursorPosition", command.newCursorPosition)
            }

            is RemoteInputCommand.SetComposingText -> {
                require(isValidTextDelta(command.text, allowEmpty = true)) { "Invalid composing delta" }
                require(command.newCursorPosition in -MAX_CURSOR_OFFSET..MAX_CURSOR_OFFSET) {
                    "Invalid cursor offset"
                }
                base(TYPE_SET_COMPOSING_TEXT, command.sessionId, command.sequence)
                    .put("text", command.text)
                    .put("newCursorPosition", command.newCursorPosition)
            }

            is RemoteInputCommand.FinishComposingText ->
                base(TYPE_FINISH_COMPOSING_TEXT, command.sessionId, command.sequence)

            is RemoteInputCommand.DeleteSurroundingText -> {
                require(command.beforeLength in 0..MAX_DELETE_LENGTH) { "Invalid before length" }
                require(command.afterLength in 0..MAX_DELETE_LENGTH) { "Invalid after length" }
                require(command.beforeLength > 0 || command.afterLength > 0) { "Empty delete" }
                base(TYPE_DELETE_SURROUNDING_TEXT, command.sessionId, command.sequence)
                    .put("beforeLength", command.beforeLength)
                    .put("afterLength", command.afterLength)
            }

            is RemoteInputCommand.PerformEditorAction ->
                base(TYPE_PERFORM_EDITOR_ACTION, command.sessionId, command.sequence)
                    .put("action", command.action.wireValue)

            is RemoteInputCommand.Close ->
                base(TYPE_CLOSE, command.sessionId, command.sequence)
                    .put("reason", command.reason.wireValue)

        }
        require(isWithinMessageLimit(payload)) { "Remote-input payload too large" }
        return payload
    }

    fun decodeCommand(payload: JSONObject): RemoteInputCommand? {
        if (!hasValidEnvelope(payload)) return null
        val sessionId = payload.getString("sessionId")
        val sequence = payload.strictLong("sequence")?.takeIf(::isValidCommandSequence) ?: return null
        return when (payload.getString("type")) {
            TYPE_COMMIT_TEXT -> {
                val text = payload.strictString("text")
                    ?.takeIf { isValidTextDelta(it, allowEmpty = false) } ?: return null
                val cursor = payload.strictInt("newCursorPosition")
                    ?.takeIf { it in -MAX_CURSOR_OFFSET..MAX_CURSOR_OFFSET } ?: return null
                RemoteInputCommand.CommitText(sessionId, sequence, text, cursor)
            }

            TYPE_SET_COMPOSING_TEXT -> {
                val text = payload.strictString("text")
                    ?.takeIf { isValidTextDelta(it, allowEmpty = true) } ?: return null
                val cursor = payload.strictInt("newCursorPosition")
                    ?.takeIf { it in -MAX_CURSOR_OFFSET..MAX_CURSOR_OFFSET } ?: return null
                RemoteInputCommand.SetComposingText(sessionId, sequence, text, cursor)
            }

            TYPE_FINISH_COMPOSING_TEXT -> RemoteInputCommand.FinishComposingText(sessionId, sequence)
            TYPE_DELETE_SURROUNDING_TEXT -> {
                val before = payload.strictInt("beforeLength")
                    ?.takeIf { it in 0..MAX_DELETE_LENGTH } ?: return null
                val after = payload.strictInt("afterLength")
                    ?.takeIf { it in 0..MAX_DELETE_LENGTH } ?: return null
                if (before == 0 && after == 0) return null
                RemoteInputCommand.DeleteSurroundingText(sessionId, sequence, before, after)
            }

            TYPE_PERFORM_EDITOR_ACTION -> {
                val action = RemoteEditorAction.fromWireValue(
                    payload.strictString("action") ?: return null,
                ) ?: return null
                RemoteInputCommand.PerformEditorAction(sessionId, sequence, action)
            }

            TYPE_CLOSE -> {
                val reason = RemoteInputCloseReason.fromWireValue(
                    payload.strictString("reason") ?: return null,
                ) ?: return null
                RemoteInputCommand.Close(sessionId, sequence, reason)
            }

            else -> null
        }
    }

    fun encodeStatus(value: RemoteInputStatus): JSONObject {
        require(isValidSessionId(value.sessionId)) { "Invalid remote-input session id" }
        require(isValidAcknowledgedSequence(value.acknowledgedSequence)) { "Invalid sequence" }
        require(value.expectedSequence == null || isValidCommandSequence(value.expectedSequence)) {
            "Invalid expected sequence"
        }
        require((value.status == RemoteInputStatusCode.REJECTED) == (value.errorCode != null)) {
            "Only rejected statuses carry an error"
        }
        require(value.expectedSequence == null || value.status == RemoteInputStatusCode.REJECTED) {
            "Only rejected statuses carry an expected sequence"
        }
        return base(TYPE_STATUS, value.sessionId, value.acknowledgedSequence)
            .put("status", value.status.wireValue)
            .putOpt("expectedSequence", value.expectedSequence)
            .putOpt("errorCode", value.errorCode?.wireValue)
    }

    fun decodeStatus(payload: JSONObject): RemoteInputStatus? {
        if (!hasValidEnvelope(payload, TYPE_STATUS)) return null
        val status = RemoteInputStatusCode.fromWireValue(
            payload.strictString("status") ?: return null,
        ) ?: return null
        val acknowledged = payload.strictLong("sequence")
            ?.takeIf(::isValidAcknowledgedSequence) ?: return null
        val expected = if (payload.has("expectedSequence")) {
            payload.strictLong("expectedSequence")?.takeIf(::isValidCommandSequence) ?: return null
        } else {
            null
        }
        val error = if (payload.has("errorCode")) {
            RemoteInputErrorCode.fromWireValue(payload.strictString("errorCode") ?: return null)
                ?: return null
        } else {
            null
        }
        if ((status == RemoteInputStatusCode.REJECTED) != (error != null)) return null
        if (expected != null && status != RemoteInputStatusCode.REJECTED) return null
        return RemoteInputStatus(payload.getString("sessionId"), status, acknowledged, expected, error)
    }

    /** Stateless guard used by receivers before touching the active InputConnection. */
    fun accepts(
        activeSessionId: String,
        lastAppliedSequence: Long,
        command: RemoteInputCommand,
    ): Boolean =
        isValidSessionId(activeSessionId) &&
            command.sessionId == activeSessionId &&
            isValidAcknowledgedSequence(lastAppliedSequence) &&
            lastAppliedSequence < MAX_SEQUENCE &&
            command.sequence == lastAppliedSequence + 1L

    fun isValidSessionId(value: String): Boolean = sessionIdPattern.matches(value)

    private fun isValidPackageName(value: String): Boolean =
        value.length <= 255 && packageNamePattern.matches(value)

    private fun base(type: String, sessionId: String, sequence: Long): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("type", type)
        .put("sessionId", sessionId)
        .put("sequence", sequence)

    private fun hasValidEnvelope(
        payload: JSONObject,
        expectedType: String? = null,
        expectedSequence: Long? = null,
    ): Boolean {
        if (!isWithinMessageLimit(payload)) return false
        if (payload.strictInt("version") != VERSION) return false
        val type = payload.strictString("type") ?: return false
        if (expectedType != null && type != expectedType) return false
        val sessionId = payload.strictString("sessionId") ?: return false
        if (!isValidSessionId(sessionId)) return false
        val sequence = payload.strictLong("sequence") ?: return false
        return expectedSequence == null || sequence == expectedSequence
    }

    private fun isValidCommandSequence(value: Long): Boolean = value in 1..MAX_SEQUENCE

    private fun isValidAcknowledgedSequence(value: Long): Boolean = value in 0..MAX_SEQUENCE

    private fun isWithinMessageLimit(payload: JSONObject): Boolean {
        val encoded = payload.toString()
        return encoded.length <= MAX_MESSAGE_CHARS &&
            encoded.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES
    }

    private fun isValidTextDelta(value: String, allowEmpty: Boolean): Boolean {
        if ((!allowEmpty && value.isEmpty()) || value.length > MAX_TEXT_UTF16_LENGTH) return false
        if (value.toByteArray(Charsets.UTF_8).size > MAX_TEXT_UTF8_BYTES || '\u0000' in value) return false
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }

                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.strictLong(key: String): Long? = when (val value = opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}
