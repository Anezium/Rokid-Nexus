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
    /** A walking journey the wearer started runs; it owns the route whatever access or switches say. */
    val journeyActive: Boolean = false,
    /** That journey's last state, once its route is planned. */
    val journey: NavRouteSnapshot? = null,
    val journeyDestination: String? = null,
    val journeyMessage: String? = null,
    /** Both hubs negotiated the launcher bundle, so `nav:main` is a route page. */
    val extended: Boolean = false,
    /** Whether the journey's street map is loading or unavailable when its maps draw no street. */
    val basemap: BasemapState = BasemapState.NONE,
)

/** The plugin's own words for the states without a route. */
internal data class NavPageText(
    val noAccess: String,
    val off: String,
    val idle: String,
    val arrived: String = "Arrived",
)

/**
 * Navigation's pages. Without the launcher bundle `nav:main` is a summary of the current guidance:
 * the manoeuvre, its quantity, the street, the ETA when the app gave one, the source and when it
 * was received. A value the app did not post is left out, never estimated. `nav:commands` holds
 * only the labelled full app; it never offers map or route controls. With the bundle `nav:main`
 * is a route page instead ([NavRoutePage]), whose views and sizes are the hub's own commands.
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
        MAIN -> if (state.extended) {
            NavRoutePage.content(revision, state, text)
        } else {
            NexusPage.Content(revision, TITLE, main(state, text), live = true)
        }
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
        if (state.journeyActive) {
            // The wearer's own journey needs neither notification access nor the app switches.
            state.guidance ?: return PageBody.Summary(
                primary = "Walking route",
                secondary = state.journeyDestination?.let { fit(it, MAX_DETAIL) },
                text = fit(state.journeyMessage ?: RouteQuality.WAITING.message, MAX_TEXT),
                items = commands,
            )
        } else {
            // Checked first: without the listener the last route is not current.
            if (!state.listenerConnected) return PageBody.Summary(primary = "No notification access", text = fit(text.noAccess, MAX_TEXT), items = commands)
            if (!state.enabled) return PageBody.Summary(primary = "Navigation is off", text = fit(text.off, MAX_TEXT), items = commands)
        }
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
    /** The running journey's epoch, or null; recorded with each revision of the main page. */
    private val journey: () -> Long? = { null },
    /** The main page came on screen or left it; its maps need street tiles only while it is there. */
    private val visibilityChanged: (Boolean) -> Unit = {},
) {
    private var revision = 0L
    private val shownJourneys = LinkedHashMap<Long, Long?>()
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
            (page as? NexusPage.Content)?.let { remember(it.revision) }
        }
        return page
    }

    fun onVisibility(pageId: String, isVisible: Boolean) {
        if (pageId != NavPages.MAIN || current == null) return
        if (visible != isVisible) visibilityChanged(isVisible)
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
        val page = NavPages.page(NavPages.MAIN, nextRevision(null), state(), text())
        when (publish(request, page)) {
            NexusSdkResult.SENT -> {
                dirty = false
                lastPublishAt = now()
                (page as? NexusPage.Content)?.let { remember(it.revision) }
            }
            // Refused: the page is covered, closed or out of lease; the next request answers afresh.
            else -> Unit
        }
    }

    /** Link, registration or service gone: nothing more is sent for the old request. */
    fun forget() {
        cancelScheduled()
        if (visible) visibilityChanged(false)
        current = null
        visible = false
        dirty = false
    }

    /**
     * Whether the main page the wearer confirmed on, at [revision], showed the journey that runs
     * now. An unknown revision, a page without a journey, or an earlier journey is not.
     */
    fun showedCurrentJourney(revision: Long): Boolean {
        val shown = shownJourneys[revision] ?: return false
        return shown == journey()
    }

    private fun remember(revision: Long) {
        shownJourneys[revision] = journey()
        while (shownJourneys.size > MAX_REMEMBERED_REVISIONS) shownJourneys.remove(shownJourneys.keys.first())
    }

    private fun nextRevision(floor: Long?): Long {
        // An equal revision would keep the glasses' old body; only identical content may reuse it.
        revision = maxOf(revision + 1, (floor ?: -1) + 1)
        return revision
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L

        /** Enough revisions for a confirmation in flight; older ones read as stale. */
        const val MAX_REMEMBERED_REVISIONS = 16
    }
}
