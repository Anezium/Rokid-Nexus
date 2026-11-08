package com.anezium.rokidbus.plugin.line

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import java.security.MessageDigest

internal data class LineMessage(val id: String, val text: String, val mine: Boolean, val media: String = "")
internal data class LineChat(val id: String, val title: String, val group: Boolean, val history: List<LineMessage>)
internal data class LineSnapshot(val state: String = "loading", val chats: List<LineChat> = emptyList())
internal data class LineLogin(val phase: String = "idle", val pin: String = "")

internal object LinePages {
    const val ROOT = "line-messages:main"
    const val SEND = "send"
    private const val PREFIX = "line-messages:chat:"

    fun pageId(chat: LineChat): String = PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(chat.id.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    fun chat(pageId: String, state: LineSnapshot): LineChat? = state.chats.firstOrNull { pageId(it) == pageId }

    fun render(pageId: String, revision: Long, state: LineSnapshot, interactive: Boolean,
        thumbnail: (LineChat, LineMessage) -> LauncherArtwork? = { _, _ -> null }): NexusPage.Content {
        if (pageId == ROOT) return NexusPage.Content(revision, "LINE", PageBody.SelectableList(
            state.chats.take(PageTemplateContract.MAX_ITEMS).map { chat ->
                PageRow(pageId(chat).removePrefix(PREFIX), clip(chat.title, PageTemplateContract.MAX_LABEL_CHARS),
                    sub = if (chat.group) "Group" else null, target = PageTarget.Page(pageId(chat)))
            }, emptyText = status(state.state),
        ), live = true)
        val chat = chat(pageId, state)
            ?: return NexusPage.Content(revision, "LINE", PageBody.Summary(text = status(state.state)), live = true)
        if (!interactive) return NexusPage.Content(revision, "LINE", PageBody.Summary(text = "Update both Nexus hubs to read and reply"))
        val ready = state.state == "ready"
        return NexusPage.Content(revision, clip(chat.title, PageSurfaceContract.MAX_TITLE_CHARS),
            PageBody.Conversation(
                threadKey = pageId.removePrefix(PREFIX),
                messages = history(chat, thumbnail),
                caption = if (ready) "Letter Sealing required to send" else status(state.state),
                composer = ConversationComposer(
                    recipient = clip(chat.title, PageTemplateContract.MAX_RECIPIENT_CHARS),
                    sendActionId = SEND,
                    maxChars = minOf(2000, PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS),
                    replyable = ready,
                    unavailableText = "LINE disconnected".takeUnless { ready },
                    unavailableSub = "Reopen LINE Messages or check sign-in on the phone".takeUnless { ready },
                ),
            ), listOf(NexusPageActionSpec(SEND, "Send", typed = NexusActionType.ComposeSend)), live = true)
    }

    private fun history(chat: LineChat, thumbnail: (LineChat, LineMessage) -> LauncherArtwork?): List<ConversationMessage> {
        var remaining = 48_000
        var imageCount = 0
        var imageBytes = 0
        return chat.history.filter { it.id.isNotBlank() }.distinctBy { it.id }
            .takeLast(PageTemplateContract.MAX_MESSAGES).asReversed().mapNotNull {
                var image = thumbnail(chat, it)?.takeIf { artwork ->
                    artwork.width <= PageTemplateContract.MAX_MESSAGE_IMAGE_EDGE &&
                        artwork.height <= PageTemplateContract.MAX_MESSAGE_IMAGE_EDGE &&
                        artwork.bytes().size in 1..PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES &&
                        imageCount < PageTemplateContract.MAX_MESSAGE_IMAGES &&
                        imageBytes + artwork.bytes().size <= PageTemplateContract.MAX_MESSAGE_IMAGES_TOTAL_BYTES
                }
                var text = clip(it.text, PageTemplateContract.MAX_MESSAGE_TEXT_CHARS)
                if (image != null && text in setOf("[Photo]", "[Sticker]")) text = ""
                // Reserve space for the envelope, composer and escaped message ids.
                fun textCost(value: String) = value.toByteArray(Charsets.UTF_8).size + value.sumOf { char ->
                    when { char < ' ' -> 5; char == '"' || char == '\\' -> 1; else -> 0 }
                } + 800
                if (textCost(text) + (image?.data?.length ?: 0) > remaining) {
                    image = null
                    text = clip(it.text, PageTemplateContract.MAX_MESSAGE_TEXT_CHARS)
                }
                val finalCost = textCost(text) + (image?.data?.length ?: 0)
                if (finalCost > remaining) null else {
                    remaining -= finalCost
                    if (image != null) { imageCount++; imageBytes += image.bytes().size }
                    ConversationMessage(it.id.take(96), if (it.mine) ConversationRole.OURS else ConversationRole.RECEIVED, text, image = image)
                }
            }.asReversed()
    }

    fun status(state: String): String = when (state) {
        "signed_out" -> "Link your LINE account in Nexus on the phone"
        "loading", "standby", "connecting" -> "Connecting to LINE…"
        "ready" -> "No recent conversations"
        "offline" -> "LINE disconnected · reopen this page to reconnect"
        else -> "LINE unavailable · check sign-in on the phone"
    }

    private fun clip(text: String, limit: Int) = text.take(limit)
}
