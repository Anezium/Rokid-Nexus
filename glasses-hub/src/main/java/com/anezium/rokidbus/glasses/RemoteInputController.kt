package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local bridge between the phone transport and the active Nexus IME session.
 *
 * Text exists only in the caller-provided operation and the short main-thread dispatch closure. It
 * is never copied into state, persisted, inspected, or logged. Every operation is bound to the
 * current session and an exact sequence number so retries cannot type a character twice.
 */
internal object RemoteInputController {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(RemoteInputSessionState) -> Unit>()
    private val gate = RemoteInputSessionGate()

    @Volatile
    private var latestState = RemoteInputSessionState()
    private var service: NexusRemoteInputMethodService? = null

    fun observe(listener: (RemoteInputSessionState) -> Unit): () -> Unit {
        listeners += listener
        listener(latestState)
        return { listeners.remove(listener) }
    }

    internal fun onInputStarted(
        owner: NexusRemoteInputMethodService,
        editorInfo: EditorInfo,
    ) {
        runOnMain {
            service = owner
            gate.open(UUID.randomUUID().toString())
            publish(
                RemoteInputSessionState(
                    active = true,
                    sessionId = gate.sessionId,
                    packageName = RemoteInputMetadataPolicy.sanitizePackageName(
                        editorInfo.packageName,
                    ),
                    inputType = editorInfo.inputType,
                    imeOptions = editorInfo.imeOptions,
                    nextSequence = gate.nextSequence,
                    autoOpenPhoneKeyboard = NoticeTextInputImeTrust.requestsPhoneKeyboard(
                        editorInfo.packageName,
                        editorInfo.privateImeOptions,
                        BuildConfig.APPLICATION_ID,
                    ),
                ),
            )
        }
    }

    internal fun onInputFinished(owner: NexusRemoteInputMethodService) {
        runOnMain {
            if (service !== owner) return@runOnMain
            service = null
            gate.close()
            publish(RemoteInputSessionState())
        }
    }

    fun commitText(
        sessionId: String,
        sequence: Long,
        text: CharSequence,
        newCursorPosition: Int = 1,
        callback: (RemoteInputResult) -> Unit = {},
    ) {
        execute(
            sessionId,
            sequence,
            isValid = RemoteInputPolicy.acceptsText(text, newCursorPosition),
            callback = callback,
        ) { connection -> connection.commitText(text, newCursorPosition) }
    }

    fun setComposingText(
        sessionId: String,
        sequence: Long,
        text: CharSequence,
        newCursorPosition: Int = 1,
        callback: (RemoteInputResult) -> Unit = {},
    ) {
        execute(
            sessionId,
            sequence,
            isValid = RemoteInputPolicy.acceptsComposingText(text, newCursorPosition),
            callback = callback,
        ) { connection -> connection.setComposingText(text, newCursorPosition) }
    }

    fun finishComposingText(
        sessionId: String,
        sequence: Long,
        callback: (RemoteInputResult) -> Unit = {},
    ) {
        execute(sessionId, sequence, callback = callback, operation = InputConnection::finishComposingText)
    }

    fun deleteSurroundingText(
        sessionId: String,
        sequence: Long,
        beforeLength: Int,
        afterLength: Int,
        callback: (RemoteInputResult) -> Unit = {},
    ) {
        execute(
            sessionId,
            sequence,
            isValid = RemoteInputPolicy.acceptsDelete(beforeLength, afterLength),
            callback = callback,
        ) { connection -> connection.deleteSurroundingText(beforeLength, afterLength) }
    }

    fun performEditorAction(
        sessionId: String,
        sequence: Long,
        actionId: Int,
        callback: (RemoteInputResult) -> Unit = {},
    ) {
        execute(
            sessionId,
            sequence,
            isValid = RemoteInputPolicy.acceptsEditorAction(actionId),
            callback = callback,
        ) { connection -> connection.performEditorAction(actionId) }
    }

    fun close(
        sessionId: String,
        sequence: Long,
        callback: (RemoteInputResult) -> Unit = {},
    ) {
        runOnMain {
            val validation = gate.evaluate(sessionId, sequence)
            if (validation != RemoteInputResultCode.APPLIED) {
                callback(result(validation, sessionId, sequence))
                return@runOnMain
            }
            val owner = service
            if (owner == null) {
                callback(result(RemoteInputResultCode.INPUT_CONNECTION_UNAVAILABLE, sessionId, sequence))
                return@runOnMain
            }
            gate.advance(sequence)
            owner.closeRemoteInputSession()
            callback(
                RemoteInputResult(
                    code = RemoteInputResultCode.APPLIED,
                    sessionId = sessionId,
                    sequence = sequence,
                    nextSequence = 0L,
                ),
            )
        }
    }

    private fun execute(
        sessionId: String,
        sequence: Long,
        isValid: Boolean = true,
        callback: (RemoteInputResult) -> Unit,
        operation: (InputConnection) -> Boolean,
    ) {
        runOnMain {
            val validation = gate.evaluate(sessionId, sequence)
            if (validation != RemoteInputResultCode.APPLIED) {
                callback(result(validation, sessionId, sequence))
                return@runOnMain
            }
            if (!isValid) {
                callback(result(RemoteInputResultCode.INVALID_COMMAND, sessionId, sequence))
                return@runOnMain
            }
            val connection = service?.currentInputConnection
            if (connection == null) {
                callback(result(RemoteInputResultCode.INPUT_CONNECTION_UNAVAILABLE, sessionId, sequence))
                return@runOnMain
            }
            val applied = runCatching { operation(connection) }.getOrDefault(false)
            if (!applied) {
                callback(result(RemoteInputResultCode.INPUT_CONNECTION_REJECTED, sessionId, sequence))
                return@runOnMain
            }
            gate.advance(sequence)
            publish(latestState.copy(nextSequence = gate.nextSequence))
            callback(result(RemoteInputResultCode.APPLIED, sessionId, sequence))
        }
    }

    private fun result(
        code: RemoteInputResultCode,
        sessionId: String,
        sequence: Long,
    ): RemoteInputResult = RemoteInputResult(
        code = code,
        sessionId = sessionId,
        sequence = sequence,
        nextSequence = gate.nextSequence,
    )

    private fun publish(state: RemoteInputSessionState) {
        latestState = state
        listeners.forEach { listener -> listener(state) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
