package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason

internal enum class HelloPluginMode {
    MENU,
    DICTATION_LIVE,
    DICTATION_ENDED,
}

internal enum class HelloPluginAction {
    RENDER,
    START_SPEECH,
    START_BACKGROUND_AUDIO,
    SPEAK_TTS,
    STOP_SPEECH,
    STOP_SPEECH_AND_SHOW_MENU,
    SHOW_MENU,
    HIDE_SURFACE,
}

internal data class HelloCardPresentation(
    val title: String,
    val lines: List<String>,
    val footer: String,
    val contentKey: String,
    val handlesBack: Boolean,
)

internal class HelloPluginState(
    private val choices: List<String> =
        listOf("Hello", "SDK", "Open platform", SPEAK, DICTATION, BACKGROUND_AUDIO),
) {
    var selectedIndex: Int = 0
        private set

    var activated: Boolean = false
        private set

    var mode: HelloPluginMode = HelloPluginMode.MENU
        private set

    private var speechStatus = SpeechStatus.STARTING
    private var batchMode = false
    private val finalSegments = mutableListOf<String>()
    private var partial = ""
    private var endedReason = ""
    private var endedError: String? = null

    fun move(delta: Int): Boolean {
        if (mode != HelloPluginMode.MENU) return false
        selectedIndex = Math.floorMod(selectedIndex + delta, choices.size)
        activated = false
        return true
    }

    fun activate(): HelloPluginAction = when (mode) {
        HelloPluginMode.MENU -> {
            if (choices[selectedIndex] == DICTATION) {
                beginDictation()
                HelloPluginAction.START_SPEECH
            } else if (choices[selectedIndex] == BACKGROUND_AUDIO) {
                activated = true
                HelloPluginAction.START_BACKGROUND_AUDIO
            } else if (choices[selectedIndex] == SPEAK) {
                activated = true
                HelloPluginAction.SPEAK_TTS
            } else {
                activated = true
                HelloPluginAction.RENDER
            }
        }
        HelloPluginMode.DICTATION_LIVE -> HelloPluginAction.STOP_SPEECH
        HelloPluginMode.DICTATION_ENDED -> {
            beginDictation()
            HelloPluginAction.START_SPEECH
        }
    }

    fun back(): HelloPluginAction = when (mode) {
        HelloPluginMode.MENU -> HelloPluginAction.HIDE_SURFACE
        HelloPluginMode.DICTATION_LIVE -> {
            resetToMenu()
            HelloPluginAction.STOP_SPEECH_AND_SHOW_MENU
        }
        HelloPluginMode.DICTATION_ENDED -> {
            resetToMenu()
            HelloPluginAction.SHOW_MENU
        }
    }

    fun resetToMenu() {
        mode = HelloPluginMode.MENU
        clearDictation()
    }

    fun onSpeechStartResult(result: NexusSdkResult?) {
        if (mode != HelloPluginMode.DICTATION_LIVE) return
        if (result == NexusSdkResult.SENT) return
        mode = HelloPluginMode.DICTATION_ENDED
        endedReason = if (result == NexusSdkResult.CAPABILITY_NOT_GRANTED) {
            "Grant Speech to text in Nexus settings."
        } else {
            "Speech isn't available right now."
        }
        endedError = null
    }

    fun onSpeechStarted(realtime: Boolean): Boolean {
        if (mode != HelloPluginMode.DICTATION_LIVE) return false
        batchMode = !realtime
        speechStatus = SpeechStatus.LISTENING
        return true
    }

    fun onSpeechState(state: NexusSpeechState): Boolean {
        if (mode != HelloPluginMode.DICTATION_LIVE) return false
        speechStatus = when (state) {
            NexusSpeechState.LISTENING -> SpeechStatus.LISTENING
            NexusSpeechState.RECOGNIZING -> SpeechStatus.RECOGNIZING
            NexusSpeechState.PROCESSING -> SpeechStatus.PROCESSING
        }
        return true
    }

    fun onSpeechPartial(text: String): Boolean {
        if (mode != HelloPluginMode.DICTATION_LIVE) return false
        partial = normalizeSpeechText(text)
        return true
    }

    fun onSpeechFinal(text: String): Boolean {
        if (mode != HelloPluginMode.DICTATION_LIVE) return false
        normalizeSpeechText(text).takeIf(String::isNotEmpty)?.let(finalSegments::add)
        partial = ""
        return true
    }

    fun onSpeechStopped(
        reason: NexusSpeechStopReason,
        error: NexusSpeechError?,
    ): Boolean {
        if (mode != HelloPluginMode.DICTATION_LIVE) return false
        mode = HelloPluginMode.DICTATION_ENDED
        endedReason = reasonLine(reason, transcriptText().isNotEmpty())
        endedError = error?.let { speechError ->
            val kind = speechError.kind.trim()
            val provider = speechError.provider?.trim()?.takeIf(String::isNotEmpty)
            if (provider == null) kind else "$kind · $provider"
        }
        return true
    }

    fun presentation(): HelloCardPresentation = when (mode) {
        HelloPluginMode.MENU -> HelloCardPresentation(
            title = "Hello Nexus",
            lines = menuLines(),
            footer = "swipe · tap · back",
            contentKey = "hello-v1",
            handlesBack = false,
        )
        HelloPluginMode.DICTATION_LIVE -> HelloCardPresentation(
            title = DICTATION,
            lines = buildList {
                add(statusLine())
                add("")
                addAll(transcriptLines(live = true))
            },
            footer = "tap to stop · back",
            contentKey = "hello-dictation",
            handlesBack = true,
        )
        HelloPluginMode.DICTATION_ENDED -> HelloCardPresentation(
            title = DICTATION,
            lines = buildList {
                add(endedReason)
                endedError?.let(::add)
                add("")
                addAll(transcriptLines(live = false))
            },
            footer = "tap to retry · back",
            contentKey = "hello-dictation-end",
            handlesBack = true,
        )
    }

    private fun menuLines(): List<String> = choices.mapIndexed { index, choice ->
        val marker = if (index == selectedIndex) ">" else " "
        "$marker $choice${if (activated && index == selectedIndex) " ✓" else ""}"
    }

    private fun beginDictation() {
        clearDictation()
        activated = false
        mode = HelloPluginMode.DICTATION_LIVE
    }

    private fun clearDictation() {
        speechStatus = SpeechStatus.STARTING
        batchMode = false
        finalSegments.clear()
        partial = ""
        endedReason = ""
        endedError = null
    }

    private fun statusLine(): String {
        val status = when (speechStatus) {
            SpeechStatus.STARTING -> "Starting..."
            SpeechStatus.LISTENING -> "Listening..."
            SpeechStatus.RECOGNIZING -> "Recognizing..."
            SpeechStatus.PROCESSING -> "Transcribing..."
        }
        return if (batchMode && speechStatus != SpeechStatus.STARTING) "$status (batch)" else status
    }

    private fun transcriptLines(live: Boolean): List<String> {
        val transcript = transcriptText()
        if (transcript.isNotEmpty()) return wrapDictationTranscript(transcript)
        if (!live) return listOf("No transcript.")
        return listOf(if (batchMode) "Text arrives when you stop." else "Say something...")
    }

    private fun transcriptText(): String =
        (finalSegments + listOfNotNull(partial.takeIf(String::isNotEmpty))).joinToString(" ")

    private fun reasonLine(reason: NexusSpeechStopReason, hasTranscript: Boolean): String = when (reason) {
        NexusSpeechStopReason.COMPLETED -> if (hasTranscript) "Done." else "Nothing heard."
        NexusSpeechStopReason.CANCELLED -> "Stopped."
        NexusSpeechStopReason.NO_SPEECH -> "Didn't catch that."
        NexusSpeechStopReason.ERROR -> "Speech failed."
        NexusSpeechStopReason.LINK_LOST -> "Glasses link lost."
        NexusSpeechStopReason.REVOKED -> "Speech access was revoked."
        NexusSpeechStopReason.DENIED_BUSY -> "Speech is busy - try again in a moment."
        NexusSpeechStopReason.DENIED_NO_LINK -> "No glasses link."
        NexusSpeechStopReason.DENIED_NOT_READY -> "Add a speech API key in Nexus > Speech."
        NexusSpeechStopReason.DENIED_START_FAILED -> "Couldn't start the recorder."
        NexusSpeechStopReason.DENIED_INVALID -> "Speech request was rejected."
    }

    private enum class SpeechStatus {
        STARTING,
        LISTENING,
        RECOGNIZING,
        PROCESSING,
    }

    private companion object {
        const val DICTATION = "Dictation"
        const val BACKGROUND_AUDIO = "Background mic"
        const val SPEAK = "Speak"
    }
}

