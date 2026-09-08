package com.anezium.rokidbus.plugin.agents

/** Phone navigation selects once; subsequent HUD selections share the same conversation. */
internal class LitterConversationSelection(initialSessionId: String?) {
    var sessionId: String? = initialSessionId
        private set
    private var explicitSelectionPending = initialSessionId != null
    private val drafts = linkedMapOf<String, String>()

    fun requestResume(visible: Boolean, connected: Boolean, sharedSessionId: String?): String? {
        val id = sessionId ?: return null
        if (!visible || !connected || (!explicitSelectionPending && sharedSessionId != null)) return null
        explicitSelectionPending = false
        return id
    }

    fun observe(sharedSessionId: String, currentDraft: String): String? {
        val previous = sessionId ?: return null
        if (explicitSelectionPending || previous == sharedSessionId) return null
        drafts[previous] = currentDraft.take(LitterProtocol.MAX_TEXT)
        while (drafts.size > 8) drafts.remove(drafts.keys.first())
        sessionId = sharedSessionId
        return drafts.remove(sharedSessionId).orEmpty()
    }

    fun created(id: String) { sessionId = id; explicitSelectionPending = false }

    fun sent(id: String, text: String) {
        if (drafts[id]?.trim() == text) drafts.remove(id)
    }
}
