package com.anezium.rokidbus.plugin.line

internal class LineCredentials(val email: String, val password: String)

/** One-shot in-process handoff: secrets never go in an Intent, Bundle or preference. */
internal object LineSignInRequest {
    private var pending: LineCredentials? = null
    @Synchronized fun put(value: LineCredentials) { pending = value }
    @Synchronized fun take(): LineCredentials? = pending.also { pending = null }
    @Synchronized fun clear() { pending = null }
}
