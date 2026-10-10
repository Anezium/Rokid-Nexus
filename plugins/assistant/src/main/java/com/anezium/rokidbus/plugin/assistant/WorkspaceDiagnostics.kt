package com.anezium.rokidbus.plugin.assistant

import android.util.Log

/**
 * Safe Workspace diagnostics: an event code with counts, elapsed times, flags, and reason codes.
 * Callers pass only numbers, booleans, and fixed lowercase codes; anything else is dropped, so a
 * query, document name, path, id, digest, text, or image can never reach the log.
 */
internal object WorkspaceDiagnostics {
    @Volatile var sink: (String) -> Unit = { line -> runCatching { Log.i(TAG, line) } }

    fun event(code: String, vararg fields: Pair<String, Any?>) {
        require(CODE.matches(code))
        val line = StringBuilder(code)
        for ((key, value) in fields) {
            if (!CODE.matches(key)) continue
            val safe = when (value) {
                is Number, is Boolean -> value.toString()
                is String -> value.takeIf { CODE.matches(it) }
                else -> null
            } ?: continue
            line.append(' ').append(key).append('=').append(safe)
        }
        runCatching { sink(line.toString()) }
    }

    private const val TAG = "NexusWorkspace"
    private val CODE = Regex("[a-z][a-z0-9_]{0,47}")
}
