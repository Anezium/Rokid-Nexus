package com.anezium.rokidbus.phone

/**
 * Pure state machine for the hub's ambient media trigger. It is deliberately Android-free:
 * a media-trigger service feeds it real MediaSession playback edges, and it decides when to
 * open the registered plugin (hub-initiated background open) and when to close it after the
 * no-playing grace. Keeping the policy here lets the open/close contract be tested without an
 * instrumented service.
 *
 * This enforces the ambient-widget doctrine: on a playback-start edge the trigger OPENS the
 * registered plugin through the normal hub-initiated open path; once no session plays for the
 * grace, it closes it -- UNLESS the plugin owns a visible foreground surface, in which case
 * the wearer is actively using it and the background widget must not steal focus.
 *
 * A clock supplies "now" so tests advance time without sleeping. The service drives
 * [onPlaybackChanged] on the main thread and [tickGrace] from a grace timer.
 */
class MediaPlaybackTrigger(
    private val now: () -> Long,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
    private val ownsVisibleSurface: () -> Boolean = { false },
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {
    private var playing = false
    private var stoppedAt = Long.MAX_VALUE

    /** Whether an open is currently owed (a play edge happened and no close has landed yet). */
    private var openHeld = false

    /** True once a play edge opened, until the grace close lands. */
    val isHoldingOpen: Boolean
        get() = openHeld

    /** Feed a play/pause/stop edge derived from a media controller's [isPlaying]. */
    fun onPlaybackChanged(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        if (playing) {
            openHeld = true
            onOpen()
        } else {
            stoppedAt = now()
        }
    }

    /** From the service's grace timer (fires after playback stopped). Closes when owed. */
    fun tickGrace() {
        if (playing || !openHeld) return
        if (ownsVisibleSurface()) return
        if (now() - stoppedAt < graceMs) return
        openHeld = false
        onClose()
    }

    companion object {
        const val DEFAULT_GRACE_MS = 60_000L
    }
}