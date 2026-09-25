package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import java.nio.charset.StandardCharsets

/**
 * Private, process-local edge between the phone UI and [BusHubService].
 *
 * The service owns transport. The activity owns neither CXR nor the remote field contents, and
 * sends only incremental edits. Every intent is package-scoped so typed text cannot be observed by
 * another application. The bridge must never log an edit intent or its extras.
 */
object RemoteInputPhoneContract {
    const val VERSION = 1

    const val ACTION_COMMAND = "com.anezium.rokidbus.phone.remoteinput.COMMAND"
    const val ACTION_STATE = "com.anezium.rokidbus.phone.remoteinput.STATE"

    const val COMMAND_REQUEST_STATE = "request_state"
    const val COMMAND_COMMIT_TEXT = "commit_text"
    const val COMMAND_SET_COMPOSING_TEXT = "set_composing_text"
    const val COMMAND_FINISH_COMPOSING = "finish_composing"
    const val COMMAND_DELETE_SURROUNDING = "delete_surrounding"
    const val COMMAND_PERFORM_EDITOR_ACTION = "perform_editor_action"
    const val COMMAND_CLOSE = "close"

    const val ACTION_NAVIGATE = "com.anezium.rokidbus.phone.remoteinput.NAVIGATE"

    const val EDITOR_ENTER = "enter"
    const val EDITOR_NEXT = "next"

    const val KEY_PREVIOUS = "previous"
    const val KEY_NEXT = "next"
    const val KEY_SELECT = "select"
    const val KEY_BACK = "back"
    const val KEY_UP = "up"
    const val KEY_DOWN = "down"
    const val KEY_LEFT = "left"
    const val KEY_RIGHT = "right"

    internal val NAVIGATION_KEYS = setOf(
        KEY_PREVIOUS,
        KEY_NEXT,
        KEY_SELECT,
        KEY_BACK,
        KEY_UP,
        KEY_DOWN,
        KEY_LEFT,
        KEY_RIGHT,
    )

    const val IME_ACTION_NONE = "none"
    const val IME_ACTION_ENTER = "enter"
    const val IME_ACTION_NEXT = "next"
    const val IME_ACTION_DONE = "done"

    private const val EXTRA_VERSION = "version"
    private const val EXTRA_COMMAND = "command"
    private const val EXTRA_SESSION_ID = "session_id"
    private const val EXTRA_SEQUENCE = "sequence"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_BEFORE_LENGTH = "before_length"
    private const val EXTRA_AFTER_LENGTH = "after_length"
    private const val EXTRA_EDITOR_ACTION = "editor_action"
    private const val EXTRA_REQUEST_ID = "request_id"
    private const val EXTRA_NAVIGATION_ACTION = "navigation_action"
    private const val EXTRA_CONNECTED = "connected"
    private const val EXTRA_FIELD_ACTIVE = "field_active"
    private const val EXTRA_PASSWORD = "password"
    private const val EXTRA_FIELD_LABEL = "field_label"
    private const val EXTRA_IME_ACTION = "ime_action"
    private const val EXTRA_KEYBOARD_REQUESTED = "keyboard_requested"

    fun requestState(context: Context): Intent = commandIntent(context, COMMAND_REQUEST_STATE)

    fun commitText(
        context: Context,
        sessionId: String,
        sequence: Long,
        text: String,
    ): Intent = sessionCommandIntent(context, COMMAND_COMMIT_TEXT, sessionId, sequence)
        .putExtra(EXTRA_TEXT, text.requireTextDelta())

    fun setComposingText(
        context: Context,
        sessionId: String,
        sequence: Long,
        text: String,
    ): Intent = sessionCommandIntent(context, COMMAND_SET_COMPOSING_TEXT, sessionId, sequence)
        .putExtra(EXTRA_TEXT, text.requireTextDelta())

    fun finishComposing(context: Context, sessionId: String, sequence: Long): Intent =
        sessionCommandIntent(context, COMMAND_FINISH_COMPOSING, sessionId, sequence)

