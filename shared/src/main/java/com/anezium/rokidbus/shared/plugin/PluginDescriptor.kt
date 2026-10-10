package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.BusConstants

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
     * Raw resource holding the skill catalog, resolved cross-package by the phone hub without
     * starting the plugin. Null when the plugin declares no catalog, or declared one the hub
     * cannot locate; see [skillsDeclared].
     */
    val skillsCatalogResId: Int? = null,
    /** The plugin asked to publish skills, whether or not its catalog resource is usable. */
    val skillsDeclared: Boolean = false,
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
        BusConstants.META_PLUGIN_SKILLS,
        BusConstants.META_PLUGIN_SKILLS_CLIENT,
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
        val declaredCapabilities = when (capabilityResult) {
            is CapabilityParseResult.Valid -> capabilityResult.capabilities
            is CapabilityParseResult.Invalid -> return PluginDescriptorParseResult.Invalid(capabilityResult.reason)
        }
        // Skill roles ride their own keys so that hubs predating skills, which reject unknown
        // capability names, keep loading the plugin. A malformed skills declaration never
        // invalidates the plugin itself: the hub reports it against the catalog instead.
        val skillsDeclared = values.containsKey(BusConstants.META_PLUGIN_SKILLS)
        val skillsCatalogResId = values[BusConstants.META_PLUGIN_SKILLS]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it != 0 }
        val skillsClient = values[BusConstants.META_PLUGIN_SKILLS_CLIENT]?.trim()?.lowercase() == "true"
        val capabilities = linkedSetOf<PluginCapability>().apply {
            addAll(declaredCapabilities)
            if (skillsDeclared) add(PluginCapability.SKILLS_PROVIDER)
            if (skillsClient) add(PluginCapability.SKILLS_CLIENT)
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
                skillsCatalogResId = skillsCatalogResId,
                skillsDeclared = skillsDeclared || PluginCapability.SKILLS_PROVIDER in declaredCapabilities,
            ),
        )
    }
}
