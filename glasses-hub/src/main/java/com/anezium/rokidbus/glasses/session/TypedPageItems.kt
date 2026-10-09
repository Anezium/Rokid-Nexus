package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract

/** One selectable row as drawn and as acted on: the same list feeds both. */
internal data class TypedRow(
    val key: String,
    val label: String,
    val sub: String?,
    val enabled: Boolean,
    val item: PageItem,
)

/**
 * G3: the shared typed body drives rendering and SELECT alike. A row keeps its provider-given id
 * as its key across revisions. Envelope actions no row targets are appended as their own rows.
 * Body rows and appended actions key in disjoint namespaces: a row id may equal an action id, or
 * look like an appended key, and the two still never share selection, focus or SELECT.
 *
 * A provider's `plugin` action is confirmed first; its `immersion` action can only open that
 * provider's own full app, behind the labelled launch step; its `hub` actions are refused. Only
 * a hub-authored local page may carry hub actions, and only the local launch ones named here.
 */
internal class TypedPageItems(
    private val isLocalPage: (String) -> Boolean,
    /** Whether a hub-authored row may open this provider page: in the catalog, pages negotiated. */
    private val providerPageAllowed: (String) -> Boolean,
) : PageItemResolver {
    private val decoded = ArrayDeque<Pair<PageSurfaceResponse.Page, PageBody?>>()

    /** The typed body, or null when this hub cannot draw it. */
    fun body(page: PageSurfaceResponse.Page): PageBody? {
        decoded.firstOrNull { it.first === page }?.let { return it.second }
        val local = isLocalPage(page.correlation.pageId)
        val owner = PageTemplateContract.namespaceOf(page.correlation.pageId)
        val body = (PageTemplateContract.decode(page, if (local) null else owner) as? PageSurfaceValidationResult.Valid)?.value
        decoded.addFirst(page to body)
        while (decoded.size > CACHE_SIZE) decoded.removeLast()
        return body
    }

    fun rows(page: PageSurfaceResponse.Page): List<TypedRow> {
        val body = body(page) ?: return emptyList()
        val local = isLocalPage(page.correlation.pageId)
        val owner = PageTemplateContract.namespaceOf(page.correlation.pageId).orEmpty()
        val actions = page.actions.associateBy { it.id }
        val targeted = body.items.mapNotNull { (it.target as? PageTarget.Action)?.actionId }.toSet()
        val rows = body.items.map { row -> TypedRow(row.id, row.label, row.sub, row.enabled, itemFor(row, local, owner, actions)) }
        val extra = page.actions.filter { it.id !in targeted }.map { action ->
            val row = PageRow(action.id, action.label, target = PageTarget.Action(action.id))
            TypedRow(ACTION_KEY_PREFIX + action.id, row.label, null, true, itemFor(row, local, owner, actions))
        }
        return rows + extra
    }

    override fun items(page: PageSurfaceResponse.Page): List<PageItem> = rows(page).map(TypedRow::item)

    override fun entries(page: PageSurfaceResponse.Page): List<PageEntry> =
        rows(page).map { PageEntry(it.key, it.item, it.label) }

    override fun hasStableKeys(): Boolean = true

    override fun isDocument(page: PageSurfaceResponse.Page): Boolean = body(page) is PageBody.Document

    override fun hasText(page: PageSurfaceResponse.Page): Boolean = body(page).let { it is PageBody.Summary || it is PageBody.Commands }

    override fun accepts(page: PageSurfaceResponse.Page): Boolean = body(page) != null

    private fun itemFor(
        row: PageRow,
        local: Boolean,
        owner: String,
        actions: Map<String, com.anezium.rokidbus.shared.PageSurfaceAction>,
    ): PageItem {
        if (!row.enabled) return PageItem.Inert
        return when (val target = row.target) {
            null -> PageItem.Inert
            is PageTarget.Page -> when {
                isLocalPage(target.pageId) -> if (local) PageItem.OpenPage(target.pageId, target.paramsJson) else PageItem.Inert
                local -> if (providerPageAllowed(target.pageId)) PageItem.OpenPage(target.pageId, target.paramsJson) else PageItem.Inert
                // The decoder already kept a provider's targets inside its own namespace.
                else -> PageItem.OpenPage(target.pageId, target.paramsJson)
            }
            is PageTarget.Action -> {
                val action = actions[target.actionId] ?: return PageItem.Inert
                if (local) {
                    if (action.kind != KIND_HUB) PageItem.Inert else localAction(action.id, row.label)
                } else {
                    when (action.kind) {
                        KIND_PLUGIN -> PageItem.Confirm(action.id, row.label)
                        KIND_IMMERSION -> PageItem.LaunchPrompt(owner, row.label)
                        else -> PageItem.Inert
                    }
                }
            }
        }
    }

    private fun localAction(actionId: String, label: String): PageItem = when {
        actionId.startsWith(LAUNCH_PREFIX) -> PageItem.LaunchPrompt(actionId.removePrefix(LAUNCH_PREFIX), label)
        actionId.startsWith(LOCAL_PREFIX) -> PageItem.LocalLaunch(actionId.removePrefix(LOCAL_PREFIX), label)
        else -> PageItem.Inert
    }

    companion object {
        const val KIND_HUB = "hub"
        const val KIND_PLUGIN = "plugin"
        const val KIND_IMMERSION = "immersion"

        /** A hub action opening a plugin's full app behind the labelled launch step. */
        const val LAUNCH_PREFIX = "launch:"

        /** A hub action launching a local catalog entry, by its key. */
        const val LOCAL_PREFIX = "local:"
        /** A space, which no wire id contains, keeps appended keys out of the row ids' namespace. */
        const val ACTION_KEY_PREFIX = "action "
        private const val CACHE_SIZE = 8
    }
}
