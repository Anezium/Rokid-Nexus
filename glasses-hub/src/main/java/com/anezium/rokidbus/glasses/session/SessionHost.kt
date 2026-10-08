// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.session

/** One line of the minimal host; [selected] is the row the wearer's tap acts on. */
internal data class HostRow(val text: String, val selected: Boolean = false)

/** What the host window shows: an optional title, its rows, and an optional status line. */
internal data class HostScreen(val title: String?, val rows: List<HostRow>, val status: String? = null)

/**
 * The window behind the host: one full-screen accessibility overlay, opaque black because pure
 * black is transparent on the additive optics, holding the screen on only while it exists. The
 * Android side implements it; this package stays free of `android.*`.
 */
internal interface SessionWindow {
    /** Adds the window; false when the window manager refused it. */
    fun add(): Boolean

    fun remove()

    fun show(screen: HostScreen)
}

/**
 * The session's one window, attached on [SessionEffect.AttachHost] and detached on
 * [SessionEffect.DetachHost], never between the gate, the root and a page. A window that cannot
 * be added aborts the session once: a session with no window would own every key invisibly.
 *
 * It draws text rows only: the gate's hint, one row per root stop, and a frame's title with its
 * state. It never reads a page beyond its title and state; templates are drawn in PR3.
 */
internal class SessionHost(
    private val window: SessionWindow,
    private val abort: () -> Unit,
    private val log: (String) -> Unit,
) {
    var isAttached: Boolean = false
        private set

    fun attach() {
        if (isAttached) return
        if (!window.add()) {
            log("session host window could not be added; closing the session")
            abort()
            return
        }
        isAttached = true
    }

    fun detach() {
        if (!isAttached) return
        isAttached = false
        window.remove()
    }

    fun render(state: SessionState, status: SessionStatus?) {
        if (!isAttached) return
        window.show(screenFor(state, status))
    }

    companion object {
        const val GATE_HINT = "Opening…"

        fun screenFor(state: SessionState, status: SessionStatus?): HostScreen {
            val statusText = status?.let(::statusText)
            return when (state) {
                SessionState.Closed -> HostScreen(null, emptyList(), statusText)
                is SessionState.Opening -> HostScreen(null, listOf(HostRow(GATE_HINT)), statusText)
                is SessionState.Root -> HostScreen(null, rootRows(state), statusText)
                is SessionState.InPage -> frameScreen(state.frames.last(), statusText)
                is SessionState.Launching ->
                    screenFor(state.previous, status).copy(status = statusText ?: "Opening ${state.pluginId}…")
            }
        }

        /** `Activities · n` has no row until the overflow page exists (PR3). */
        private fun rootRows(root: SessionState.Root): List<HostRow> =
            root.stops.mapIndexedNotNull { index, stop ->
                if (stop.kind == RootStopKind.ACTIVITIES) return@mapIndexedNotNull null
                val text = when (stop.kind) {
                    RootStopKind.NOTIFICATIONS -> "${stop.text} · ${stop.count ?: 0}"
                    RootStopKind.ACTIVITY -> if (stop.ended) "${stop.text} · Ended" else stop.text
                    else -> stop.text
                }
                HostRow(text, selected = index == root.selected)
            }

        private fun frameScreen(frame: Frame, statusText: String?): HostScreen {
            val retained = when (val status = frame.status) {
                is FrameStatus.Loading -> status.previous
                is FrameStatus.Shown -> status.snapshot
                is FrameStatus.Unavailable -> status.lastSnapshot
            }
            val title = retained?.page?.title ?: frame.pageId
            val rows = when (val status = frame.status) {
                is FrameStatus.Loading -> listOf(HostRow(if (status.stillLoading) "Loading… still waiting" else "Loading"))
                is FrameStatus.Shown -> listOf(HostRow("Shown (${status.revision})"))
                is FrameStatus.Unavailable -> listOf(HostRow("Unavailable (${status.reason})")) +
                    SessionReducer.UNAVAILABLE_ITEMS.mapIndexed { index, item ->
                        HostRow(if (item == PageItem.Retry) "Retry" else "Back", selected = index == frame.selected)
                    }
            }
            return HostScreen(title, rows, statusText)
        }

        private fun statusText(status: SessionStatus): String = when (status) {
            is SessionStatus.OpenFailed -> "Could not open ${status.pluginId} (${status.reason.name.lowercase()})"
        }
    }
}
