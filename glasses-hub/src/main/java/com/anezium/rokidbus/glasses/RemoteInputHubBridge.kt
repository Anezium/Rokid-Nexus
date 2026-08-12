package com.anezium.rokidbus.glasses

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.anezium.rokidbus.shared.RemoteEditorAction
import com.anezium.rokidbus.shared.RemoteInputCloseReason
import com.anezium.rokidbus.shared.RemoteInputCommand
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteInputErrorCode
import com.anezium.rokidbus.shared.RemoteInputSessionClosed
import com.anezium.rokidbus.shared.RemoteInputSessionOpen
import com.anezium.rokidbus.shared.RemoteInputStatus
import com.anezium.rokidbus.shared.RemoteInputStatusCode
import com.anezium.rokidbus.shared.RemoteNavigationAction as WireNavigationAction
import com.anezium.rokidbus.shared.RemoteNavigationContract
import com.anezium.rokidbus.shared.RemoteNavigationErrorCode
import com.anezium.rokidbus.shared.RemoteNavigationResult as WireNavigationResult
import org.json.JSONObject

/** Adapts core bus envelopes to the process-local IME and Accessibility controllers. */
internal object RemoteInputHubBridge {
    private var sender: ((String, JSONObject) -> Boolean)? = null
    private var observing = false
    private var lastState = RemoteInputSessionState()
    private var closeReason = RemoteInputCloseReason.FOCUS_LOST
    private var autoOpenedSessionId: String? = null
    private val navigationReplay = RemoteNavigationReplayCache(MAX_NAVIGATION_RESULTS)

    fun initialize(send: (String, JSONObject) -> Boolean) {
        sender = send
        if (observing) return
        observing = true
        RemoteInputController.observe(::onSessionState)
    }

    fun onLinkAvailable() {
        lastState.takeIf(RemoteInputSessionState::active)?.let(::sendSessionOpen)
    }

    fun handle(path: String, payload: JSONObject): Boolean = when (path) {
        RemoteInputContract.COMMAND_PATH -> handleInput(payload)
        RemoteNavigationContract.REQUEST_PATH -> handleNavigation(payload)
        else -> false
    }

    private fun handleInput(payload: JSONObject): Boolean {
        val command = RemoteInputContract.decodeCommand(payload) ?: return false
        if (command is RemoteInputCommand.Close) closeReason = command.reason
        val callback: (RemoteInputResult) -> Unit = { result -> sendInputResult(command, result) }
        when (command) {
            is RemoteInputCommand.CommitText -> RemoteInputController.commitText(
                command.sessionId,
                command.sequence,
                command.text,
                command.newCursorPosition,
                callback,
            )
            is RemoteInputCommand.SetComposingText -> RemoteInputController.setComposingText(
                command.sessionId,
                command.sequence,
                command.text,
                command.newCursorPosition,
                callback,
            )
            is RemoteInputCommand.FinishComposingText -> RemoteInputController.finishComposingText(
                command.sessionId,
                command.sequence,
                callback,
            )
            is RemoteInputCommand.DeleteSurroundingText -> RemoteInputController.deleteSurroundingText(
                command.sessionId,
                command.sequence,
                command.beforeLength,
                command.afterLength,
                callback,
            )
            is RemoteInputCommand.PerformEditorAction -> RemoteInputController.performEditorAction(
                command.sessionId,
                command.sequence,
                command.action.toAndroidAction(),
                callback,
            )
            is RemoteInputCommand.Close -> RemoteInputController.close(
                command.sessionId,
                command.sequence,
                callback,
            )
        }
        return true
    }

    private fun handleNavigation(payload: JSONObject): Boolean {
        val request = RemoteNavigationContract.parseRequest(payload) ?: return false
        when (val replay = navigationReplay.reserve(request.requestId)) {
            RemoteNavigationReplay.New -> Unit
            RemoteNavigationReplay.InFlight -> return true
            is RemoteNavigationReplay.Completed -> {
                send(
                    RemoteNavigationContract.RESULT_PATH,
                    RemoteNavigationContract.result(replay.result),
                )
                return true
            }
        }
        RemoteNavigationController.perform(request.action.toLocalAction()) { localResult ->
            val result = WireNavigationResult(
                requestId = request.requestId,
                action = request.action,
                errorCode = when (localResult) {
                    RemoteNavigationResult.PERFORMED -> null
                    RemoteNavigationResult.SERVICE_UNAVAILABLE ->
                        RemoteNavigationErrorCode.SERVICE_UNAVAILABLE
                    RemoteNavigationResult.NO_READABLE_WINDOW,
                    RemoteNavigationResult.NO_TARGET,
                    -> RemoteNavigationErrorCode.ACTION_UNAVAILABLE
                },
            )
            navigationReplay.complete(result)
            send(RemoteNavigationContract.RESULT_PATH, RemoteNavigationContract.result(result))
        }
        return true
    }

    private fun onSessionState(state: RemoteInputSessionState) {
        val previous = lastState
        lastState = state
        when {
            state.active && (!previous.active || previous.sessionId != state.sessionId) -> {
                if (previous.active) sendSessionClosed(previous, RemoteInputCloseReason.SUPERSEDED)
                closeReason = RemoteInputCloseReason.FOCUS_LOST
                autoOpenedSessionId = null
                sendSessionOpen(state)
            }
            !state.active && previous.active -> {
                sendSessionClosed(previous, closeReason)
                closeReason = RemoteInputCloseReason.FOCUS_LOST
            }
        }
    }

