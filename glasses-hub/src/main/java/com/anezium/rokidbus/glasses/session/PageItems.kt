package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceResponse

/** What SELECT does on a selectable row of a frame. */
internal sealed interface PageItem {
    data class OpenPage(val pageId: String, val paramsJson: String? = null) : PageItem
    data class Invoke(val actionId: String) : PageItem

    /** Opens [pluginId]'s immersion. Only an injected resolver produces it; nothing does before PR3. */
    data class Launch(val pluginId: String) : PageItem
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
}

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
