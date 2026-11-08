package com.anezium.rokidbus.plugin.meta

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.anezium.rokidbus.shared.ConversationMessageStatus
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The glasses side of Meta Messages as launcher pages: services, conversations, and one
 * conversation page with the hub composer. The services connect when the first page opens
 * and release when the last one closes; nothing is sent until the composer's own Send, which
 * the hub offers only after the dictated or typed text is shown with its recipient.
 *
 * A photo stays on the conversation page (launcher bundle v6): its Photo composer tool is a
 * plugin action that starts the glasses camera and answers with a live "Taking photo…" page,
 * the capture is published as an image page with Send, Retake and Cancel, and only a tap on
 * Send dispatches it, once, after which the conversation comes back with the photo's thumbnail.
 * With composer tools the newest messages carrying an image preview show a thumbnail, fetched
 * off the main thread and kept for the session.
 */
class MetaMessagesPluginService : NexusPluginService() {
    /** One service's connection and conversation list, started when the pages open. */
    private class Load(val startedAt: Long)

    /** One compose send in flight: the engine's answer, and what the hub was told meanwhile. */
    private class Dispatch {
        val latch = CountDownLatch(1)
        @Volatile var started = false
        @Volatile var cancelled = false
        @Volatile var outcome: SendOutcome? = null
        var reported: SendOutcome? = null
    }

    private companion object {
        /** Under the glasses' eight-second invocation deadline, leaving room for the bus. */
        const val SEND_WAIT_MS = 6_000L
        /** How long a send told "unconfirmed" is still watched for the engine's late answer. */
        const val LATE_OUTCOME_MS = 60_000L
        /** A photo upload may outlast the action budget; its late answer is watched this long. */
        const val LATE_PHOTO_OUTCOME_MS = 120_000L
        const val MAX_REMEMBERED = 64
        const val TAG = "MetaMessages"
    }

    private val main = Handler(Looper.getMainLooper())
    private val state = MetaPagesState()
    private val current = LinkedHashMap<String, NexusPageRequest>()
    private val visible = HashSet<String>()
    /** Pages whose content changed while the hub had not yet shown them; published once shown. */
    private val stale = StalePages()
    private val loads = HashMap<MessagingService, Load>()
    private val answered = LinkedHashMap<String, NexusPageResult>()
    private var whatsAppOwner: Any? = null
    private var generation = 0
    /** Whether the services are held for the pages; [begin] sets it, [end] clears it. */
    private var active = false

    /**
     * The photo flow of one conversation page: the request it publishes against, its pure
     * state, and the camera's full JPEG, kept only between capture and Send, Retake or Cancel.
     * All of it is dropped by [discardPhoto].
     */
    private class PhotoSession(val pageId: String, val request: NexusPageRequest, val chat: MetaChat, val flow: PhotoFlow) {
        var snapshot: NexusSnapshotSession? = null
        var bytes: ByteArray? = null
        var preview: LauncherArtwork? = null
    }

    private var photo: PhotoSession? = null
    private val photoWorker by lazy { Executors.newSingleThreadExecutor() }
    /** Conversations whose older messages were already asked for in this run. */
    private val olderRequested = HashSet<String>()
    /** Fetched message previews, by message id; each finished fetch publishes the visible pages again. */
    private val thumbnails = ThumbnailLoader(
        onReady = { main.post { if (active) publishVisible() } },
        // WhatsApp thumbnails come from its engine's history, through the session the pages hold.
        readStored = { preview, done ->
            main.post {
                val owner = whatsAppOwner
                if (owner == null) done(null) else WhatsMeowSession.call(this, owner, { it.thumbnail(preview) }) { done(it.getOrNull()) }
            }
        },
    )
    /** Stops hearing the phone screens' sign-ins; set on create. */
    private var stopPresence: (() -> Unit)? = null
    private val pending by lazy { getSharedPreferences("pending-sends", MODE_PRIVATE) }
    private val whatsAppSends by lazy { PendingWhatsAppSends(
        load = { pending.getStringSet("ids", emptySet()).orEmpty().toSet() },
        save = { check(pending.edit().putStringSet("ids", it).commit()) },
    ) }

