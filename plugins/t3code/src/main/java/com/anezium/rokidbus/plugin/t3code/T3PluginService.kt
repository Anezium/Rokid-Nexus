package com.anezium.rokidbus.plugin.t3code

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.max

class T3PluginService : NexusPluginService() {
    private enum class View {
        BOARD,
        THREAD,
        WIZARD,
    }

    private enum class WizardStage {
        PROJECT,
        PROVIDER,
        MODEL,
        EFFORT,
        DICTATE,
    }

    private val main = Handler(Looper.getMainLooper())
    private val settings by lazy { T3Settings(this) }
    private val connection = T3Connection { event ->
        main.post { if (sessionActive) handleConnectionEvent(event) }
    }
    private var sessionActive = false
    private var surface: NexusSurfaceSession? = null
    private var endpoint: T3Endpoint? = null
    private var connectionState = T3ConnectionState.CLOSED
    private var connectionMessage: String? = null
    private var shellReady = false
    private var serverLabel = "T3 Code"
    private var config: T3ServerConfig? = null
    private val projects = linkedMapOf<String, T3Project>()
    private val threads = linkedMapOf<String, T3BoardThread>()

    private var view = View.BOARD
    private var boardCursor = 0
    private var boardError: String? = null
    private var currentThreadId: String? = null
    private var threadDetail: T3ThreadDetail? = null
    private var threadWindowEnd = 0
    private var threadError: String? = null

    private var wizardStage = WizardStage.PROJECT
    private var pickerCursor = 0
    private var wizardProject: T3Project? = null
    private var wizardProvider: T3Provider? = null
    private var wizardModel: T3Model? = null
    private var wizardEffort: T3ModelOption? = null
    private var wizardError: String? = null
    private val finalPromptSegments = mutableListOf<String>()
    private var partialPrompt = ""
    private var dictationReady = false
    private var dictationStatus = "Starting speech…"
    private var dispatching = false
    private var pendingThreadId: String? = null
    private var speechGeneration = 0
    private var speech: NexusSpeechSession? = null

    private var lastDirectionAt = Long.MIN_VALUE
    private var lastThreadRefreshAt = Long.MIN_VALUE
    private var threadRefreshScheduled = false
    private val refreshThreadRunnable = Runnable {
        threadRefreshScheduled = false
        if (!sessionActive || view != View.THREAD) return@Runnable
        lastThreadRefreshAt = SystemClock.uptimeMillis()
        connection.refreshThread()
    }

    override fun onNexusOpen() {
        sessionActive = true
        main.removeCallbacks(refreshThreadRunnable)
        threadRefreshScheduled = false
        invalidateSpeech()
        connection.stop()
        resetUiState()
        surface = nexusSurfaceSession(SURFACE_ID)
        endpoint = settings.endpoint()
        endpoint?.let { linked -> serverLabel = linked.label }
        renderBoard(show = true)
        endpoint?.let(connection::start)
    }

    override fun onNexusClose() {
        sessionActive = false
        main.removeCallbacks(refreshThreadRunnable)
        threadRefreshScheduled = false
        invalidateSpeech()
        connection.stop()
        surface = null
        resetUiState()
    }

