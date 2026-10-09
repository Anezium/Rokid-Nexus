package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageTarget

/** What Navigation knows when a page is asked for; the same facts its activity and card show. */
internal data class NavPageState(
    val guidance: NavGuidance?,
    val listenerConnected: Boolean,
    val enabled: Boolean,
    /** When the guidance last changed, already formatted, or null when nothing was received. */
    val updatedAt: String?,
)

/** The plugin's own words for the states without a route. */
internal data class NavPageText(
    val noAccess: String,
    val off: String,
    val idle: String,
    val arrived: String = "Arrived",
)

/**
 * Navigation's two read-only pages. `nav:main` is a summary of the current guidance: the
 * manoeuvre, its quantity, the street, the ETA when the app gave one, the source and when it was
 * received. A value the app did not post is left out, never estimated. `nav:commands` holds only
 * the labelled full app until there are real commands; it never offers map or route controls.
 */
internal object NavPages {
    const val MAIN = "nav:main"
    const val COMMANDS = "nav:commands"
    const val OPEN_APP = "open-app"

    private const val TITLE = "Navigation"
    private const val MAX_PRIMARY = 120
    private const val MAX_DETAIL = 240
    private const val MAX_TEXT = 4_096

    fun page(pageId: String, revision: Long, state: NavPageState, text: NavPageText): NexusPage = when (pageId) {
        MAIN -> NexusPage.Content(revision, TITLE, main(state, text), live = true)
        COMMANDS -> NexusPage.Content(
            revision,
            "Commands",
            PageBody.Commands(
                listOf(PageRow(OPEN_APP, "Open the full app", sub = "exclusive surface", target = PageTarget.Action(OPEN_APP))),
            ),
            actions = listOf(NexusPageActionSpec(OPEN_APP, "Open full app", NexusPageActionKind.IMMERSION, confirm = false)),
        )
        else -> NexusPage.Unavailable
    }

    private fun main(state: NavPageState, text: NavPageText): PageBody.Summary {
        val commands = listOf(PageRow("commands", "Commands", target = PageTarget.Page(COMMANDS)))
        // Checked first: without the listener the last route is not current.
        if (!state.listenerConnected) return PageBody.Summary(primary = "No notification access", text = fit(text.noAccess, MAX_TEXT), items = commands)
        if (!state.enabled) return PageBody.Summary(primary = "Navigation is off", text = fit(text.off, MAX_TEXT), items = commands)
        val guidance = state.guidance
            ?: return PageBody.Summary(primary = "No active route", text = fit(text.idle, MAX_TEXT), items = commands)
        val facts = listOfNotNull(
            guidance.measure?.let { PageFact("measure", "Also", fit(it, MAX_DETAIL)) },
            guidance.track?.let { track ->
                PageFact("track", track.label?.let { fit(it, 120) } ?: "Progress", "${track.at} of ${track.count}")
            },
            guidance.eta?.let { PageFact("eta", "Arrival", fit(it, MAX_DETAIL)) },
            guidance.badge?.let { PageFact("line", "Line", fit(it, MAX_DETAIL)) },
            PageFact("source", "Source", guidance.source.label),
            state.updatedAt?.let { PageFact("updated", "Received", it) },
        )
        val instruction = listOfNotNull(guidance.instruction) + guidance.detail
        return PageBody.Summary(
            primary = fit(if (guidance.arrived) text.arrived else guidance.primary, MAX_PRIMARY),
            secondary = guidance.secondary?.let { fit(it, MAX_DETAIL) },
            text = instruction.joinToString("\n").takeIf(String::isNotBlank)?.let { fit(it, MAX_TEXT) },
            facts = facts,
            items = commands,
        )
    }

    private fun fit(value: String, limit: Int): String = NavText.fit(value, limit)
}

/**
 * Publishes `nav:main` live, from the guidance events themselves: only to the frame's current
 * visible request, at most two revisions a second, and never after a cover, a close or a loss.
 * Revisions only go up, and always above the floor the glasses retain: this provider rebuilds a
 * page without knowing that it matches what they kept, for instance after it was bound cold again.
 */
internal class NavPageProvider<R : Any>(
    private val pageIdOf: (R) -> String,
    private val floorOf: (R) -> Long?,
    private val publish: (R, NexusPage) -> NexusSdkResult,
    private val state: () -> NavPageState,
    private val text: () -> NavPageText,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
) {
    private var revision = 0L
    private var current: R? = null
    private var visible = false
    private var lastPublishAt: Long? = null
    private var dirty = false

    fun onRequest(request: R): NexusPage {
        val pageId = pageIdOf(request)
        val page = NavPages.page(pageId, nextRevision(floorOf(request)), state(), text())
        if (pageId == NavPages.MAIN) {
            current = request
            dirty = false
        }
        return page
    }

    fun onVisibility(pageId: String, isVisible: Boolean) {
        if (pageId != NavPages.MAIN || current == null) return
        visible = isVisible
        if (!visible) {
            cancelScheduled()
        } else if (dirty) {
            guidanceChanged()
        }
    }

    fun onClosed(pageId: String) {
        if (pageId != NavPages.MAIN) return
        forget()
    }

    /** The route changed: publish now, or once the half-second since the last revision has passed. */
    fun guidanceChanged() {
        current ?: return
        dirty = true
        if (!visible) return
        val last = lastPublishAt
        val wait = if (last == null) 0L else (last + MIN_INTERVAL_MS - now()).coerceAtLeast(0)
        if (wait > 0) {
            cancelScheduled()
            schedule(wait) { guidanceChanged() }
            return
        }
        val request = current ?: return
        when (publish(request, NavPages.page(NavPages.MAIN, nextRevision(null), state(), text()))) {
            NexusSdkResult.SENT -> {
                dirty = false
                lastPublishAt = now()
            }
            // Refused: the page is covered, closed or out of lease; the next request answers afresh.
            else -> Unit
        }
    }

    /** Link, registration or service gone: nothing more is sent for the old request. */
    fun forget() {
        cancelScheduled()
        current = null
        visible = false
        dirty = false
    }

    private fun nextRevision(floor: Long?): Long {
        // An equal revision would keep the glasses' old body; only identical content may reuse it.
        revision = maxOf(revision + 1, (floor ?: -1) + 1)
        return revision
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L
    }
}
