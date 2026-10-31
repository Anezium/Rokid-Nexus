package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageActionTypeContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NexusPageRequestReason(val wireValue: String) {
    OPEN("open"),
    REFRESH("refresh"),
    RETRY("retry"),
}

/**
 * The glasses ask this provider for one of its pages. Answer by returning a [NexusPage] from
 * the callback; that return is the response. [minimumRevision] is the revision the glasses
 * already retain for this frame, when they retain one: answer with that revision or a higher
 * one, never lower, even after a cold start.
 */
class NexusPageRequest internal constructor(
    val requestId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val pageId: String,
    val reason: NexusPageRequestReason,
    private val paramsJson: String?,
    val minimumRevision: Long?,
    internal val registrationId: String,
) {
    /** A copy of the `params` the selected row carried, or null. */
    val params: JSONObject?
        get() = paramsJson?.let(::JSONObject)

    internal val correlation: PageCorrelation
        get() = PageCorrelation(requestId, sessionGeneration, frameIndex, pageId)
}

enum class NexusPageActionKind(val wireValue: String) {
    /** Hub navigation the hub itself understands; a provider cannot direct the hub with it. */
    HUB("hub"),
    PLUGIN("plugin"),

    /** Opens this provider's own full app, behind the hub's labelled launch step. */
    IMMERSION("immersion"),
}

/**
 * An action a page offers. The hub confirms every plugin action with the wearer first, except a
 * [typed] action of a family the user approved for this plugin on the phone (checklist or media),
 * which runs on one tap. [typed] needs [NexusPluginClient.supportsLauncherInteractive]; without
 * it the hub drops it. A `ComposeSend` action is reached only through a conversation's composer.
 */
data class NexusPageActionSpec(
    val id: String,
    val label: String,
    val kind: NexusPageActionKind = NexusPageActionKind.PLUGIN,
    val confirm: Boolean = true,
    val typed: NexusActionType? = null,
)

/** A page answer: drawn content, or an honest refusal the glasses show as unavailable. */
sealed class NexusPage {
    /**
     * [body] is one of the four base templates, or, once [NexusPluginClient.supportsLauncherInteractive]
     * is true, a `Conversation`, `Media` or `Route` body; otherwise those answer as invalid, so fall
     * back to the base four. [live] promises revisions while the page is visible under its lease,
     * published through [NexusPluginClient.publishPage].
     */
    data class Content(
        val revision: Long,
        val title: String,
        val body: PageBody,
        val actions: List<NexusPageActionSpec> = emptyList(),
        val live: Boolean = false,
    ) : NexusPage()

    data object Unavailable : NexusPage()
}

/** How the wearer reached a page action. */
enum class NexusPageActionMode(val wireValue: String) {
    /** Through the hub's confirmation, or as a hub-confirmed page action. */
    CONFIRMED(PageSurfaceContract.MODE_CONFIRMED),

    /** On one tap: a typed action of a family the user approved for this plugin. */
    QUICK(PageSurfaceContract.MODE_QUICK),

    /** The hub composer's Send on a conversation page, with [NexusPageAction.text]. */
    COMPOSE(PageSurfaceContract.MODE_COMPOSE),
}

/**
 * The wearer invoked one of a page's actions on the revision they were shown. [typed] is the type
 * this plugin declared on the action, as it was sent; [text] is the composer's text, present
 * exactly when [mode] is [NexusPageActionMode.COMPOSE].
 */
class NexusPageAction internal constructor(
    val invocationId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val pageId: String,
    val revision: Long,
    val actionId: String,
    internal val registrationId: String,
    val mode: NexusPageActionMode = NexusPageActionMode.CONFIRMED,
    val text: String? = null,
    val typed: NexusActionType? = null,
)

enum class NexusPageResultStatus(val wireValue: String) {
    DONE("done"),
    REJECTED("rejected"),
    STALE("stale"),
}

/**
 * The outcome of an action; a [replacement] must be the same page at a higher revision. [code] is
 * a short machine-readable refinement (printable ASCII, at most 32); the hub acts on
 * [CODE_AUTHORITY_LOST] only.
 */