    fun deleteSurrounding(
        context: Context,
        sessionId: String,
        sequence: Long,
        beforeLength: Int,
        afterLength: Int,
    ): Intent = sessionCommandIntent(context, COMMAND_DELETE_SURROUNDING, sessionId, sequence)
        .putExtra(EXTRA_BEFORE_LENGTH, beforeLength.coerceIn(0, MAX_TEXT_DELTA_UTF16))
        .putExtra(EXTRA_AFTER_LENGTH, afterLength.coerceIn(0, MAX_TEXT_DELTA_UTF16))

    fun editorAction(
        context: Context,
        sessionId: String,
        sequence: Long,
        action: String,
    ): Intent = sessionCommandIntent(context, COMMAND_PERFORM_EDITOR_ACTION, sessionId, sequence)
        .putExtra(EXTRA_EDITOR_ACTION, action)

    fun close(context: Context, sessionId: String, sequence: Long): Intent =
        sessionCommandIntent(context, COMMAND_CLOSE, sessionId, sequence)

    fun navigate(context: Context, requestId: String, action: String): Intent =
        Intent(ACTION_NAVIGATE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_VERSION, VERSION)
            .putExtra(EXTRA_REQUEST_ID, requestId.take(MAX_REQUEST_ID_LENGTH))
            .putExtra(EXTRA_NAVIGATION_ACTION, action)

