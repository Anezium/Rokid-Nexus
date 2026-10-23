package com.anezium.rokidbus.plugin.relay

internal enum class RelayAppPolicy(val label: String) {
    OFF("Off"),
    MESSAGES("Messages only"),
    ALL("All notifications");

    fun admits(repliable: Boolean, ongoing: Boolean = false, groupSummary: Boolean = false): Boolean =
        when (this) {
            OFF -> false
            MESSAGES -> repliable
            ALL -> repliable || (!ongoing && !groupSummary)
        }

    companion object {
        fun fromStored(value: String?): RelayAppPolicy =
            entries.firstOrNull { it.name == value } ?: MESSAGES
    }
}
