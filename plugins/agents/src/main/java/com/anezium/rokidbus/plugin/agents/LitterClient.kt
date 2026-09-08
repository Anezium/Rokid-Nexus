package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** One configured server; no requests or decisions are replayed after a disconnect. */
internal class LitterClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val store: AgentSessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val reconnectDelayMs: Long = 1_000L,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var endpoint: LitterEndpoint? = null
    private var connection: LitterRpcConnection? = null
    private var loop: Job? = null
    private var epoch = 0L
    private val turns = mutableMapOf<String, String>()
    private val approvals = LitterApprovals(elapsed)
    private val turnRevisions = mutableMapOf<String, Long>()
    private var conversationRevision = 0L
    private val resuming = mutableMapOf<String, Long>()
    private val deferredApprovals = mutableListOf<Pair<Long, JSONObject>>()
    private val itemDetails = linkedMapOf<Triple<String, String, String>, String>()
    private val timeline = linkedMapOf<String, AgentMessage>()
    private val liveItems = mutableSetOf<String>()
    private val fragmentItems = mutableSetOf<String>()
    private val completedItems = mutableSetOf<String>()
    private var selectedThread: String? = null
    private val _message = MutableStateFlow("Add a Litter-compatible Codex server.")
    val message = _message.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private var cursor: String? = null
    private var loadedPages = 1
    private var listing = false
    private var submitting = false

    fun start(config: LitterEndpoint) {
        stop()
        if (endpoint?.url != config.url || endpoint?.cwd != config.cwd) loadedPages = 1
        endpoint = config
        loop = scope.launch {
            var attempt = 0
            while (isActive) {
                val generation = ++epoch
                val reconnectThread = selectedThread
                store.setConnection(AgentProvider.CODEX, ConnectionState.CONNECTING)
                _message.value = "Connecting to ${config.name}…"
                val rpc = LitterRpcConnection(http, scope) { frame ->
                    if (generation == epoch) receive(frame, generation)
                }
                connection = rpc
                var authFailed = false
                var maintenance: Job? = null
                try {
                    rpc.connect(config)
                    _message.value = "Initializing the app-server connection…"
                    rpc.request("initialize", JSONObject().put("clientInfo", JSONObject()
                        .put("name", "nexus_agents").put("title", "Nexus Agents").put("version", "1.0.0")))
                    check(rpc.send(JSONObject().put("method", "initialized").put("params", JSONObject())))
                    store.setConnection(AgentProvider.CODEX, ConnectionState.CONNECTED)
                    _message.value = "Loading sessions…"
                    attempt = 0
                    refresh(preserveWindow = true)
                    _message.value = "Connected · monitoring while Agents is open"
                    reconnectThread?.takeIf { it == selectedThread }?.let { id -> session(id)?.let { openSession(it) } }
                    maintenance = scope.launch {
                        var ticks = 0
                        while (isActive) {
                            delay(1_000)
                            approvals.expired().forEach { pending ->
                                rpc.send(JSONObject().put("id", pending.wireId).put("result", JSONObject().put("decision", "decline")))
                                clearApprovals(listOf(pending))
                            }
                            if (++ticks % 20 == 0) runOperation { refresh(preserveWindow = true) }
                        }
                    }
                    throw rpc.awaitClosed()
                } catch (failure: LitterFailure) {
                    authFailed = failure.authFailed
                    _message.value = failure.visibleMessage
                } catch (_: TimeoutCancellationException) {
                    _message.value = "The server did not answer in time. Reconnecting…"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _message.value = "Cannot connect to this app-server. Check its URL and TLS certificate."
                } finally {
                    maintenance?.cancel()
                    rpc.close()
                    if (connection === rpc) connection = null
                    if (generation == epoch) {
                        invalidateLiveState()
                        store.setConnection(AgentProvider.CODEX,
                            if (authFailed) ConnectionState.AUTH_FAILED else ConnectionState.DISCONNECTED)
                    }
                }
                if (authFailed) break
                delay((reconnectDelayMs * (1L shl attempt.coerceAtMost(5))).coerceAtMost(30_000L))
                attempt++
            }
        }
    }

    fun stop() {
        ++epoch
        loop?.cancel()
        loop = null
        connection?.close()
        connection = null
        invalidateLiveState()
        store.setConnection(AgentProvider.CODEX, ConnectionState.DISCONNECTED)
        _message.value = "Disconnected · open Agents to reconnect"
    }

    private fun invalidateLiveState() {
        turns.clear()
        turnRevisions.clear()
        resuming.clear()
        deferredApprovals.clear()
        approvals.clear()
        itemDetails.clear()
        liveItems.clear()
        fragmentItems.clear()
        completedItems.clear()
        store.clearApprovals(setOf(AgentProvider.CODEX))
        store.sessions.value.filter { it.provider == AgentProvider.CODEX }.forEach {
            store.upsert(it.copy(stale = true, pendingRequest = null))
        }
    }

    suspend fun refresh(more: Boolean = false, preserveWindow: Boolean = false) {
        if (listing) return
        val config = endpoint ?: return
        val rpc = connected()
        listing = true
        try {
            if (more && cursor == null) return
            val pageLimit = if (preserveWindow && !more) loadedPages else 1
            var nextCursor = if (more) cursor else null
            var fetchedPages = 0
            val incoming = mutableListOf<AgentSession>()
            do {
                val params = JSONObject().put("limit", 50).put("sortKey", "updated_at")
                    .put("sourceKinds", JSONArray(listOf("cli", "vscode", "appServer", "exec", "unknown")))
                if (config.cwd.isNotBlank()) params.put("cwd", config.cwd)
                nextCursor?.let { params.put("cursor", it) }
                val result = rpc.request("thread/list", params)
                incoming += result.optJSONArray("data").objects().take(50).mapNotNull { LitterProtocol.thread(it, config) }
                nextCursor = result.wireString("nextCursor", 8_192)
                fetchedPages++
            } while (fetchedPages < pageLimit && nextCursor != null)
            val merged = if (more) store.sessions.value.associateBy { it.id }.toMutableMap() else linkedMapOf()
            incoming.forEach { fresh ->
                val previous = session(fresh.id)
                merged[fresh.id] = fresh.copy(
                    lastAssistantText = previous?.lastAssistantText,
                    turn = previous?.turn,
                    status = if (store.approvalFor(fresh.key) != null) AgentStatus.NEEDS_YOU else fresh.status,
                )
            }
            selectedThread?.let { id -> if (id !in merged) session(id)?.let { merged[id] = it } }
            store.replaceProvider(AgentProvider.CODEX, merged.values.take(LitterProtocol.MAX_SESSIONS))
            loadedPages = if (more) (loadedPages + 1).coerceAtMost(4) else fetchedPages
            cursor = nextCursor
            _hasMore.value = cursor != null && merged.size < LitterProtocol.MAX_SESSIONS
        } finally { listing = false }
    }

    suspend fun openSession(session: AgentSession) {
        val rpc = connected()
        val revision = ++conversationRevision
        val turnRevision = turnRevisions[session.id] ?: 0L
        if (selectedThread != session.id) { timeline.clear(); fragmentItems.clear() }
        selectedThread = session.id
        liveItems.clear()
        completedItems.clear()
        store.openConversation(session)
        resuming[session.id] = revision
        try {
            val result = rpc.request("thread/resume", JSONObject().put("threadId", session.id))
            if (selectedThread != session.id || conversationRevision != revision) return
            val thread = result.optJSONObject("thread") ?: throw LitterFailure("The server did not return this session.")
            if (thread.wireId("id") != session.id) throw LitterFailure("The server returned a different session.")
            val resumed = endpoint?.let { LitterProtocol.thread(thread, it) }
                ?: throw LitterFailure("The server returned an invalid session.")
            val newer = if ((turnRevisions[session.id] ?: 0L) != turnRevision) this.session(session.id) else null
            store.upsert(resumed.copy(status = newer?.status ?: resumed.status, lastAssistantText = newer?.lastAssistantText))
            hydrate(thread, turnRevision)
        } finally {
            if (resuming[session.id] == revision) {
                resuming.remove(session.id)
                val waiting = deferredApprovals.filter { it.second.optJSONObject("params")?.wireId("threadId") == session.id }
                deferredApprovals.removeAll(waiting.toSet())
                waiting.forEach { (generation, frame) -> if (generation == epoch) receiveRequest(frame, generation) }
            }
        }
    }

    fun closeConversation() {
        ++conversationRevision
        selectedThread = null
        timeline.clear()
        liveItems.clear()
        fragmentItems.clear()
        completedItems.clear()
        store.closeConversation()
    }

    suspend fun createSession(cwd: String): String {
        if (submitting) throw LitterFailure("A prompt is already being sent.")
        if (cwd.isBlank() || cwd.length > 4_096 || cwd.any { it.code < 32 }) throw LitterFailure("Enter the server's project folder.")
        submitting = true
        try {
            val rpc = connected()
            val result = rpc.request("thread/start", JSONObject().put("cwd", cwd))
            val thread = result.optJSONObject("thread") ?: throw LitterFailure("The server did not create a session.")
            val session = LitterProtocol.thread(thread, endpoint!!) ?: throw LitterFailure("The server returned an invalid session.")
            store.upsert(session)
            selectedThread = session.id
            timeline.clear()
            liveItems.clear()
            fragmentItems.clear()
            completedItems.clear()
            store.openConversation(session)
            hydrate(thread)
            return session.id
        } finally { submitting = false }
    }

    suspend fun submit(sessionId: String, prompt: String) {
        if (submitting) throw LitterFailure("A prompt is already being sent.")
        validatePrompt(prompt)
        val session = session(sessionId) ?: throw LitterFailure("Refresh and reopen this session first.")
        if (session.stale || selectedThread != sessionId) throw LitterFailure("Reconnect and reopen this session first.")
        submitting = true
        try { startTurn(connected(), sessionId, prompt) } finally { submitting = false }
    }

    private suspend fun startTurn(rpc: LitterRpcConnection, id: String, prompt: String) {
        val activeTurn = turns[id]
        val params = JSONObject().put("threadId", id).put("input", LitterProtocol.input(prompt))
        if (activeTurn != null) params.put("expectedTurnId", activeTurn)
        val result = rpc.request(if (activeTurn == null) "turn/start" else "turn/steer", params)
        result.optJSONObject("turn")?.wireId("id")?.let { turns[id] = it }
        updateSession(id) { it.copy(status = AgentStatus.WORKING, stale = false, lastActivityAt = now()) }
        _message.value = "Prompt accepted by the server"
    }

    suspend fun interrupt(sessionId: String) {
        val turnId = turns[sessionId] ?: throw LitterFailure("This session has no active turn.")
        connected().request("turn/interrupt", JSONObject().put("threadId", sessionId).put("turnId", turnId))
        _message.value = "Stop requested"
    }

    fun decide(requestId: String, sessionId: String, allow: Boolean): Boolean {
        val rpc = connection ?: return false
        val approval = approvals.answer(requestId, sessionId, epoch, turns[sessionId], allow) ?: return false
        val sent = rpc.send(JSONObject().put("id", approval.wireId)
            .put("result", JSONObject().put("decision", if (allow) "accept" else "decline")))
        clearApprovals(listOf(approval))
        _message.value = if (sent) "Decision sent for this request only" else "Decision was not sent. Review it on the computer."
        return sent
    }

    fun canAllow(id: String): Boolean = approvals.get(id)?.canAllow == true

    suspend fun runOperation(action: suspend () -> Unit) {
        try { action() }
        catch (_: TimeoutCancellationException) { _message.value = "The server did not answer. Check the session before resending; the request may have arrived." }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LitterFailure) { _message.value = failure.visibleMessage }
        catch (_: Exception) { _message.value = "The request could not be completed. Check the server connection." }
    }

    private fun validatePrompt(text: String) {
        if (text.isBlank() || text.length > LitterProtocol.MAX_TEXT) throw LitterFailure("Enter a prompt up to 16,000 characters.")
    }

    private fun connected(): LitterRpcConnection {
        if (store.connections.value[AgentProvider.CODEX]?.state != ConnectionState.CONNECTED) throw LitterFailure("Connect to the server first.")
        return connection ?: throw LitterFailure("Connect to the server first.")
    }

    private fun session(id: String): AgentSession? = store.sessions.value.firstOrNull { it.provider == AgentProvider.CODEX && it.id == id }
    private fun updateSession(id: String, update: (AgentSession) -> AgentSession) { session(id)?.let { store.upsert(update(it)) } }

    private fun hydrate(thread: JSONObject, turnRevision: Long = 0L) {
        val id = thread.wireId("id") ?: return
        val previousLive = timeline.toMap()
        timeline.clear()
        thread.optJSONArray("turns").objects().takeLast(20).forEach { turn ->
            val turnId = turn.wireId("id") ?: return@forEach
            if (turn.optString("status") == "inProgress" && (turnRevisions[id] ?: 0L) == turnRevision) turns[id] = turnId
            turn.optJSONArray("items").objects().takeLast(AgentConversation.MAX_MESSAGES).forEach { item ->
                val itemId = item.wireId("id") ?: return@forEach
                LitterProtocol.item(item)?.let { snapshot ->
                    val live = previousLive[itemId]?.takeIf { itemId in liveItems }
                    timeline[itemId] = when {
                        live == null -> snapshot
                        itemId in fragmentItems -> snapshot.copy(text = mergeLitterFragment(snapshot.text, live.text))
                        itemId !in completedItems && snapshot.text.startsWith(live.text) -> snapshot
                        else -> live
                    }
                    fragmentItems.remove(itemId)
                }
                rememberItem(id, turnId, item)
            }
        }
        previousLive.filterKeys { it in liveItems && it !in timeline }.forEach { (key, value) -> timeline[key] = value }
        publishTimeline(id)
    }

    private fun receive(frame: JSONObject, generation: Long) {
        val method = frame.optString("method")
        val params = frame.optJSONObject("params") ?: JSONObject()
        if (frame.has("id")) {
            receiveRequest(frame, generation)
            return
        }
        val id = params.wireId("threadId")
        if (method == "thread/started") {
            params.optJSONObject("thread")?.let { thread -> endpoint?.let { LitterProtocol.thread(thread, it) } }
                ?.let(store::upsert)
            return
        }
        if (id == null) return
        when (method) {
            "serverRequest/resolved" -> removeDeferred(id) {
                rpcIdentity(it.opt("id")) == rpcIdentity(params.opt("requestId"))
            }
            "item/completed" -> removeDeferred(id) {
                val request = it.optJSONObject("params")
                request?.wireId("turnId") == params.wireId("turnId") &&
                    request?.wireId("itemId") == params.optJSONObject("item")?.wireId("id")
            }
            "turn/completed" -> removeDeferred(id) {
                it.optJSONObject("params")?.wireId("turnId") == params.optJSONObject("turn")?.wireId("id")
            }
            "thread/archived", "thread/closed" -> removeDeferred(id) { true }
        }
        if (session(id) == null && id !in resuming) return
        when (method) {
            "thread/status/changed" -> updateSession(id) { it.copy(status = LitterProtocol.status(params.optJSONObject("status")), stale = false) }
            "thread/archived", "thread/closed" -> {
                turns.remove(id)?.let { clearApprovals(approvals.finishTurn(id, it)) }
                if (method == "thread/archived") store.remove(AgentProvider.CODEX, id)
            }
            "turn/started" -> params.optJSONObject("turn")?.wireId("id")?.let { turnId ->
                turnRevisions[id] = (turnRevisions[id] ?: 0L) + 1L
                turns.put(id, turnId)?.takeIf { it != turnId }?.let { clearApprovals(approvals.finishTurn(id, it)) }
                updateSession(id) { it.copy(status = AgentStatus.WORKING, stale = false, lastActivityAt = now(), pendingRequest = null) }
            }
            "turn/completed" -> params.optJSONObject("turn")?.let { turn ->
                val turnId = turn.wireId("id") ?: return
                turnRevisions[id] = (turnRevisions[id] ?: 0L) + 1L
                clearApprovals(approvals.finishTurn(id, turnId))
                if (turns[id] == turnId) {
                    turns.remove(id)
                    updateSession(id) { it.copy(status = if (turn.optString("status") == "failed") AgentStatus.ERROR else AgentStatus.DONE,
                        pendingRequest = null, turn = null, lastActivityAt = now(),
                        statusDetail = if (turn.optString("status") == "failed") "The turn failed. Check the server's sign-in, usage and logs." else null) }
                }
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let { item ->
                val turnId = params.wireId("turnId") ?: return
                if (turns[id] != turnId) return
                rememberItem(id, turnId, item)
                val itemId = item.wireId("id") ?: return
                if (method == "item/completed") clearApprovals(approvals.finishItem(id, turnId, itemId))
                LitterProtocol.item(item)?.let { message ->
                    if (id == selectedThread) {
                        timeline[itemId] = message
                        liveItems += itemId
                        fragmentItems.remove(itemId)
                        if (method == "item/completed") completedItems += itemId
                        publishTimeline(id)
                    }
                    if (message.role == MessageRole.ASSISTANT) updateSession(id) { it.copy(lastAssistantText = message.text.takeLast(1_000), lastActivityAt = now()) }
                    if (message.role == MessageRole.TOOL) updateSession(id) { it.copy(turn = AgentTurn(message.tool, now()), lastActivityAt = now()) }
                }
            }
            "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/plan/delta" -> {
                if (turns[id] != params.wireId("turnId")) return
                val itemId = params.wireId("itemId") ?: return
                val delta = (params.opt("delta") as? String)?.take(LitterProtocol.MAX_TEXT) ?: return
                if (id == selectedThread) {
                    if (itemId !in timeline) fragmentItems += itemId
                    val existing = timeline[itemId] ?: AgentMessage(if (method.contains("commandExecution")) MessageRole.TOOL else MessageRole.ASSISTANT, "")
                    timeline[itemId] = existing.copy(text = (existing.text + delta).takeLast(LitterProtocol.MAX_TEXT))
                    liveItems += itemId
                    publishTimeline(id)
                }
                if (method == "item/agentMessage/delta") updateSession(id) { it.copy(lastAssistantText = (it.lastAssistantText.orEmpty() + delta).takeLast(1_000), lastActivityAt = now()) }
            }
            "serverRequest/resolved" -> clearApprovals(approvals.resolve(params.opt("requestId"), id))
        }
    }

    private fun receiveRequest(frame: JSONObject, generation: Long) {
        val rpc = connection ?: return
        val params = frame.optJSONObject("params") ?: JSONObject()
        val threadId = params.wireId("threadId")
        val method = frame.optString("method")
        // Some app-servers replay a pending request before the resume response.
        // Hold it until that response proves the active turn, with a strict bound.
        if (method in LitterApprovals.METHODS && threadId != null && threadId in resuming &&
            turns[threadId] == null && deferredApprovals.size < AgentApproval.MAX_PENDING &&
            frame.toString().length <= 32_000
        ) {
            deferredApprovals += generation to frame
            return
        }
        if (method in LitterApprovals.METHODS && threadId != null && session(threadId) != null) {
            val key = Triple(threadId, params.wireId("turnId").orEmpty(), params.wireId("itemId").orEmpty())
            val approval = approvals.offer(frame, generation, turns[threadId], itemDetails[key], createdAt = now())
            if (approval != null) {
                store.upsertApproval(approval.display)
                updateSession(threadId) { it.copy(status = AgentStatus.NEEDS_YOU, pendingRequest = AgentPendingRequest(PendingRequestKind.PERMISSION, approval.display.summary, now())) }
                return
            }
        }
        if (rpcIdentity(frame.opt("id")) == null) return
        clearApprovals(approvals.invalidateWire(frame.opt("id")))
        val response = JSONObject().put("id", frame.opt("id"))
        when (method) {
            in LitterApprovals.METHODS -> response.put("result", JSONObject().put("decision", "decline"))
            "item/permissions/requestApproval" -> response.put("result", JSONObject().put("permissions", JSONObject()).put("scope", "turn"))
            else -> response.put("error", JSONObject().put("code", -32601).put("message", "This request requires a supported client on the computer."))
        }
        rpc.send(response)
        _message.value = "An unsupported or stale server request was declined. Review it on the computer."
    }

    private fun rememberItem(thread: String, turn: String, item: JSONObject) {
        val id = item.wireId("id") ?: return
        if (item.optString("type") == "fileChange") {
            val changes = item.optJSONArray("changes") ?: return
            if (changes.objects().size != changes.length() || changes.objects().any {
                    it.opt("diff") !is String || it.wireString("path", 4_096) == null
                }) return
            val detail = changes.objects().joinToString("\n\n") {
                listOfNotNull(it.wireString("path", 4_096), it.optJSONObject("kind")?.optString("type"), it.opt("diff") as? String).joinToString("\n")
            }
            itemDetails[Triple(thread, turn, id)] = if (detail.length <= LitterProtocol.MAX_TEXT) detail else ""
            while (itemDetails.size > 64) itemDetails.remove(itemDetails.keys.first())
        }
    }

    private fun clearApprovals(removed: List<LitterApproval>) {
        removed.forEach { store.resolveApproval(it.display.requestId) }
        removed.map { it.display.sessionId }.distinct().forEach { id ->
            if (store.approvalFor("codex:$id") == null) updateSession(id) {
                it.copy(pendingRequest = null, status = if (id in turns) AgentStatus.WORKING else it.status)
            }
        }
    }

    private fun removeDeferred(threadId: String, matches: (JSONObject) -> Boolean) {
        deferredApprovals.removeAll { (_, frame) ->
            frame.optJSONObject("params")?.wireId("threadId") == threadId && matches(frame)
        }
    }

    private fun publishTimeline(thread: String) {
        while (timeline.size > AgentConversation.MAX_MESSAGES) {
            val key = timeline.keys.first()
            timeline.remove(key); liveItems.remove(key); fragmentItems.remove(key); completedItems.remove(key)
        }
        store.setConversation(AgentProvider.CODEX, thread, timeline.values.toList())
    }
}
