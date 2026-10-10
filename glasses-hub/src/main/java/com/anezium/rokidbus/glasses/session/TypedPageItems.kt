package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceAction
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract

/**
 * One selectable row as drawn and as acted on: the same list feeds both. [actionId] is the
 * envelope action the row invokes, so a pending invocation marks its row; [checked] is the
 * provider's checkbox mark, shown only over a pair that negotiated the launcher bundle.
 */
internal data class TypedRow(
    val key: String,
    val label: String,
    val sub: String?,
    val enabled: Boolean,
    val item: PageItem,
    val actionId: String? = null,
    val checked: Boolean? = null,
)

/**
 * G3: the shared typed body drives rendering and SELECT alike. A row keeps its provider-given id
 * as its key across revisions. Envelope actions no row targets are appended as their own rows.
 * Body rows and appended actions key in disjoint namespaces: a row id may equal an action id, or
 * look like an appended key, and the two still never share selection, focus or SELECT.
 *
 * A provider's `plugin` action is confirmed first, unless [quickGate] lets one tap run it; its
 * `immersion` action can only open that provider's own full app, behind the labelled launch step;
 * its `hub` actions are refused, and so is an action typed `compose.send`, which only the
 * composer's deliberate Send reaches. Only a hub-authored local page may carry hub actions, and
 * only the local launch ones named here.
 *
 * [extended] is whether the pair negotiated the launcher bundle: its page bodies are decoded and
 * its row fields drawn only then.
 */
internal class TypedPageItems(
    private val isLocalPage: (String) -> Boolean,
    /** Whether a hub-authored row may open this provider page: in the catalog, pages negotiated. */
    private val providerPageAllowed: (String) -> Boolean,
    private val quickGate: QuickGate = QuickGate.NEVER,
    private val extended: () -> Boolean = { false },
) : PageItemResolver {
    private data class Decoded(val page: PageSurfaceResponse.Page, val extended: Boolean, val body: PageBody?)

    private val decoded = ArrayDeque<Decoded>()

    /** The typed body, or null when this hub cannot draw it. */
    fun body(page: PageSurfaceResponse.Page): PageBody? {
        val extended = extended()
        decoded.firstOrNull { it.page === page && it.extended == extended }?.let { return it.body }
        val local = isLocalPage(page.correlation.pageId)
        val owner = PageTemplateContract.namespaceOf(page.correlation.pageId)
        val body = (PageTemplateContract.decode(page, if (local) null else owner, extended) as? PageSurfaceValidationResult.Valid)?.value
        decoded.addFirst(Decoded(page, extended, body))
        while (decoded.size > CACHE_SIZE) decoded.removeLast()
        return body
    }

    fun rows(page: PageSurfaceResponse.Page): List<TypedRow> {
        val body = body(page) ?: return emptyList()
        val local = isLocalPage(page.correlation.pageId)
        val owner = PageTemplateContract.namespaceOf(page.correlation.pageId).orEmpty()
        val actions = page.actions.associateBy { it.id }
        val marks = extended()
        val targeted = body.items.mapNotNull { (it.target as? PageTarget.Action)?.actionId }.toSet()
        val rows = body.items.map { row ->
            TypedRow(
                row.id, row.label, row.sub, row.enabled, itemFor(row, local, owner, actions),
                actionId = (row.target as? PageTarget.Action)?.actionId,
                checked = row.checked.takeIf { marks },
            )
        }
        val extra = appendedActions(body, page.actions.filter { it.id !in targeted }).map { action ->
            val row = PageRow(action.id, action.label, target = PageTarget.Action(action.id))
            TypedRow(ACTION_KEY_PREFIX + action.id, row.label, null, true, itemFor(row, local, owner, actions), actionId = action.id)
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

    /** A media page opens on its default control. */
    override fun initialIndex(page: PageSurfaceResponse.Page): Int {
        val media = body(page) as? PageBody.Media ?: return 0
        val control = media.defaultControl ?: return 0
        return rows(page).indexOfFirst { it.actionId == control && it.key.startsWith(ACTION_KEY_PREFIX) }.coerceAtLeast(0)
    }

    /** A media page's controls come first, in their swipe order; every other action keeps its place. */
    private fun appendedActions(body: PageBody, actions: List<PageSurfaceAction>): List<PageSurfaceAction> {
        val controls = (body as? PageBody.Media)?.controls ?: return actions
        val byId = actions.associateBy { it.id }
        return controls.mapNotNull(byId::get) + actions.filter { it.id !in controls }
    }

    private fun itemFor(
        row: PageRow,
        local: Boolean,
        owner: String,
        actions: Map<String, PageSurfaceAction>,
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
                        KIND_PLUGIN -> pluginAction(owner, action, row.label)
                        KIND_IMMERSION -> PageItem.LaunchPrompt(owner, row.label)
                        else -> PageItem.Inert
                    }
                }
            }
        }
    }

    private fun pluginAction(owner: String, action: PageSurfaceAction, label: String): PageItem = when {
        // A send is reachable only through the composer's own deliberate Send, never as a row.
        action.typed is PageActionType.ComposeSend -> PageItem.Inert
        quickGate.allows(owner, action.typed, action.hubQuick) -> PageItem.Quick(action.id, label)
        else -> PageItem.Confirm(action.id, label)
    }

    private fun localAction(actionId: String, label: String): PageItem = when {
        actionId.startsWith(LAUNCH_PREFIX) -> PageItem.LaunchPrompt(actionId.removePrefix(LAUNCH_PREFIX), label)
        actionId.startsWith(LOCAL_PREFIX) -> PageItem.LocalLaunch(actionId.removePrefix(LOCAL_PREFIX), label)
        else -> PageItem.Inert
    }

    companion object {
        const val KIND_HUB = PageSurfaceContract.KIND_HUB
        const val KIND_PLUGIN = PageSurfaceContract.KIND_PLUGIN
        const val KIND_IMMERSION = PageSurfaceContract.KIND_IMMERSION

        /** A hub action opening a plugin's full app behind the labelled launch step. */
        const val LAUNCH_PREFIX = "launch:"

        /** A hub action launching a local catalog entry, by its key. */
        const val LOCAL_PREFIX = "local:"
        /** A space, which no wire id contains, keeps appended keys out of the row ids' namespace. */
        const val ACTION_KEY_PREFIX = "action "
        private const val CACHE_SIZE = 8
    }
}
