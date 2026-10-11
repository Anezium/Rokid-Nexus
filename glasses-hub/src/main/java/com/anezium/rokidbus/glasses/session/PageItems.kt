package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse

/** What SELECT does on a selectable row of a frame. */
internal sealed interface PageItem {
    data class OpenPage(val pageId: String, val paramsJson: String? = null) : PageItem
    data class Invoke(val actionId: String) : PageItem

    /**
     * A typed plugin action the user approved to run on one tap: it is invoked in quick mode,
     * with no confirmation, once the fresh-contact rule holds. Only [QuickGate] makes one.
     */
    data class Quick(val actionId: String, val label: String) : PageItem

    /** Opens [pluginId]'s immersion at once. Only an injected resolver produces it. */
    data class Launch(val pluginId: String) : PageItem

    /** A plugin business action; the hub confirms it with the wearer before invoking it. */
    data class Confirm(val actionId: String, val label: String) : PageItem

    /** [pluginId]'s full app, behind a labelled launch step whose Open alone produces [Launch]. */
    data class LaunchPrompt(val pluginId: String, val label: String) : PageItem

    /** A hub-authored local launch (a native app, the hub camera); never a provider's target. */
    data class LocalLaunch(val key: String, val label: String) : PageItem

    /**
     * An entry of the hub's Activities page: SELECT does what tapping its card does (opens its
     * destination, focuses its commands, or opens its summary). Only a hub-authored page has one.
     */
    data class Activity(val ownerPluginId: String) : PageItem

    /**
     * An entry of the hub's Notifications page: SELECT marks it seen and opens it, a conversation
     * at its exact destination, anything else as the hub's notice page. Only a hub page has one.
     */
    data class Notification(val entryId: String) : PageItem

    /**
     * A route page's hub-owned rows (plan 028a §5.5); no provider produces one. SELECT on the
     * compact map or on Full screen opens the chrome-less full map; a view row picks the view kept
     * for the session; Display opens the hub's display page for [ownerPluginId]'s ambient size,
     * starting from [current] (null: automatic).
     */
    data object RouteMap : PageItem
    data class RouteView(val view: MapView) : PageItem
    data object RouteFullScreen : PageItem
    data class RouteDisplay(val ownerPluginId: String, val current: AmbientSize?) : PageItem
    data object Retry : PageItem
    data object Back : PageItem
    data object Inert : PageItem
}

/**
 * Template bodies are opaque to the reducer, so the selectable rows of a page
 * come from the host's template layer.
 */
internal fun interface PageItemResolver {
    fun items(page: PageSurfaceResponse.Page): List<PageItem>

    /** The rows with their keys; positional unless the resolver knows a stable identity. */
    fun entries(page: PageSurfaceResponse.Page): List<PageEntry> =
        items(page).mapIndexed { index, item -> PageEntry("#$index", item) }

    /** Whether [entries] keys survive revisions, so a removed selected row becomes a tombstone. */
    fun hasStableKeys(): Boolean = false

    /** Whether the page is read through as a document before its rows. */
    fun isDocument(page: PageSurfaceResponse.Page): Boolean = false

    /** Whether the page has text above its rows, which is read page by page when it overflows. */
    fun hasText(page: PageSurfaceResponse.Page): Boolean = false

    /** Whether the page's typed body can be drawn here at all; checked before it is ever shown. */
    fun accepts(page: PageSurfaceResponse.Page): Boolean = true

    /**
     * The keys of a page's control strip (a media page's controls), in swipe order, with the one
     * a first tap focuses; null for a page without one. Its rows come first among [entries].
     */
    fun controls(page: PageSurfaceResponse.Page): PageControls? = null

    /** The page's conversation body, which the hub composer serves; null for any other page. */
    fun conversation(page: PageSurfaceResponse.Page): PageBody.Conversation? = null
}

/** A page's control strip: [keys] are entry keys in swipe order, [defaultKey] is focused first. */
internal data class PageControls(val keys: List<String>, val defaultKey: String)

/** A selectable row: what SELECT does, under a key that names the row across revisions. */
internal data class PageEntry(val key: String, val item: PageItem, val label: String = "")

/**
 * The resolver a reducer gets when none is injected. Every `plugin` action is confirmed first,
 * whatever its `confirm` flag says, exactly as the typed resolver confirms it: a reducer built
 * without the host's resolver must never run a plugin action the wearer did not confirm. With no
 * quick-action gate here, no action is ever quick. `hub` and `immersion` rows stay inert.
 */
internal object DefaultPageItems : PageItemResolver {
    override fun items(page: PageSurfaceResponse.Page): List<PageItem> = page.actions.map {
        if (it.kind == PageSurfaceContract.KIND_PLUGIN) PageItem.Confirm(it.id, it.label) else PageItem.Inert
    }
}
