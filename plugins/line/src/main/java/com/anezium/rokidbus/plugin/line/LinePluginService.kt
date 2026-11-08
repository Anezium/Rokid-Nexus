package com.anezium.rokidbus.plugin.line

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageAction
import com.anezium.rokidbus.client.plugin.NexusPageActionMode
import com.anezium.rokidbus.client.plugin.NexusPageClosed
import com.anezium.rokidbus.client.plugin.NexusPageRequest
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusPageVisibility
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotCallbacks
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSnapshotSession
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinePluginService : NexusPluginService() {
    // Pages own this plugin's UI; it has no immersion or surface input.
    override fun onNexusOpen() = Unit
    override fun onNexusClose() { if (requests.isEmpty()) end() }
    override fun onNexusInput(event: NexusInputEvent) = Unit

    private val main = Handler(Looper.getMainLooper())
    private val requests = linkedMapOf<String, NexusPageRequest>()
    private val visible = mutableSetOf<String>()
    private val loaded = mutableSetOf<String>()
    private val answers = linkedMapOf<String, NexusPageResult>()
    private var owner: Any? = null
    private var revision = 0L
    private var connectRequested = false
    private val thumbnails = linkedMapOf<Pair<String, String>, LauncherArtwork>()
    private val mediaAsked = linkedSetOf<Pair<String, String>>()
    private var mediaRunning = false

    /**
     * The photo flow of one conversation page: the request it publishes against, its pure state
     * and the camera's full JPEG, kept only between capture and Send, Retake or Cancel.
     */
    private class PhotoSession(val pageId: String, val request: NexusPageRequest, val chat: LineChat, val flow: PhotoFlow) {
        var snapshot: NexusSnapshotSession? = null
        var bytes: ByteArray? = null
        var preview: LauncherArtwork? = null
    }

    private var photo: PhotoSession? = null
    private val photoWorker by lazy { Executors.newSingleThreadExecutor() }

    override fun onNexusPageRequest(request: NexusPageRequest): NexusPage {
        // A refresh takes the frame over (a cover, a reconnect): whatever photo it held is gone.
        photo?.takeIf { it.pageId == request.pageId }?.let(::discardPhoto)
        if (owner == null) {
            val lease = Any()
            owner = lease
            LineSession.acquire(this, lease) { changed(lease) }
            connect(lease)
        }
        requests[request.pageId] = request
        loadHistory(request.pageId)
        return render(request.pageId, request.minimumRevision)
    }

    private fun changed(lease: Any) {
        if (owner !== lease) return
        if (LineSession.snapshot.state == "signed_out") { connectRequested = false; loaded.clear() }
        if (LineSession.snapshot.state in setOf("standby", "offline") && !connectRequested) connect(lease)
        requests.keys.toList().forEach(::loadHistory)
        publish()
    }

    private fun connect(lease: Any) {
        // Signed-out pages can already be open when phone pairing finishes.
        if (LineSession.snapshot.state == "signed_out") return
        connectRequested = true
        LineSession.call(this, lease, { it.connect() }) {
            if (owner === lease) {
                if (LineSession.snapshot.state == "signed_out") connectRequested = false
                publish()
            }
        }
    }

    private fun loadHistory(pageId: String) {
        val lease = owner ?: return
        val chat = LinePages.chat(pageId, LineSession.snapshot) ?: return
        if (LineSession.snapshot.state != "ready" || !loaded.add(chat.id)) return
        LineSession.call(this, lease, { it.loadHistory(chat.id) }) { }
    }

    override fun onNexusPageVisibility(visibility: NexusPageVisibility) {
        if (visibility.pageId !in requests) return
        if (visibility.visible) { visible += visibility.pageId; publish() }
        else {
            visible -= visibility.pageId
            // A covered page cannot be published to; its photo is dropped rather than kept for later.
            photo?.takeIf { it.pageId == visibility.pageId }?.let(::discardPhoto)
        }
    }

    override fun onNexusPageClosed(closed: NexusPageClosed) {
        requests.remove(closed.pageId)
        visible -= closed.pageId
        photo?.takeIf { it.pageId == closed.pageId }?.let(::discardPhoto)
        if (requests.isEmpty()) end()
    }

    override fun onNexusRegistrationState(result: Int) {
        if (result != PluginRegistrationResult.APPROVED) end()
    }

    override fun onNexusLinkState(linkState: Int) {
        if (linkState and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) == 0) end()
    }

    private fun render(pageId: String, floor: Long? = null): NexusPage.Content {
        revision = maxOf(revision + 1, (floor ?: -1) + 1)
        // A page in its photo flow shows the photo, not the conversation, until that flow ends.
        photo?.takeIf { it.pageId == pageId }?.let { return it.flow.page(revision, it.preview) }
        val client = nexusClient
        return LinePages.render(pageId, revision, LineSession.snapshot, client?.supportsLauncherInteractive() == true,
            photoTool = client?.supportsComposerTools() == true && client.supportsImagePages()) { chat, message ->
            if (client?.supportsComposerTools() == true) thumbnails[chat.id to message.id] else null
        }
    }

    private fun publish() {
        visible.toList().forEach { page -> requests[page]?.let { nexusClient?.publishPage(it, render(page)) } }
        loadMedia()
    }

    private fun loadMedia() {
        val lease = owner ?: return
        if (mediaRunning || nexusClient?.supportsComposerTools() != true || LineSession.snapshot.state != "ready") return
        val candidate = visible.asSequence().mapNotNull { LinePages.chat(it, LineSession.snapshot) }
            .flatMap { chat -> chat.history.asReversed().filter { it.media in setOf("photo", "sticker") }
                .take(PageTemplateContract.MAX_MESSAGE_IMAGES).asSequence().map { chat to it } }
            .firstOrNull { (chat, message) -> (chat.id to message.id) !in mediaAsked } ?: return
        val (chat, message) = candidate
        val key = chat.id to message.id
        mediaAsked += key
        while (mediaAsked.size > 256) mediaAsked.remove(mediaAsked.first())
        mediaRunning = true
        // One download at a time keeps work bounded and avoids expiring queued native calls.
        LineSession.call(this, lease, { LineThumbnailEncoder.encode(it.media(chat.id, message.id)) }) { result ->
            if (owner === lease) {
                mediaRunning = false
                result.getOrNull()?.let { thumbnails[key] = it }
                while (thumbnails.size > 64) {
                    val oldest = thumbnails.keys.first()
                    thumbnails.remove(oldest)
                    mediaAsked.remove(oldest)
                }
                publish()
            }
        }
    }

    override fun onNexusPageAction(action: NexusPageAction): NexusPageResult {
        answers[action.invocationId]?.let { return it }
        when (action.actionId) {
            LinePages.TAKE_PHOTO, LinePages.RETAKE_PHOTO -> return remember(action, startPhoto(action))
            LinePages.SEND_PHOTO -> return remember(action, sendPhoto(action))
            LinePages.CANCEL_PHOTO -> return remember(action, cancelPhoto(action))
        }
        if (action.actionId != LinePages.SEND || action.mode != NexusPageActionMode.COMPOSE ||
            action.typed != NexusActionType.ComposeSend || action.pageId !in requests) return NexusPageResult.rejected()
        val chat = LinePages.chat(action.pageId, LineSession.snapshot) ?: return authorityLost()
        val lease = owner ?: return authorityLost()
        if (LineSession.snapshot.state != "ready") return authorityLost()
        val text = action.text?.trim().orEmpty()
        if (text.isBlank() || text.length > 2000) return NexusPageResult.rejected("Empty or oversized message")
        val send = LineDispatch()
        LineSession.call(this, lease, { native ->
            if (send.start()) {
                val outcome = runCatching { native.send(action.invocationId, chat.id, chat.title, text) }.getOrDefault("unconfirmed")
                send.finish(outcome)
            }
        }) { if (owner === lease) publish() }
        send.await()
        val result = when (send.result()) {
            "sent" -> NexusPageResult.done("Handed to LINE")
            "recipient_changed" -> NexusPageResult.stale("Conversation changed; reopen it before sending")
            "not_sent" -> NexusPageResult.rejected(NOT_SENT_TEXT, code = CODE_NOT_SENT)
            else -> NexusPageResult.rejected(UNCONFIRMED_TEXT)
        }
        return remember(action, result)
    }

    private fun remember(action: NexusPageAction, result: NexusPageResult): NexusPageResult {
        answers[action.invocationId] = result
        while (answers.size > 64) answers.remove(answers.keys.first())
        return result
    }

    private fun authorityLost() = NexusPageResult.rejected(code = NexusPageResult.CODE_AUTHORITY_LOST)

    // ---- the photo flow on a conversation page ----

    /**
     * Photo from the composer bar, or Retake: the camera is asked for a frame and the page
     * answers at once with "Taking photo…"; the photo itself arrives on the live lease.
     */
    private fun startPhoto(action: NexusPageAction): NexusPageResult {
        val request = requests[action.pageId] ?: return NexusPageResult.rejected()
        if (nexusClient?.supportsImagePages() != true) return NexusPageResult.rejected(LinePages.PHOTO_NEEDS_UPDATE)
        val chat = LinePages.chat(action.pageId, LineSession.snapshot) ?: return authorityLost()
        if (owner == null || LineSession.snapshot.state != "ready") return authorityLost()
        val session = photo?.takeIf { it.pageId == action.pageId && it.chat.id == chat.id }
            ?: PhotoSession(action.pageId, request, chat, PhotoFlow(chat.title))
        if (session.flow.action(action.actionId) != PhotoCommand.CAPTURE) return NexusPageResult.stale()
        photo?.takeIf { it !== session }?.let(::discardPhoto)
        photo = session
        session.bytes = null
        session.preview = null
        capture(session)
        return NexusPageResult.done(replacement = render(action.pageId))
    }

    private fun capture(session: PhotoSession) {
        session.snapshot?.cancel()
        var created: NexusSnapshotSession? = null
        val snapshot = nexusSnapshotSession(object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) {
                main.post {
                    if (photo !== session || session.snapshot !== created) return@post
                    session.snapshot = null
                    onPhotoCaptured(session, jpeg)
                }
            }

            override fun onSnapshotError(error: NexusSnapshotError) {
                main.post {
                    if (photo !== session || session.snapshot !== created) return@post
                    session.snapshot = null
                    if (session.flow.cameraFailed(cameraErrorText(error))) publishPhoto(session)
                }
            }
        })
        created = snapshot
        if (snapshot == null) {
            session.flow.cameraFailed(cameraStartErrorText(NexusSdkResult.NOT_REGISTERED))
            return
        }
        session.snapshot = snapshot
        val result = snapshot.capture()
        if (result != NexusSdkResult.SENT) {
            session.snapshot = null
            snapshot.cancel()
            session.flow.cameraFailed(cameraStartErrorText(result))
        }
    }

    /** The full JPEG is kept for the send; the glasses get a copy within the image page's bounds. */
    private fun onPhotoCaptured(session: PhotoSession, jpeg: ByteArray) {
        photoWorker.execute {
            val preview = runCatching { PhotoPreviewEncoder.encode(jpeg) }.getOrNull()
            main.post {
                if (photo !== session || session.flow.step != PhotoStep.Capturing) return@post
                if (preview == null) {
                    session.flow.cameraFailed(PhotoFlow.PREVIEW_FAILED)
                } else {
                    session.bytes = jpeg
                    session.preview = preview
                    session.flow.captured()
                }
                publishPhoto(session)
            }
        }
    }

    /** The flow's page on the conversation's live lease; a refusal means the lease is gone, and so is the photo. */
    private fun publishPhoto(session: PhotoSession) {
        if (nexusClient?.publishPage(session.request, render(session.pageId)) != NexusSdkResult.SENT) discardPhoto(session)
    }

    /**
     * Send on the image page: one dispatch, the bytes leave this service with it, and the
     * conversation comes back. The action waits the same bounded time as the text Send; an upload
     * still running past it is unconfirmed, and the engine lists the photo once it is through.
     */
    private fun sendPhoto(action: NexusPageAction): NexusPageResult {
        val session = photo?.takeIf { it.pageId == action.pageId } ?: return NexusPageResult.stale(PhotoFlow.PHOTO_GONE)
        val bytes = session.bytes ?: return NexusPageResult.stale(PhotoFlow.PHOTO_GONE)
        if (session.flow.action(action.actionId) != PhotoCommand.SEND) return NexusPageResult.rejected()
        val chat = LinePages.chat(action.pageId, LineSession.snapshot)
        val lease = owner
        if (chat == null || chat.id != session.chat.id || lease == null || LineSession.snapshot.state != "ready") {
            discardPhoto(session)
            return authorityLost()
        }
        val send = LineDispatch()
        LineSession.call(this, lease, { native ->
            if (send.start()) {
                val outcome = runCatching { native.sendImage(action.invocationId, chat.id, chat.title, bytes) }.getOrDefault("unconfirmed")
                send.finish(outcome)
            }
        }) { if (owner === lease) publish() }
        send.await()
        return when (send.result()) {
            "sent" -> {
                discardPhoto(session)
                NexusPageResult.done("Handed to LINE", replacement = render(action.pageId))
            }
            "recipient_changed" -> {
                discardPhoto(session)
                NexusPageResult.stale("Conversation changed; reopen it before sending")
            }
            // Refused before anything left: the photo stays on its page for another Send, a Retake or Cancel.
            "not_sent" -> {
                session.flow.keepForRetry()
                NexusPageResult.rejected(NOT_SENT_TEXT, code = CODE_NOT_SENT)
            }
            else -> {
                discardPhoto(session)
                publish()
                NexusPageResult.rejected(UNCONFIRMED_TEXT)
            }
        }
    }

    /** Cancel drops the photo and puts the conversation back; without a photo it is the same conversation. */
    private fun cancelPhoto(action: NexusPageAction): NexusPageResult {
        if (action.pageId !in requests) return NexusPageResult.rejected()
        photo?.takeIf { it.pageId == action.pageId }?.let { session ->
            if (session.flow.action(action.actionId) != PhotoCommand.CLOSE) return NexusPageResult.rejected()
            discardPhoto(session)
        }
        return NexusPageResult.done(replacement = render(action.pageId))
    }

    private fun discardPhoto(session: PhotoSession) {
        session.snapshot?.cancel()
        session.snapshot = null
        session.bytes = null
        session.preview = null
        if (photo === session) photo = null
    }

    private fun end() {
        photo?.let(::discardPhoto)
        owner?.let(LineSession::release)
        owner = null
        requests.clear()
        visible.clear()
        loaded.clear()
        answers.clear()
        connectRequested = false
        thumbnails.clear()
        mediaAsked.clear()
        mediaRunning = false
    }

    override fun onDestroy() { end(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    private companion object {
        const val NOT_SENT_TEXT = "Not sent · Letter Sealing or the connection is unavailable"
        const val UNCONFIRMED_TEXT = "Unconfirmed · check LINE before sending again"
        /**
         * `PageSurfaceContract.CODE_NOT_SENT`, spelled out until this branch builds against the
         * SDK that names it: a hub that knows the code keeps the draft sendable instead of
         * showing it unconfirmed; older hubs ignore it.
         */
        const val CODE_NOT_SENT = "not_sent"
    }
}

/** A queued action that misses its reply budget must never start afterwards. */
internal class LineDispatch {
    private val latch = CountDownLatch(1)
    private var started = false
    private var expired = false
    private var outcome: String? = null

    @Synchronized fun start(): Boolean {
        if (expired || started) return false
        started = true
        return true
    }

    @Synchronized fun finish(value: String) { outcome = value; latch.countDown() }
    fun await() { latch.await(6, TimeUnit.SECONDS) }
    @Synchronized fun result(): String {
        expired = true
        return outcome ?: if (started) "unconfirmed" else "not_sent"
    }
}
