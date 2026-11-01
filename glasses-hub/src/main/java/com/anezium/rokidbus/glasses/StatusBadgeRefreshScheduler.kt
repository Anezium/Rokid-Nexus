package com.anezium.rokidbus.glasses

/** Window transitions can outlive their accessibility events; reread without keeping old bounds. */
internal class StatusBadgeRefreshScheduler(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val refresh: () -> Unit,
) {
    private var settlePending = false
    private val settle = Runnable {
        settlePending = false
        refresh()
    }
    private val recovery = mutableSetOf<Runnable>()
    private var missingRowEpisode = false

    fun onEvent(windowChanged: Boolean) {
        // A trailing debounce can starve forever on an animated launcher screen.
        if (!settlePending) {
            settlePending = true
            postDelayed(settle, 150L)
        }
        if (windowChanged) scheduleRecovery()
    }

    fun onRead(needsRecovery: Boolean) {
        if (!needsRecovery) {
            missingRowEpisode = false
        } else if (!missingRowEpisode) {
            missingRowEpisode = true
            scheduleRecovery()
        }
    }

    private fun scheduleRecovery() {
        if (recovery.isNotEmpty()) return
        listOf(1_000L, 3_000L, 10_000L, 30_000L).forEach { delay ->
            lateinit var callback: Runnable
            callback = Runnable {
                recovery.remove(callback)
                refresh()
            }
            recovery += callback
            postDelayed(callback, delay)
        }
    }

    fun cancel() {
        removeCallbacks(settle)
        settlePending = false
        recovery.forEach(removeCallbacks)
        recovery.clear()
        missingRowEpisode = false
    }
}