    // ---- pages -------------------------------------------------------------------------

    override fun onNexusPageRequest(request: NexusPageRequest): NexusPage {
        if (!active) begin()
        current[request.pageId] = request
        // A refresh takes the frame over (a cover, a reconnect): whatever photo it held is gone.
        photo?.takeIf { it.pageId == request.pageId }?.let(::discardPhoto)
        if (request.pageId.startsWith(MetaPages.CHAT_PREFIX)) {
            state.chatFor(request.pageId)?.let { (service, chat) ->
                loadOlderIfShort(service, chat)
                requestThumbnails(chat)
            }
        }
        // A service the wearer comes back to after a failure is tried again right away.
        MetaPages.serviceOfPage(request.pageId)?.let { if (state.state(it) == ServiceState.Failed) connect(it) }
        stale.answered(request.pageId)
        return MetaPages.page(request.pageId, state.nextRevision(request.minimumRevision), state, support())
    }

    override fun onNexusPageAction(action: NexusPageAction): NexusPageResult {
        answered[action.invocationId]?.let { return it }
        val result = when (action.actionId) {
            MetaPages.RETRY_ACTION_ID -> retry(action)
            MetaPages.TAKE_PHOTO_ACTION_ID, MetaPages.RETAKE_ACTION_ID -> startPhoto(action)
            MetaPages.SEND_PHOTO_ACTION_ID -> sendPhoto(action)
            MetaPages.CANCEL_PHOTO_ACTION_ID -> cancelPhoto(action)
            else -> send(action)
        }
        answered[action.invocationId] = result
        while (answered.size > MAX_REMEMBERED) answered.remove(answered.keys.first())
        return result
    }

    override fun onNexusPageVisibility(visibility: NexusPageVisibility) {
        if (visibility.pageId !in current) return
        if (visibility.visible) visible += visibility.pageId else visible -= visibility.pageId
        // A covered page cannot be published to; its photo, taken or not, is dropped rather than kept for later.
        if (!visibility.visible) photo?.takeIf { it.pageId == visibility.pageId }?.let(::discardPhoto)
        // A service that became ready between the answer and this moment was published to nobody.
        if (visibility.visible && stale.shown(visibility.pageId)) publish(visibility.pageId)
    }

    override fun onNexusPageClosed(closed: NexusPageClosed) {
        current.remove(closed.pageId)
        visible -= closed.pageId
        stale.forget(closed.pageId)
        photo?.takeIf { it.pageId == closed.pageId }?.let(::discardPhoto)
        if (current.isEmpty()) end()
    }

    override fun onNexusRegistrationState(result: Int) {
        if (result != PluginRegistrationResult.APPROVED) {
            end()
            return
        }
        nexusClient?.let { client -> RetiredActivity.settle(RetiredActivity.preferences(this)) { client.endActivity() } }
    }

    override fun onCreate() {
        super.onCreate()
        stopPresence = MetaPresence.observe { serviceId, presence -> main.post { onPresence(serviceId, presence) } }
    }

    /**
     * The owner signed in, paired or forgot a service on the phone while the pages are open:
     * the pages follow at once instead of waiting for the glasses to leave and come back.
     */
    private fun onPresence(serviceId: String, presence: MetaPresence.State) {
        if (!active) return
        val service = MessagingService.byId(serviceId) ?: return
        when (presence) {
            is MetaPresence.State.Present -> if (state.state(service) == ServiceState.SignedOut) {
                connect(service)
                publishVisible()
            }
            MetaPresence.State.Absent -> if (state.state(service) != ServiceState.SignedOut) {
                loads.remove(service)
                state.set(service, ServiceState.SignedOut)
                publishVisible()
            }
            MetaPresence.State.Unknown -> Unit
        }
    }