class NexusPageResult private constructor(
    val status: NexusPageResultStatus,
    val message: String?,
    val replacement: NexusPage.Content?,
    val code: String? = null,
) {
    init {
        require(code == null || PageSurfaceContract.isValidResultCode(code)) { "code must be 1..32 printable ASCII characters" }
    }

    companion object {
        /** A conversation's reply authority is gone: the composer keeps the text and stops offering Send. */
        const val CODE_AUTHORITY_LOST = PageSurfaceContract.CODE_AUTHORITY_LOST

        fun done(message: String? = null, replacement: NexusPage.Content? = null, code: String? = null) =
            NexusPageResult(NexusPageResultStatus.DONE, message, replacement, code)

        fun rejected(message: String? = null, code: String? = null) =
            NexusPageResult(NexusPageResultStatus.REJECTED, message, null, code)

        fun stale(message: String? = null, code: String? = null) =
            NexusPageResult(NexusPageResultStatus.STALE, message, null, code)
    }
}

/**
 * A page was covered, uncovered or had its lease renewed. [leaseDurationMs] is how long the
 * visible lease still runs, counted from when this callback arrives on this device's own
 * clock; it is null while the page is covered.
 */
class NexusPageVisibility internal constructor(
    val pageId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val visible: Boolean,
    val leaseDurationMs: Long?,
)

/** A page left the glasses; `back`, `session_closed`, `timeout`, `link_lost`, `replaced` or `frame_limit`. */
class NexusPageClosed internal constructor(
    val pageId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val reason: String,
)

/**
 * The provider side of the page protocol for one client: it validates what the hub asks,
 * dispatches it to the callbacks, answers with correlated replies, and remembers just enough of
 * each frame to judge an action or a live revision. Bounded to the six frames of the one glasses
 * session.
 *
 * Callbacks into the plugin run outside the lock. Everything that sends runs under it, together
 * with the state it depends on, so a publication can never interleave with a cover, a close or a
 * loss of support, and a revision once used is never sent again or undercut.
 */
