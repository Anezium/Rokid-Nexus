package com.anezium.rokidbus.phone

/**
 * Wearer-facing rows for [PluginDisplayPolicy]. They behave as a radio group:
 * at most one is on, and all off is [PluginDisplayPolicy.NORMAL]. Mute is not
 * a capability; this only maps switch state onto the grant field.
 */
object PluginDisplayPolicySelection {
    enum class Row {
        MUTE,
        DEMOTE,
        NOTICES,
    }

    fun isChecked(policy: PluginDisplayPolicy, row: Row): Boolean =
        when (row) {
            Row.MUTE -> policy == PluginDisplayPolicy.MUTE
            Row.DEMOTE -> policy == PluginDisplayPolicy.DEMOTE
            Row.NOTICES -> policy == PluginDisplayPolicy.NOTICES
        }

    /**
     * Apply a switch change. Turning a row on selects its policy and turns the
     * others off. Turning the active row off returns [PluginDisplayPolicy.NORMAL].
     */
    fun afterToggle(
        current: PluginDisplayPolicy,
        row: Row,
        checked: Boolean,
    ): PluginDisplayPolicy {
        if (checked) {
            return when (row) {
                Row.MUTE -> PluginDisplayPolicy.MUTE
                Row.DEMOTE -> PluginDisplayPolicy.DEMOTE
                Row.NOTICES -> PluginDisplayPolicy.NOTICES
            }
        }
        return if (isChecked(current, row)) PluginDisplayPolicy.NORMAL else current
    }
}
