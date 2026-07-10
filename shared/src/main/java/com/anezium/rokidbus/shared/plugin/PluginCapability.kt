package com.anezium.rokidbus.shared.plugin

enum class PluginCapability(val wireValue: String) {
    SURFACES("surfaces"),
    MICROPHONE("microphone"),
    HTTP_PROXY("http_proxy"),
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