    override fun onDestroy() {
        sessionActive = false
        main.removeCallbacksAndMessages(null)
        invalidateSpeech()
        connection.destroy()
        super.onDestroy()
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> if (acceptDirection()) move(1)

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> if (acceptDirection()) move(-1)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> activate()

            KeyEvent.KEYCODE_BACK -> back()
        }
    }

    private fun handleConnectionEvent(event: T3ConnectionEvent) {
        when (event) {
            is T3ConnectionEvent.State -> {
                connectionState = event.state
                connectionMessage = event.message
                when (view) {
                    View.BOARD -> renderBoard(show = false)
                    View.THREAD -> renderThread(show = false)
                    View.WIZARD -> renderWizard(show = false)
                }
            }
            is T3ConnectionEvent.Config -> {
                config = event.config
                boardError = null
                event.config.environmentLabel?.let { label ->
                    serverLabel = label
                    settings.updateLabel(label)
                }
                if (view == View.BOARD) renderBoard(show = false)
            }
            is T3ConnectionEvent.Shell -> applyShellEvent(event.event)
            is T3ConnectionEvent.Thread -> applyThreadEvent(event.threadId, event.event)
            is T3ConnectionEvent.ThreadFailure -> {
                if (event.threadId != currentThreadId) return
                threadError = event.message
                scheduleThreadRefresh()
                if (view == View.THREAD) renderThread(show = false)
            }
            is T3ConnectionEvent.DispatchSuccess -> {
                if (pendingThreadId != event.threadId) return
                pendingThreadId = null
                dispatching = false
                openThread(event.threadId)
            }
            is T3ConnectionEvent.DispatchFailure -> {
                if (pendingThreadId != event.threadId) return
                pendingThreadId = null
                dispatching = false
                wizardError = event.message
                if (view == View.WIZARD) renderWizard(show = false)
            }
        }
    }

    private fun applyShellEvent(event: T3ShellEvent) {
        boardError = null
        when (event) {
            is T3ShellEvent.Snapshot -> {
                projects.clear()
                event.projects.forEach { projects[it.id] = it }
                threads.clear()
                event.threads.forEach { threads[it.id] = it }
            }
            T3ShellEvent.Synchronized -> shellReady = true
            is T3ShellEvent.ProjectUpserted -> projects[event.project.id] = event.project
            is T3ShellEvent.ProjectRemoved -> projects.remove(event.projectId)
            is T3ShellEvent.ThreadUpserted -> threads[event.thread.id] = event.thread
            is T3ShellEvent.ThreadRemoved -> threads.remove(event.threadId)
        }
        clampBoardCursor()
        if (view == View.BOARD) renderBoard(show = false)
    }

    private fun applyThreadEvent(threadId: String, event: T3ThreadStreamEvent) {
        if (threadId != currentThreadId) return
        when (event) {
            is T3ThreadStreamEvent.Snapshot -> {
                threadDetail = event.thread
                threadWindowEnd = visibleMessages(event.thread).size
                threadError = null
            }
            T3ThreadStreamEvent.Synchronized -> threadError = null
            is T3ThreadStreamEvent.Event -> {
                val detail = threadDetail
                val updated = detail?.let { T3Parsers.applyThreadEvent(it, event) }
                if (updated == null) {
                    scheduleThreadRefresh()
                } else {
                    val wasAtTail = threadWindowEnd >= visibleMessages(detail).size
                    threadDetail = updated
                    if (wasAtTail) threadWindowEnd = visibleMessages(updated).size
                }
            }
        }
        if (view == View.THREAD) renderThread(show = false)
    }

    private fun move(delta: Int) {
        when (view) {
            View.BOARD -> {
                boardError = null
                val size = T3Board.build(threads.values.toList()).rows.size + 1
                boardCursor = Math.floorMod(boardCursor + delta, size)
                renderBoard(show = false)
            }
            View.THREAD -> moveThreadWindow(delta)
            View.WIZARD -> {
                if (wizardStage == WizardStage.DICTATE) return
                val size = pickerItems().size
                if (size == 0) return
                pickerCursor = Math.floorMod(pickerCursor + delta, size)
                renderWizard(show = false)
            }
        }
    }

    private fun activate() {
        when (view) {
            View.BOARD -> activateBoard()
            View.THREAD -> Unit
            View.WIZARD -> activateWizard()
        }
    }

    private fun back() {
        when (view) {
            View.BOARD -> {
                connection.stop()
                surface?.hide()
            }
            View.THREAD -> returnToBoard()
            View.WIZARD -> backWizard()
        }
    }

    private fun activateBoard() {
        val board = T3Board.build(threads.values.toList())
        if (boardCursor == 0) {
            if (connectionState != T3ConnectionState.CONNECTED || !shellReady) {
                boardError = boardUnavailableMessage()
                renderBoard(show = false)
                return
            }
            if (projects.isEmpty()) {
                boardError = "No T3 Code projects"
                renderBoard(show = false)
                return
            }
            if (config?.availableProviders.isNullOrEmpty()) {
                boardError = "No installed harness providers"
                renderBoard(show = false)
                return
            }
            beginWizard()
            return
        }
        board.rows.getOrNull(boardCursor - 1)?.let { row -> openThread(row.threadId) }
    }

    private fun openThread(threadId: String) {
        invalidateSpeech()
        view = View.THREAD
        currentThreadId = threadId
        threadDetail = null
        threadWindowEnd = 0
        threadError = null
        main.removeCallbacks(refreshThreadRunnable)
        threadRefreshScheduled = false
        connection.subscribeThread(threadId)
        renderThread(show = false)
    }

    private fun returnToBoard() {
        connection.leaveThread()
        main.removeCallbacks(refreshThreadRunnable)
        threadRefreshScheduled = false
        currentThreadId = null
        threadDetail = null
        threadError = null
        view = View.BOARD
        clampBoardCursor()
        renderBoard(show = false)
    }

    private fun moveThreadWindow(delta: Int) {
        val messages = threadDetail?.let(::visibleMessages).orEmpty()
        if (messages.size <= THREAD_MESSAGE_ROWS) return
        val minimumEnd = THREAD_MESSAGE_ROWS
        val next = (threadWindowEnd + delta).coerceIn(minimumEnd, messages.size)
        if (next == threadWindowEnd) return
        threadWindowEnd = next
        renderThread(show = false)
    }

    private fun beginWizard() {
        view = View.WIZARD
        wizardStage = WizardStage.PROJECT
        pickerCursor = 0
        wizardProject = null
        wizardProvider = null
        wizardModel = null
        wizardEffort = null
        wizardError = null
        clearPrompt()
        renderWizard(show = false)
    }

    private fun activateWizard() {
        if (wizardStage == WizardStage.DICTATE) {
            dispatchPrompt()
            return
        }
        val items = pickerItems()
        if (items.isEmpty()) return
        when (wizardStage) {
            WizardStage.PROJECT -> {
                wizardProject = projectChoices().getOrNull(pickerCursor) ?: return
                wizardStage = WizardStage.PROVIDER
                val defaultProvider = wizardProject?.defaultModelSelection?.instanceId
                pickerCursor = providerChoices().indexOfFirst { it.instanceId == defaultProvider }.coerceAtLeast(0)
            }
            WizardStage.PROVIDER -> {
                wizardProvider = providerChoices().getOrNull(pickerCursor) ?: return
                wizardStage = WizardStage.MODEL
                pickerCursor = 0
            }
            WizardStage.MODEL -> {
                wizardModel = modelChoices().getOrNull(pickerCursor) ?: return
                val descriptor = wizardModel?.let(T3Catalog::effortDescriptor)
                if (descriptor == null) {
                    wizardEffort = null
                    startDictation()
                    return
                }
                wizardStage = WizardStage.EFFORT
                pickerCursor = descriptor.defaultOptionIndex().coerceIn(0, descriptor.options.lastIndex)
            }
            WizardStage.EFFORT -> {
                val descriptor = wizardModel?.let(T3Catalog::effortDescriptor) ?: return
                wizardEffort = descriptor.options.getOrNull(pickerCursor) ?: return
                startDictation()
                return
            }
            WizardStage.DICTATE -> Unit
        }
        wizardError = null
        renderWizard(show = false)
    }

    private fun backWizard() {
        wizardError = null
        when (wizardStage) {
            WizardStage.PROJECT -> {
                view = View.BOARD
                renderBoard(show = false)
            }
            WizardStage.PROVIDER -> {
                wizardStage = WizardStage.PROJECT
                pickerCursor = projectChoices().indexOfFirst { it.id == wizardProject?.id }.coerceAtLeast(0)
                renderWizard(show = false)
            }
            WizardStage.MODEL -> {
                wizardStage = WizardStage.PROVIDER
                pickerCursor = providerChoices().indexOfFirst { it.instanceId == wizardProvider?.instanceId }.coerceAtLeast(0)
                wizardModel = null
                wizardEffort = null
                renderWizard(show = false)
            }
            WizardStage.EFFORT -> {
                wizardStage = WizardStage.MODEL
                pickerCursor = modelChoices().indexOfFirst { it.slug == wizardModel?.slug }.coerceAtLeast(0)
                wizardEffort = null
                renderWizard(show = false)
            }
            WizardStage.DICTATE -> {
                invalidateSpeech()
                clearPrompt()
                view = View.BOARD
                renderBoard(show = false)
            }
        }
    }

    private fun startDictation() {
        invalidateSpeech()
        clearPrompt()
        wizardStage = WizardStage.DICTATE
        dictationStatus = "Starting speech…"
        renderWizard(show = false)
        val generation = ++speechGeneration
        val session = nexusSpeechSession(speechCallbacks(generation))
        if (session == null) {
            wizardError = "Speech isn't available right now."
            renderWizard(show = false)
            return
        }
        speech = session
        val result = session.start()
        if (result != NexusSdkResult.SENT) {
            speech = null
            wizardError = when (result) {
                NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Grant Speech to text in Nexus settings."
                else -> "Speech start refused: $result"
            }
            renderWizard(show = false)
        }
    }

    private fun speechCallbacks(generation: Int): NexusSpeechCallbacks = object : NexusSpeechCallbacks {
        override fun onSpeechStarted(realtime: Boolean) {
            if (!speechIsCurrent(generation)) return
            dictationStatus = if (realtime) "Listening…" else "Listening… text arrives when you stop"
            renderWizard(show = false)
        }

        override fun onSpeechState(state: NexusSpeechState) {
            if (!speechIsCurrent(generation)) return
            dictationStatus = when (state) {
                NexusSpeechState.LISTENING -> "Listening…"
                NexusSpeechState.RECOGNIZING -> "Recognizing…"
                NexusSpeechState.PROCESSING -> "Transcribing…"
            }
            renderWizard(show = false)
        }

        override fun onSpeechPartial(text: String) {
            if (!speechIsCurrent(generation) || dictationReady) return
            partialPrompt = collapseWhitespace(text)
            renderWizard(show = false)
        }

        override fun onSpeechFinal(text: String) {
            if (!speechIsCurrent(generation)) return
            collapseWhitespace(text).takeIf(String::isNotEmpty)?.let(finalPromptSegments::add)
            partialPrompt = ""
            dictationReady = promptText().isNotEmpty()
            if (dictationReady) {
                dictationStatus = "Ready to send"
                speech?.stop()
            }
            renderWizard(show = false)
        }

        override fun onSpeechStopped(reason: NexusSpeechStopReason, error: NexusSpeechError?) {
            if (!speechIsCurrent(generation)) return
            speech = null
            if (!dictationReady) {
                wizardError = speechFailure(reason, error)
            }
            renderWizard(show = false)
        }
    }

    private fun dispatchPrompt() {
        if (!dictationReady || dispatching) return
        val project = wizardProject ?: return
        val provider = wizardProvider ?: return
        val model = wizardModel ?: return
        val descriptor = T3Catalog.effortDescriptor(model)
        val options = if (descriptor != null && wizardEffort != null) {
            listOf(T3ModelOptionValue(descriptor.id, wizardEffort!!.id))
        } else {
            emptyList()
        }
        val selection = T3ModelSelection(provider.instanceId, model.slug, options)
        val command = T3CommandFactory.threadTurnStart(project.id, selection, promptText())
        pendingThreadId = command.threadId
        dispatching = true
        wizardError = null
        renderWizard(show = false)
        connection.dispatch(command)
    }

    private fun renderBoard(show: Boolean) {
        val board = T3Board.build(threads.values.toList())
        boardCursor = boardCursor.coerceIn(0, board.rows.size)
        val rows = buildList {
            add(
                NexusCardLine(
                    text = "+ New thread",
                    tone = NexusRowTone.NORMAL,
                    selected = boardCursor == 0,
                ),
            )
            board.rows.forEachIndexed { index, row ->
                add(
                    NexusCardLine(
                        text = row.text,
                        badge = row.badge,
                        sub = row.sub,
                        tone = row.tone.toNexusTone(),
                        selected = boardCursor == index + 1,
                    ),
                )
            }
        }
        val subtitle = if (endpoint == null) {
            "Not linked"
        } else {
            "${serverLabel.uppercase().take(80)} · ${board.totalThreads} threads"
        }
        showCard(
            NexusCard(
                title = "T3 CODE",
                lines = emptyList(),
                subtitle = subtitle,
                footer = boardFooter(),
                contentKey = contentHash("board", "T3 CODE", subtitle, boardFooter(), rows),
                richLines = rows,
                handlesBack = false,
            ),
            show,
        )
    }

    private fun renderThread(show: Boolean) {
        val detail = threadDetail
        val boardThread = currentThreadId?.let(threads::get)
        val selection = detail?.modelSelection ?: boardThread?.modelSelection
        val session = detail?.session ?: boardThread?.session
        val title = collapseWhitespace(detail?.title ?: boardThread?.title ?: "Thread").take(120)
        val provider = session?.providerName
            ?: selection?.instanceId?.let(::providerName)
            ?: "harness"
        val subtitle = listOf(
            provider,
            selection?.model?.let(T3Board::modelShort) ?: "model",
            session?.status ?: "loading",
        ).joinToString(" · ").take(240)
        val messages = detail?.let(::visibleMessages).orEmpty()
        val end = threadWindowEnd.coerceIn(0, messages.size)
        val start = max(0, end - THREAD_MESSAGE_ROWS)
        val rows = if (messages.isEmpty()) {
            listOf(NexusCardLine("Loading conversation…", tone = NexusRowTone.DIM))
        } else {
            messages.subList(start, end).map { message ->
                NexusCardLine(
                    text = collapseWhitespace(message.text).ifBlank { "…" }.take(240),
                    sub = "${messageRole(message.role)} · ${T3Board.relativeAge(message.createdAt)}".take(240),
                    tone = NexusRowTone.BODY,
                )
            }
        }
        val footer = when {
            !session?.lastError.isNullOrBlank() -> session?.lastError.orEmpty().take(240)
            !threadError.isNullOrBlank() -> threadError.orEmpty().take(240)
            session?.activeTurnId != null -> "thinking…"
            connectionState == T3ConnectionState.RECONNECTING -> connectionMessage.orEmpty().take(240)
            else -> "swipe scroll · back"
        }
        showCard(
            NexusCard(
                title = title.ifBlank { "Thread" },
                lines = emptyList(),
                subtitle = subtitle,
                footer = footer,
                contentKey = contentHash("thread", title, subtitle, footer, rows),
                richLines = rows,
                handlesBack = true,
            ),
            show,
        )
    }

    private fun renderWizard(show: Boolean) {
        if (wizardStage == WizardStage.DICTATE) {
            renderDictation(show)
            return
        }
        val labels = pickerItems()
        pickerCursor = if (labels.isEmpty()) 0 else pickerCursor.coerceIn(0, labels.lastIndex)
        val rows = labels.mapIndexed { index, label ->
            NexusCardLine(
                text = collapseWhitespace(label).take(120),
                tone = NexusRowTone.NORMAL,
                selected = pickerCursor == index,
            )
        }
        val title = when (wizardStage) {
            WizardStage.PROJECT -> "Project"
            WizardStage.PROVIDER -> "Harness"
            WizardStage.MODEL -> "Model"
            WizardStage.EFFORT -> "Reasoning"
            WizardStage.DICTATE -> error("handled above")
        }
        val footer = wizardError?.take(240) ?: "swipe · tap choose · back"
        showCard(
            NexusCard(
                title = title,
                lines = emptyList(),
                footer = footer,
                contentKey = contentHash("wizard", title, "", footer, rows),
                richLines = rows,
                handlesBack = true,
            ),
            show,
        )
    }

    private fun renderDictation(show: Boolean) {
        val prompt = promptText()
        val preview = when {
            prompt.isNotEmpty() -> prompt.takeLast(240)
            partialPrompt.isNotEmpty() -> partialPrompt.takeLast(240)
            else -> dictationStatus
        }
        val rows = listOf(NexusCardLine(preview, tone = NexusRowTone.BODY))
        val footer = when {
            dispatching -> "Sending…"
            wizardError != null -> wizardError.orEmpty().take(240)
            dictationReady -> "tap send · back cancel"
            else -> "${dictationStatus.take(210)} · back cancel"
        }
        showCard(
            NexusCard(
                title = "Say your prompt",
                lines = emptyList(),
                footer = footer,
                contentKey = contentHash("dictation", "Say your prompt", "", footer, rows),
                richLines = rows,
                handlesBack = true,
            ),
            show,
        )
    }

    private fun showCard(card: NexusCard, show: Boolean) {
        runCatching {
            if (show) surface?.showCard(card) else surface?.updateCard(card)
        }
    }

    private fun pickerItems(): List<String> = when (wizardStage) {
        WizardStage.PROJECT -> projectChoices().map(T3Project::title)
        WizardStage.PROVIDER -> providerChoices().map(T3Provider::displayName)
        WizardStage.MODEL -> modelChoices().map(T3Model::name)
        WizardStage.EFFORT -> wizardModel?.let(T3Catalog::effortDescriptor)?.options
            .orEmpty()
            .take(MAX_PICKER_ROWS)
            .map(T3ModelOption::label)
        WizardStage.DICTATE -> emptyList()
    }

    private fun projectChoices(): List<T3Project> = projects.values
        .sortedBy { it.title.lowercase() }
        .take(MAX_PICKER_ROWS)

    private fun providerChoices(): List<T3Provider> = config?.availableProviders.orEmpty().take(MAX_PICKER_ROWS)

    private fun modelChoices(): List<T3Model> {
        val provider = wizardProvider ?: return emptyList()
        val project = wizardProject ?: return emptyList()
        val activeBoardThreads = threads.values.filter { it.archivedAt == null && it.deletedAt == null }
        return T3Catalog.reachableModels(provider, project, activeBoardThreads)
    }

    private fun providerName(instanceId: String): String = config?.providers
        ?.firstOrNull { it.instanceId == instanceId }
        ?.displayName
        ?: instanceId

    private fun visibleMessages(detail: T3ThreadDetail): List<T3Message> = detail.messages
        .filter { it.role == "user" || it.role == "assistant" }

    private fun promptText(): String = collapseWhitespace(
        (finalPromptSegments + listOfNotNull(partialPrompt.takeIf(String::isNotEmpty))).joinToString(" "),
    )

    private fun clearPrompt() {
        finalPromptSegments.clear()
        partialPrompt = ""
        dictationReady = false
        dictationStatus = "Starting speech…"
        dispatching = false
        pendingThreadId = null
    }

    private fun invalidateSpeech() {
        speechGeneration += 1
        speech?.stop()
        speech = null
    }

    private fun speechIsCurrent(generation: Int): Boolean =
        sessionActive && view == View.WIZARD && wizardStage == WizardStage.DICTATE && generation == speechGeneration

    private fun speechFailure(reason: NexusSpeechStopReason, error: NexusSpeechError?): String {
        val base = when (reason) {
            NexusSpeechStopReason.COMPLETED -> "Nothing heard."
            NexusSpeechStopReason.CANCELLED -> "Speech stopped."
            NexusSpeechStopReason.NO_SPEECH -> "Didn't catch that."
            NexusSpeechStopReason.ERROR -> "Speech failed."
            NexusSpeechStopReason.LINK_LOST -> "Glasses link lost."
            NexusSpeechStopReason.REVOKED -> "Speech access was revoked."
            NexusSpeechStopReason.DENIED_BUSY -> "Speech is busy — try again."
            NexusSpeechStopReason.DENIED_NO_LINK -> "No glasses link."
            NexusSpeechStopReason.DENIED_NOT_READY -> "Configure speech in Nexus settings."
            NexusSpeechStopReason.DENIED_START_FAILED -> "Couldn't start speech."
            NexusSpeechStopReason.DENIED_INVALID -> "Speech request was rejected."
        }
        val kind = error?.kind?.trim().orEmpty()
        return if (kind.isBlank()) base else "$base $kind"
    }

    private fun scheduleThreadRefresh() {
        if (threadRefreshScheduled) return
        val delay = if (lastThreadRefreshAt == Long.MIN_VALUE) {
            0L
        } else {
            val elapsed = SystemClock.uptimeMillis() - lastThreadRefreshAt
            (THREAD_REFRESH_MIN_MS - elapsed).coerceAtLeast(0L)
        }
        threadRefreshScheduled = true
        main.postDelayed(refreshThreadRunnable, delay)
    }

    private fun boardFooter(): String = when {
        endpoint == null -> "Pair from phone settings"
        connectionState == T3ConnectionState.AUTH_EXPIRED -> "Re-pair from phone settings"
        connectionState == T3ConnectionState.CONNECTING -> "Connecting…"
        connectionState == T3ConnectionState.RECONNECTING -> connectionMessage.orEmpty().take(240)
        connectionState == T3ConnectionState.ERROR -> connectionMessage.orEmpty().ifBlank { "T3 Code error" }.take(240)
        connectionState == T3ConnectionState.CONNECTED && !shellReady -> "Connecting…"
        !boardError.isNullOrBlank() -> boardError.orEmpty().take(240)
        else -> "tap open · back exit"
    }

    private fun boardUnavailableMessage(): String = when {
        endpoint == null -> "Pair from phone settings"
        connectionState == T3ConnectionState.AUTH_EXPIRED -> "Re-pair from phone settings"
        else -> "T3 Code is still connecting"
    }

    private fun clampBoardCursor() {
        val count = T3Board.build(threads.values.toList()).rows.size
        boardCursor = boardCursor.coerceIn(0, count)
    }

    private fun resetUiState() {
        connectionState = T3ConnectionState.CLOSED
        connectionMessage = null
        shellReady = false
        config = null
        projects.clear()
        threads.clear()
        view = View.BOARD
        boardCursor = 0
        boardError = null
        currentThreadId = null
        threadDetail = null
        threadWindowEnd = 0
        threadError = null
        wizardStage = WizardStage.PROJECT
        pickerCursor = 0
        wizardProject = null
        wizardProvider = null
        wizardModel = null
        wizardEffort = null
        wizardError = null
        clearPrompt()
        lastDirectionAt = Long.MIN_VALUE
        lastThreadRefreshAt = Long.MIN_VALUE
    }

    private fun acceptDirection(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (lastDirectionAt != Long.MIN_VALUE && now - lastDirectionAt < DIRECTION_DEBOUNCE_MS) return false
        lastDirectionAt = now
        return true
    }

    private fun messageRole(role: String): String = if (role == "user") "you" else "assistant"

    private fun T3BoardTone.toNexusTone(): NexusRowTone = when (this) {
        T3BoardTone.ALERT -> NexusRowTone.ALERT
        T3BoardTone.NORMAL -> NexusRowTone.NORMAL
        T3BoardTone.DIM -> NexusRowTone.DIM
    }

    private fun contentHash(
        scope: String,
        title: String,
        subtitle: String,
        footer: String,
        rows: List<NexusCardLine>,
    ): String {
        val canonical = buildString {
            append(scope).append('\u0000').append(title).append('\u0000').append(subtitle)
                .append('\u0000').append(footer)
            rows.forEach { row ->
                append('\u0000').append(row.text)
                append('\u0000').append(row.badge)
                append('\u0000').append(row.sub)
                append('\u0000').append(row.tone.name)
                append('\u0000').append(row.selected)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SURFACE_ID = "main"
        const val THREAD_MESSAGE_ROWS = 8
        const val MAX_PICKER_ROWS = 64
        const val DIRECTION_DEBOUNCE_MS = 250L
        const val THREAD_REFRESH_MIN_MS = 1_000L
    }
}
