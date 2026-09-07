package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.EditableSurfaceContract
import java.util.UUID

internal enum class AssistantTextEntryKind {
    PHONE_QUESTION,
    HUD_QUESTION,
    NOTE,
}

internal enum class AssistantTextInputStatus(val message: String) {
    READY("Type on this phone. The answer appears on your glasses."),
    SENT("Question sent. Read the answer on your glasses."),
    CANCELLED("Question cancelled."),
    EMPTY("Write a question first."),
    TOO_LONG("Keep your question to 512 characters."),
    NOT_OPEN("Open Assistant from the glasses launcher first."),
    DISCONNECTED("Connect your glasses to Nexus first."),
    BUSY("Assistant is busy. Finish the current request or text entry first."),
    AUTH_REQUIRED("Connect an AI provider in Assistant settings first."),
    UNSUPPORTED("Update the glasses hub, or write a question in phone settings."),
    EXPIRED("This text entry ended. Close it and choose Write a question again."),
    UNAVAILABLE("Text input is unavailable. Reopen Assistant and try again."),
}

internal data class AssistantTextEntry(val id: String, val kind: AssistantTextEntryKind)

internal data class AssistantTextEntryStart(
    val status: AssistantTextInputStatus,
    val entry: AssistantTextEntry? = null,
)

/** Main-thread ownership shared by the phone composer and the one-shot HUD field. */
internal class AssistantTextInput(
    private val availability: (AssistantTextEntryKind) -> AssistantTextInputStatus,
    private val onQuestion: (String) -> AssistantTextInputStatus,
    private val onNote: (String) -> Unit,
    private val newId: () -> String = { "assistant-text-${UUID.randomUUID()}" },
) {
    var active: AssistantTextEntry? = null
        private set

    fun availableFor(kind: AssistantTextEntryKind): AssistantTextInputStatus =
        availability(kind).takeUnless { it == AssistantTextInputStatus.READY && active != null }
            ?: AssistantTextInputStatus.BUSY

    fun begin(kind: AssistantTextEntryKind): AssistantTextEntryStart {
        val status = availableFor(kind)
        if (status != AssistantTextInputStatus.READY) return AssistantTextEntryStart(status)
        val entry = AssistantTextEntry(newId(), kind)
        active = entry
        return AssistantTextEntryStart(AssistantTextInputStatus.READY, entry)
    }

    fun submitPhone(id: String, text: String): AssistantTextInputStatus {
        val entry = active?.takeIf { it.id == id && it.kind == AssistantTextEntryKind.PHONE_QUESTION }
            ?: return AssistantTextInputStatus.EXPIRED
        return submit(entry, text, keepInvalidDraft = true)
    }

    fun commitSurface(id: String, text: String, cancelled: Boolean): AssistantTextInputStatus {
        // Existing hubs return the wire id; tolerate local ids without accepting another owner.
        val localId = id.removePrefix("$PLUGIN_ID:")
        val entry = active?.takeIf { it.id == localId && it.kind != AssistantTextEntryKind.PHONE_QUESTION }
            ?: return AssistantTextInputStatus.EXPIRED
        if (cancelled) {
            active = null
            return AssistantTextInputStatus.CANCELLED
        }
        return submit(entry, text, keepInvalidDraft = false)
    }

    fun cancel(id: String): Boolean {
        if (active?.id != id) return false
        active = null
        return true
    }

    fun clear() {
        active = null
    }

    private fun submit(
        entry: AssistantTextEntry,
        text: String,
        keepInvalidDraft: Boolean,
    ): AssistantTextInputStatus {
        val status = availability(entry.kind)
        if (status != AssistantTextInputStatus.READY) {
            active = null
            return status
        }
        val normalized = text.trim()
        val invalid = when {
            normalized.isEmpty() -> AssistantTextInputStatus.EMPTY
            text.length > MAX_TEXT_LENGTH -> AssistantTextInputStatus.TOO_LONG
            else -> null
        }
        if (invalid != null) {
            if (!keepInvalidDraft) active = null
            return invalid
        }
        // Consume before invoking any provider or storage code, including reentrant callbacks.
        active = null
        return if (entry.kind == AssistantTextEntryKind.NOTE) {
            onNote(normalized)
            AssistantTextInputStatus.SENT
        } else {
            onQuestion(normalized)
        }
    }

    companion object {
        private const val PLUGIN_ID = "assistant"
        const val MAX_TEXT_LENGTH = EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH
    }
}
