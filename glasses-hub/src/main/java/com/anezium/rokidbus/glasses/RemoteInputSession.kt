package com.anezium.rokidbus.glasses

import android.view.inputmethod.EditorInfo
import java.util.UUID

/** Process-private proof that an editor was created by the hub notice renderer. */
internal object NoticeTextInputImeTrust {
    val privateImeOptions: String =
        "com.anezium.rokidbus.NOTICE_TEXT_INPUT:${UUID.randomUUID()}"

    fun requestsPhoneKeyboard(
        packageName: String?,
        privateOptions: String?,
        hubPackageName: String,
    ): Boolean =
        packageName == hubPackageName && privateOptions == privateImeOptions
}

internal data class RemoteInputSessionState(
    val active: Boolean = false,
    val sessionId: String = "",
    val packageName: String = "",
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val nextSequence: Long = 0L,
    val autoOpenPhoneKeyboard: Boolean = false,
)

internal enum class RemoteInputResultCode {
    APPLIED,
    NO_ACTIVE_SESSION,
    SESSION_MISMATCH,
    DUPLICATE_SEQUENCE,
    OUT_OF_ORDER_SEQUENCE,
    INVALID_COMMAND,
    INPUT_CONNECTION_UNAVAILABLE,
    INPUT_CONNECTION_REJECTED,
}

internal data class RemoteInputResult(
    val code: RemoteInputResultCode,
    val sessionId: String,
    val sequence: Long,
    val nextSequence: Long,
) {
    val applied: Boolean
        get() = code == RemoteInputResultCode.APPLIED
}

/** Pure session/ordering state. It deliberately never receives or retains user-entered text. */
internal class RemoteInputSessionGate {
    var sessionId: String = ""
        private set
    var nextSequence: Long = 0L
        private set

    val active: Boolean
        get() = sessionId.isNotEmpty()

    fun open(newSessionId: String) {
        require(newSessionId.isNotBlank())
        sessionId = newSessionId
        nextSequence = FIRST_SEQUENCE
    }

    fun close() {
        sessionId = ""
        nextSequence = 0L
    }

    fun evaluate(candidateSessionId: String, sequence: Long): RemoteInputResultCode = when {
        !active -> RemoteInputResultCode.NO_ACTIVE_SESSION
        candidateSessionId != sessionId -> RemoteInputResultCode.SESSION_MISMATCH
        sequence < nextSequence -> RemoteInputResultCode.DUPLICATE_SEQUENCE
        sequence > nextSequence -> RemoteInputResultCode.OUT_OF_ORDER_SEQUENCE
        else -> RemoteInputResultCode.APPLIED
    }

    fun advance(sequence: Long) {
        check(active && sequence == nextSequence)
        nextSequence += 1L
    }

    companion object {
        const val FIRST_SEQUENCE = 1L
    }
}

/** Bounds untrusted phone input before it reaches Android's InputConnection. */
internal object RemoteInputPolicy {
    const val MAX_TEXT_CODE_UNITS = 256
    const val MAX_DELETE_CODE_UNITS = 2_048

    fun acceptsText(text: CharSequence, newCursorPosition: Int): Boolean =
        text.isNotEmpty() &&
            text.length <= MAX_TEXT_CODE_UNITS &&
            newCursorPosition in -MAX_TEXT_CODE_UNITS..MAX_TEXT_CODE_UNITS

    fun acceptsComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
        text.length <= MAX_TEXT_CODE_UNITS &&
            newCursorPosition in -MAX_TEXT_CODE_UNITS..MAX_TEXT_CODE_UNITS

    fun acceptsDelete(beforeLength: Int, afterLength: Int): Boolean =
        beforeLength in 0..MAX_DELETE_CODE_UNITS &&
            afterLength in 0..MAX_DELETE_CODE_UNITS &&
            (beforeLength != 0 || afterLength != 0)

    fun acceptsEditorAction(actionId: Int): Boolean =
        actionId in EditorInfo.IME_ACTION_UNSPECIFIED..EditorInfo.IME_ACTION_PREVIOUS
}

internal object RemoteInputMetadataPolicy {
    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun sanitizePackageName(value: String?): String {
        val candidate = value.orEmpty().take(MAX_PACKAGE_NAME_LENGTH)
        return candidate.takeIf(PACKAGE_NAME::matches).orEmpty()
    }
}