    override fun onNexusLinkState(linkState: Int) {
        if (linkState and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) == 0) end()
    }

    private fun support(): PageSupport = PageSupport(
        interactive = nexusClient?.supportsLauncherInteractive() == true,
        composerTools = nexusClient?.supportsComposerTools() == true,
        imagePages = nexusClient?.supportsImagePages() == true,
        thumbnail = thumbnails::get,
        rowIcon = if (nexusClient?.supportsRowIcons() == true) { service -> ServiceMarks.mark(this, service) } else { _ -> null },
    )

    /** Fetches the previews a conversation page would show; a no-op on hubs that draw no thumbnails. */
    private fun requestThumbnails(chat: MetaChat) {
        if (nexusClient?.supportsComposerTools() == true) thumbnails.request(chat.history)
    }

    /**
     * Sends the visible pages again at a newer revision; only what is visible can be published. A
     * current page the hub has not shown yet is remembered and published once it is.
     */
    private fun publishVisible() {
        for (pageId in current.keys.toList()) {
            if (pageId in visible) publish(pageId) else stale.skipped(pageId)
        }
    }

    private fun publish(pageId: String) {
        val client = nexusClient ?: return
        val request = current[pageId] ?: return
        // A page in its photo flow shows the photo, not the conversation, until that flow ends.
        if (photo?.pageId == pageId) return
        if (pageId.startsWith(MetaPages.CHAT_PREFIX)) state.chatFor(pageId)?.let { (_, chat) -> requestThumbnails(chat) }
        client.publishPage(request, MetaPages.page(pageId, state.nextRevision(null), state, support()))
    }

    private fun retry(action: NexusPageAction): NexusPageResult {
        val service = MetaPages.serviceOfPage(action.pageId) ?: return NexusPageResult.rejected()
        if (state.state(service) != ServiceState.Failed) return NexusPageResult.stale()
        connect(service)
        return NexusPageResult.done(replacement = MetaPages.service(state.nextRevision(null), service, state.state(service)))
    }

    // ---- connections -------------------------------------------------------------------

    private fun begin() {
        active = true
        generation++
        state.clear()
        // Connecting takes a few seconds; doing it while the wearer browses hides that wait.
        MessagingService.entries.filter(::hasSession).forEach(::connect)
    }

    private fun end() {
        active = false
        photo?.let(::discardPhoto)
        generation++
        whatsAppOwner?.let(WhatsMeowSession::release)
        whatsAppOwner = null
        for (service in loads.keys) {
            val host = accountOf(service)?.host ?: continue
            host.glasses = false
            if (!host.visible) host.worker.execute { if (!host.visible && !host.glasses) host.engine?.disconnect() }
            host.releaseIfHidden()
        }
        loads.clear()
        olderRequested.clear()
        thumbnails.clear()
        state.clear()
        current.clear()
        visible.clear()
        stale.clear()
        answered.clear()
    }

    override fun onDestroy() {
        end()
        stopPresence?.invoke()
        stopPresence = null
        super.onDestroy()
    }

    private fun accountOf(service: MessagingService): MetaAccount? = when (service) {
        MessagingService.WHATSAPP -> null
        MessagingService.INSTAGRAM -> MetaAccount.INSTAGRAM
        MessagingService.MESSENGER -> MetaAccount.MESSENGER
    }

    private fun hasSession(service: MessagingService): Boolean {
        val account = accountOf(service) ?: return File(noBackupFilesDir, "whatsmeow/session.sqlite").isFile
        return File(File(noBackupFilesDir, account.directory), account.sessionFile).isFile
    }

    private fun connect(service: MessagingService) {
        if (state.state(service) == ServiceState.Connecting) return
        val load = Load(SystemClock.elapsedRealtime())
        loads[service] = load
        state.set(service, ServiceState.Connecting)
        val token = generation
        val deliver: (Result<List<MetaChat>>) -> Unit = { result ->
            if (token == generation && loads[service] === load) {
                // Durations only: never names, ids or contents.
                android.util.Log.i(TAG, "${service.name} ready=${result.isSuccess} in ${SystemClock.elapsedRealtime() - load.startedAt} ms")
                state.set(service, result.fold({ ServiceState.Ready(it) }, { ServiceState.Failed }))
                publishVisible()
            }
        }
        val account = accountOf(service)
        if (account == null) loadWhatsApp(deliver) else loadAccount(account, deliver)
    }

    private fun loadWhatsApp(done: (Result<List<MetaChat>>) -> Unit) {
        val owner = whatsAppOwner ?: Any().also { whatsAppOwner = it; WhatsMeowSession.acquire(it) }
        WhatsMeowSession.call(this, owner, { engine ->
            whatsAppSends.reconcile { id -> JSONObject(engine.lookup(id)).getString("state") }
            val status = JSONObject(engine.awaitLogin(8_000))
            check(status.getBoolean("connected") && status.getBoolean("loggedIn") && status.optString("blocker").isEmpty())
            whatsAppChats(engine)
        }, done)
    }

    /** Stored conversations with their messages; the synced contacts until any message is stored. */
    private fun whatsAppChats(engine: WhatsAppEngine): List<MetaChat> {
        val chats = parseChats(JSONArray(engine.chats(MetaSettings.historyLimit(this).toLong())), "jid", "name", "isGroup")
        if (chats.isNotEmpty()) return chats
        val rows = JSONArray(engine.contacts())
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            MetaChat(row.getString("jid"), row.getString("name"), group = row.optBoolean("isGroup"), supported = true)
        }
    }

    private fun parseChats(rows: JSONArray, idKey: String, titleKey: String, groupKey: String): List<MetaChat> =
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val messages = row.optJSONArray("messages") ?: JSONArray()
            MetaChat(row.getString(idKey), row.getString(titleKey), row.optBoolean(groupKey), row.optBoolean("supported", true),
                (0 until messages.length()).map { j ->
                    val message = messages.getJSONObject(j)
                    HistoryMessage(message.getString("id"), message.optBoolean("mine"), message.optString("text"),
                        message.optString("sender").takeIf { it.isNotEmpty() },
                        preview = message.optString("preview").takeIf(MetaThumbnails::fetchable))
                })
        }

    /**
     * Opening a conversation that shows fewer messages than the owner asked for fetches older
     * ones once: Instagram and Messenger from the service, WhatsApp from the main phone. The
     * page updates when they arrive; nothing keeps polling after that.
     */
    private fun loadOlderIfShort(service: MessagingService, chat: MetaChat) {
        val limit = MetaSettings.historyLimit(this)
        if (chat.history.size >= limit || !olderRequested.add(MetaPages.chatPageId(service, chat))) return
        val token = generation
        val account = accountOf(service)
        if (account == null) {
            val owner = whatsAppOwner ?: return
            WhatsMeowSession.call(this, owner, { it.requestHistory(chat.id, minOf(50, limit).toLong()) }) {}
            // The main phone answers asynchronously; look again twice while the page is open.
            for (delay in listOf(4_000L, 10_000L)) main.postDelayed({ refreshWhatsApp(token) }, delay)
            return
        }
        val host = account.host
        host.worker.execute {
            runCatching { host.engine?.loadOlder(chat.id) }
            main.post { refreshAccount(service, account, token) }
        }
        // Messenger can deliver the older messages in a follow-up update.
        main.postDelayed({ refreshAccount(service, account, token) }, 3_000L)
    }

    private fun refreshWhatsApp(token: Int) {
        val owner = whatsAppOwner ?: return
        if (token != generation) return
        WhatsMeowSession.call(this, owner, ::whatsAppChats) { result ->
            if (token == generation) result.onSuccess { state.set(MessagingService.WHATSAPP, ServiceState.Ready(it)); publishVisible() }
        }
    }

    private fun refreshAccount(service: MessagingService, account: MetaAccount, token: Int) {
        if (token != generation) return
        val host = account.host
        host.worker.execute {
            val chats = runCatching { parseChats(JSONArray(checkNotNull(host.engine).chats()), "id", "title", "group") }
            main.post {
                if (token == generation) chats.onSuccess { state.set(service, ServiceState.Ready(it)); publishVisible() }
            }
        }
    }

    private fun loadAccount(account: MetaAccount, done: (Result<List<MetaChat>>) -> Unit) {
        val host = account.host
        host.glasses = true
        val directory = File(noBackupFilesDir, account.directory).absolutePath
        host.worker.execute {
            val result = runCatching {
                val engine = host.engine ?: checkNotNull(account.open)(directory).also { host.engine = it }
                if (!JSONObject(engine.status()).getBoolean("connected")) engine.connect(30_000)
                engine.setHistoryLimit(MetaSettings.historyLimit(this).toLong())
                parseChats(JSONArray(engine.chats()), "id", "title", "group")
            }
            main.post { done(result) }
        }
    }

    // ---- sending -----------------------------------------------------------------------

    /**
     * The composer's Send. The SDK delivered it for the revision the wearer saw, the composer's
     * own action and text within its limit; this checks the conversation is still listed and
     * its service connected, dispatches once under a fresh id, and waits a bounded time for the
     * engine so the glasses show a real outcome. A late answer still reaches the page.
     */
    private fun send(action: NexusPageAction): NexusPageResult {
        if (action.mode != NexusPageActionMode.COMPOSE || action.typed != NexusActionType.ComposeSend ||
            action.actionId != MetaPages.SEND_ACTION_ID || !action.pageId.startsWith(MetaPages.CHAT_PREFIX)
        ) {
            return NexusPageResult.rejected()
        }
        val text = action.text?.trim().orEmpty()
        if (text.isEmpty()) return NexusPageResult.rejected("Empty message")
        val (service, chat) = state.chatFor(action.pageId) ?: return authorityLost()
        if (!chat.supported) return authorityLost()
        val id = UUID.randomUUID().toString()
        val dispatch = Dispatch()
        val account = accountOf(service)
        if (account == null) dispatchWhatsApp(dispatch, chat, id, text) else dispatchAccount(dispatch, account, chat, id, text)
        dispatch.latch.await(SEND_WAIT_MS, TimeUnit.MILLISECONDS)
        val outcome = dispatch.outcome ?: if (dispatch.started) {
            SendOutcome.Unconfirmed
        } else {
            dispatch.cancelled = true
            SendOutcome.NotSent("busy")
        }
        dispatch.reported = outcome
        val message = sendOutcomeText(outcome, service)
        return when (outcome) {
            SendOutcome.HandedOff -> {
                state.recordSent(action.pageId, SentMessage(id, text, ConversationMessageStatus.HANDED_OFF))
                NexusPageResult.done(message, replacement = MetaPages.chat(action.pageId, state.nextRevision(null), state, support()))
            }
            SendOutcome.Unconfirmed -> {
                // The hub keeps the draft as unconfirmed; a late admission is shown on the page.
                lateOutcome(dispatch, action.pageId, id, text, SystemClock.elapsedRealtime() + LATE_OUTCOME_MS)
                NexusPageResult.rejected(message)
            }
            is SendOutcome.NotSent -> {
                if (outcome.reason == "disconnected") {
                    state.set(service, ServiceState.Failed)
                    publishVisible()
                }
                NexusPageResult.rejected(message)
            }
            SendOutcome.RecipientChanged -> {
                connect(service)
                publishVisible()
                NexusPageResult.stale(message)
            }
            SendOutcome.AuthorityLost -> authorityLost()
        }
    }

    private fun authorityLost() = NexusPageResult.rejected(code = NexusPageResult.CODE_AUTHORITY_LOST)

    /**
     * The engine answered after the glasses were told "unconfirmed": an admission is shown as
     * handed off. A photo was listed as unconfirmed meanwhile, so a refusal takes it off the page.
     */
    private fun lateOutcome(dispatch: Dispatch, pageId: String, id: String, text: String, until: Long, image: LauncherArtwork? = null) {
        main.postDelayed({
            if (dispatch.reported != SendOutcome.Unconfirmed || SystemClock.elapsedRealtime() > until) return@postDelayed
            if (dispatch.outcome == null) return@postDelayed lateOutcome(dispatch, pageId, id, text, until, image)
            if (pageId !in current) return@postDelayed
            if (dispatch.outcome == SendOutcome.HandedOff) {
                state.recordSent(pageId, SentMessage(id, text, ConversationMessageStatus.HANDED_OFF, image = image))
                publishVisible()
            } else if (image != null) {
                state.forgetSent(pageId, id)
                publishVisible()
            }
        }, 1_000)
    }

    private fun dispatchWhatsApp(dispatch: Dispatch, chat: MetaChat, id: String, text: String) {
        val owner = whatsAppOwner
        if (owner == null) {
            dispatch.outcome = SendOutcome.NotSent("disconnected")
            dispatch.latch.countDown()
            return
        }
        callWhatsApp(owner, dispatch) { engine ->
            whatsAppSends.send(id,
                allowed = { !dispatch.cancelled },
                lookup = { JSONObject(engine.lookup(it)).getString("state") },
                submit = { JSONObject(engine.send(id, chat.id, chat.title, text)).getString("state") },
            )
        }
    }

    /** Runs [work] on the WhatsApp worker and settles [dispatch] from there, not from the main thread this call blocks. */
    private fun callWhatsApp(owner: Any, dispatch: Dispatch, work: (WhatsAppEngine) -> String) {
        WhatsMeowSession.call(this, owner, { engine ->
            if (!dispatch.cancelled) {
                dispatch.started = true
                dispatch.outcome = sendOutcomeOf(work(engine), null)
                dispatch.latch.countDown()
            }
        }) { result ->
            if (dispatch.outcome == null) {
                dispatch.outcome = if (result.isFailure && !dispatch.started) SendOutcome.NotSent("busy") else SendOutcome.Unconfirmed
                dispatch.latch.countDown()
            }
        }
    }

    private fun dispatchAccount(dispatch: Dispatch, account: MetaAccount, chat: MetaChat, id: String, text: String) {
        val host = account.host
        host.worker.execute {
            if (dispatch.cancelled) return@execute
            dispatch.started = true
            val engine = host.engine
            dispatch.outcome = if (engine == null) {
                SendOutcome.NotSent("disconnected")
            } else {
                runCatching { JSONObject(engine.sendText(id, chat.id, chat.title, text)) }
                    .map { sendOutcomeOf(it.optString("state"), it.optString("reason")) }
                    .getOrElse { SendOutcome.Unconfirmed }
            }
            dispatch.latch.countDown()
        }
    }

    // ---- the photo flow on a conversation page (launcher bundle v6) ----

    /**
     * Photo from the composer bar, or Retake: the camera is asked for a frame and the page
     * answers at once with "Taking photo…", well inside the action budget; the photo itself is
     * published later on the page's lease. A camera that cannot be asked says why on the page.
     */
    private fun startPhoto(action: NexusPageAction): NexusPageResult {
        if (!action.pageId.startsWith(MetaPages.CHAT_PREFIX)) return NexusPageResult.rejected()
        val request = current[action.pageId] ?: return NexusPageResult.rejected()
        if (nexusClient?.supportsImagePages() != true) return NexusPageResult.rejected(MetaPages.PHOTO_NEEDS_UPDATE)
        val (service, chat) = state.chatFor(action.pageId) ?: return authorityLost()
        if (!MetaPages.photoOffered(service, chat) || state.state(service) !is ServiceState.Ready) return authorityLost()
        val session = photo?.takeIf { it.pageId == action.pageId && it.chat.id == chat.id }
            ?: PhotoSession(action.pageId, request, chat, PhotoFlow(chat.title, service))
        if (session.flow.action(action.actionId) != PhotoCommand.CAPTURE) return NexusPageResult.stale()
        photo?.takeIf { it !== session }?.let(::discardPhoto)
        photo = session
        session.bytes = null
        session.preview = null
        capture(session)
        return NexusPageResult.done(replacement = session.flow.page(state.nextRevision(null), null))
    }

    private fun capture(session: PhotoSession) {
        session.snapshot?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        var created: NexusSnapshotSession? = null
        val snapshot = nexusSnapshotSession(object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) {
                main.post {
                    if (photo !== session || session.snapshot !== created) return@post
                    session.snapshot = null
                    android.util.Log.i(TAG, "photo captured in ${SystemClock.elapsedRealtime() - startedAt} ms")
                    onPhotoCaptured(session, jpeg)
                }
            }

            override fun onSnapshotError(error: NexusSnapshotError) {
                main.post {
                    if (photo !== session || session.snapshot !== created) return@post
                    session.snapshot = null
                    android.util.Log.i(TAG, "photo capture failed ${error.name} after ${SystemClock.elapsedRealtime() - startedAt} ms")
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
        val sent = nexusClient?.publishPage(session.request, session.flow.page(state.nextRevision(null), session.preview)) == NexusSdkResult.SENT
        if (!sent) discardPhoto(session)
    }

    /**
     * Send on the image page: one dispatch under a fresh id, the bytes leave this service with
     * it, and the conversation comes back with the photo's thumbnail. The action waits a bounded
     * time for the engine, as the text Send does; an upload still running past it is listed as
     * unconfirmed and settled by its late answer.
     */
    private fun sendPhoto(action: NexusPageAction): NexusPageResult {
        val session = photo?.takeIf { it.pageId == action.pageId } ?: return NexusPageResult.stale(PhotoFlow.PHOTO_GONE)
        val bytes = session.bytes ?: return NexusPageResult.stale(PhotoFlow.PHOTO_GONE)
        if (session.flow.action(action.actionId) != PhotoCommand.SEND) return NexusPageResult.rejected()
        val service = session.flow.service
        val chat = session.chat
        if (state.chatFor(action.pageId)?.second?.id != chat.id || !chat.supported) {
            discardPhoto(session)
            return authorityLost()
        }
        val thumbnail = if (nexusClient?.supportsComposerTools() == true) runCatching { MessageThumbnailEncoder.encode(bytes) }.getOrNull() else null
        val id = UUID.randomUUID().toString()
        val dispatch = Dispatch()
        val startedAt = SystemClock.elapsedRealtime()
        val account = accountOf(service)
        if (account == null) dispatchWhatsAppPhoto(dispatch, chat, id, bytes) else dispatchAccountPhoto(dispatch, account, chat, id, bytes)
        dispatch.latch.await(SEND_WAIT_MS, TimeUnit.MILLISECONDS)
        val outcome = dispatch.outcome ?: if (dispatch.started) {
            SendOutcome.Unconfirmed
        } else {
            dispatch.cancelled = true
            SendOutcome.NotSent("busy")
        }
        dispatch.reported = outcome
        android.util.Log.i(TAG, "photo send ${outcome::class.simpleName} in ${SystemClock.elapsedRealtime() - startedAt} ms")
        val message = sendOutcomeText(outcome, service)
        return when (outcome) {
            SendOutcome.HandedOff -> {
                discardPhoto(session)
                state.recordSent(action.pageId, SentMessage(id, PhotoFlow.SENT_TEXT, ConversationMessageStatus.HANDED_OFF, image = thumbnail))
                NexusPageResult.done(message, replacement = MetaPages.chat(action.pageId, state.nextRevision(null), state, support()))
            }
            SendOutcome.Unconfirmed -> {
                // The engine has the bytes; there is no draft to keep, so the page lists the photo as unconfirmed.
                discardPhoto(session)
                state.recordSent(action.pageId, SentMessage(id, PhotoFlow.SENT_TEXT, ConversationMessageStatus.UNCONFIRMED, image = thumbnail))
                lateOutcome(dispatch, action.pageId, id, PhotoFlow.SENT_TEXT, SystemClock.elapsedRealtime() + LATE_PHOTO_OUTCOME_MS, thumbnail)
                NexusPageResult.done(message, replacement = MetaPages.chat(action.pageId, state.nextRevision(null), state, support()))
            }
            is SendOutcome.NotSent -> {
                if (outcome.reason == "disconnected") {
                    state.set(service, ServiceState.Failed)
                    discardPhoto(session)
                    publishVisible()
                } else {
                    // Busy, a refused upload: the photo stays on its page for another Send, a Retake or Cancel.
                    session.flow.keepForRetry()
                }
                NexusPageResult.rejected(message)
            }
            SendOutcome.RecipientChanged -> {
                discardPhoto(session)
                connect(service)
                publishVisible()
                NexusPageResult.stale(message)
            }
            SendOutcome.AuthorityLost -> {
                discardPhoto(session)
                authorityLost()
            }
        }
    }

    /** Cancel drops the photo and puts the conversation back; without a photo it is the same conversation. */
    private fun cancelPhoto(action: NexusPageAction): NexusPageResult {
        if (!action.pageId.startsWith(MetaPages.CHAT_PREFIX)) return NexusPageResult.rejected()
        val session = photo?.takeIf { it.pageId == action.pageId }
        if (session != null) {
            if (session.flow.action(action.actionId) != PhotoCommand.CLOSE) return NexusPageResult.rejected()
            discardPhoto(session)
        }
        return NexusPageResult.done(replacement = MetaPages.chat(action.pageId, state.nextRevision(null), state, support()))
    }

    private fun dispatchWhatsAppPhoto(dispatch: Dispatch, chat: MetaChat, id: String, bytes: ByteArray) {
        val owner = whatsAppOwner
        if (owner == null) {
            dispatch.outcome = SendOutcome.NotSent("disconnected")
            dispatch.latch.countDown()
            return
        }
        var reason: String? = null
        WhatsMeowSession.call(this, owner, { engine ->
            if (!dispatch.cancelled) {
                dispatch.started = true
                val state = whatsAppSends.send(id,
                    allowed = { !dispatch.cancelled },
                    lookup = { JSONObject(engine.lookup(it)).getString("state") },
                    submit = {
                        val answer = JSONObject(engine.sendImage(id, chat.id, chat.title, bytes))
                        reason = answer.optString("reason").takeIf { it.isNotEmpty() }
                        answer.getString("state")
                    },
                )
                dispatch.outcome = sendOutcomeOf(state, reason)
                dispatch.latch.countDown()
            }
        }) { result ->
            if (dispatch.outcome == null) {
                dispatch.outcome = if (result.isFailure && !dispatch.started) SendOutcome.NotSent("busy") else SendOutcome.Unconfirmed
                dispatch.latch.countDown()
            }
        }
    }

    private fun dispatchAccountPhoto(dispatch: Dispatch, account: MetaAccount, chat: MetaChat, id: String, bytes: ByteArray) {
        val host = account.host
        host.worker.execute {
            if (dispatch.cancelled) return@execute
            dispatch.started = true
            val engine = host.engine
            dispatch.outcome = if (engine == null) {
                SendOutcome.NotSent("disconnected")
            } else {
                runCatching { JSONObject(engine.sendImage(id, chat.id, chat.title, bytes)) }
                    .map { sendOutcomeOf(it.optString("state"), it.optString("reason")) }
                    .getOrElse { SendOutcome.Unconfirmed }
            }
            dispatch.latch.countDown()
        }
    }

    /** Drops the photo, its pending capture and its page state; nothing further is published or sent from it. */
    private fun discardPhoto(session: PhotoSession) {
        session.snapshot?.cancel()
        session.snapshot = null
        session.bytes = null
        session.preview = null
        if (photo === session) photo = null
    }

    // ---- no immersion: the manifest declares LAUNCHABLE=false, so the hub never opens one ----

    override fun onNexusOpen() = Unit

    override fun onNexusClose() = Unit

    override fun onNexusInput(event: NexusInputEvent) = Unit
}
