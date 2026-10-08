package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceResponse

/** What SELECT does on a selectable row of a frame. */
internal sealed interface PageItem {
    data class OpenPage(val pageId: String, val paramsJson: String? = null) : PageItem
    data class Invoke(val actionId: String) : PageItem

    /** Opens [pluginId]'s immersion at once. Only an injected resolver produces it. */
    data class Launch(val pluginId: String) : PageItem

    /** A plugin business action; the hub confirms it with the wearer before invoking it. */
    data class Confirm(val actionId: String, val label: String) : PageItem

    /** [pluginId]'s full app, behind a labelled launch step whose Open alone produces [Launch]. */
    data class LaunchPrompt(val pluginId: String, val label: String) : PageItem

    /** A hub-authored local launch (a native app, the hub camera); never a provider's target. */
    data class LocalLaunch(val key: String, val label: String) : PageItem
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

    /** Whether the page's typed body can be drawn here at all; checked before it is ever shown. */
    fun accepts(page: PageSurfaceResponse.Page): Boolean = true
}

/** A selectable row: what SELECT does, under a key that names the row across revisions. */
internal data class PageEntry(val key: String, val item: PageItem, val label: String = "")

/**
 * Until template rendering lands, only a `plugin` action without confirmation
 * is invokable. Confirmation, `hub` and `immersion` rows stay inert rather than
 * sending something the wearer did not confirm.
 */
internal object DefaultPageItems : PageItemResolver {
    override fun items(page: PageSurfaceResponse.Page): List<PageItem> = page.actions.map {
        if (it.kind == "plugin" && !it.confirm) PageItem.Invoke(it.id) else PageItem.Inert
    }
}
