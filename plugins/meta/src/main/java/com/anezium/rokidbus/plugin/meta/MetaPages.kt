package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.shared.ComposerSlot
import com.anezium.rokidbus.shared.ComposerTool
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationMessageStatus
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import java.security.MessageDigest

/** The services the glasses pages list, in the order they are shown. */
internal enum class MessagingService(val id: String, val title: String) {
    WHATSAPP("whatsapp", "WhatsApp"),
    INSTAGRAM("instagram", "Instagram"),
    MESSENGER("messenger", "Messenger");

    companion object {
        fun byId(id: String): MessagingService? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A message the engine already had for a conversation when it connected; never logged.
 * [preview] is the image URL of a photo, reel, post or story, fetched for a thumbnail.
 */
internal data class HistoryMessage(
    val id: String,
    val mine: Boolean,
    val text: String,
    val sender: String? = null,
    val preview: String? = null,
)

/** One conversation as the engine lists it; [supported] is false for an encrypted Messenger chat. */
internal data class MetaChat(
    val id: String,
    val title: String,
    val group: Boolean,
    val supported: Boolean,
    val history: List<HistoryMessage> = emptyList(),
)

/** Where one service's connection stands while the Messages pages are open. */
internal sealed interface ServiceState {
    data object SignedOut : ServiceState
    data object Connecting : ServiceState
    data class Ready(val chats: List<MetaChat>) : ServiceState
    data object Failed : ServiceState
}

/** A message the wearer sent from the glasses this session; a photo carries its own thumbnail. */
internal data class SentMessage(val id: String, val text: String, val status: ConversationMessageStatus, val image: LauncherArtwork? = null)

/**
 * What the negotiated hubs draw: [interactive] the launcher bundle's conversation body,
 * [composerTools] its v5 composer tools and message thumbnails, and [imagePages] its v6 image
 * pages, which the photo flow needs. [thumbnail] is the session's cache of fetched previews by
 * message id; a message without one shows its caption alone. [rowIcon] is a service's brand
 * mark for its row (bundle v7); null draws the plain row. [pins] are a service's pinned
 * conversation ids in the owner's order.
 */
internal data class PageSupport(
    val interactive: Boolean,
    val composerTools: Boolean = false,
    val imagePages: Boolean = false,
    val thumbnail: (String) -> LauncherArtwork? = { null },
    val rowIcon: (MessagingService) -> LauncherArtwork? = { null },
    val pins: (MessagingService) -> List<String> = { emptyList() },
)

/**
 * The pure state behind the Meta Messages pages: each service's connection and conversations,
 * the messages sent from the glasses while they were open, and one revision counter that only
 * goes up and always above the floor the glasses retain. Everything is held in memory and
 * dropped when the last page closes; nothing here touches an engine.
 */
internal class MetaPagesState {
    private val services = MessagingService.entries.associateWith<MessagingService, ServiceState> { ServiceState.SignedOut }.toMutableMap()
    private val sent = HashMap<String, MutableList<SentMessage>>()
    private var revision = 0L

    fun state(service: MessagingService): ServiceState = services.getValue(service)

    fun set(service: MessagingService, state: ServiceState) {
        services[service] = state
    }

    /** The chat a chat page id names, with its service, or null once the list no longer has it. */
    fun chatFor(pageId: String): Pair<MessagingService, MetaChat>? {
        val (service, key) = MetaPages.chatKeyOf(pageId) ?: return null
        val chats = (state(service) as? ServiceState.Ready)?.chats ?: return null
        val chat = chats.firstOrNull { MetaPages.chatKey(service, it) == key } ?: return null
        return service to chat
    }

    fun sentOn(pageId: String): List<SentMessage> = sent[pageId].orEmpty()

    fun recordSent(pageId: String, message: SentMessage) {
        val log = sent.getOrPut(pageId) { mutableListOf() }
        log.removeAll { it.id == message.id }
        log += message
        while (log.size > PageTemplateContract.MAX_MESSAGES) log.removeAt(0)
    }

    fun forgetSent(pageId: String, id: String) {
        sent[pageId]?.removeAll { it.id == id }
    }

    /** An equal revision would keep the glasses' old body; only identical content may reuse it. */
    fun nextRevision(floor: Long?): Long {
        revision = maxOf(revision + 1, (floor ?: -1) + 1)
        return revision
    }

    fun clear() {
        MessagingService.entries.forEach { services[it] = ServiceState.SignedOut }
        sent.clear()
    }
}

/**
 * The Meta Messages pages of the Nexus launcher: `meta-messages:main` lists the services with
 * their live state, a service page lists its conversations, and a conversation page carries the
 * hub composer, whose Send is the only action that sends text. Its Photo tool (bundle v6) turns
 * the same page into the photo flow of [PhotoFlow], whose Send is the only action that sends a
 * photo. Encrypted Messenger chats are listed dimmed and inert. Page ids carry a digest of the
 * conversation id, never the id or a name.
 */
internal object MetaPages {
    const val MAIN = "meta-messages:main"
    const val SERVICE_PREFIX = "meta-messages:service:"
    const val CHAT_PREFIX = "meta-messages:chat:"

    const val TITLE = "Messages"
    const val SEND_ACTION_ID = "send"
    const val RETRY_ACTION_ID = "retry"
    /** The composer bar's camera: a plugin action on the conversation page that starts the capture. */
    const val TAKE_PHOTO_ACTION_ID = "take-photo"
    /** The photo flow's own actions, on the capture's failure page and on the image page. */
    const val RETAKE_ACTION_ID = "retake"
    const val SEND_PHOTO_ACTION_ID = "send-photo"
    const val CANCEL_PHOTO_ACTION_ID = "cancel-photo"
    const val PHOTO_TOOL_ID = "photo"
    const val PHOTO_TOOL_GLYPH = "camera"

    const val PHOTO_LABEL = "Photo"
    const val PHOTO_NEEDS_UPDATE = "Photos need the Nexus launcher update on the phone and the glasses"

    const val SUB_CONNECTING = "Connecting…"
    const val SUB_SIGNED_OUT = "Not signed in · sign in on the phone"
    const val SUB_FAILED = "Can't reach · tap to try again"
    const val SUB_GROUP = "Group"
    /** A service page's section captions; the SDK drops them for hubs that cannot draw them. */
    const val PINNED = "PINNED"
    const val RECENT = "RECENT"
    const val SUB_ENCRYPTED = "Encrypted · reply from the phone"
    const val EMPTY_CONNECTING = "Connecting…"
    const val EMPTY_NO_CHATS = "No conversations yet"
    const val RETRY_LABEL = "Try again"
    const val UNAVAILABLE_ENCRYPTED = "Encrypted chat"
    const val UNAVAILABLE_DISCONNECTED = "Not connected"
    const val UNAVAILABLE_SUB = "reply from the phone"
    const val MISSING_TEXT = "This conversation is no longer listed."
    const val CLASSIC_TEXT = "Sending from the glasses needs the Nexus 2.0 launcher on the phone and the glasses."

    fun servicePageId(service: MessagingService): String = SERVICE_PREFIX + service.id

    /** A conversation's key in page ids: a digest of its id, never the id or a name. */
    fun chatKey(service: MessagingService, chat: MetaChat): String = digest(service.id + "\u0000" + chat.id).take(16)

    fun chatPageId(service: MessagingService, chat: MetaChat): String = CHAT_PREFIX + service.id + ":" + chatKey(service, chat)

    fun serviceOfPage(pageId: String): MessagingService? =
        pageId.takeIf { it.startsWith(SERVICE_PREFIX) }?.let { MessagingService.byId(it.removePrefix(SERVICE_PREFIX)) }

    fun serviceOfChatPage(pageId: String): MessagingService? = chatKeyOf(pageId)?.first

    /** The service and conversation key a chat page names. */
    fun chatKeyOf(pageId: String): Pair<MessagingService, String>? {
        if (!pageId.startsWith(CHAT_PREFIX)) return null
        val rest = pageId.removePrefix(CHAT_PREFIX)
        if (!rest.contains(':')) return null
        val service = MessagingService.byId(rest.substringBefore(':')) ?: return null
        return service to rest.substringAfter(':')
    }

    /** Whether a photo from the glasses can go to this conversation: any one it can answer. */
    fun photoOffered(service: MessagingService, chat: MetaChat): Boolean = chat.supported

    fun page(pageId: String, revision: Long, state: MetaPagesState, interactive: Boolean): NexusPage =
        page(pageId, revision, state, PageSupport(interactive))

    fun page(pageId: String, revision: Long, state: MetaPagesState, support: PageSupport): NexusPage = when {
        pageId == MAIN -> main(revision, state, support)
        pageId.startsWith(SERVICE_PREFIX) -> serviceOfPage(pageId)?.let { service(revision, it, state.state(it), support.pins(it)) } ?: NexusPage.Unavailable
        pageId.startsWith(CHAT_PREFIX) -> chat(pageId, revision, state, support)
        else -> NexusPage.Unavailable
    }

    /** The services, each with what is known right now; a signed-out service is listed but inert. */
    fun main(revision: Long, state: MetaPagesState, support: PageSupport = PageSupport(interactive = false)): NexusPage.Content {
        val rows = MessagingService.entries.map { service ->
            val current = state.state(service)
            PageRow(
                id = service.id,
                label = service.title,
                icon = support.rowIcon(service),
                sub = when (current) {
                    ServiceState.SignedOut -> SUB_SIGNED_OUT
                    ServiceState.Connecting -> SUB_CONNECTING
                    is ServiceState.Ready -> conversationCount(current.chats.size)
                    ServiceState.Failed -> SUB_FAILED
                },
                enabled = current != ServiceState.SignedOut,
                target = PageTarget.Page(servicePageId(service)).takeIf { current != ServiceState.SignedOut },
            )
        }
        return NexusPage.Content(revision, TITLE, PageBody.SelectableList(rows), live = true)
    }

    /**
     * The list in its order: the owner's [pins] the service still lists, in the owner's order,
     * then every other conversation newest first. A pin the service no longer lists is skipped,
     * and a pinned conversation never shows a second time among the recent ones.
     */
    fun ordered(chats: List<MetaChat>, pins: List<String>): Pair<List<MetaChat>, List<MetaChat>> {
        val byId = chats.associateBy { it.id }
        val pinned = pins.distinct().mapNotNull(byId::get)
        val ids = pinned.mapTo(HashSet()) { it.id }
        return pinned to chats.filter { it.id !in ids }
    }

    /**
     * One service's conversations: with pins, the pinned ones under PINNED and then the others
     * newest first under RECENT; without, the engine's list as it is. While it connects the list
     * is empty under "Connecting…"; after a failure its one row tries again.
     */
    fun service(revision: Long, service: MessagingService, state: ServiceState, pins: List<String> = emptyList()): NexusPage.Content {
        val body = when (state) {
            is ServiceState.Ready -> PageBody.SelectableList(
                ordered(state.chats, pins).let { (pinned, recent) -> (pinned + recent).map { it to pinned.size } }
                    .take(PageTemplateContract.MAX_ITEMS).mapIndexed { index, (chat, pinnedCount) ->
                    PageRow(
                        id = chatPageId(service, chat).removePrefix(CHAT_PREFIX).substringAfter(':'),
                        label = clip(chat.title, PageTemplateContract.MAX_LABEL_CHARS),
                        sub = when {
                            !chat.supported -> SUB_ENCRYPTED
                            chat.group -> SUB_GROUP
                            else -> null
                        },
                        enabled = chat.supported,
                        target = PageTarget.Page(chatPageId(service, chat)).takeIf { chat.supported },
                        section = when {
                            pinnedCount == 0 -> null
                            index == 0 -> PINNED
                            index == pinnedCount -> RECENT
                            else -> null
                        },
                    )
                },
                emptyText = EMPTY_NO_CHATS,
            )
            ServiceState.Failed -> PageBody.SelectableList(
                listOf(PageRow(RETRY_ACTION_ID, RETRY_LABEL, sub = "Can't reach ${service.title}", target = PageTarget.Action(RETRY_ACTION_ID))),
            )
            ServiceState.SignedOut -> PageBody.SelectableList(emptyList(), emptyText = SUB_SIGNED_OUT)
            ServiceState.Connecting -> PageBody.SelectableList(emptyList(), emptyText = EMPTY_CONNECTING)
        }
        val actions = if (state == ServiceState.Failed) {
            listOf(NexusPageActionSpec(RETRY_ACTION_ID, RETRY_LABEL, NexusPageActionKind.PLUGIN, confirm = false))
        } else {
            emptyList()
        }
        return NexusPage.Content(revision, service.title, body, actions, live = true)
    }

    fun chat(pageId: String, revision: Long, state: MetaPagesState, interactive: Boolean): NexusPage.Content =
        chat(pageId, revision, state, PageSupport(interactive))

    /**
     * One exact conversation with the hub composer: the history the engine carried, then what was
     * sent from the glasses this session. The composer names the recipient and its Send is the
     * page's one sending action, withheld while the service is not ready. With composer tools
     * the newest messages with a fetched preview show it as a thumbnail within the page budget;
     * with image pages the bar also carries Photo, a plugin action that starts [PhotoFlow] on
     * this very page.
     */
    fun chat(pageId: String, revision: Long, state: MetaPagesState, support: PageSupport): NexusPage.Content {
        val service = serviceOfChatPage(pageId) ?: return missing(revision, TITLE)
        if (!support.interactive) return classic(revision, service)
        val connection = state.state(service)
        val found = state.chatFor(pageId)
        if (found == null && connection is ServiceState.Ready) return missing(revision, service.title)
        val chat = found?.second
        val recipient = chat?.title ?: service.title
        val ready = connection is ServiceState.Ready
        val replyable = ready && chat != null && chat.supported
        val unavailable = when {
            replyable -> null
            chat != null && !chat.supported -> UNAVAILABLE_ENCRYPTED
            else -> UNAVAILABLE_DISCONNECTED
        }
        val photoTool = support.composerTools && support.imagePages && replyable && chat != null && photoOffered(service, chat)
        val messages = (chat?.history.orEmpty().map { old ->
            ConversationMessage(old.id, if (old.mine) ConversationRole.OURS else ConversationRole.RECEIVED,
                clip(old.text, PageTemplateContract.MAX_MESSAGE_TEXT_CHARS), sender = old.sender?.let { clip(it, PageTemplateContract.MAX_RECIPIENT_CHARS) },
                image = if (support.composerTools) support.thumbnail(old.id) else null)
        } + state.sentOn(pageId).map { sent ->
            ConversationMessage(sent.id, ConversationRole.OURS, clip(sent.text, PageTemplateContract.MAX_MESSAGE_TEXT_CHARS), status = sent.status,
                image = if (support.composerTools) sent.image else null)
        }).takeLast(PageTemplateContract.MAX_MESSAGES)
        return NexusPage.Content(
            revision = revision,
            title = clip(recipient, PageSurfaceContract.MAX_TITLE_CHARS),
            body = PageBody.Conversation(
                threadKey = pageId.removePrefix(CHAT_PREFIX),
                messages = withinImageBudget(messages),
                // WhatsApp keeps no history on this device; the others show what their inbox carried.
                caption = if (chat?.history.isNullOrEmpty()) clip("${service.title} · earlier messages aren't shown", PageTemplateContract.MAX_CAPTION_CHARS) else null,
                composer = ConversationComposer(
                    recipient = clip(recipient, PageTemplateContract.MAX_RECIPIENT_CHARS),
                    sendActionId = SEND_ACTION_ID,
                    maxChars = PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS,
                    replyable = replyable,
                    unavailableText = unavailable,
                    unavailableSub = UNAVAILABLE_SUB.takeUnless { replyable },
                    tools = if (photoTool) listOf(ComposerTool(PHOTO_TOOL_ID, PHOTO_LABEL, TAKE_PHOTO_ACTION_ID, glyph = PHOTO_TOOL_GLYPH)) else emptyList(),
                    // Dictate and Type make the draft, the camera sits after them, Send closes the bar as in every messenger.
                    layout = if (photoTool) listOf(ComposerSlot.Dictate, ComposerSlot.Type, ComposerSlot.Tool(PHOTO_TOOL_ID), ComposerSlot.Send) else null,
                ),
            ),
            actions = listOfNotNull(
                NexusPageActionSpec(SEND_ACTION_ID, "Send", typed = NexusActionType.ComposeSend),
                NexusPageActionSpec(TAKE_PHOTO_ACTION_ID, PHOTO_LABEL, NexusPageActionKind.PLUGIN, confirm = false).takeIf { photoTool },
            ),
            live = true,
        )
    }

    /**
     * The newest thumbnails within the page's count and byte budget; the others keep their caption
     * alone. Under a drawn thumbnail the "[Photo]" placeholder goes: it would only repeat the
     * picture. A real caption, or the placeholder of a picture that was dropped, stays.
     */
    fun withinImageBudget(messages: List<ConversationMessage>): List<ConversationMessage> {
        val kept = MetaThumbnails.keep(messages.map { message -> message.image?.let(MetaThumbnails::decodedSize) })
        return messages.mapIndexed { index, message ->
            when {
                message.image == null -> message
                index in kept -> message.copy(text = withoutPhotoPlaceholder(message.text))
                else -> message.copy(image = null)
            }
        }
    }

    /** "[Photo]" alone becomes nothing; "[Photo] caption" keeps the caption. Other placeholders are untouched. */
    fun withoutPhotoPlaceholder(text: String): String = PHOTO_PLACEHOLDER.replace(text, "")

    private val PHOTO_PLACEHOLDER = Regex("""^\s*\[(Photo|Image)\]\s*""")

    private fun missing(revision: Long, title: String) = NexusPage.Content(
        revision, title, PageBody.Document(listOf(PageSegment("missing", PageSegmentKind.ASIDE, MISSING_TEXT))), live = true,
    )

    private fun classic(revision: Long, service: MessagingService) = NexusPage.Content(
        revision, service.title, PageBody.Document(listOf(PageSegment("classic", PageSegmentKind.ASIDE, CLASSIC_TEXT))),
    )

    fun conversationCount(count: Int): String = when (count) {
        0 -> EMPTY_NO_CHATS
        1 -> "1 conversation"
        else -> "$count conversations"
    }

    private fun clip(value: String, limit: Int): String {
        if (value.length <= limit) return value
        var end = limit - 1
        if (Character.isHighSurrogate(value[end - 1])) end -= 1
        return value.substring(0, end).trimEnd() + "…"
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** What a compose Send on a Meta conversation page does, once the SDK has delivered it. */
internal sealed interface SendOutcome {
    /** The engine admitted the text; the page shows it as handed off. */
    data object HandedOff : SendOutcome

    /** The engine refused with a reason the wearer can act on. */
    data class NotSent(val reason: String) : SendOutcome

    /** The conversation itself changed: the wearer must pick it again. */
    data object RecipientChanged : SendOutcome

    /** Sending to this conversation is no longer possible from here. */
    data object AuthorityLost : SendOutcome

    /** Admitted or not, nobody knows; never retried automatically. */
    data object Unconfirmed : SendOutcome
}

/** The engines' `state`/`reason` answers as outcomes; any unknown pair is unconfirmed. */
internal fun sendOutcomeOf(state: String?, reason: String?): SendOutcome = when (state) {
    "acked" -> SendOutcome.HandedOff
    "not_sent", "not_found" -> when (reason) {
        "recipient_changed" -> SendOutcome.RecipientChanged
        "encrypted_chat_unsupported" -> SendOutcome.AuthorityLost
        "not_connected" -> SendOutcome.NotSent("disconnected")
        // The image engines' own refusals; the text path reads them as a plain "Not sent".
        "invalid_image", "upload_failed" -> SendOutcome.NotSent(reason)
        else -> SendOutcome.NotSent("")
    }
    else -> SendOutcome.Unconfirmed
}

/** The outcome in the wearer's words, as the composer's result line says it; a photo's result says the same. */
internal fun sendOutcomeText(outcome: SendOutcome, service: MessagingService): String = when (outcome) {
    SendOutcome.HandedOff -> "Sent"
    SendOutcome.Unconfirmed -> "Unconfirmed · check ${service.title}"
    is SendOutcome.NotSent -> when (outcome.reason) {
        "disconnected" -> "Not sent · ${service.title} disconnected"
        "busy" -> "Not sent · ${service.title} is busy, try again"
        "invalid_image" -> "Not sent · ${service.title} refused the photo"
        "upload_failed" -> "Not sent · upload failed, try again"
        else -> "Not sent"
    }
    SendOutcome.RecipientChanged -> "That conversation changed. Pick it again"
    SendOutcome.AuthorityLost -> "Not sent · ${MetaPages.UNAVAILABLE_SUB}"
}
