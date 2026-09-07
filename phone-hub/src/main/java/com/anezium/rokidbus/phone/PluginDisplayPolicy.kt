package com.anezium.rokidbus.phone

/**
 * Wearer display policy stored on a [PluginGrant]. This is not a capability:
 * grants stay; painting and wake are what stop. Default [NORMAL] so records
 * written before this field existed keep today's behaviour.
 */
enum class PluginDisplayPolicy(val wireValue: String) {
    NORMAL("normal"),
    DEMOTE("demote"),
    NOTICES("notices"),
    MUTE("mute"),
    ;

    companion object {
        fun fromWire(value: String?): PluginDisplayPolicy {
            if (value.isNullOrBlank()) return NORMAL
            return entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
                ?: NORMAL
        }
    }
}
