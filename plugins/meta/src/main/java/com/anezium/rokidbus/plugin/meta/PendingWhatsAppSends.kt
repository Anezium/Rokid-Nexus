package com.anezium.rokidbus.plugin.meta

internal class PendingWhatsAppSends(
    private val load: () -> Set<String>,
    private val save: (Set<String>) -> Unit,
) {
    private fun resolved(state: String) = state in setOf("acked", "not_sent", "not_found")

    fun reconcile(lookup: (String) -> String) {
        save(load().filterNot { id -> runCatching { resolved(lookup(id)) }.getOrDefault(false) }.toSet())
    }

    fun send(id: String, allowed: () -> Boolean, lookup: (String) -> String, submit: () -> String): String {
        if (!allowed()) return "not_sent"
        val ids = load().toMutableSet()
        if (ids.size >= 64) return "not_sent"
        ids.add(id)
        // Admission to the native outbox happens only after this journal is durable.
        try { save(ids) } catch (_: Exception) { return "not_sent" }
        val state = try {
            if (allowed()) submit() else "not_sent"
        } catch (_: Exception) {
            runCatching { lookup(id) }.getOrDefault("unknown")
        }
        if (resolved(state)) {
            ids.remove(id)
            runCatching { save(ids) }
        }
        return state
    }
}

internal fun whatsAppSendMessage(state: String): String = when (state) {
    "acked" -> "Sent."
    "not_sent", "not_found" -> "Not sent. Review the connection and confirm a new message."
    else -> "Unconfirmed · check WhatsApp. No automatic retry."
}
