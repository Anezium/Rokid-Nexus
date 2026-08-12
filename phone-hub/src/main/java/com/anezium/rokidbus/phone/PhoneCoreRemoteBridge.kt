package com.anezium.rokidbus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.NativeAppLaunchRequest
import com.anezium.rokidbus.shared.RemoteEditorAction
import com.anezium.rokidbus.shared.RemoteInputCloseReason
import com.anezium.rokidbus.shared.RemoteInputCommand
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteInputStatusCode
import com.anezium.rokidbus.shared.RemoteNavigationAction
import com.anezium.rokidbus.shared.RemoteNavigationContract
import com.anezium.rokidbus.shared.RemoteNavigationRequest
import com.anezium.rokidbus.shared.RemotePointerAction
import com.anezium.rokidbus.shared.RemotePointerCommand
import com.anezium.rokidbus.shared.RemotePointerContract
import java.util.ArrayDeque
import java.util.UUID

internal const val INTERNAL_CORE_PERMISSION =
    "com.anezium.rokidbus.phone.permission.INTERNAL_CORE_CONTROL"

/** Owns the private phone-UI edge and translates it to versioned core bus messages. */
internal class PhoneCoreRemoteBridge(
    context: Context,
    private val sendRemote: (BusEnvelope) -> String?,
    private val sendNativePointer: (RokidNativePointerCommand) -> Boolean,
    private val isConnected: () -> Boolean,
    private val isNativePointerAvailable: () -> Boolean,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var inputState = RemoteInputTransportState(connected = false, fieldActive = false)
    private var nextInputSequence = 1L
    private var remoteImeOptions = EditorInfo.IME_ACTION_NONE
    private var nativeAppsState: NativeAppsUiState = NativeAppsUiState.Loading
    private val pendingNativeRequests = linkedSetOf<String>()
    private val pointerMoves = RemotePointerMoveCoalescer()
    private var pointerStreamId = newPointerStreamId()
    private var nextPointerSequence = 1L
    private var hubPointerStreamStarted = false
    private var pointerTransport = PointerTransport.NONE
    private val pendingNativePointerActions = ArrayDeque<RemotePointerAction>()
    private var pointerFlushScheduled = false
    private val flushPointerMove = Runnable {
        pointerFlushScheduled = false
        flushPointerMove()
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val commandIntent = intent ?: return
            when (commandIntent.action) {
                RemoteInputPhoneContract.ACTION_COMMAND ->
                    RemoteInputPhoneContract.parseCommand(commandIntent)?.let(::handlePhoneInput)
                RemoteInputPhoneContract.ACTION_NAVIGATE ->
                    RemoteInputPhoneContract.parseNavigation(commandIntent)?.let(::handlePhoneNavigation)
                RemotePointerPhoneContract.ACTION_COMMAND ->
                    RemotePointerPhoneContract.parse(commandIntent)?.let(::handlePhonePointer)
                NativeAppsPhoneContract.ACTION_COMMAND ->
                    NativeAppsPhoneContract.parseCommand(commandIntent)?.let(::handlePhoneNativeApps)
            }
        }
    }

    fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(RemoteInputPhoneContract.ACTION_COMMAND)
            addAction(RemoteInputPhoneContract.ACTION_NAVIGATE)
            addAction(RemotePointerPhoneContract.ACTION_COMMAND)
            addAction(NativeAppsPhoneContract.ACTION_COMMAND)
        }
        ContextCompat.registerReceiver(
            appContext,
            commandReceiver,
            filter,
            INTERNAL_CORE_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        // Movement skips the broadcast queue; see PhonePointerChannel.
        PhonePointerChannel.setHandler { intent ->
            main.post {
                RemotePointerPhoneContract.parse(intent)?.let(::handlePhonePointer)
            }
        }
        onLinkStateChanged(isConnected(), isNativePointerAvailable())
    }

    fun onLinkStateChanged(connected: Boolean, nativePointerAvailable: Boolean) {
        if (Looper.myLooper() != main.looper) {
            main.post { onLinkStateChanged(connected, nativePointerAvailable) }
            return
        }
        inputState = when (
            RemotePointerLinkPolicy.decide(
                connected = connected,
                nativePointerAvailable = nativePointerAvailable,
                nativePointerActive = pointerTransport == PointerTransport.NATIVE,
            )
        ) {
            RemotePointerLinkAction.KEEP -> inputState.copy(connected = true)
            RemotePointerLinkAction.SWITCH_TO_HUB -> {
                switchToHub(pointerMoves.currentPosition())
                inputState.copy(connected = true)
            }
            RemotePointerLinkAction.RESET -> {
                remoteImeOptions = EditorInfo.IME_ACTION_NONE
                nextInputSequence = 1L
                resetPointerState()
                RemoteInputTransportState(connected = false, fieldActive = false)
            }
        }
        publishInputState()
    }

    fun handleRemote(envelope: BusEnvelope): Boolean = when (envelope.path) {
        RemoteInputContract.SESSION_PATH -> handleRemoteSession(envelope)
        RemoteInputContract.STATUS_PATH -> handleRemoteInputStatus(envelope)
        RemoteNavigationContract.RESULT_PATH ->
            envelope.binary == null && RemoteNavigationContract.parseResult(envelope.payload) != null
        RemotePointerContract.RESULT_PATH ->
            envelope.binary == null && RemotePointerContract.parseResult(envelope.payload) != null
        NativeAppContract.RESULT_PATH -> handleRemoteNativeApps(envelope)
        else -> false
    }

    override fun close() {
        if (!receiverRegistered) return
        PhonePointerChannel.setHandler(null)
        runCatching { appContext.unregisterReceiver(commandReceiver) }
        receiverRegistered = false
        hideAndResetPointer()
        pendingNativeRequests.clear()
    }

    private fun handlePhoneInput(command: PhoneRemoteCommand) {
        if (command is PhoneRemoteCommand.RequestState) {
            publishInputState()
            return
        }
        val sessionId = command.sessionIdOrNull() ?: return
        if (!inputState.connected || !inputState.fieldActive || inputState.sessionId != sessionId) return

        val sequence = nextInputSequence
        val wireCommand = when (command) {
            PhoneRemoteCommand.RequestState -> return
            is PhoneRemoteCommand.CommitText -> {
                if (command.text.isEmpty()) return
                RemoteInputCommand.CommitText(sessionId, sequence, command.text)
            }
            is PhoneRemoteCommand.SetComposingText ->
                RemoteInputCommand.SetComposingText(sessionId, sequence, command.text)
            is PhoneRemoteCommand.FinishComposing ->
                RemoteInputCommand.FinishComposingText(sessionId, sequence)
            is PhoneRemoteCommand.DeleteSurrounding -> {
                if (command.beforeLength == 0 && command.afterLength == 0) return
                RemoteInputCommand.DeleteSurroundingText(
                    sessionId,
                    sequence,
                    command.beforeLength,
                    command.afterLength,
                )
            }
            is PhoneRemoteCommand.PerformEditorAction -> RemoteInputCommand.PerformEditorAction(
                sessionId,
                sequence,
                if (command.action == RemoteInputPhoneContract.EDITOR_NEXT) {
                    RemoteEditorAction.NEXT
                } else {
                    remoteEditorAction(remoteImeOptions)
                },
            )
            is PhoneRemoteCommand.Close -> RemoteInputCommand.Close(
                sessionId,
                sequence,
                RemoteInputCloseReason.USER_DISMISSED,
            )
        }
        val payload = runCatching { RemoteInputContract.encodeCommand(wireCommand) }.getOrNull() ?: return
        if (sendRemote(BusEnvelope(path = RemoteInputContract.COMMAND_PATH, payload = payload)) == null) {
            nextInputSequence += 1L
        }
    }

    private fun handlePhoneNavigation(navigation: PhoneRemoteNavigation) {
        if (!isConnected()) return
        val action = when (navigation.action) {
            RemoteInputPhoneContract.KEY_PREVIOUS -> RemoteNavigationAction.PREVIOUS
            RemoteInputPhoneContract.KEY_NEXT -> RemoteNavigationAction.NEXT
            RemoteInputPhoneContract.KEY_SELECT -> RemoteNavigationAction.SELECT
            RemoteInputPhoneContract.KEY_BACK -> RemoteNavigationAction.BACK
            RemoteInputPhoneContract.KEY_UP -> RemoteNavigationAction.UP
            RemoteInputPhoneContract.KEY_DOWN -> RemoteNavigationAction.DOWN
            RemoteInputPhoneContract.KEY_LEFT -> RemoteNavigationAction.LEFT
            RemoteInputPhoneContract.KEY_RIGHT -> RemoteNavigationAction.RIGHT
            else -> return
        }
        val request = runCatching {
            RemoteNavigationContract.request(RemoteNavigationRequest(navigation.requestId, action))
        }.getOrNull() ?: return
        sendRemote(BusEnvelope(path = RemoteNavigationContract.REQUEST_PATH, payload = request))
    }

    private fun handlePhonePointer(command: PhonePointerCommand) {
        if (!isConnected()) {
            resetPointerState()
            return
        }
        when (command) {
            PhonePointerCommand.Show -> {
                hideAndResetPointer()
                startPointerTransport(pointerMoves.currentPosition())
            }
            is PhonePointerCommand.Move -> {
                if (pointerMoves.add(command.delta)) schedulePointerMove()
            }
            PhonePointerCommand.MoveEnd -> handlePointerTerminal(RemotePointerAction.MOVE_END)
            PhonePointerCommand.Click -> handlePointerTerminal(RemotePointerAction.CLICK)
            PhonePointerCommand.LongPress -> handlePointerTerminal(RemotePointerAction.LONG_PRESS)
            PhonePointerCommand.Hide -> hideAndResetPointer()
        }
    }

    private fun handlePointerTerminal(action: RemotePointerAction) {
        check(
            action == RemotePointerAction.MOVE_END ||
                action == RemotePointerAction.CLICK ||
                action == RemotePointerAction.LONG_PRESS,
        )
        val position = pointerMoves.currentPosition()
        if (!ensurePointerTransport(position)) return
        if (pointerTransport == PointerTransport.NATIVE && pointerMoves.hasPendingMove()) {
            pendingNativePointerActions += action
            schedulePointerMove()
            return
        }
        if (pointerTransport == PointerTransport.HUB && pointerMoves.hasPendingMove()) {
            cancelPointerMovement(resetRateLimit = false)
            pointerMoves.takeLatest()
        }
        sendPointerTerminal(action, pointerMoves.currentPosition())
    }

    private fun schedulePointerMove() {
        if (pointerFlushScheduled) return
        val delay = pointerMoves.delayUntilReady(SystemClock.uptimeMillis()) ?: return
        pointerFlushScheduled = true
        main.postDelayed(flushPointerMove, delay)
    }

    private fun flushPointerMove() {
        if (!isConnected()) {
            resetPointerState()
            return
        }
        val emission = pointerMoves.takeReady(SystemClock.uptimeMillis())
        if (emission == null) {
            schedulePointerMove()
            return
        }
        if (ensurePointerTransport(emission.position)) {
            when (pointerTransport) {
                PointerTransport.NATIVE -> {
                    if (!sendNativePointer(RokidNativePointerProtocol.mappedDelta(emission.delta))) {
                        switchToHub(emission.position)
                    }
                }
                PointerTransport.HUB -> sendHubPointer(RemotePointerAction.MOVE, emission.position)
                PointerTransport.NONE -> Unit
            }
        }
        flushPendingNativePointerActions(emission.position)
        if (pointerMoves.hasPendingMove()) schedulePointerMove()
    }

    private fun startPointerTransport(position: RemotePointerPosition): Boolean {
        if (sendNativePointer(RokidNativePointerCommand.Enter)) {
            if (hubPointerStreamStarted) sendHubPointer(RemotePointerAction.HIDE, null)
            hubPointerStreamStarted = false
            pointerTransport = PointerTransport.NATIVE
            return true
        }
        return switchToHub(position)
    }

    private fun ensurePointerTransport(position: RemotePointerPosition): Boolean =
        when (pointerTransport) {
            PointerTransport.NATIVE -> true
            PointerTransport.HUB -> ensureHubPointerStream(position)
            PointerTransport.NONE -> startPointerTransport(position)
        }

    private fun switchToHub(position: RemotePointerPosition): Boolean {
        if (pointerTransport == PointerTransport.NATIVE) {
            sendNativePointer(RokidNativePointerCommand.Exit)
        }
        pointerTransport = PointerTransport.HUB
        return ensureHubPointerStream(position).also { started ->
            if (!started) pointerTransport = PointerTransport.NONE
        }
    }

    private fun ensureHubPointerStream(position: RemotePointerPosition): Boolean {
        rotateHubPointerStreamIfNeeded()
        if (hubPointerStreamStarted) return true
        hubPointerStreamStarted = sendHubPointer(RemotePointerAction.SHOW, position)
        return hubPointerStreamStarted
    }

    private fun sendPointerTerminal(action: RemotePointerAction, position: RemotePointerPosition) {
        when (pointerTransport) {
            PointerTransport.NATIVE -> {
                val command = when (action) {
                    RemotePointerAction.MOVE_END -> RokidNativePointerCommand.MoveEnd
                    RemotePointerAction.CLICK -> RokidNativePointerCommand.Click
                    RemotePointerAction.LONG_PRESS -> RokidNativePointerCommand.LongPress
                    else -> error("Unsupported pointer terminal action: $action")
                }
                if (!sendNativePointer(command) && switchToHub(position)) {
                    sendHubPointer(action, position)
                }
            }
            PointerTransport.HUB -> if (ensureHubPointerStream(position)) {
                sendHubPointer(action, position)
            }
            PointerTransport.NONE -> Unit
        }
    }

    private fun flushPendingNativePointerActions(position: RemotePointerPosition) {
        while (pendingNativePointerActions.isNotEmpty()) {
            sendPointerTerminal(pendingNativePointerActions.removeFirst(), position)
        }
    }

    private fun sendHubPointer(
        action: RemotePointerAction,
        position: RemotePointerPosition?,
    ): Boolean {
        val streamId = pointerStreamId
        val sequence = takePointerSequence()
        val command = RemotePointerCommand(
            streamId = streamId,
            sequence = sequence,
            action = action,
            x = position?.x,
            y = position?.y,
        )
        val payload = runCatching { RemotePointerContract.command(command) }.getOrNull() ?: return false
        return sendRemote(
            BusEnvelope(
                path = RemotePointerContract.COMMAND_PATH,
                id = "$streamId-$sequence",
                payload = payload,
            ),
        ) == null
    }

    private fun takePointerSequence(): Long {
        val sequence = nextPointerSequence
        if (sequence == RemotePointerContract.MAX_SAFE_SEQUENCE) {
            pointerStreamId = newPointerStreamId()
            nextPointerSequence = 1L
            hubPointerStreamStarted = false
        } else {
            nextPointerSequence += 1L
        }
        return sequence
    }

    private fun cancelPointerMovement(resetRateLimit: Boolean) {
        main.removeCallbacks(flushPointerMove)
        pointerFlushScheduled = false
        pointerMoves.clearPending(resetRateLimit)
    }

    private fun rotateHubPointerStreamIfNeeded() {
        if (nextPointerSequence != RemotePointerContract.MAX_SAFE_SEQUENCE) return
        pointerStreamId = newPointerStreamId()
        nextPointerSequence = 1L
        hubPointerStreamStarted = false
    }

    private fun hideAndResetPointer() {
        cancelPointerMovement(resetRateLimit = true)
        pendingNativePointerActions.clear()
        when (pointerTransport) {
            PointerTransport.NATIVE -> sendNativePointer(RokidNativePointerCommand.Exit)
            PointerTransport.HUB -> if (hubPointerStreamStarted) {
                sendHubPointer(RemotePointerAction.HIDE, null)
            }
            PointerTransport.NONE -> Unit
        }
        pointerMoves.reset()
        pointerTransport = PointerTransport.NONE
        hubPointerStreamStarted = false
        renewHubPointerStream()
    }

    private fun resetPointerState() {
        cancelPointerMovement(resetRateLimit = true)
        pendingNativePointerActions.clear()
        pointerMoves.reset()
        pointerTransport = PointerTransport.NONE
        hubPointerStreamStarted = false
        renewHubPointerStream()
    }

    private fun renewHubPointerStream() {
        pointerStreamId = newPointerStreamId()
        nextPointerSequence = 1L
    }

    private fun newPointerStreamId(): String =
        "pointer_${UUID.randomUUID().toString().replace("-", "")}"

    private enum class PointerTransport { NONE, NATIVE, HUB }

    private fun handlePhoneNativeApps(command: PhoneNativeAppsCommand) {
        when (command) {
            is PhoneNativeAppsCommand.Install -> {
                publishNativeApps(
                    NativeAppsUiState.Error(
                        "Installing glasses apps is the next step; this screen currently lists and opens them.",
                    ),
                )
            }
            is PhoneNativeAppsCommand.RequestList -> {
                if (!isConnected()) {
                    publishNativeApps(NativeAppsUiState.Error("The glasses are not connected."))
                    return
                }
                publishNativeApps(NativeAppsUiState.Loading)
                val payload = runCatching { NativeAppContract.listRequest(command.requestId) }
                    .getOrNull() ?: return
                if (sendRemote(BusEnvelope(path = NativeAppContract.REQUEST_PATH, payload = payload)) == null) {
                    pendingNativeRequests += command.requestId
                } else {
                    publishNativeApps(NativeAppsUiState.Error("The app request could not reach the glasses."))
                }
            }
            is PhoneNativeAppsCommand.Open -> {
                if (!isConnected()) {
                    publishNativeApps(NativeAppsUiState.Error("The glasses are not connected."))
                    return
                }
                val payload = runCatching {
                    NativeAppContract.launchRequest(
                        NativeAppLaunchRequest(command.requestId, command.appId),
                    )
                }.getOrNull() ?: return
                if (sendRemote(BusEnvelope(path = NativeAppContract.REQUEST_PATH, payload = payload)) == null) {
                    pendingNativeRequests += command.requestId
                }
            }
        }
    }

    private fun handleRemoteSession(envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) return false
        RemoteInputContract.decodeSessionOpen(envelope.payload)?.let { session ->
            remoteImeOptions = session.imeOptions
            nextInputSequence = session.nextSequence
            inputState = RemoteInputTransportState(
                connected = true,
                fieldActive = true,
                password = session.sensitive,
                sessionId = session.sessionId,
                fieldLabel = null,
                imeAction = localImeAction(session.imeOptions),
            )
            publishInputState()
            if (session.autoOpenPhoneKeyboard) openPhoneKeyboard(session.sessionId)
            return true
        }
        val closed = RemoteInputContract.decodeSessionClosed(envelope.payload) ?: return false
        if (closed.sessionId == inputState.sessionId) clearActiveInput()
        return true
    }

    private fun handleRemoteInputStatus(envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) return false
        val status = RemoteInputContract.decodeStatus(envelope.payload) ?: return false
        if (status.sessionId != inputState.sessionId) return true
        if (status.status == RemoteInputStatusCode.CLOSED) {
            clearActiveInput()
        } else if (status.status == RemoteInputStatusCode.REJECTED) {
            status.expectedSequence?.let { nextInputSequence = it }
        }
        return true
    }

    private fun handleRemoteNativeApps(envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) return false
        NativeAppContract.parseListResult(envelope.payload)?.let { result ->
            if (!pendingNativeRequests.remove(result.requestId)) return true
            if (!result.success) {
                publishNativeApps(NativeAppsUiState.Error("The glasses could not list installed apps."))
                return true
            }
            val apps = result.apps.map { app ->
                NativeGlassesApp(
                    id = app.packageName,
                    name = app.label,
                    detail = app.packageName,
                    action = NativeAppAction.OPEN,
                )
            }
            publishNativeApps(
                if (apps.isEmpty()) NativeAppsUiState.Empty else NativeAppsUiState.Content(apps),
            )
            return true
        }
        val result = NativeAppContract.parseLaunchResult(envelope.payload) ?: return false
        if (!pendingNativeRequests.remove(result.requestId)) return true
        if (!result.success) {
            publishNativeApps(NativeAppsUiState.Error("The selected app could not be opened."))
        } else {
            publishNativeApps(nativeAppsState)
        }
        return true
    }

    private fun clearActiveInput() {
        remoteImeOptions = EditorInfo.IME_ACTION_NONE
        nextInputSequence = 1L
        inputState = RemoteInputTransportState(
            connected = isConnected(),
            fieldActive = false,
        )
        publishInputState()
    }

    private fun publishInputState() {
        appContext.sendBroadcast(RemoteInputPhoneContract.stateIntent(appContext, inputState))
    }

    private fun openPhoneKeyboard(sessionId: String) {
        val intent = Intent(appContext, RemoteInputActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(RemoteInputActivity.EXTRA_AUTO_SHOW_KEYBOARD, true)
            .putExtra(RemoteInputActivity.EXTRA_AUTO_SHOW_KEYBOARD_SESSION_ID, sessionId)
        runCatching { appContext.startActivity(intent) }
    }

    private fun publishNativeApps(state: NativeAppsUiState) {
        nativeAppsState = state
        appContext.sendBroadcast(NativeAppsPhoneContract.stateIntent(appContext, state))
    }

    private fun PhoneRemoteCommand.sessionIdOrNull(): String? = when (this) {
        PhoneRemoteCommand.RequestState -> null
        is PhoneRemoteCommand.CommitText -> sessionId
        is PhoneRemoteCommand.SetComposingText -> sessionId
        is PhoneRemoteCommand.FinishComposing -> sessionId
        is PhoneRemoteCommand.DeleteSurrounding -> sessionId
        is PhoneRemoteCommand.PerformEditorAction -> sessionId
        is PhoneRemoteCommand.Close -> sessionId
    }

    private fun localImeAction(imeOptions: Int): String = when (imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_NEXT -> RemoteInputPhoneContract.IME_ACTION_NEXT
        EditorInfo.IME_ACTION_DONE -> RemoteInputPhoneContract.IME_ACTION_DONE
        EditorInfo.IME_ACTION_NONE -> RemoteInputPhoneContract.IME_ACTION_NONE
        else -> RemoteInputPhoneContract.IME_ACTION_ENTER
    }

    private fun remoteEditorAction(imeOptions: Int): RemoteEditorAction =
        when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> RemoteEditorAction.GO
            EditorInfo.IME_ACTION_SEARCH -> RemoteEditorAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> RemoteEditorAction.SEND
            EditorInfo.IME_ACTION_NEXT -> RemoteEditorAction.NEXT
            EditorInfo.IME_ACTION_DONE -> RemoteEditorAction.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> RemoteEditorAction.PREVIOUS
            EditorInfo.IME_ACTION_NONE -> RemoteEditorAction.NONE
            else -> RemoteEditorAction.UNSPECIFIED
        }
}
