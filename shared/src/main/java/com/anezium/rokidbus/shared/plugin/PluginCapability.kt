package com.anezium.rokidbus.shared.plugin

enum class PluginCapability(val wireValue: String) {
    SURFACES("surfaces"),
    MICROPHONE("microphone"),
    STT("stt"),
    TTS("tts"),
    HTTP_PROXY("http_proxy"),
    CAMERA("camera"),
    MEDIA_SYNC("mediasync"),
    ASSISTANT("assistant"),
    WIRELESS_DEBUGGING("wireless_debugging"),
    INK_SURFACE("ink_surface"),

    /**
     * Publishes typed operations that an approved skills client may invoke through the hub.
     * Requested by declaring a catalog under [com.anezium.rokidbus.shared.BusConstants.META_PLUGIN_SKILLS]
     * rather than in the capability list, so that hubs predating skills still load the plugin.
     */
    SKILLS_PROVIDER("skills_provider"),

    /**
     * Discovers and invokes the skill operations the wearer approved for this caller. Requested
     * with [com.anezium.rokidbus.shared.BusConstants.META_PLUGIN_SKILLS_CLIENT] for the same reason.
     */
    SKILLS_CLIENT("skills_client"),
    ;

    companion object {
        private val byWireValue = entries.associateBy(PluginCapability::wireValue)

        fun fromWireValue(value: String): PluginCapability? = byWireValue[value]

        fun parseList(value: String): CapabilityParseResult {
            val rawValues = splitMetadataList(value)
            val parsed = linkedSetOf<PluginCapability>()
            rawValues.forEach { raw ->
                val capability = fromWireValue(raw)
                    ?: return CapabilityParseResult.Invalid("UNKNOWN_CAPABILITY")
                parsed += capability
            }
            return CapabilityParseResult.Valid(parsed)
        }

        fun serialize(capabilities: Collection<PluginCapability>): String =
            entries.filter(capabilities::contains).joinToString(",") { it.wireValue }
    }
}

sealed interface CapabilityParseResult {
    data class Valid(val capabilities: Set<PluginCapability>) : CapabilityParseResult
    data class Invalid(val reason: String) : CapabilityParseResult
}

internal fun splitMetadataList(value: String): List<String> =
    value.split(',', ';', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
