package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.TtsContract

/** Pure selection and fitting for the one message a fresh notice reads aloud. */
internal object RelayReadAloud {
    /** A mixed notification may end with our reply even though it also contains a new arrival. */
    fun receivedText(input: NotificationTextInput, fallback: String): String? {
        if (input.messages.isEmpty()) return fallback
        val message = input.messages.lastOrNull { !it.fromSelf && !it.text.isNullOrBlank() } ?: return null
        val text = message.text.orEmpty().trim()
        return message.sender?.trim()?.takeIf(String::isNotBlank)?.let { "$it: $text" } ?: text
    }

    fun textFor(
        enabled: Boolean,
        senderOnly: Boolean,
        sender: String,
        renderedThread: String,
    ): String? {
        if (!enabled) return null
        val spoken = if (senderOnly) {
            // Who wrote is all the glasses may say; the words stay on the phone.
            sender.trim().takeIf(String::isNotBlank)?.let { "Message from $it" } ?: return null
        } else {
            val newest = RelayInboxCatalog.threadMessages(renderedThread).lastOrNull() ?: return null
            val speaker = newest.speaker.ifBlank { sender.trim() }
            if (speaker.isBlank()) newest.text else "$speaker: ${newest.text}"
        }
        return spoken
            .replace(LINE_BREAKS, " ")
            .trim()
            .takeIf(String::isNotBlank)
            ?.takeLast(TtsContract.MAX_TEXT_CHARS)
    }

    private val LINE_BREAKS = Regex("[\\r\\n]+")
}
