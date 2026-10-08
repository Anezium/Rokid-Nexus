package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject

/** What SELECT on a row does: open a page, or invoke one of the envelope's declared actions. */
sealed interface PageTarget {
    /** [paramsJson] is the serialized `params` object handed to the page's provider. */
    data class Page(val pageId: String, val paramsJson: String? = null) : PageTarget
    data class Action(val actionId: String) : PageTarget
}

/** A selectable row. Without a [target] it is readable but inert; a disabled row is inert too. */
data class PageRow(
    val id: String,
    val label: String,
    val sub: String? = null,
    val enabled: Boolean = true,
    val target: PageTarget? = null,
)

data class PageFact(val id: String, val label: String, val value: String)

enum class PageSegmentKind(val wire: String) {
    HEADER("header"),
    PROSE("prose"),
    ASIDE("aside"),
}

data class PageSegment(val id: String, val kind: PageSegmentKind, val text: String)

/** The four template bodies this renderer draws; see [PageTemplateContract.supportedTemplates]. */
sealed interface PageBody {
    val template: String
    val items: List<PageRow>

    data class Summary(
        val primary: String? = null,
        val secondary: String? = null,
        val text: String? = null,
        val facts: List<PageFact> = emptyList(),
        override val items: List<PageRow> = emptyList(),
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_SUMMARY
    }

    data class SelectableList(
        override val items: List<PageRow>,
        val emptyText: String? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_SELECTABLE_LIST
    }

    data class Document(
        val segments: List<PageSegment>,
        override val items: List<PageRow> = emptyList(),
        val footer: String? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_DOCUMENT
    }

    data class Commands(
        override val items: List<PageRow>,
        val text: String? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_COMMANDS
    }
}

/**
 * Typed bodies of the page v1 templates a hub can draw today, and the page-id namespaces.
 *
 * The envelope validator in [PageSurfaceContract] stays the size and correlation authority and
 * keeps treating `body` as opaque; this decoder is the second check, run before a body is drawn
 * or a row is acted on. The reserved `conversation`, `media`, `route` and `ink` templates are
 * valid envelopes this decoder reports as unsupported. Lengths count UTF-16 code units, as the
 * envelope's own title and label limits do.
 */
object PageTemplateContract {
    const val MAX_ITEMS = 64
    const val MAX_FACTS = 16
    const val MAX_SEGMENTS = 240
    const val MAX_LABEL_CHARS = 120
    const val MAX_PRIMARY_CHARS = 120
    /** `sub`, `secondary`, `footer`, a fact `value` and a list's `emptyText`. */
    const val MAX_DETAIL_CHARS = 240
    /** Summary and commands `text`, and each document segment. */
    const val MAX_TEXT_CHARS = 4_096

    /** The namespace of pages the glasses hub authors itself; no plugin may provide them. */
    const val LOCAL_NAMESPACE = "nexus"
    const val MAIN_PAGE = "main"

    val supportedTemplates: Set<String> = setOf(
        PageSurfaceContract.TEMPLATE_SUMMARY,
        PageSurfaceContract.TEMPLATE_SELECTABLE_LIST,
        PageSurfaceContract.TEMPLATE_DOCUMENT,
        PageSurfaceContract.TEMPLATE_COMMANDS,
    )

    fun isSupported(template: String): Boolean = template in supportedTemplates

    /** A provider's conventional entry page. */
    fun mainPageId(pluginId: String): String = "$pluginId:$MAIN_PAGE"

    /** The owner before the first `:` of a valid page id with a nonempty suffix, or null. */
    fun namespaceOf(pageId: String): String? {
        if (!PageSurfaceContract.isValidId(pageId)) return null
        val separator = pageId.indexOf(':')
        if (separator <= 0 || separator == pageId.lastIndex) return null
        return pageId.substring(0, separator)
    }

    fun isLocalPageId(pageId: String): Boolean = namespaceOf(pageId) == LOCAL_NAMESPACE

    /** Whether [pluginId] may provide [pageId]: its own namespace, never the hub's. */
    fun isProviderPageId(pageId: String, pluginId: String): Boolean =
        pluginId != LOCAL_NAMESPACE && PluginDescriptor.isValidId(pluginId) && namespaceOf(pageId) == pluginId

