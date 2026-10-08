package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
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

/** An action a page offers. The hub confirms every plugin action with the wearer first. */
data class NexusPageActionSpec(
    val id: String,
    val label: String,
    val kind: NexusPageActionKind = NexusPageActionKind.PLUGIN,
    val confirm: Boolean = true,
)

/** A page answer: drawn content, or an honest refusal the glasses show as unavailable. */
sealed class NexusPage {
    /**
     * [body] is one of the four typed templates. [live] promises revisions while the page is
     * visible under its lease, published through [NexusPluginClient.publishPage].
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

/** The wearer confirmed one of a page's actions on the revision they were shown. */
class NexusPageAction internal constructor(
    val invocationId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val pageId: String,
    val revision: Long,
    val actionId: String,
    internal val registrationId: String,
)

enum class NexusPageResultStatus(val wireValue: String) {
    DONE("done"),
    REJECTED("rejected"),
    STALE("stale"),
}

/** The outcome of an action; a [replacement] must be the same page at a higher revision. */
class NexusPageResult private constructor(
    val status: NexusPageResultStatus,
    val message: String?,
    val replacement: NexusPage.Content?,
) {
    companion object {
        fun done(message: String? = null, replacement: NexusPage.Content? = null) =
            NexusPageResult(NexusPageResultStatus.DONE, message, replacement)

        fun rejected(message: String? = null) = NexusPageResult(NexusPageResultStatus.REJECTED, message, null)

        fun stale(message: String? = null) = NexusPageResult(NexusPageResultStatus.STALE, message, null)
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
 * each frame to judge a live revision. Bounded to the six frames of the one glasses session.
 */
internal class NexusPageChannel(
    private val pluginId: String,
    private val clock: () -> Long,
    private val send: (path: String, id: String, payload: JSONObject) -> Boolean,
) {
    private data class FrameKey(val generation: Long, val frameIndex: Int)

    private class FrameContext(
        var pageId: String,
        var requestId: String,
        var registrationId: String,
        var lastRevision: Long? = null,
        var live: Boolean = false,
        var visible: Boolean = false,
        var leaseExpiresAt: Long? = null,
    )

    private val lock = Any()
    private val frames = linkedMapOf<FrameKey, FrameContext>()
    private val seenRequests = ArrayDeque<String>()
    private val seenInvocations = ArrayDeque<String>()

    @Volatile var sessionVersion = 0
        private set

    /** The hub's effective negotiated page version for this plugin, from `/system/hub/capabilities`. */
    fun onCapabilities(payload: JSONObject) {
        val version = PageSurfaceContract.parseSessionVersion(
            payload.opt(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION),
        )
        synchronized(lock) {
            sessionVersion = if (version == PageSurfaceContract.VERSION) version else 0
            if (sessionVersion == 0) frames.clear()
        }
    }

    /** Registration replaced, revoked, link or client gone: every frame and publisher is void. */
    fun reset(keepVersion: Boolean = false) = synchronized(lock) {
        if (!keepVersion) sessionVersion = 0
        frames.clear()
        seenRequests.clear()
        seenInvocations.clear()
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
            if (!remember(seenRequests, request.requestId)) return
            adoptRegistration(registrationId)
            dropOtherSessions(request.sessionGeneration)
            val key = FrameKey(request.sessionGeneration, request.frameIndex)
            val existing = frames[key]?.takeIf { it.pageId == pageId }
            if (existing != null) {
                // A refresh on uncover keeps the frame's visibility; it only changes the request.
                existing.requestId = request.requestId
            } else {
                frames.remove(key)
                frames[key] = FrameContext(pageId, request.requestId, registrationId)
                while (frames.size > PageSurfaceContract.MAX_FRAMES) frames.remove(frames.keys.first())
            }
        }
        val page = runCatching { callbacks.onPageRequest(request) }.getOrElse { NexusPage.Unavailable }
        respond(request, page)
    }

    fun onAction(payload: JSONObject, callbacks: NexusPluginCallbacks) {
        val parsed = (PageSurfaceContract.validateAction(payload) as? PageSurfaceValidationResult.Valid)?.value ?: return
        val registrationId = registrationOf(payload) ?: return
        if (!PageTemplateContract.isProviderPageId(parsed.pageId, pluginId)) return
        val requestId = synchronized(lock) {
            if (!remember(seenInvocations, parsed.invocationId)) return
            adoptRegistration(registrationId)
            frames[FrameKey(parsed.sessionGeneration, parsed.frameIndex)]?.takeIf { it.pageId == parsed.pageId }?.requestId
        }
        val action = NexusPageAction(
            parsed.invocationId, parsed.sessionGeneration, parsed.frameIndex, parsed.pageId,
            parsed.revision, parsed.actionId, registrationId,
        )
        val result = runCatching { callbacks.onPageAction(action) }.getOrElse { NexusPageResult.rejected() }
        val correlation = PageCorrelation(requestId ?: parsed.invocationId, parsed.sessionGeneration, parsed.frameIndex, parsed.pageId)
        val outgoing = resultPayload(action.invocationId, result, correlation)
            ?.takeIf { PageSurfaceContract.validateBoundedResult(it) is PageSurfaceValidationResult.Valid }
            ?: resultPayload(action.invocationId, NexusPageResult.rejected(), correlation)
            ?: return
        if (send(BusPaths.PAGE_RESULT, UUID.randomUUID().toString(), outgoing)) {
            result.replacement?.takeIf { outgoing.has("replacement") }?.let { replacement ->
                synchronized(lock) {
                    frames[FrameKey(parsed.sessionGeneration, parsed.frameIndex)]?.takeIf { it.pageId == parsed.pageId }?.let {
                        it.lastRevision = maxOf(it.lastRevision ?: replacement.revision, replacement.revision)
                    }
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
            if (registrationId != currentRegistration()) return
            val context = frames[key]?.takeIf { it.pageId == parsed.pageId } ?: return
            context.visible = parsed.visible && lease != null
            context.leaseExpiresAt = lease?.let { clock() + it }
        }
        runCatching {
            callbacks.onPageVisibility(NexusPageVisibility(parsed.pageId, key.generation, key.frameIndex, parsed.visible, lease))
        }
    }

    fun onClosed(payload: JSONObject, callbacks: NexusPluginCallbacks) {
        val parsed = (PageSurfaceContract.validateClosed(payload) as? PageSurfaceValidationResult.Valid)?.value ?: return
        val registrationId = registrationOf(payload) ?: return
        val key = frameKey(payload) ?: return
        synchronized(lock) {
            if (registrationId != currentRegistration()) return
            if (frames[key]?.pageId != parsed.pageId) return
            frames.remove(key)
        }
        runCatching { callbacks.onPageClosed(NexusPageClosed(parsed.pageId, key.generation, key.frameIndex, parsed.reason)) }
    }

    /** A live revision for the current request of a frame that is visible under an unexpired lease. */
    fun publish(request: NexusPageRequest, page: NexusPage): NexusSdkResult {
        val content = page as? NexusPage.Content ?: return NexusSdkResult.INVALID_PAYLOAD
        val key = FrameKey(request.sessionGeneration, request.frameIndex)
        synchronized(lock) {
            val context = frames[key] ?: return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            val current = context.requestId == request.requestId && context.pageId == request.pageId &&
                context.registrationId == request.registrationId
            if (!current || !context.live || !content.live) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            val expires = context.leaseExpiresAt
            if (!context.visible || expires == null || clock() >= expires) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            if (content.revision <= (context.lastRevision ?: Long.MIN_VALUE)) return NexusSdkResult.INVALID_PAYLOAD
        }
        val payload = contentPayload(request.correlation, content) ?: return NexusSdkResult.INVALID_PAYLOAD
        if (!send(BusPaths.PAGE_RESPONSE, UUID.randomUUID().toString(), payload)) return NexusSdkResult.NOT_REGISTERED
        synchronized(lock) {
            frames[key]?.takeIf { it.requestId == request.requestId }?.lastRevision = content.revision
        }
        return NexusSdkResult.SENT
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
        if (!send(BusPaths.PAGE_RESPONSE, UUID.randomUUID().toString(), payload)) return
        if (answer == null || content == null) return
        synchronized(lock) {
            val context = frames[FrameKey(request.sessionGeneration, request.frameIndex)]
                ?.takeIf { it.requestId == request.requestId } ?: return
            context.lastRevision = maxOf(context.lastRevision ?: content.revision, content.revision)
            context.live = content.live
        }
    }

    /** The full response for [content], or null when it would not pass the hub's own validation. */
    private fun contentPayload(correlation: PageCorrelation, content: NexusPage.Content): JSONObject? {
        val payload = runCatching {
            PageSurfaceContract.correlationPayload(correlation)
                .put("revision", content.revision)
                .put("template", content.body.template)
                .put("title", content.title)
                .put("body", PageTemplateContract.encode(content.body))
                .put(
                    "actions",
                    JSONArray().apply {
                        content.actions.forEach {
                            put(
                                JSONObject().put("id", it.id).put("label", it.label)
                                    .put("kind", it.kind.wireValue).put("confirm", it.confirm),
                            )
                        }
                    },
                )
                .put("live", content.live)
        }.getOrNull() ?: return null
        val pending = PageSurfaceRequest(correlation, NexusPageRequestReason.OPEN.wireValue)
        val page = (PageSurfaceContract.validateResponse(payload, pending) as? PageSurfaceValidationResult.Valid)
            ?.value as? PageSurfaceResponse.Page ?: return null
        if (PageTemplateContract.decode(page, pluginId) !is PageSurfaceValidationResult.Valid) return null
        return payload
    }

    private fun errorPayload(correlation: PageCorrelation, code: String): JSONObject =
        PageSurfaceContract.correlationPayload(correlation).put("error", code)

    private fun resultPayload(invocationId: String, result: NexusPageResult, correlation: PageCorrelation): JSONObject? {
        val payload = JSONObject().put("invocationId", invocationId).put("status", result.status.wireValue)
        result.message?.let { payload.put("message", it) }
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

    /** A callback from another registration than the frames were recorded under voids them all. */
    private fun adoptRegistration(registrationId: String) {
        if (frames.values.any { it.registrationId != registrationId }) frames.clear()
    }

    private fun currentRegistration(): String? = frames.values.firstOrNull()?.registrationId

    private fun dropOtherSessions(generation: Long) {
        frames.keys.filter { it.generation != generation }.forEach(frames::remove)
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
