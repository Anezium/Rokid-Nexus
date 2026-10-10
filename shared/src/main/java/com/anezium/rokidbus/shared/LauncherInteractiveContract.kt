package com.anezium.rokidbus.shared

/**
 * The launcher bundle: interactive activities with destinations and commands, typed and quick
 * actions, the conversation, media and route page bodies, notice destinations and withdrawal, and
 * the hub-to-hub composer, ambient and policy controls.
 *
 * It is negotiated once, additively, beside every existing tier version, which all stay exactly as
 * they were: raising `pageSessionVersion` or `activitySurfaceVersion` would switch pages or
 * activities off between a new and an old hub, because those match exactly. This version does not:
 * both hubs announce theirs and the pair runs the lower one, so every later version must be a
 * superset of the one before it. Nothing in the bundle is active unless [negotiated] is at least 1.
 */
object LauncherInteractiveContract {
    const val VERSION = 1

    /** The version field in both hubs' capability payloads and in a page provider's `/system/hub/capabilities`. */
    const val CAPABILITY_FIELD_VERSION = "launcherInteractiveVersion"

    /** The phone's explicit launcher backend choice in its capability payload; absent means no opinion. */
    const val CAPABILITY_FIELD_LAUNCHER_BACKEND = "launcherBackend"

    const val BACKEND_SESSION = "session"
    const val BACKEND_LEGACY = "legacy"

    val backends: Set<String> = setOf(BACKEND_SESSION, BACKEND_LEGACY)

    /**
     * A peer's announced version as this build reads it: a nonnegative integer, or 0 for anything
     * absent, malformed, negative or not an integer.
     */
    fun parseVersion(value: Any?): Int = when (value) {
        is Int -> sanitizeVersion(value)
        is Long -> if (value in 0..Int.MAX_VALUE) value.toInt() else 0
        else -> 0
    }

    fun sanitizeVersion(value: Int): Int = value.coerceAtLeast(0)

    /** The version both sides run: the lower of the two when both announce one, else 0. */
    fun negotiated(local: Int, remote: Int): Int =
        if (local >= 1 && remote >= 1) minOf(local, remote) else 0

    /** Whether a pair running [negotiatedVersion] has the bundle at all. */
    fun isActive(negotiatedVersion: Int): Boolean = negotiatedVersion >= 1

    /** An explicit backend choice, or null for anything absent or unknown. */
    fun parseBackend(value: Any?): String? = (value as? String)?.takeIf { it in backends }
}
