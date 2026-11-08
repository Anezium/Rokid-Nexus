package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject

/**
 * Where a composer tool was tapped, handed to the plugin whose immersion it opens: the
 * conversation page ([pageId] with its [paramsJson]), its [threadKey], and the tool ([toolId])
 * with the `immersion` action it named ([actionId]). It rides on `/launcher/open` from the
 * glasses and on `/system/plugin/open` to the plugin, as `context`.
 */
data class ComposerToolContext(
    val pageId: String,
    val paramsJson: String?,
    val threadKey: String,
    val toolId: String,
    val actionId: String,
) {
    /** Whether this context names one of [pluginId]'s own pages. */
    fun isOwnedBy(pluginId: String): Boolean = PageTemplateContract.isProviderPageId(pageId, pluginId)
}

/**
 * The wire form of a [ComposerToolContext]: `{pageId, params?, threadKey, toolId, actionId}`,
 * every id by the page id rules and params within the page params bound. Both hubs parse it
 * fail-closed: anything malformed is no context at all, and the phone refuses an open whose
 * context names another provider's page.
 */
object ComposerToolContract {
    const val FIELD_CONTEXT = "context"

    fun encode(context: ComposerToolContext): JSONObject = JSONObject()
        .put("pageId", context.pageId)
        .put("threadKey", context.threadKey)
        .put("toolId", context.toolId)
        .put("actionId", context.actionId)
        .apply { context.paramsJson?.let { put("params", JSONObject(it)) } }

    /** The context in [value], or null when it is absent or malformed in any way. */
    fun parse(value: Any?): ComposerToolContext? {
        val payload = value as? JSONObject ?: return null
        fun id(key: String): String? = (payload.opt(key) as? String)?.takeIf(PageSurfaceContract::isValidId)
        val pageId = id("pageId") ?: return null
        val owner = PageTemplateContract.namespaceOf(pageId) ?: return null
        if (owner == PageTemplateContract.LOCAL_NAMESPACE || !PluginDescriptor.isValidId(owner)) return null
        val params = if (payload.has("params") && !payload.isNull("params")) {
            val raw = payload.opt("params") as? JSONObject ?: return null
            PageSurfaceContract.boundedParams(raw) ?: return null
        } else {
            null
        }
        return ComposerToolContext(
            pageId = pageId,
            paramsJson = params,
            threadKey = id("threadKey") ?: return null,
            toolId = id("toolId") ?: return null,
            actionId = id("actionId") ?: return null,
        )
    }
}