    private fun sendSessionOpen(state: RemoteInputSessionState) {
        val requestAutoOpen = state.autoOpenPhoneKeyboard && autoOpenedSessionId != state.sessionId
        val sent = send(
            RemoteInputContract.SESSION_PATH,
            RemoteInputContract.encodeSessionOpen(
                RemoteInputSessionOpen(
                    sessionId = state.sessionId,
                    packageName = state.packageName.ifBlank { null },
                    inputType = state.inputType,
                    imeOptions = state.imeOptions,
                    sensitive = isSensitiveInput(state.inputType),
                    nextSequence = state.nextSequence,
                    autoOpenPhoneKeyboard = requestAutoOpen,
                ),
            ),
        )
        if (sent && requestAutoOpen) autoOpenedSessionId = state.sessionId
    }

    private fun sendSessionClosed(
        state: RemoteInputSessionState,
        reason: RemoteInputCloseReason,
    ) {
        send(
            RemoteInputContract.SESSION_PATH,
            RemoteInputContract.encodeSessionClosed(
                RemoteInputSessionClosed(
                    sessionId = state.sessionId,
                    reason = reason,
                    lastAppliedSequence = (state.nextSequence - 1L).coerceAtLeast(0L),
                ),
            ),
        )
    }

    private fun sendInputResult(command: RemoteInputCommand, result: RemoteInputResult) {
        val success = result.code == RemoteInputResultCode.APPLIED
        val closed = success && command is RemoteInputCommand.Close
        val expected = result.nextSequence.takeIf { !success && it > 0L }
        send(
            RemoteInputContract.STATUS_PATH,
            RemoteInputContract.encodeStatus(
                RemoteInputStatus(
                    sessionId = command.sessionId,
                    status = when {
                        closed -> RemoteInputStatusCode.CLOSED
                        success -> RemoteInputStatusCode.APPLIED
                        else -> RemoteInputStatusCode.REJECTED
                    },
                    acknowledgedSequence = if (success) {
                        command.sequence
                    } else {
                        (result.nextSequence - 1L).coerceAtLeast(0L)
                    },
                    expectedSequence = expected,
                    errorCode = result.code.toWireError(),
                ),
            ),
        )
    }

    private fun RemoteInputResultCode.toWireError(): RemoteInputErrorCode? = when (this) {
        RemoteInputResultCode.APPLIED -> null
        RemoteInputResultCode.NO_ACTIVE_SESSION -> RemoteInputErrorCode.NO_ACTIVE_SESSION
        RemoteInputResultCode.SESSION_MISMATCH -> RemoteInputErrorCode.SESSION_MISMATCH
        RemoteInputResultCode.DUPLICATE_SEQUENCE -> RemoteInputErrorCode.DUPLICATE_SEQUENCE
        RemoteInputResultCode.OUT_OF_ORDER_SEQUENCE -> RemoteInputErrorCode.OUT_OF_ORDER_SEQUENCE
        RemoteInputResultCode.INVALID_COMMAND -> RemoteInputErrorCode.INVALID_COMMAND
        RemoteInputResultCode.INPUT_CONNECTION_UNAVAILABLE ->
            RemoteInputErrorCode.INPUT_CONNECTION_UNAVAILABLE
        RemoteInputResultCode.INPUT_CONNECTION_REJECTED ->
            RemoteInputErrorCode.INPUT_CONNECTION_REJECTED
    }

    private fun RemoteEditorAction.toAndroidAction(): Int = when (this) {
        RemoteEditorAction.UNSPECIFIED -> EditorInfo.IME_ACTION_UNSPECIFIED
        RemoteEditorAction.NONE -> EditorInfo.IME_ACTION_NONE
        RemoteEditorAction.GO -> EditorInfo.IME_ACTION_GO
        RemoteEditorAction.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        RemoteEditorAction.SEND -> EditorInfo.IME_ACTION_SEND
        RemoteEditorAction.NEXT -> EditorInfo.IME_ACTION_NEXT
        RemoteEditorAction.DONE -> EditorInfo.IME_ACTION_DONE
        RemoteEditorAction.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
    }

    private fun WireNavigationAction.toLocalAction(): RemoteNavigationAction = when (this) {
        WireNavigationAction.PREVIOUS -> RemoteNavigationAction.PREVIOUS
        WireNavigationAction.NEXT -> RemoteNavigationAction.NEXT
        WireNavigationAction.SELECT -> RemoteNavigationAction.SELECT
        WireNavigationAction.BACK -> RemoteNavigationAction.BACK
        WireNavigationAction.UP -> RemoteNavigationAction.UP
        WireNavigationAction.DOWN -> RemoteNavigationAction.DOWN
        WireNavigationAction.LEFT -> RemoteNavigationAction.LEFT
        WireNavigationAction.RIGHT -> RemoteNavigationAction.RIGHT
    }

    internal fun isSensitiveInput(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun send(path: String, payload: JSONObject): Boolean =
        sender?.invoke(path, payload) == true

    private const val MAX_NAVIGATION_RESULTS = 64
}

internal sealed interface RemoteNavigationReplay {
    data object New : RemoteNavigationReplay
    data object InFlight : RemoteNavigationReplay
    data class Completed(val result: WireNavigationResult) : RemoteNavigationReplay
}

internal class RemoteNavigationReplayCache(
    private val maximumEntries: Int,
) {
    private val results = object : LinkedHashMap<String, WireNavigationResult?>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, WireNavigationResult?>?,
        ): Boolean = size > maximumEntries
    }

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun reserve(requestId: String): RemoteNavigationReplay {
        results[requestId]?.let { return RemoteNavigationReplay.Completed(it) }
        if (results.containsKey(requestId)) return RemoteNavigationReplay.InFlight
        results[requestId] = null
        return RemoteNavigationReplay.New
    }

    @Synchronized
    fun complete(result: WireNavigationResult) {
        results[result.requestId] = result
    }
}