internal fun wrapDictationTranscript(text: String): List<String> {
    val words = normalizeSpeechText(text).split(" ").filter(String::isNotEmpty)
    if (words.isEmpty()) return emptyList()

    val lines = mutableListOf<String>()
    var current = ""

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            lines += current
            current = ""
        }
    }

    words.forEach { word ->
        if (word.length <= DICTATION_LINE_LENGTH) {
            if (current.isEmpty()) {
                current = word
            } else if (current.length + 1 + word.length <= DICTATION_LINE_LENGTH) {
                current += " $word"
            } else {
                flushCurrent()
                current = word
            }
        } else {
            flushCurrent()
            var offset = 0
            while (word.length - offset > DICTATION_LINE_LENGTH) {
                lines += word.substring(offset, offset + DICTATION_LINE_LENGTH)
                offset += DICTATION_LINE_LENGTH
            }
            current = word.substring(offset)
        }
    }
    flushCurrent()
    return lines.takeLast(DICTATION_LINE_TAIL)
}

private fun normalizeSpeechText(text: String): String =
    text.trim().replace(Regex("\\s+"), " ")

// The HUD renderer re-wraps anything longer, so this only has to match what the optic
// actually shows (measured at roughly 29 characters) for the six-line tail to be honest.
private const val DICTATION_LINE_LENGTH = 28
private const val DICTATION_LINE_TAIL = 6
