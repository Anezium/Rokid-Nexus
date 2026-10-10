package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.plugin.NexusPageResult

/**
 * The hub composer's Send on a Relay conversation page. The SDK already delivered it only for the
 * revision the wearer saw, the composer's own send action and text within its limit; this decides
 * whether Relay may still hand that text to the app, at the moment it would.
 *
 * Authority is the live reply action of that exact thread and nothing else: no other thread, no
 * guessed recipient, and never a retry. Sent means the app has the text ("handed to the app"),
 * not that anyone received it. When the action is gone the result carries `authority_lost`, which
 * the glasses read as "keep the text, stop offering Send". A send that failed in a way that does
 * not say whether the app got the text is reported as unconfirmed, never as a failure to retry.
 *
 * Each invocation id is answered once: a repeat gets the first answer and sends nothing.
 */
internal class RelayComposeHandler(
    private val threadIdOfPage: (pageId: String) -> String?,
    private val hasAuthority: (threadId: String) -> Boolean,
    private val send: (threadId: String, text: String) -> ReplySendResult,
    private val changed: () -> Unit,
) {
    private val answered = LinkedHashMap<String, NexusPageResult>()

    fun handle(
        invocationId: String,
        pageId: String,
        actionId: String,
        compose: Boolean,
        composeSendTyped: Boolean,
        text: String?,
    ): NexusPageResult {
        answered[invocationId]?.let { return it }
        val result = decide(pageId, actionId, compose, composeSendTyped, text)
        answered[invocationId] = result
        while (answered.size > MAX_REMEMBERED) answered.remove(answered.keys.first())
        return result
    }

    private fun decide(
        pageId: String,
        actionId: String,
        compose: Boolean,
        composeSendTyped: Boolean,
        text: String?,
    ): NexusPageResult {
        // A send is reachable only through the composer; nothing else on Relay's pages acts.
        if (!compose || !composeSendTyped || actionId != RelayPages.SEND_ACTION_ID || !pageId.startsWith(RelayPages.THREAD_PREFIX)) {
            return NexusPageResult.rejected()
        }
        if (text.isNullOrBlank()) return NexusPageResult.rejected(EMPTY_MESSAGE)
        val threadId = threadIdOfPage(pageId) ?: return authorityLost()
        if (!hasAuthority(threadId)) {
            // The page may still show it replyable; it is not, so it follows.
            changed()
            return authorityLost()
        }
        return when (val result = send(threadId, text)) {
            ReplySendResult.Sent -> NexusPageResult.done(HANDED_OFF_MESSAGE)
            ReplySendResult.Missing,
            ReplySendResult.NoFreeFormInput,
            -> authorityLost()
            ReplySendResult.Blank -> NexusPageResult.rejected(EMPTY_MESSAGE)
            is ReplySendResult.Failed -> if (result.authorityLost) {
                authorityLost()
            } else {
                NexusPageResult.rejected(UNCONFIRMED_MESSAGE)
            }
        }
    }

    private fun authorityLost() = NexusPageResult.rejected(code = NexusPageResult.CODE_AUTHORITY_LOST)

    companion object {
        const val HANDED_OFF_MESSAGE = "handed to the app"
        const val UNCONFIRMED_MESSAGE = "unconfirmed · check on the phone"
        const val EMPTY_MESSAGE = "Empty reply"
        const val MAX_REMEMBERED = 64
    }
}