    /**
     * Decodes [page]'s body. Navigation targets must stay inside [namespace], the authenticated
     * provider's; a hub-authored local page passes null and has its targets authorized by the
     * catalog instead. Action targets must name one of the envelope's actions.
     */
    fun decode(page: PageSurfaceResponse.Page, namespace: String?): PageSurfaceValidationResult<PageBody> =
        decode(page.template, page.bodyJson, page.actions.mapTo(linkedSetOf()) { it.id }, namespace)

    fun decode(
        template: String,
        bodyJson: String,
        actionIds: Set<String>,
        namespace: String?,
    ): PageSurfaceValidationResult<PageBody> = try {
        if (!isSupported(template)) fail("template $template is not available on this hub")
        val body = JSONObject(bodyJson)
        val reader = Reader(actionIds, namespace)
        PageSurfaceValidationResult.Valid(
            when (template) {
                PageSurfaceContract.TEMPLATE_SUMMARY -> PageBody.Summary(
                    primary = reader.optionalText(body, "primary", MAX_PRIMARY_CHARS),
                    secondary = reader.optionalText(body, "secondary", MAX_DETAIL_CHARS),
                    text = reader.optionalText(body, "text", MAX_TEXT_CHARS),
                    facts = reader.facts(body),
                    items = reader.items(body, required = false),
                )
                PageSurfaceContract.TEMPLATE_SELECTABLE_LIST -> PageBody.SelectableList(
                    items = reader.items(body, required = true),
                    emptyText = reader.optionalText(body, "emptyText", MAX_DETAIL_CHARS),
                )
                PageSurfaceContract.TEMPLATE_DOCUMENT -> PageBody.Document(
                    segments = reader.segments(body),
                    items = reader.items(body, required = false),
                    footer = reader.optionalText(body, "footer", MAX_DETAIL_CHARS),
                )
                else -> PageBody.Commands(
                    items = reader.items(body, required = true),
                    text = reader.optionalText(body, "text", MAX_TEXT_CHARS),
                )
            },
        )
    } catch (invalid: InvalidBody) {
        PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, invalid.detail)
    } catch (_: Exception) {
        PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "body could not be read")
    }

    /** The wire form of [body]; [decode] of it, with the same actions and namespace, gives [body] back. */
    fun encode(body: PageBody): JSONObject = JSONObject().apply {
        when (body) {
            is PageBody.Summary -> {
                body.primary?.let { put("primary", it) }
                body.secondary?.let { put("secondary", it) }
                body.text?.let { put("text", it) }
                if (body.facts.isNotEmpty()) {
                    put(
                        "facts",
                        JSONArray().apply {
                            body.facts.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("value", it.value)) }
                        },
                    )
                }
                if (body.items.isNotEmpty()) put("items", encodeItems(body.items))
            }
            is PageBody.SelectableList -> {
                put("items", encodeItems(body.items))
                body.emptyText?.let { put("emptyText", it) }
            }
            is PageBody.Document -> {
                put(
                    "segments",
                    JSONArray().apply {
                        body.segments.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind.wire).put("text", it.text)) }
                    },
                )
                if (body.items.isNotEmpty()) put("items", encodeItems(body.items))
                body.footer?.let { put("footer", it) }
            }
            is PageBody.Commands -> {
                put("items", encodeItems(body.items))
                body.text?.let { put("text", it) }
            }
        }
    }

    private fun encodeItems(items: List<PageRow>): JSONArray = JSONArray().apply {
        items.forEach { row ->
            put(
                JSONObject().put("id", row.id).put("label", row.label).apply {
                    row.sub?.let { put("sub", it) }
                    if (!row.enabled) put("enabled", false)
                    when (val target = row.target) {
                        is PageTarget.Page -> put(
                            "target",
                            JSONObject().put("pageId", target.pageId).apply {
                                target.paramsJson?.let { put("params", JSONObject(it)) }
                            },
                        )
                        is PageTarget.Action -> put("target", JSONObject().put("actionId", target.actionId))
                        null -> Unit
                    }
                },
            )
        }
    }

    private class Reader(private val actionIds: Set<String>, private val namespace: String?) {
        fun items(body: JSONObject, required: Boolean): List<PageRow> {
            val array = array(body, "items", required) ?: return emptyList()
            check(array.length() <= MAX_ITEMS, "more than $MAX_ITEMS items")
            val rows = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("items[$index] must be an object")
                PageRow(
                    id = id(entry, "id"),
                    label = text(entry, "label", MAX_LABEL_CHARS),
                    sub = optionalText(entry, "sub", MAX_DETAIL_CHARS),
                    enabled = if (entry.has("enabled")) entry.opt("enabled") as? Boolean ?: fail("enabled must be a boolean") else true,
                    target = if (entry.has("target")) target(entry.opt("target")) else null,
                )
            }
            unique(rows.map(PageRow::id), "item")
            return rows
        }

        fun facts(body: JSONObject): List<PageFact> {
            val array = array(body, "facts", required = false) ?: return emptyList()
            check(array.length() <= MAX_FACTS, "more than $MAX_FACTS facts")
            val facts = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("facts[$index] must be an object")
                PageFact(id(entry, "id"), text(entry, "label", MAX_LABEL_CHARS), text(entry, "value", MAX_DETAIL_CHARS))
            }
            unique(facts.map(PageFact::id), "fact")
            return facts
        }

        fun segments(body: JSONObject): List<PageSegment> {
            val array = array(body, "segments", required = true) ?: return emptyList()
            check(array.length() <= MAX_SEGMENTS, "more than $MAX_SEGMENTS segments")
            val segments = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("segments[$index] must be an object")
                val kind = entry.opt("kind") as? String ?: fail("segment kind must be a string")
                PageSegment(
                    id(entry, "id"),
                    PageSegmentKind.entries.firstOrNull { it.wire == kind } ?: fail("segment kind $kind is unknown"),
                    text(entry, "text", MAX_TEXT_CHARS),
                )
            }
            unique(segments.map(PageSegment::id), "segment")
            return segments
        }

        private fun target(value: Any?): PageTarget {
            val target = value as? JSONObject ?: fail("target must be an object")
            val hasPage = target.has("pageId")
            val hasAction = target.has("actionId")
            check(hasPage != hasAction, "target needs exactly one of pageId and actionId")
            if (hasAction) {
                check(!target.has("params"), "an action target carries no params")
                val actionId = id(target, "actionId")
                check(actionId in actionIds, "target action $actionId is not one of the page's actions")
                return PageTarget.Action(actionId)
            }
            val pageId = id(target, "pageId")
            val owner = namespaceOf(pageId) ?: fail("target page $pageId has no namespace")
            check(namespace == null || owner == namespace, "target page $pageId leaves the provider's namespace")
            val params = if (target.has("params")) {
                val params = target.opt("params") as? JSONObject ?: fail("params must be an object")
                val serialized = params.toString()
                check(
                    serialized.toByteArray(Charsets.UTF_8).size <= PageSurfaceContract.MAX_PARAMS_BYTES,
                    "target params exceed ${PageSurfaceContract.MAX_PARAMS_BYTES} UTF-8 bytes",
                )
                serialized
            } else {
                null
            }
            return PageTarget.Page(pageId, params)
        }

        fun optionalText(payload: JSONObject, key: String, limit: Int): String? =
            if (payload.has(key)) text(payload, key, limit) else null

        private fun text(payload: JSONObject, key: String, limit: Int): String {
            val value = payload.opt(key) as? String ?: fail("$key must be a string")
            check(value.length <= limit, "$key exceeds $limit characters")
            return value
        }

        private fun id(payload: JSONObject, key: String): String {
            val value = payload.opt(key) as? String ?: fail("$key must be a string")
            check(PageSurfaceContract.isValidId(value), "$key must be a safe id")
            return value
        }

        private fun array(payload: JSONObject, key: String, required: Boolean): JSONArray? {
            if (!payload.has(key)) return if (required) fail("$key is required") else null
            return payload.opt(key) as? JSONArray ?: fail("$key must be an array")
        }

        private fun unique(ids: List<String>, kind: String) {
            check(ids.toSet().size == ids.size, "$kind ids must be unique")
        }

        private fun check(condition: Boolean, reason: String) {
            if (!condition) fail(reason)
        }
    }

    private class InvalidBody(val detail: String) : RuntimeException()

    private fun fail(reason: String): Nothing = throw InvalidBody(reason)
}
