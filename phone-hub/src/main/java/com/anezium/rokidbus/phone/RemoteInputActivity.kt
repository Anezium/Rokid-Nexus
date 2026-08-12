package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.UUID

/** System-wide phone keyboard and remote for whichever editable field is active on the glasses. */
class RemoteInputActivity : Activity() {
    private val sequence = RemoteInputSequence()
    private lateinit var publisher: RemoteInputPublisher
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var privacyLabel: TextView
    private lateinit var editor: StreamingEditText
    private lateinit var editorAction: Button
    private lateinit var closeAction: Button
    private lateinit var trackpad: TrackpadView
    private lateinit var trackpadPublisher: RemoteTrackpadPublisher
    private val remoteButtons = mutableListOf<Button>()

    private var viewState = RemoteInputViewState.INITIAL
    private var receiverRegistered = false
    private var closeSent = false
    private var autoShowKeyboardSessionId: String? = null
    private var autoShownSessionId: String? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.let(RemoteInputPhoneContract::parseState) ?: return
            applyState(RemoteInputViewState.from(state))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeLaunchIntent(intent)
        publisher = BroadcastRemoteInputPublisher(applicationContext)
        trackpadPublisher = RemoteTrackpadPublisher(applicationContext)
        buildUi()
        renderState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
        if (::publisher.isInitialized) publisher.requestState()
        maybeShowKeyboard()
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        registerStateReceiver()
        publisher.requestState()
        trackpadPublisher.show()
    }

    override fun onStop() {
        resetLocalEditor()
        // The pointer belongs to this screen: leaving it takes the cursor away
        // rather than stranding one on the glasses with nothing driving it.
        trackpadPublisher.hide()
        unregisterStateReceiver()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !closeSent) sendClose()
        resetLocalEditor()
        trackpadPublisher.close()
        super.onDestroy()
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(RemoteInputPhoneContract.ACTION_STATE)
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            INTERNAL_CORE_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(stateReceiver) }
        receiverRegistered = false
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        statusDot = NexusUi.dot(this)
        statusTitle = NexusUi.cardTitle(this, "")
        statusBody = NexusUi.cardBody(this, "")
        privacyLabel = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        editor = StreamingEditText(this).apply {
            hint = getString(R.string.remote_input_hint)
            setHintTextColor(NexusUi.INK4)
            setTextColor(NexusUi.INK)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 16f
            setSingleLine(true)
            includeFontPadding = false
            minHeight = NexusUi.dp(this@RemoteInputActivity, 58)
            setPadding(
                NexusUi.dp(this@RemoteInputActivity, 16),
                0,
                NexusUi.dp(this@RemoteInputActivity, 16),
                0,
            )
            background = NexusUi.bordered(
                this@RemoteInputActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                14,
            )
            isSaveEnabled = false
            setTextIsSelectable(false)
            setSelectAllOnFocus(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            onInputOperation = ::publishInputOperation
        }
        trackpad = TrackpadView(this).apply {
            onMove = { dx, dy -> trackpadPublisher.moveBy(dx, dy) }
            onTap = { trackpadPublisher.click() }
            onLongPress = { trackpadPublisher.longPress() }
            onGestureEnd = { trackpadPublisher.endGesture() }
        }
        editorAction = NexusUi.outlinePillButton(this, getString(R.string.remote_input_enter)).apply {
            setOnClickListener { sendEditorAction() }
        }
        closeAction = NexusUi.textButton(this, getString(R.string.remote_input_close)).apply {
            setOnClickListener {
                sendClose()
                hideKeyboard()
                finish()
            }
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(connectionCard(), NexusUi.block())
            addView(BusTheme.gap(this@RemoteInputActivity, 24))
            addView(NexusUi.sectionRow(this@RemoteInputActivity, getString(R.string.remote_input_keyboard_section)))
            addView(BusTheme.gap(this@RemoteInputActivity, 10))
            addView(keyboardCard(), NexusUi.block())
            addView(BusTheme.gap(this@RemoteInputActivity, 24))
            addView(NexusUi.sectionRow(this@RemoteInputActivity, getString(R.string.remote_input_remote_section)))
            addView(BusTheme.gap(this@RemoteInputActivity, 10))
            addView(remoteCard(), NexusUi.block())
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            // Not fillViewport: this column is a stack of cards, and stretching it
            // to the viewport hands the slack to the tallest card, which then eats
            // the screen and pushes the remote off the bottom.
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader(), NexusUi.block())
                addView(
                    scroll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun connectionCard(): LinearLayout = NexusUi.card(this).apply {
        addView(
            LinearLayout(this@RemoteInputActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    statusDot,
                    LinearLayout.LayoutParams(
                        NexusUi.dp(this@RemoteInputActivity, 9),
                        NexusUi.dp(this@RemoteInputActivity, 9),
                    ).apply { marginEnd = NexusUi.dp(this@RemoteInputActivity, 11) },
                )
                addView(statusTitle)
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@RemoteInputActivity, 7))
        addView(statusBody, NexusUi.block())
        addView(BusTheme.gap(this@RemoteInputActivity, 10))
        addView(privacyLabel)
    }

    private fun keyboardCard(): LinearLayout = NexusUi.card(this).apply {
        addView(editor, NexusUi.block())
        addView(BusTheme.gap(this@RemoteInputActivity, 10))
        addView(
            NexusUi.rowSub(
                this@RemoteInputActivity,
                getString(R.string.remote_input_live_help),
            ).apply { maxLines = 2 },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@RemoteInputActivity, 12))
        addView(
            LinearLayout(this@RemoteInputActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    editorAction,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(BusTheme.hgap(this@RemoteInputActivity, 8))
                addView(closeAction)
            },
            NexusUi.block(),
        )
    }

    /**
     * Pad and cross side by side, because they are two ways to do one thing and
     * you pick per screen, not per session: drag where a pointer helps, press the
     * cross where a list just wants the next item. Stacking them would also put
     * the cross under a pad that swallows the drag it needs to scroll into view.
     */
    private fun remoteCard(): LinearLayout = NexusUi.card(this).apply {
        addView(
            NexusUi.cardBody(this@RemoteInputActivity, getString(R.string.remote_input_remote_help)),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@RemoteInputActivity, 12))
        addView(
            LinearLayout(this@RemoteInputActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    trackpad,
                    LinearLayout.LayoutParams(
                        0,
                        NexusUi.dp(this@RemoteInputActivity, 190),
                        1f,
                    ),
                )
                addView(BusTheme.hgap(this@RemoteInputActivity, 10))
                addView(
                    directionalCross(),
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@RemoteInputActivity, 10))
        addView(
            remoteButton(getString(R.string.remote_input_back), RemoteInputPhoneContract.KEY_BACK),
            NexusUi.block(),
        )
    }

    /**
     * A cross, because that is what a thumb expects: up and down where they look,
     * select in the middle. Back sits outside it — it leaves the screen rather
     * than moving inside it, and a thumb should not find it by accident.
     */
    private fun directionalCross(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(crossRow(null, R.string.remote_input_up to RemoteInputPhoneContract.KEY_UP, null))
        addView(BusTheme.gap(this@RemoteInputActivity, 8))
        addView(
            crossRow(
                R.string.remote_input_left to RemoteInputPhoneContract.KEY_LEFT,
                R.string.remote_input_select to RemoteInputPhoneContract.KEY_SELECT,
                R.string.remote_input_right to RemoteInputPhoneContract.KEY_RIGHT,
            ),
        )
        addView(BusTheme.gap(this@RemoteInputActivity, 8))
        addView(crossRow(null, R.string.remote_input_down to RemoteInputPhoneContract.KEY_DOWN, null))
    }

    private fun crossRow(
        left: Pair<Int, String>?,
        center: Pair<Int, String>?,
        right: Pair<Int, String>?,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(left, center, right).forEachIndexed { index, cell ->
            if (index > 0) addView(BusTheme.hgap(this@RemoteInputActivity, 8))
            val child = cell?.let { (label, key) ->
                remoteButton(getString(label), key)
            } ?: View(this@RemoteInputActivity)
            addView(child, weightedButtonParams())
        }
    }

    private fun remoteButtonRow(
        first: Pair<String, String>,
        second: Pair<String, String>,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(remoteButton(first.first, first.second), weightedButtonParams())
        addView(BusTheme.hgap(this@RemoteInputActivity, 8))
        addView(remoteButton(second.first, second.second), weightedButtonParams())
    }

    private fun remoteButton(label: String, key: String): Button =
        NexusUi.outlinePillButton(this, label).apply {
            setOnClickListener { publisher.navigate(UUID.randomUUID().toString(), key) }
            remoteButtons += this
        }

    private fun weightedButtonParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun titleHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(this@RemoteInputActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    NexusUi.dp(this@RemoteInputActivity, 10),
                    NexusUi.dp(this@RemoteInputActivity, 12),
                    NexusUi.dp(this@RemoteInputActivity, 22),
                    NexusUi.dp(this@RemoteInputActivity, 12),
                )
                addView(backButton())
                addView(
                    NexusUi.metaLabel(
                        this@RemoteInputActivity,
                        getString(R.string.remote_input_title),
                        NexusUi.INK,
                    ).apply {
                        textSize = 12f
                        letterSpacing = 0.2f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(NexusUi.wordmark(this@RemoteInputActivity, "NEXUS"))
            },
            NexusUi.block(),
        )
        addView(NexusUi.divider(this@RemoteInputActivity))
    }

    private fun backButton(): TextView = TextView(this).apply {
        text = "\u2039"
        textSize = 26f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(NexusUi.INK)
        background = NexusUi.pressed(this@RemoteInputActivity, Color.TRANSPARENT, 22)
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.remote_input_close)
        setOnClickListener {
            sendClose()
            finish()
        }
        layoutParams = LinearLayout.LayoutParams(
            NexusUi.dp(this@RemoteInputActivity, 44),
            NexusUi.dp(this@RemoteInputActivity, 44),
        )
    }

    private fun applyState(next: RemoteInputViewState) {
        val sessionChanged = next.sessionId != viewState.sessionId || next.password != viewState.password
        viewState = next
        if (sessionChanged || !next.editorEnabled) {
            resetLocalEditor()
            sequence.reset(next.sessionId)
        }
        if (sessionChanged && next.sessionId != autoShowKeyboardSessionId) {
            autoShowKeyboardSessionId = null
        }
        if (!next.editorEnabled) {
            hideKeyboard()
            editor.clearFocus()
        }
        renderState()
        maybeShowKeyboard()
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_SHOW_KEYBOARD, false) == true) {
            autoShowKeyboardSessionId = intent
                .getStringExtra(EXTRA_AUTO_SHOW_KEYBOARD_SESSION_ID)
                ?.takeIf(String::isNotBlank)
            intent.removeExtra(EXTRA_AUTO_SHOW_KEYBOARD)
            intent.removeExtra(EXTRA_AUTO_SHOW_KEYBOARD_SESSION_ID)
        }
    }

    private fun maybeShowKeyboard() {
        val sessionId = viewState.sessionId ?: return
        val requestedSessionId = autoShowKeyboardSessionId ?: return
        if (requestedSessionId == sessionId && autoShownSessionId == sessionId) {
            autoShowKeyboardSessionId = null
            return
        }
        if (
            requestedSessionId != sessionId ||
            !viewState.editorEnabled
        ) {
            return
        }
        autoShowKeyboardSessionId = null
        autoShownSessionId = sessionId
        editor.post(::showKeyboard)
    }

    private fun renderState() {
        val (dotColor, title, body) = when (viewState.phase) {
            RemoteInputViewState.Phase.CONNECTING -> Triple(
                NexusUi.AMBER,
                getString(R.string.remote_input_status_connecting),
                getString(R.string.remote_input_status_connecting_body),
            )
            RemoteInputViewState.Phase.DISCONNECTED -> Triple(
                NexusUi.DANGER,
                getString(R.string.remote_input_status_disconnected),
                getString(R.string.remote_input_status_disconnected_body),
            )
            RemoteInputViewState.Phase.WAITING_FOR_FIELD -> Triple(
                NexusUi.GREEN_DIM,
                getString(R.string.remote_input_status_waiting),
                getString(R.string.remote_input_status_waiting_body),
            )
            RemoteInputViewState.Phase.READY -> Triple(
                NexusUi.GREEN,
                viewState.fieldLabel ?: getString(R.string.remote_input_status_ready),
                getString(R.string.remote_input_status_ready_body),
            )
        }
        NexusUi.setDotColor(statusDot, dotColor)
        statusTitle.text = title
        statusBody.text = body
        privacyLabel.text = if (viewState.password) {
            getString(R.string.remote_input_private_mode)
        } else {
            getString(R.string.remote_input_no_remote_content)
        }
        privacyLabel.setTextColor(if (viewState.password) NexusUi.AMBER else NexusUi.GREEN_DIM)

        if (viewState.secureWindow) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        editor.isEnabled = viewState.editorEnabled
        editor.isFocusable = viewState.editorEnabled
        editor.isFocusableInTouchMode = viewState.editorEnabled
        editor.inputType = if (viewState.password) {
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        editor.imeOptions = if (viewState.primaryAction == RemoteInputPhoneContract.EDITOR_NEXT) {
            EditorInfo.IME_ACTION_NEXT
        } else {
            EditorInfo.IME_ACTION_DONE
        }
        // Uppercased like every other pill on this screen: outlinePillButton does it
        // at construction, and this label is replaced after that.
        editorAction.text = if (viewState.primaryAction == RemoteInputPhoneContract.EDITOR_NEXT) {
            getString(R.string.remote_input_next).uppercase()
        } else {
            getString(R.string.remote_input_enter).uppercase()
        }
        editorAction.isEnabled = viewState.editorEnabled
        closeAction.isEnabled = true
        remoteButtons.forEach { it.isEnabled = viewState.controlsEnabled }
        trackpad.isEnabled = viewState.controlsEnabled
        trackpad.alpha = if (viewState.controlsEnabled) 1f else 0.4f
    }

    private fun publishInputOperation(operation: LocalInputOperation) {
        if (!viewState.editorEnabled) return
        val sessionId = viewState.sessionId ?: return
        when (operation) {
            is LocalInputOperation.CommitText -> {
                RemoteTextChunks.split(operation.text).forEach { chunk ->
                    publisher.commitText(sessionId, sequence.next(sessionId), chunk)
                }
            }
            is LocalInputOperation.SetComposingText -> {
                val chunks = RemoteTextChunks.split(operation.text)
                chunks.dropLast(1).forEach { chunk ->
                    publisher.commitText(sessionId, sequence.next(sessionId), chunk)
                }
                publisher.setComposingText(sessionId, sequence.next(sessionId), chunks.last())
            }
            LocalInputOperation.FinishComposing ->
                publisher.finishComposing(sessionId, sequence.next(sessionId))
            is LocalInputOperation.DeleteSurrounding -> publishDeleteSurrounding(
                sessionId,
                operation.beforeLength,
                operation.afterLength,
            )
            is LocalInputOperation.PerformEditorAction -> {
                val action = if (operation.actionId == EditorInfo.IME_ACTION_NEXT) {
                    RemoteInputPhoneContract.EDITOR_NEXT
                } else {
                    RemoteInputPhoneContract.EDITOR_ENTER
                }
                publisher.editorAction(sessionId, sequence.next(sessionId), action)
            }
        }
    }

    private fun publishDeleteSurrounding(sessionId: String, beforeLength: Int, afterLength: Int) {
        var before = beforeLength.coerceAtLeast(0)
        var after = afterLength.coerceAtLeast(0)
        while (before > 0 || after > 0) {
            val beforeChunk = minOf(before, RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF16)
            val afterChunk = minOf(after, RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF16)
            publisher.deleteSurrounding(
                sessionId,
                sequence.next(sessionId),
                beforeChunk,
                afterChunk,
            )
            before -= beforeChunk
            after -= afterChunk
        }
    }

    private fun sendEditorAction() {
        if (!viewState.editorEnabled) return
        val sessionId = viewState.sessionId ?: return
        publisher.editorAction(
            sessionId = sessionId,
            sequence = sequence.next(sessionId),
            action = viewState.primaryAction,
        )
    }

    private fun sendClose() {
        if (closeSent) return
        closeSent = true
        publisher.close(viewState.sessionId, sequence.next(viewState.sessionId))
    }

    private fun resetLocalEditor() {
        if (!::editor.isInitialized) return
        editor.text?.clear()
    }

    private fun showKeyboard() {
        if (!viewState.editorEnabled || isFinishing) return
        editor.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    companion object {
        internal const val EXTRA_AUTO_SHOW_KEYBOARD =
            "com.anezium.rokidbus.phone.extra.AUTO_SHOW_KEYBOARD"
        internal const val EXTRA_AUTO_SHOW_KEYBOARD_SESSION_ID =
            "com.anezium.rokidbus.phone.extra.AUTO_SHOW_KEYBOARD_SESSION_ID"
    }
}

private interface RemoteInputPublisher {
    fun requestState()
    fun commitText(sessionId: String, sequence: Long, text: String)
    fun setComposingText(sessionId: String, sequence: Long, text: String)
    fun finishComposing(sessionId: String, sequence: Long)
    fun deleteSurrounding(
        sessionId: String,
        sequence: Long,
        beforeLength: Int,
        afterLength: Int,
    )
    fun editorAction(sessionId: String, sequence: Long, action: String)
    fun navigate(requestId: String, action: String)
    fun close(sessionId: String?, sequence: Long)
}

private class BroadcastRemoteInputPublisher(context: Context) : RemoteInputPublisher {
    private val appContext = context.applicationContext

    override fun requestState() = send(RemoteInputPhoneContract.requestState(appContext))

    override fun commitText(sessionId: String, sequence: Long, text: String) =
        send(RemoteInputPhoneContract.commitText(appContext, sessionId, sequence, text))

    override fun setComposingText(sessionId: String, sequence: Long, text: String) =
        send(RemoteInputPhoneContract.setComposingText(appContext, sessionId, sequence, text))

    override fun finishComposing(sessionId: String, sequence: Long) =
        send(RemoteInputPhoneContract.finishComposing(appContext, sessionId, sequence))

    override fun deleteSurrounding(
        sessionId: String,
        sequence: Long,
        beforeLength: Int,
        afterLength: Int,
    ) = send(
        RemoteInputPhoneContract.deleteSurrounding(
            appContext,
            sessionId,
            sequence,
            beforeLength,
            afterLength,
        ),
    )

    override fun editorAction(sessionId: String, sequence: Long, action: String) =
        send(RemoteInputPhoneContract.editorAction(appContext, sessionId, sequence, action))

    override fun navigate(requestId: String, action: String) =
        send(RemoteInputPhoneContract.navigate(appContext, requestId, action))

    override fun close(sessionId: String?, sequence: Long) =
        sessionId?.takeIf(String::isNotBlank)?.let {
            send(RemoteInputPhoneContract.close(appContext, it, sequence))
        } ?: Unit

    private fun send(intent: Intent) {
        appContext.sendBroadcast(intent)
    }
}

private sealed interface LocalInputOperation {
    data class CommitText(val text: CharSequence) : LocalInputOperation
    data class SetComposingText(val text: CharSequence) : LocalInputOperation
    data object FinishComposing : LocalInputOperation
    data class DeleteSurrounding(val beforeLength: Int, val afterLength: Int) : LocalInputOperation
    data class PerformEditorAction(val actionId: Int) : LocalInputOperation
}

/** Mirrors the IME protocol one call at a time instead of shipping editor snapshots. */
private class StreamingEditText(context: Context) : EditText(context) {
    var onInputOperation: ((LocalInputOperation) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.setComposingText(text, newCursorPosition).also { success ->
                    if (success) onInputOperation?.invoke(
                        LocalInputOperation.SetComposingText(text ?: ""),
                    )
                }

            override fun finishComposingText(): Boolean =
                super.finishComposingText().also { success ->
                    if (success) onInputOperation?.invoke(LocalInputOperation.FinishComposing)
                }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { success ->
                    if (success) onInputOperation?.invoke(LocalInputOperation.CommitText(text ?: ""))
                }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                super.deleteSurroundingText(beforeLength, afterLength).also { success ->
                    if (success) onInputOperation?.invoke(
                        LocalInputOperation.DeleteSurrounding(beforeLength, afterLength),
                    )
                }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                val (beforeUtf16, afterUtf16) = codePointDeleteLengths(beforeLength, afterLength)
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { success ->
                    if (success) onInputOperation?.invoke(
                        LocalInputOperation.DeleteSurrounding(beforeUtf16, afterUtf16),
                    )
                }
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                val handled = super.performEditorAction(editorAction)
                onInputOperation?.invoke(LocalInputOperation.PerformEditorAction(editorAction))
                return handled
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                super.sendKeyEvent(event).also { success ->
                    if (!success || event.action != KeyEvent.ACTION_DOWN) return@also
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> onInputOperation?.invoke(
                            LocalInputOperation.DeleteSurrounding(1, 0),
                        )
                        KeyEvent.KEYCODE_ENTER -> onInputOperation?.invoke(
                            LocalInputOperation.PerformEditorAction(EditorInfo.IME_ACTION_DONE),
                        )
                    }
                }

            private fun codePointDeleteLengths(beforeCodePoints: Int, afterCodePoints: Int): Pair<Int, Int> {
                val value = this@StreamingEditText.text ?: return beforeCodePoints to afterCodePoints
                val cursor = this@StreamingEditText.selectionEnd.coerceIn(0, value.length)
                val beforeStart = runCatching {
                    Character.offsetByCodePoints(value, cursor, -beforeCodePoints.coerceAtLeast(0))
                }.getOrDefault(0)
                val afterEnd = runCatching {
                    Character.offsetByCodePoints(value, cursor, afterCodePoints.coerceAtLeast(0))
                }.getOrDefault(value.length)
                return (cursor - beforeStart) to (afterEnd - cursor)
            }
        }
    }
}