    fun parseCommand(intent: Intent): PhoneRemoteCommand? {
        if (intent.action != ACTION_COMMAND || intent.getIntExtra(EXTRA_VERSION, -1) != VERSION) {
            return null
        }
        if (intent.getStringExtra(EXTRA_COMMAND) == COMMAND_REQUEST_STATE) {
            return PhoneRemoteCommand.RequestState
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).normalizedSessionId() ?: return null
        val sequence = intent.getLongExtra(EXTRA_SEQUENCE, -1L).takeIf { it > 0L } ?: return null
        return when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_COMMIT_TEXT -> intent.validTextDelta()?.let {
                PhoneRemoteCommand.CommitText(sessionId, sequence, it)
            }
            COMMAND_SET_COMPOSING_TEXT -> intent.validTextDelta()?.let {
                PhoneRemoteCommand.SetComposingText(sessionId, sequence, it)
            }
            COMMAND_FINISH_COMPOSING -> PhoneRemoteCommand.FinishComposing(sessionId, sequence)
            COMMAND_DELETE_SURROUNDING -> {
                val before = intent.getIntExtra(EXTRA_BEFORE_LENGTH, -1)
                val after = intent.getIntExtra(EXTRA_AFTER_LENGTH, -1)
                if (before !in 0..MAX_TEXT_DELTA_UTF16 || after !in 0..MAX_TEXT_DELTA_UTF16) {
                    null
                } else {
                    PhoneRemoteCommand.DeleteSurrounding(sessionId, sequence, before, after)
                }
            }
            COMMAND_PERFORM_EDITOR_ACTION -> when (val action = intent.getStringExtra(EXTRA_EDITOR_ACTION)) {
                EDITOR_ENTER,
                EDITOR_NEXT,
                -> PhoneRemoteCommand.PerformEditorAction(sessionId, sequence, action)
                else -> null
            }
            COMMAND_CLOSE -> PhoneRemoteCommand.Close(sessionId, sequence)
            else -> null
        }
    }

    fun parseNavigation(intent: Intent): PhoneRemoteNavigation? {
        if (intent.action != ACTION_NAVIGATE || intent.getIntExtra(EXTRA_VERSION, -1) != VERSION) {
            return null
        }
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            ?.trim()
            ?.take(MAX_REQUEST_ID_LENGTH)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val action = intent.getStringExtra(EXTRA_NAVIGATION_ACTION)
            ?.takeIf { it in NAVIGATION_KEYS }
            ?: return null
        return PhoneRemoteNavigation(requestId, action)
    }

    private fun sessionCommandIntent(
        context: Context,
        command: String,
        sessionId: String,
        sequence: Long,
    ): Intent = commandIntent(context, command)
        .putExtra(EXTRA_SESSION_ID, sessionId)
        .putExtra(EXTRA_SEQUENCE, sequence)

    fun stateIntent(context: Context, state: RemoteInputTransportState): Intent =
        Intent(ACTION_STATE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_VERSION, VERSION)
            .putExtra(EXTRA_CONNECTED, state.connected)
            .putExtra(EXTRA_FIELD_ACTIVE, state.fieldActive)
            .putExtra(EXTRA_PASSWORD, state.password)
            .putExtra(EXTRA_SESSION_ID, state.sessionId)
            .putExtra(EXTRA_FIELD_LABEL, state.fieldLabel)
            .putExtra(EXTRA_IME_ACTION, state.imeAction)
            .putExtra(EXTRA_KEYBOARD_REQUESTED, state.keyboardRequested)

    fun parseState(intent: Intent): RemoteInputTransportState? {
        if (intent.action != ACTION_STATE || intent.getIntExtra(EXTRA_VERSION, -1) != VERSION) {
            return null
        }
        val active = intent.getBooleanExtra(EXTRA_FIELD_ACTIVE, false)
        val session = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.trim()
            ?.take(MAX_SESSION_ID_LENGTH)
            ?.takeIf(String::isNotEmpty)
        if (active && session == null) return null
        return RemoteInputTransportState(
            connected = intent.getBooleanExtra(EXTRA_CONNECTED, false),
            fieldActive = active,
            password = active && intent.getBooleanExtra(EXTRA_PASSWORD, false),
            sessionId = session,
            fieldLabel = intent.getStringExtra(EXTRA_FIELD_LABEL)
                ?.trim()
                ?.take(MAX_FIELD_LABEL_LENGTH)
                ?.takeIf(String::isNotEmpty),
            imeAction = intent.getStringExtra(EXTRA_IME_ACTION).normalizeImeAction(),
            keyboardRequested = active && intent.getBooleanExtra(EXTRA_KEYBOARD_REQUESTED, false),
        )
    }

    private fun commandIntent(context: Context, command: String): Intent =
        Intent(ACTION_COMMAND)
            .setPackage(context.packageName)
            .putExtra(EXTRA_VERSION, VERSION)
            .putExtra(EXTRA_COMMAND, command)

    private fun String.requireTextDelta(): String {
        require(
            length <= MAX_TEXT_DELTA_UTF16 &&
                toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_DELTA_UTF8,
        ) { "Text delta exceeds protocol limit" }
        return this
    }

    private fun Intent.validTextDelta(): String? =
        getStringExtra(EXTRA_TEXT)?.takeIf {
            it.length <= MAX_TEXT_DELTA_UTF16 &&
                it.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_DELTA_UTF8
        }

    private fun String?.normalizedSessionId(): String? = this
        ?.trim()
        ?.take(MAX_SESSION_ID_LENGTH)
        ?.takeIf(String::isNotEmpty)

    private fun String?.normalizeImeAction(): String = when (this) {
        IME_ACTION_ENTER,
        IME_ACTION_NEXT,
        IME_ACTION_DONE,
        -> this
        else -> IME_ACTION_NONE
    }

    private const val MAX_SESSION_ID_LENGTH = 128
    private const val MAX_FIELD_LABEL_LENGTH = 80
    private const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_TEXT_DELTA_UTF16 = 256
    const val MAX_TEXT_DELTA_UTF8 = 512
}

sealed interface PhoneRemoteCommand {
    data object RequestState : PhoneRemoteCommand
    data class CommitText(val sessionId: String, val sequence: Long, val text: String) : PhoneRemoteCommand
    data class SetComposingText(val sessionId: String, val sequence: Long, val text: String) : PhoneRemoteCommand
    data class FinishComposing(val sessionId: String, val sequence: Long) : PhoneRemoteCommand
    data class DeleteSurrounding(
        val sessionId: String,
        val sequence: Long,
        val beforeLength: Int,
        val afterLength: Int,
    ) : PhoneRemoteCommand
    data class PerformEditorAction(
        val sessionId: String,
        val sequence: Long,
        val action: String,
    ) : PhoneRemoteCommand
    data class Close(val sessionId: String, val sequence: Long) : PhoneRemoteCommand
}

