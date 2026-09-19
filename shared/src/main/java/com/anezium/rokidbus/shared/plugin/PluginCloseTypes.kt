package com.anezium.rokidbus.shared.plugin

/**
 * The `type` a `/system/plugin/close` lifecycle message carries. Version-1 receivers fully
 * close for an absent or unknown value, preserving compatibility with older lifecycle reasons.
 */
object PluginCloseTypes {
    /** The plugin has no foreground surface but remains bound while its audio lease is active. */
    const val BACKGROUND = "background"

    /** The plugin must release every session and return to its dormant state. */
    const val CLOSED = "closed"
}
