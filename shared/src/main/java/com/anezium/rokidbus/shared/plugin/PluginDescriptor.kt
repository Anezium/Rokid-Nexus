package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.PageSurfaceContract

data class PluginDescriptor(
    val id: String,
    val displayName: String,
    val apiVersion: Int,
    val requestedCapabilities: Set<PluginCapability>,
    val receivePrefixes: List<String>,
    val settingsActivity: String?,
    val launchable: Boolean,
    val iconKey: String? = null,
    val iconDrawableResId: Int? = null,
    /**
     * String-array resource holding this plugin's own HUD glyphs, resolved
     * cross-package by the hub. The array is parsed by `GlyphContract`; a
     * plugin that declares none simply has no custom glyphs.
     */
    val glyphsResId: Int? = null,
    /**
     * The page protocol version this plugin provides, from `rokidbus.plugin.pages`; 0 when it
     * provides no pages. Additive metadata: it is not a capability and needs no approval.
     */
    val pageSessionVersion: Int = 0,
) {
    companion object {
        private val idPattern = Regex("[a-z][a-z0-9._-]{2,63}")

        fun isValidId(id: String): Boolean = idPattern.matches(id)
    }
}

sealed interface PluginDescriptorParseResult {
    data class Valid(val descriptor: PluginDescriptor) : PluginDescriptorParseResult
    data class Invalid(val reason: String) : PluginDescriptorParseResult
}

object PluginDescriptorParser {
    private val knownKeys = setOf(
        BusConstants.META_PLUGIN_ID,
        BusConstants.META_PLUGIN_DISPLAY_NAME,
        BusConstants.META_PLUGIN_ICON,
        BusConstants.META_PLUGIN_ICON_DRAWABLE,
        BusConstants.META_PLUGIN_GLYPHS,
        BusConstants.META_PLUGIN_API_VERSION,
        BusConstants.META_PLUGIN_CAPABILITIES,
        BusConstants.META_PLUGIN_RECEIVE_PREFIXES,
        BusConstants.META_PLUGIN_SETTINGS_ACTIVITY,
        BusConstants.META_PLUGIN_LAUNCHABLE,
    )

    fun parse(metadata: Map<String, String?>): PluginDescriptorParseResult =
        parse(metadata.entries.map { it.key to it.value })

    fun parse(metadata: List<Pair<String, String?>>): PluginDescriptorParseResult {
        val values = linkedMapOf<String, String?>()
        metadata.filter { it.first in knownKeys }.forEach { (key, value) ->
            if (
                key !in setOf(
                    BusConstants.META_PLUGIN_ICON,
                    BusConstants.META_PLUGIN_ICON_DRAWABLE,
                ) &&
                values.containsKey(key) &&
                values[key] != value
            ) {
                return PluginDescriptorParseResult.Invalid("CONFLICTING_METADATA")
            }
            values[key] = value
        }

        val id = values[BusConstants.META_PLUGIN_ID]?.trim().orEmpty()
        if (!PluginDescriptor.isValidId(id)) return PluginDescriptorParseResult.Invalid("INVALID_PLUGIN_ID")

        val displayName = values[BusConstants.META_PLUGIN_DISPLAY_NAME]?.trim().orEmpty()
        if (displayName.isBlank() || displayName.length > 80) {
            return PluginDescriptorParseResult.Invalid("INVALID_DISPLAY_NAME")
        }

        val apiVersion = values[BusConstants.META_PLUGIN_API_VERSION]
            ?.trim()
            ?.toIntOrNull()
            ?: return PluginDescriptorParseResult.Invalid("INVALID_API_VERSION")
        if (apiVersion <= 0) return PluginDescriptorParseResult.Invalid("INVALID_API_VERSION")

        val capabilityResult = PluginCapability.parseList(
            values[BusConstants.META_PLUGIN_CAPABILITIES].orEmpty(),
        )
        val capabilities = when (capabilityResult) {
            is CapabilityParseResult.Valid -> capabilityResult.capabilities
            is CapabilityParseResult.Invalid -> return PluginDescriptorParseResult.Invalid(capabilityResult.reason)
        }

        val rawPrefixes = splitMetadataList(values[BusConstants.META_PLUGIN_RECEIVE_PREFIXES].orEmpty())
        if (rawPrefixes.isEmpty()) return PluginDescriptorParseResult.Invalid("MISSING_RECEIVE_PREFIXES")
        val normalizedPrefixes = rawPrefixes.map { prefix ->
            PathRules.normalizeAbsolute(prefix)
                ?: return PluginDescriptorParseResult.Invalid("INVALID_RECEIVE_PREFIX")
        }
        if (normalizedPrefixes.toSet().size != normalizedPrefixes.size) {
            return PluginDescriptorParseResult.Invalid("DUPLICATE_RECEIVE_PREFIX")
        }
        if (normalizedPrefixes.any { !PathRules.isAllowedReceivePrefix(it, id, capabilities) }) {
            return PluginDescriptorParseResult.Invalid("RECEIVE_PREFIX_OUTSIDE_NAMESPACE")
        }

        val settingsActivity = values[BusConstants.META_PLUGIN_SETTINGS_ACTIVITY]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val iconKey = values[BusConstants.META_PLUGIN_ICON]
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
        val iconDrawableResId = values[BusConstants.META_PLUGIN_ICON_DRAWABLE]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it != 0 }
        val glyphsResId = values[BusConstants.META_PLUGIN_GLYPHS]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it != 0 }
        // Page opt-in never invalidates an otherwise valid plugin: anything but one agreed
        // supported value, conflicting entries included, simply means no pages.
        val pageValues = metadata.filter { it.first == PageSurfaceContract.META_PLUGIN_PAGES }
            .map { it.second?.trim() }
            .toSet()
        val pageSessionVersion = if (pageValues == setOf(PAGES_VALUE_V1)) PageSurfaceContract.VERSION else 0
        val launchable = when (values[BusConstants.META_PLUGIN_LAUNCHABLE]?.trim()?.lowercase()) {
            null, "", "true" -> true
            "false" -> false
            else -> return PluginDescriptorParseResult.Invalid("INVALID_LAUNCHABLE")
        }
        return PluginDescriptorParseResult.Valid(
            PluginDescriptor(
                id = id,
                displayName = displayName,
                apiVersion = apiVersion,
                requestedCapabilities = capabilities,
                receivePrefixes = normalizedPrefixes.sorted(),
                settingsActivity = settingsActivity,
                launchable = launchable,
                iconKey = iconKey,
                iconDrawableResId = iconDrawableResId,
                glyphsResId = glyphsResId,
                pageSessionVersion = pageSessionVersion,
            ),
        )
    }

    private const val PAGES_VALUE_V1 = "1"
}