data class PhoneRemoteNavigation(val requestId: String, val action: String)

data class RemoteInputTransportState(
    val connected: Boolean,
    val fieldActive: Boolean,
    val password: Boolean = false,
    val sessionId: String? = null,
    val fieldLabel: String? = null,
    val imeAction: String = RemoteInputPhoneContract.IME_ACTION_NONE,
    val keyboardRequested: Boolean = false,
)

class RemoteInputSequence {
    private var sessionId: String? = null
    private var sequence = 0L

    fun reset(sessionId: String?) {
        this.sessionId = sessionId
        sequence = 0L
    }

    fun next(sessionId: String? = this.sessionId): Long {
        if (sessionId != this.sessionId) reset(sessionId)
        sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
        return sequence
    }
}

object RemoteTextChunks {
    fun split(
        text: CharSequence,
        maxUtf16: Int = RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF16,
        maxUtf8: Int = RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF8,
    ): List<String> {
        require(maxUtf16 >= 2)
        require(maxUtf8 >= 4)
        if (text.isEmpty()) return listOf("")
        val chunks = ArrayList<String>((text.length / maxUtf16) + 1)
        var start = 0
        while (start < text.length) {
            var end = start
            var utf8Bytes = 0
            while (end < text.length) {
                val nextEnd = if (
                    Character.isHighSurrogate(text[end]) &&
                    end + 1 < text.length &&
                    Character.isLowSurrogate(text[end + 1])
                ) {
                    end + 2
                } else {
                    end + 1
                }
                val nextBytes = text.subSequence(end, nextEnd)
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
                    .size
                if (nextEnd - start > maxUtf16 || utf8Bytes + nextBytes > maxUtf8) break
                end = nextEnd
                utf8Bytes += nextBytes
            }
            check(end > start) { "Protocol limits cannot contain one Unicode code point" }
            chunks += text.subSequence(start, end).toString()
            start = end
        }
        return chunks
    }
}

data class RemoteInputViewState(
    val phase: Phase,
    val sessionId: String? = null,
    val fieldLabel: String? = null,
    val password: Boolean = false,
    val imeAction: String = RemoteInputPhoneContract.IME_ACTION_NONE,
    val keyboardRequested: Boolean = false,
) {
    enum class Phase { CONNECTING, DISCONNECTED, WAITING_FOR_FIELD, READY }

    val editorEnabled: Boolean get() = phase == Phase.READY
    val controlsEnabled: Boolean get() = phase == Phase.WAITING_FOR_FIELD || phase == Phase.READY
    val secureWindow: Boolean get() = editorEnabled && password
    val primaryAction: String
        get() = if (imeAction == RemoteInputPhoneContract.IME_ACTION_NEXT) {
            RemoteInputPhoneContract.EDITOR_NEXT
        } else {
            RemoteInputPhoneContract.EDITOR_ENTER
        }

    companion object {
        val INITIAL = RemoteInputViewState(Phase.CONNECTING)

        fun from(state: RemoteInputTransportState): RemoteInputViewState = when {
            !state.connected -> RemoteInputViewState(Phase.DISCONNECTED)
            !state.fieldActive -> RemoteInputViewState(Phase.WAITING_FOR_FIELD)
            else -> RemoteInputViewState(
                phase = Phase.READY,
                sessionId = state.sessionId,
                fieldLabel = state.fieldLabel,
                password = state.password,
                imeAction = state.imeAction,
                keyboardRequested = state.keyboardRequested,
            )
        }
    }
}

/**
 * Whether a Keyboard & remote screen that opened itself for a plugin's field
 * should now close: that field has gone (sent, cancelled, or the link dropped).
 * A screen opened by hand never closes itself, and neither does one the user
 * has started steering the pointer from — it has become theirs.
 */
fun keyboardRequestEnded(
    openedForKeyboard: Boolean,
    userTookOver: Boolean,
    requestedSessionId: String?,
    currentSessionId: String?,
): Boolean = openedForKeyboard &&
    !userTookOver &&
    requestedSessionId != null &&
    currentSessionId != requestedSessionId
