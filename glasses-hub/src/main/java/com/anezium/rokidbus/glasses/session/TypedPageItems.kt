package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceAction
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.RouteGeometry

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
 * `immersion` action can only open that provider's own full app on a fresh tap;
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
    /** An owner's live `/activity/geometry`, which feeds the map of a route page carrying none. */
    private val liveGeometry: (String) -> RouteGeometry? = { null },
    /** An owner's saved ambient size, for the route page's Display row; null while automatic. */
    private val ambientSize: (String) -> AmbientSize? = { null },
) : PageItemResolver {
    private data class Decoded(val page: PageSurfaceResponse.Page, val extended: Boolean, val body: PageBody?)

    private val decoded = ArrayDeque<Decoded>()

    /** The typed body, or null when this hub cannot draw it. */
    fun body(page: PageSurfaceResponse.Page): PageBody? = decode(page, extended())

    /**
     * A retained snapshot's body, read with the templates it was accepted under, so a page the
     * bundle validated stays readable once the bundle is gone. Only ever drawn dimmed and inert:
     * rows, actions and new answers all go through [body] and the live negotiation.
     */
    fun retainedBody(snapshot: Snapshot): PageBody? = decode(snapshot.page, snapshot.extended)

    override fun acceptsExtended(): Boolean = extended()

    private fun decode(page: PageSurfaceResponse.Page, extended: Boolean): PageBody? {
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
        val route = (body as? PageBody.Route)?.takeUnless { local } ?: return rows + extra
        // The command list reads hub, then plugin actions, then the full app (r17 3.7).
        return routeRows(route, owner) + (rows + extra).sortedBy { it.item is PageItem.Launch }
    }

    /**
     * A route page's hub rows, ahead of the provider's actions (plan 028a §5.5, r17 3.4/3.7): the
     * compact map when there is one to draw, then Commands; the page itself selects only those
     * two. Behind Commands come the two views and Full screen (with a map), then Display, then
     * the provider's actions: the command list.
     */
    private fun routeRows(body: PageBody.Route, owner: String): List<TypedRow> = buildList {
        val map = RoutePresentation.hasMap(body, liveGeometry(owner))
        if (map) add(TypedRow(MAP_KEY, RoutePresentation.MAP_FOLLOW, null, true, PageItem.RouteMap))
        add(TypedRow(COMMANDS_KEY, RoutePresentation.COMMANDS, RoutePresentation.COMMANDS_SUB, true, PageItem.RouteCommands))
        if (map) {
            add(TypedRow(VIEW_FOLLOW_KEY, RoutePresentation.VIEW_FOLLOW, RoutePresentation.VIEW_FOLLOW_SUB, true, PageItem.RouteView(MapView.FOLLOW)))
            add(TypedRow(VIEW_OVERVIEW_KEY, RoutePresentation.VIEW_OVERVIEW, RoutePresentation.VIEW_OVERVIEW_SUB, true, PageItem.RouteView(MapView.OVERVIEW)))
            add(TypedRow(FULL_SCREEN_KEY, RoutePresentation.FULL_SCREEN, RoutePresentation.FULL_SCREEN_SUB, true, PageItem.RouteFullScreen))
        }
        if (com.anezium.rokidbus.shared.AmbientPreferencesContract.hasSizeChoice(owner)) {
            val size = ambientSize(owner)
            add(TypedRow(DISPLAY_KEY, RoutePresentation.displayLabel(size), RoutePresentation.DISPLAY_SUB, true, PageItem.RouteDisplay(owner, size)))
        }
    }

    override fun items(page: PageSurfaceResponse.Page): List<PageItem> = rows(page).map(TypedRow::item)

    override fun entries(page: PageSurfaceResponse.Page): List<PageEntry> =
        rows(page).map { PageEntry(it.key, it.item, it.label) }

    override fun hasStableKeys(): Boolean = true

    override fun isDocument(page: PageSurfaceResponse.Page): Boolean = body(page) is PageBody.Document

    override fun hasText(page: PageSurfaceResponse.Page): Boolean = body(page).let { it is PageBody.Summary || it is PageBody.Commands }

    override fun accepts(page: PageSurfaceResponse.Page): Boolean = body(page) != null

    /** A media page's controls, as appended action rows; a tap first focuses its default control. */
    override fun controls(page: PageSurfaceResponse.Page): PageControls? {
        val media = body(page) as? PageBody.Media ?: return null
        val present = rows(page).map(TypedRow::key).toSet()
        val keys = media.controls.map { ACTION_KEY_PREFIX + it }.filter { it in present }
        if (keys.isEmpty()) return null
        val wanted = media.defaultControl?.let { ACTION_KEY_PREFIX + it }?.takeIf { it in keys }
        return PageControls(keys, wanted ?: keys.first())
    }

    override fun conversation(page: PageSurfaceResponse.Page): PageBody.Conversation? = body(page) as? PageBody.Conversation

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
                        KIND_IMMERSION -> PageItem.Launch(owner, row.label)
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
        else -> PageItem.PluginAction(action.id, label)
    }

    private fun localAction(actionId: String, label: String): PageItem = when {
        actionId.startsWith(LAUNCH_PREFIX) -> PageItem.Launch(actionId.removePrefix(LAUNCH_PREFIX), label)
        actionId.startsWith(LOCAL_PREFIX) -> PageItem.LocalLaunch(actionId.removePrefix(LOCAL_PREFIX), label)
        actionId.startsWith(ACTIVITY_PREFIX) -> PageItem.Activity(actionId.removePrefix(ACTIVITY_PREFIX))
        actionId.startsWith(NOTICE_PREFIX) -> PageItem.Notification(actionId.removePrefix(NOTICE_PREFIX))
        else -> PageItem.Inert
    }

    companion object {
        const val KIND_HUB = PageSurfaceContract.KIND_HUB
        const val KIND_PLUGIN = PageSurfaceContract.KIND_PLUGIN
        const val KIND_IMMERSION = PageSurfaceContract.KIND_IMMERSION

        /** A hub action opening a plugin's full app on a fresh tap. */
        const val LAUNCH_PREFIX = "launch:"

        /** A hub action launching a local catalog entry, by its key. */
        const val LOCAL_PREFIX = "local:"

        /** A hub action standing for an entry of the Activities page, by its owner. */
        const val ACTIVITY_PREFIX = "activity:"

        /** A hub action standing for an entry of the Notifications page, by its history id. */
        const val NOTICE_PREFIX = "notice:"
        /** A space, which no wire id contains, keeps appended keys out of the row ids' namespace. */
        const val ACTION_KEY_PREFIX = "action "

        /** A route page's hub rows; the space keeps them out of every wire id as well. */
        const val MAP_KEY = "route map"
        const val COMMANDS_KEY = "route commands"
        const val VIEW_FOLLOW_KEY = "route view-follow"
        const val VIEW_OVERVIEW_KEY = "route view-overview"
        const val FULL_SCREEN_KEY = "route full-screen"
        const val DISPLAY_KEY = "route display"
        private const val CACHE_SIZE = 8
    }
}
