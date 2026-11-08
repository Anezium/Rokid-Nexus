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
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.util.concurrent.CountDownLatch
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

    override fun onNexusPageRequest(request: NexusPageRequest): NexusPage {
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
        if (LineSession.snapshot.state == "offline" && !connectRequested) connect(lease)
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
        else visible -= visibility.pageId
    }

    override fun onNexusPageClosed(closed: NexusPageClosed) {
        requests.remove(closed.pageId)
        visible -= closed.pageId
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
        return LinePages.render(pageId, revision, LineSession.snapshot, nexusClient?.supportsLauncherInteractive() == true)
    }

    private fun publish() {
        visible.toList().forEach { page -> requests[page]?.let { nexusClient?.publishPage(it, render(page)) } }
    }

    override fun onNexusPageAction(action: NexusPageAction): NexusPageResult {
        answers[action.invocationId]?.let { return it }
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
            "not_sent" -> NexusPageResult.rejected("Not sent · Letter Sealing or the connection is unavailable")
            else -> NexusPageResult.rejected("Unconfirmed · check LINE before sending again")
        }
        answers[action.invocationId] = result
        while (answers.size > 64) answers.remove(answers.keys.first())
        return result
    }

    private fun authorityLost() = NexusPageResult.rejected(code = NexusPageResult.CODE_AUTHORITY_LOST)

    private fun end() {
        owner?.let(LineSession::release)
        owner = null
        requests.clear()
        visible.clear()
        loaded.clear()
        answers.clear()
        connectRequested = false
    }

    override fun onDestroy() { end(); main.removeCallbacksAndMessages(null); super.onDestroy() }
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