internal class NexusPageChannel(
    private val pluginId: String,
    private val clock: () -> Long,
    private val demandChanged: () -> Unit,
    private val send: (path: String, id: String, payload: JSONObject) -> Boolean,
) {
    private data class FrameKey(val generation: Long, val frameIndex: Int)

    private class FrameContext(
        val pageId: String,
        var requestId: String,
        /** The current request has not been answered yet; nothing may be published for it. */
        var awaitingAnswer: Boolean = true,
        /** The current request was answered with valid live content, so revisions may follow. */
        var publishable: Boolean = false,
        /**
         * The current request's valid answer reached the hub, so the actions it advertised may be
         * invoked. Separate from the revision history, which survives a refused refresh.
         */
        var actionable: Boolean = false,
        /** Highest revision ever sent or reserved for this frame; never goes down. */
        var highestRevision: Long? = null,
        /** The revision the glasses were last sent, and the actions it advertised with their types. */
        var shownRevision: Long? = null,
        var shownActions: Map<String, PageActionType?> = emptyMap(),
        /** The composer of that revision, when it was a conversation page that offered one. */
        var shownComposer: ConversationComposer? = null,
        var visible: Boolean = false,
        var leaseExpiresAt: Long? = null,
        var foregroundUntil: Long? = null,
    )

    private val lock = Any()
    private val frames = linkedMapOf<FrameKey, FrameContext>()
    private val seenRequests = ArrayDeque<String>()
    private val seenInvocations = ArrayDeque<String>()
    private var activeDeliveries = 0

    internal val foregroundLeaseRemainingMs: Long?
        get() = synchronized(lock) {
            frames.values.mapNotNull { it.foregroundUntil?.minus(clock())?.takeIf { remaining -> remaining > 0 } }.minOrNull()
        }

    internal val hasForegroundDemand: Boolean
        get() = synchronized(lock) { activeDeliveries > 0 || foregroundLeaseRemainingMs != null }

    private inline fun withForegroundDelivery(block: () -> Unit) {
        synchronized(lock) { activeDeliveries++ }
        demandChanged()
        try {
            block()
        } finally {
            synchronized(lock) { activeDeliveries-- }
            demandChanged()
        }
    }

    /** The registration epoch the hub stamped on this client's page work; anchored until it re-registers. */
    private var epoch: String? = null

    @Volatile var sessionVersion = 0
        private set

    /** The launcher bundle version both hubs run for this plugin, from the same message; 0 is none. */
    @Volatile var launcherInteractiveVersion = 0
        private set

    /** The hub's effective negotiated page version for this plugin, from `/system/hub/capabilities`. */
    fun onCapabilities(payload: JSONObject) {
        val version = PageSurfaceContract.parseSessionVersion(
            payload.opt(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION),
        )
        val launcher = LauncherInteractiveContract.negotiated(
            LauncherInteractiveContract.VERSION,
            LauncherInteractiveContract.parseVersion(payload.opt(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION)),
        )
        synchronized(lock) {
            sessionVersion = if (version == PageSurfaceContract.VERSION) version else 0
            launcherInteractiveVersion = if (sessionVersion == 0) 0 else launcher
            if (sessionVersion == 0) frames.clear()
        }
        demandChanged()
    }

    /** A new registration, a revoked one, or a closed client: every frame, publisher and the epoch are void. */
    fun onRegistrationReset() {
        synchronized(lock) {
            sessionVersion = 0
            launcherInteractiveVersion = 0
            epoch = null
            frames.clear()
            seenRequests.clear()
            seenInvocations.clear()
        }
        demandChanged()
    }

    /**
     * No usable transport to the glasses: every lease there is void, and support only returns
     * with the hub's next owner-scoped capability update. The registration and its epoch remain.
     */
    fun onLinkUnusable() {
        synchronized(lock) {
            sessionVersion = 0
            launcherInteractiveVersion = 0
            frames.clear()
        }
        demandChanged()
    }

    fun onRequest(payload: JSONObject, callbacks: NexusPluginCallbacks) {
        val parsed = (PageSurfaceContract.validateRequest(payload) as? PageSurfaceValidationResult.Valid)?.value ?: return
        val registrationId = registrationOf(payload) ?: return
        val pageId = parsed.correlation.pageId
        if (!PageTemplateContract.isProviderPageId(pageId, pluginId)) return
        val request = NexusPageRequest(
            requestId = parsed.correlation.requestId,
            sessionGeneration = parsed.correlation.sessionGeneration,
            frameIndex = parsed.correlation.frameIndex,
            pageId = pageId,
            reason = NexusPageRequestReason.entries.first { it.wireValue == parsed.reason },
            paramsJson = parsed.paramsJson,
            minimumRevision = nonnegativeLong(payload, PageSurfaceContract.FIELD_MINIMUM_REVISION),
            registrationId = registrationId,
        )
        synchronized(lock) {
            // Another registration's work never replaces this one's.
            if (!anchor(registrationId)) return
            if (!remember(seenRequests, request.requestId)) return
            frames.keys.filter { it.generation != request.sessionGeneration }.forEach(frames::remove)
            val key = FrameKey(request.sessionGeneration, request.frameIndex)
            val existing = frames[key]?.takeIf { it.pageId == pageId }
            if (existing != null) {
                // A refresh keeps the frame's lease but loses its publishing and action rights until answered.
                existing.requestId = request.requestId
                existing.awaitingAnswer = true
                existing.publishable = false
                existing.actionable = false
            } else {
                frames.remove(key)
                frames[key] = FrameContext(pageId, request.requestId)
                while (frames.size > PageSurfaceContract.MAX_FRAMES) frames.remove(frames.keys.first())
            }
            frames.getValue(key).foregroundUntil = existing?.leaseExpiresAt?.takeIf { it > clock() }
                ?: (clock() + PageSurfaceContract.PAGE_TIMEOUT_MS)
        }
        withForegroundDelivery {
            val page = runCatching { callbacks.onPageRequest(request) }.getOrElse { NexusPage.Unavailable }
            respond(request, page)
        }
    }

    fun onAction(payload: JSONObject, callbacks: NexusPluginCallbacks) {
        val parsed = (PageSurfaceContract.validateAction(payload) as? PageSurfaceValidationResult.Valid)?.value ?: return
        val registrationId = registrationOf(payload) ?: return
        val key = FrameKey(parsed.sessionGeneration, parsed.frameIndex)
        val mode = NexusPageActionMode.entries.firstOrNull { it.wireValue == parsed.mode } ?: return
        val (requestId, typed) = synchronized(lock) {
            // Only an action on an accepted page of this registration, at the revision the
            // glasses were sent and naming an action that revision advertised, reaches the plugin.
            if (registrationId != epoch) return
            val context = frames[key]?.takeIf { it.pageId == parsed.pageId } ?: return
            if (!context.actionable || context.awaitingAnswer) return
            // A covered page, or one whose lease ran out here, is no longer what the wearer sees.
            val expires = context.leaseExpiresAt
            if (!context.visible || expires == null || clock() >= expires) return
            if (context.shownRevision != parsed.revision || parsed.actionId !in context.shownActions) return
            // The way it was reached must fit this plugin's own declaration: quick only for a
            // quick-eligible type, a send only from the composer of the page shown, within its limit.
            val declared = context.shownActions[parsed.actionId]
            if (!PageSurfaceContract.modeAllowedFor(parsed.mode, declared)) return
            if (mode == NexusPageActionMode.QUICK && launcherInteractiveVersion == 0) return
            if (mode == NexusPageActionMode.COMPOSE) {
                val composer = context.shownComposer ?: return
                if (composer.sendActionId != parsed.actionId || (parsed.text?.length ?: 0) > composer.maxChars) return
            }
            if (!remember(seenInvocations, parsed.invocationId)) return
            context.requestId to declared
        }
        val action = NexusPageAction(
            parsed.invocationId, parsed.sessionGeneration, parsed.frameIndex, parsed.pageId,
            parsed.revision, parsed.actionId, registrationId,
            mode = mode,
            text = parsed.text,
            typed = typed?.toNexus(),
        )
        withForegroundDelivery {
            val result = runCatching { callbacks.onPageAction(action) }.getOrElse { NexusPageResult.rejected() }
            val correlation = PageCorrelation(requestId, parsed.sessionGeneration, parsed.frameIndex, parsed.pageId)
            synchronized(lock) {
                val context = frames[key]?.takeIf { it.pageId == parsed.pageId }
                // A replacement must be newer than what the wearer acted on and than anything sent since.
                val replacement = result.replacement
                val replacementAllowed = replacement == null || (
                    context != null && replacement.revision > parsed.revision &&
                        replacement.revision > (context.highestRevision ?: Long.MIN_VALUE)
                    )
                val outgoing = resultPayload(action.invocationId, result, correlation)
                    ?.takeIf { replacementAllowed && PageSurfaceContract.validateBoundedResult(it) is PageSurfaceValidationResult.Valid }
                    ?: resultPayload(action.invocationId, NexusPageResult.rejected(), correlation)
                    ?: return
                val sentReplacement = outgoing.optJSONObject("replacement")
                if (replacement != null && context != null && sentReplacement != null) {
                    context.highestRevision = replacement.revision
                    if (send(BusPaths.PAGE_RESULT, UUID.randomUUID().toString(), outgoing)) {
                        context.shownRevision = replacement.revision
                        recordShown(context, sentReplacement)
                        // The replacement is now the page: whether it is live decides publishing, as for an answer.
                        context.publishable = sentReplacement.optBoolean("live")
                    }
                } else {
                    send(BusPaths.PAGE_RESULT, UUID.randomUUID().toString(), outgoing)
                }
            }
        }
    }

    fun onVisibility(payload: JSONObject, callbacks: NexusPluginCallbacks) {
        val parsed = (PageSurfaceContract.validateVisibility(payload) as? PageSurfaceValidationResult.Valid)?.value ?: return
        val registrationId = registrationOf(payload) ?: return
        val key = frameKey(payload) ?: return
        val lease = if (parsed.visible) {
            nonnegativeLong(payload, PageSurfaceContract.FIELD_LEASE_DURATION_MS)
                ?.takeIf { it in 1..PageSurfaceContract.LEASE_MS }
        } else {
            null
        }
        synchronized(lock) {
            if (registrationId != epoch) return
            val context = frames[key]?.takeIf { it.pageId == parsed.pageId } ?: return
            context.visible = parsed.visible && lease != null
            context.leaseExpiresAt = lease?.let { clock() + it }
            context.foregroundUntil = context.leaseExpiresAt
        }
        demandChanged()
        runCatching {
            callbacks.onPageVisibility(NexusPageVisibility(parsed.pageId, key.generation, key.frameIndex, parsed.visible, lease))
        }
    }

    fun onClosed(payload: JSONObject, callbacks: NexusPluginCallbacks) {
        val parsed = (PageSurfaceContract.validateClosed(payload) as? PageSurfaceValidationResult.Valid)?.value ?: return
        val registrationId = registrationOf(payload) ?: return
        val key = frameKey(payload) ?: return
        synchronized(lock) {
            if (registrationId != epoch) return
            if (frames[key]?.pageId != parsed.pageId) return
            frames.remove(key)
        }
        demandChanged()
        runCatching { callbacks.onPageClosed(NexusPageClosed(parsed.pageId, key.generation, key.frameIndex, parsed.reason)) }
    }

    /**
     * A live revision for the frame's current, answered request while it is visible under an
     * unexpired lease. Authorization, the revision it takes and the send are one step.
     */
    fun publish(request: NexusPageRequest, page: NexusPage): NexusSdkResult {
        val content = page as? NexusPage.Content ?: return NexusSdkResult.INVALID_PAYLOAD
        val key = FrameKey(request.sessionGeneration, request.frameIndex)
        synchronized(lock) {
            val context = frames[key] ?: return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            val current = request.registrationId == epoch && context.requestId == request.requestId &&
                context.pageId == request.pageId
            if (!current || context.awaitingAnswer || !context.publishable || !content.live) {
                return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            }
            val expires = context.leaseExpiresAt
            if (!context.visible || expires == null || clock() >= expires) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            if (content.revision <= (context.highestRevision ?: Long.MIN_VALUE)) return NexusSdkResult.INVALID_PAYLOAD
            // The payload is built now: what is sent is this snapshot, whatever the caller does next.
            val payload = contentPayload(request.correlation, content) ?: return NexusSdkResult.INVALID_PAYLOAD
            context.highestRevision = content.revision
            if (!send(BusPaths.PAGE_RESPONSE, UUID.randomUUID().toString(), payload)) return NexusSdkResult.NOT_REGISTERED
            context.shownRevision = content.revision
            recordShown(context, payload)
            return NexusSdkResult.SENT
        }
    }

    private fun respond(request: NexusPageRequest, page: NexusPage) {
        val floor = request.minimumRevision
        // A revision below the retained floor would be dropped by the glasses as a rollback.
        val content = (page as? NexusPage.Content)?.takeIf { floor == null || it.revision >= floor }
        val answer = content?.let { contentPayload(request.correlation, it) }
        val payload = answer ?: errorPayload(
            request.correlation,
            if (page is NexusPage.Content) PageSurfaceContract.ERROR_INVALID_PAGE else PageSurfaceContract.ERROR_PAGE_UNAVAILABLE,
        )
        synchronized(lock) {
            val context = frames[FrameKey(request.sessionGeneration, request.frameIndex)]
                ?.takeIf { it.requestId == request.requestId && request.registrationId == epoch } ?: return
            context.awaitingAnswer = false
            context.publishable = false
            // A refusal, an invalid answer or a failed send leaves nothing of the old revision actionable.
            context.actionable = false
            context.shownActions = emptyMap()
            context.shownComposer = null
            if (answer != null && content != null) {
                context.highestRevision = maxOf(context.highestRevision ?: content.revision, content.revision)
            }
            val sent = send(BusPaths.PAGE_RESPONSE, UUID.randomUUID().toString(), payload)
            if (!sent || answer == null || content == null) {
                context.foregroundUntil = null
                return
            }
            context.foregroundUntil = context.leaseExpiresAt?.takeIf { it > clock() }
                ?: (clock() + PageSurfaceContract.PAGE_TIMEOUT_MS)
            context.shownRevision = content.revision
            recordShown(context, answer)
            context.actionable = true
            context.publishable = answer.optBoolean("live")
        }
    }

    /** The full response for [content], or null when it would not pass the hub's own validation. */
    private fun contentPayload(correlation: PageCorrelation, content: NexusPage.Content): JSONObject? {
        // The launcher bundle's bodies, types and rows exist only where both hubs negotiated it.
        val extended = LauncherInteractiveContract.isActive(launcherInteractiveVersion)
        // A hub without the bundle would offer a send as an ordinary confirmed action, with no text.
        if (!extended && content.actions.any { it.typed == NexusActionType.ComposeSend }) return null
        val built = runCatching {
            PageSurfaceContract.correlationPayload(correlation)
                .put("revision", content.revision)
                .put("template", content.body.template)
                .put("title", content.title)
                .put("body", PageTemplateContract.encode(content.body).apply {
                    com.anezium.rokidbus.shared.LauncherArtwork.stripUnsupported(this, launcherInteractiveVersion)
                    if (launcherInteractiveVersion < LauncherInteractiveContract.ARTWORK_VERSION) {
                        remove("artwork")
                        remove("positionMs")
                        remove("durationMs")
                        remove("playbackSpeed")
                        remove("advancing")
                    }
                })
                .put(
                    "actions",
                    JSONArray().apply {
                        content.actions.forEach {
                            put(
                                JSONObject().put("id", it.id).put("label", it.label)
                                    .put("kind", it.kind.wireValue).put("confirm", it.confirm)
                                    .apply {
                                        it.typed?.let { type ->
                                            put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(type.toContract()))
                                        }
                                    },
                            )
                        }
                    },
                )
                .put("live", content.live)
        }.getOrNull() ?: return null
        val launcherFields = content.actions.any { it.typed != null } || content.body.items.any { it.checked != null }
        val payload = if (extended || !launcherFields) built else PageSurfaceContract.stripLauncherFields(built)
        val pending = PageSurfaceRequest(correlation, NexusPageRequestReason.OPEN.wireValue)
        val page = (PageSurfaceContract.validateResponse(payload, pending) as? PageSurfaceValidationResult.Valid)
            ?.value as? PageSurfaceResponse.Page ?: return null
        if (PageTemplateContract.decode(page, pluginId, extended) !is PageSurfaceValidationResult.Valid) return null
        return payload
    }

    /**
     * Remembers the actions of a payload as it was sent, with their types, and its composer, never
     * re-read from the caller's model.
     */
    private fun recordShown(context: FrameContext, payload: JSONObject) {
        val page = (PageSurfaceContract.validateResponse(payload) as? PageSurfaceValidationResult.Valid)?.value as? PageSurfaceResponse.Page
        context.shownActions = page?.actions?.associate { it.id to it.typed }.orEmpty()
        val extended = LauncherInteractiveContract.isActive(launcherInteractiveVersion)
        val body = page?.let { (PageTemplateContract.decode(it, pluginId, extended) as? PageSurfaceValidationResult.Valid)?.value }
        context.shownComposer = (body as? PageBody.Conversation)?.composer
    }

    private fun errorPayload(correlation: PageCorrelation, code: String): JSONObject =
        PageSurfaceContract.correlationPayload(correlation).put("error", code)

    private fun resultPayload(invocationId: String, result: NexusPageResult, correlation: PageCorrelation): JSONObject? {
        val payload = JSONObject().put("invocationId", invocationId).put("status", result.status.wireValue)
        result.message?.let { payload.put("message", it) }
        result.code?.let { payload.put("code", it) }
        result.replacement?.let { replacement ->
            payload.put("replacement", contentPayload(correlation, replacement) ?: return null)
        }
        return payload
    }

    private fun registrationOf(payload: JSONObject): String? =
        (payload.opt(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID) as? String)
            ?.takeIf(PageSurfaceContract::isValidId)

    private fun frameKey(payload: JSONObject): FrameKey? {
        val generation = nonnegativeLong(payload, "sessionGeneration") ?: return null
        val frameIndex = nonnegativeLong(payload, "frameIndex")?.takeIf { it < PageSurfaceContract.MAX_FRAMES } ?: return null
        return FrameKey(generation, frameIndex.toInt())
    }

    private fun nonnegativeLong(payload: JSONObject, key: String): Long? = when (val value = payload.opt(key)) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }

    /** The first request after a registration anchors its epoch; any other epoch is refused. */
    private fun anchor(registrationId: String): Boolean {
        val current = epoch
        if (current == null) {
            epoch = registrationId
            return true
        }
        return current == registrationId
    }

    private fun remember(seen: ArrayDeque<String>, id: String): Boolean {
        if (id in seen) return false
        seen.addLast(id)
        while (seen.size > MAX_SEEN_IDS) seen.removeFirst()
        return true
    }

    private companion object {
        const val MAX_SEEN_IDS = 64
    }
}
