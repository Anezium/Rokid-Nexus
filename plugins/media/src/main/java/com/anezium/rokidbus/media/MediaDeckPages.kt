package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckControlOutcome
import com.anezium.rokidbus.media.session.MediaDeckMonitorHub
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaDeckStateListener
import com.anezium.rokidbus.media.session.MediaDeckSubscriber
import com.anezium.rokidbus.media.session.MediaDeckTarget
import com.anezium.rokidbus.media.session.MediaPlaybackPhase
import com.anezium.rokidbus.shared.MediaPlaybackState
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract

/**
 * Media Deck's page, `media:main`: what plays now and its three transport controls, each the
 * explicit command for the state shown. With the launcher bundle it is a `media` body whose
 * controls the hub draws as one strip, and the hub adds its own Open app; without it, the base
 * summary template with the controls as rows and a labelled row to the full app, which otherwise
 * could not be reached from a page-capable launcher.
 */
internal object MediaDeckPages {
    const val MAIN = "media:main"
    const val TITLE = "Media Deck"
    const val OPEN_APP = "open-app"

    /**
     * The media body's middle control. One id for Play and Pause keeps the hub's selection on the
     * control when the state flips; the explicit desired state is its typed value at the revision
     * the wearer saw, never a toggle.
     */
    const val PLAY_PAUSE = "playpause"

    fun actionIdOf(command: MediaDeckCommand): String = when (command) {
        MediaDeckCommand.PREVIOUS -> "previous"
        MediaDeckCommand.PLAY -> "play"
        MediaDeckCommand.PAUSE -> "pause"
        MediaDeckCommand.NEXT -> "next"
    }

    fun commandOf(actionId: String): MediaDeckCommand? = MediaDeckCommand.entries.firstOrNull { actionIdOf(it) == actionId }

    /** A control id on the launcher's media body: Play and Pause share [PLAY_PAUSE]. */
    fun controlIdOf(command: MediaDeckCommand): String = when (command) {
        MediaDeckCommand.PLAY, MediaDeckCommand.PAUSE -> PLAY_PAUSE
        else -> actionIdOf(command)
    }

    fun page(pageId: String, revision: Long, state: MediaDeckState, extended: Boolean): NexusPage = when {
        pageId != MAIN -> NexusPage.Unavailable
        extended -> mediaPage(revision, state)
        else -> basePage(revision, state)
    }

    /** The commands a page for [state] offers, in swipe order; none without a session. */
    fun commandsFor(state: MediaDeckState): List<MediaDeckCommand> {
        val active = state as? MediaDeckState.Active ?: return emptyList()
        return listOf(MediaDeckCommand.PREVIOUS, MediaDeckCommand.desiredFor(active.phase), MediaDeckCommand.NEXT)
    }

    /** Equal for two states a page draws the same; a new revision is published only when it moves. */
    fun contentKey(state: MediaDeckState): String = when (state) {
        is MediaDeckState.Active -> "active:${state.identity.value}:${state.track.key}:${state.phase}"
        is MediaDeckState.AccessRequired -> "access:${state.revoked}"
        is MediaDeckState.Unavailable -> "unavailable"
        is MediaDeckState.NoSession, is MediaDeckState.Unknown -> "none"
    }

    private fun mediaPage(revision: Long, state: MediaDeckState): NexusPage.Content {
        val active = state as? MediaDeckState.Active
        if (active == null) {
            val idle = idleCopy(state)
            return NexusPage.Content(
                revision,
                TITLE,
                PageBody.Media(title = idle.title, state = idle.playback, status = idle.status),
                live = true,
            )
        }
        val commands = commandsFor(active)
        val track = active.track
        return NexusPage.Content(
            revision = revision,
            title = TITLE,
            body = PageBody.Media(
                title = MediaText.clip(track.title, PageTemplateContract.MAX_MEDIA_TEXT_CHARS),
                state = playbackOf(active.phase),
                artist = optional(track.artist, PageTemplateContract.MAX_MEDIA_TEXT_CHARS),
                album = optional(track.album, PageTemplateContract.MAX_MEDIA_TEXT_CHARS),
                source = optional(track.sourceLabel, PageTemplateContract.MAX_SOURCE_CHARS),
                controls = commands.map(::controlIdOf),
                defaultControl = PLAY_PAUSE,
            ),
            actions = commands.map { actionSpec(it, controlIdOf(it)) },
            live = true,
        )
    }

    private fun basePage(revision: Long, state: MediaDeckState): NexusPage.Content {
        val openApp = PageRow(OPEN_APP, "Open the full app", sub = "exclusive surface", target = PageTarget.Action(OPEN_APP))
        val openAppAction = NexusPageActionSpec(OPEN_APP, "Open full app", NexusPageActionKind.IMMERSION, confirm = false)
        val active = state as? MediaDeckState.Active
        if (active == null) {
            val idle = idleCopy(state)
            return NexusPage.Content(
                revision,
                TITLE,
                PageBody.Summary(primary = idle.title, text = idle.status, items = listOf(openApp)),
                actions = listOf(openAppAction),
                live = true,
            )
        }
        val commands = commandsFor(active)
        val track = active.track
        val facts = listOfNotNull(
            optional(track.sourceLabel, PageTemplateContract.MAX_DETAIL_CHARS)?.let { PageFact("source", "Source", it) },
            optional(track.album, PageTemplateContract.MAX_DETAIL_CHARS)?.let { PageFact("album", "Album", it) },
            PageFact("state", "State", phaseLabel(active.phase)),
        )
        val rows = commands.map { command ->
            PageRow(actionIdOf(command), MediaDeckActivityModel.labelOf(command), target = PageTarget.Action(actionIdOf(command)))
        } + openApp
        return NexusPage.Content(
            revision = revision,
            title = TITLE,
            body = PageBody.Summary(
                primary = MediaText.clip(track.title, PageTemplateContract.MAX_PRIMARY_CHARS),
                secondary = optional(track.artist, PageTemplateContract.MAX_DETAIL_CHARS),
                facts = facts,
                items = rows,
            ),
            actions = commands.map { actionSpec(it, actionIdOf(it)) } + openAppAction,
            live = true,
        )
    }

    private fun actionSpec(command: MediaDeckCommand, id: String) = NexusPageActionSpec(
        id = id,
        label = MediaDeckActivityModel.labelOf(command),
        kind = NexusPageActionKind.PLUGIN,
        typed = command.typed,
    )

    private data class IdleCopy(val title: String, val playback: MediaPlaybackState, val status: String)

    private fun idleCopy(state: MediaDeckState): IdleCopy = when (state) {
        is MediaDeckState.AccessRequired -> if (state.revoked) {
            IdleCopy("Media access was turned off", MediaPlaybackState.UNAVAILABLE, "Allow it again in Media Deck on the phone")
        } else {
            IdleCopy("Media access needed", MediaPlaybackState.UNAVAILABLE, "Allow notification access in Media Deck on the phone")
        }
        is MediaDeckState.Unavailable ->
            IdleCopy("Media control unavailable", MediaPlaybackState.UNAVAILABLE, "Check Media Deck on the phone")
        is MediaDeckState.NoSession, is MediaDeckState.Unknown, is MediaDeckState.Active ->
            IdleCopy("Nothing playing", MediaPlaybackState.NONE, "Start playback on the phone")
    }

    private fun playbackOf(phase: MediaPlaybackPhase): MediaPlaybackState = when (phase) {
        MediaPlaybackPhase.PLAYING -> MediaPlaybackState.PLAYING
        MediaPlaybackPhase.PAUSED -> MediaPlaybackState.PAUSED
        MediaPlaybackPhase.STOPPED -> MediaPlaybackState.STOPPED
    }

    private fun phaseLabel(phase: MediaPlaybackPhase): String = when (phase) {
        MediaPlaybackPhase.PLAYING -> "Playing"
        MediaPlaybackPhase.PAUSED -> "Paused"
        MediaPlaybackPhase.STOPPED -> "Stopped"
    }

    private fun optional(value: String, limit: Int): String? = MediaText.clip(value, limit).takeIf(String::isNotBlank)
}

/** The wording a page or activity result carries for an outcome; never a success it did not check. */
internal fun MediaDeckControlOutcome.toPageResult(): NexusPageResult = when (status) {
    MediaDeckControlOutcome.Status.DONE -> NexusPageResult.done(message)
    MediaDeckControlOutcome.Status.STALE -> NexusPageResult.stale(message)
    MediaDeckControlOutcome.Status.REJECTED -> NexusPageResult.rejected(message)
}

/**
 * Serves `media:main` from the shared monitor and keeps it live while the wearer looks at it:
 * the page subscribes while it is current and visible, publishes only when what it draws changes,
 * at most two revisions a second, and lets go on a cover, a close, a lapsed lease or a loss.
 * Revisions only go up, and always above the floor the glasses retain. Each revision remembers
 * the session it showed, so an action runs only on that session at that revision.
 */
internal class MediaDeckPageProvider<R : Any>(
    private val hub: MediaDeckMonitorHub,
    private val pageIdOf: (R) -> String,
    private val floorOf: (R) -> Long?,
    private val publish: (R, NexusPage) -> NexusSdkResult,
    private val extended: () -> Boolean,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> () -> Unit,
) {
    private var revision = 0L
    private var current: R? = null
    private var visible = false
    private var watching = false
    private var answering = false
    private var dirty = false
    private var shownKey: String? = null
    private var shownRevision: Long? = null
    private var lastPublishAt: Long? = null
    private var cancelPublish: (() -> Unit)? = null
    private var cancelLease: (() -> Unit)? = null
    private val shownTargets = LinkedHashMap<Long, MediaDeckTarget?>()
    private val listener = MediaDeckStateListener { _, _, changed -> if (changed) stateChanged() }

    val isWatching: Boolean get() = watching

    fun onRequest(request: R): NexusPage {
        val pageId = pageIdOf(request)
        if (pageId != MediaDeckPages.MAIN) return NexusPage.Unavailable
        current = request
        dirty = false
        // A fresh look at the sessions, answered from it at once; nothing is published meanwhile.
        answering = true
        try {
            watch()
        } finally {
            answering = false
        }
        if (!visible) {
            // Watched until the page's visibility says otherwise, or for one lease if it never comes.
            cancelLease?.invoke()
            cancelLease = schedule(PENDING_LEASE_MS) {
                cancelLease = null
                if (!visible) unwatch()
            }
        }
        return show(nextRevision(floorOf(request)))
    }

    fun onAction(pageId: String, revision: Long, actionId: String, typed: NexusActionType?): NexusPageResult {
        if (pageId != MediaDeckPages.MAIN) return MediaDeckControlOutcome.NOT_OFFERED.toPageResult()
        val command = if (actionId == MediaDeckPages.PLAY_PAUSE) {
            // Only the typed value says which of the two the wearer saw; without it nothing runs.
            typed?.let(MediaDeckCommand::of)?.takeIf { it == MediaDeckCommand.PLAY || it == MediaDeckCommand.PAUSE }
                ?: return MediaDeckControlOutcome.NOT_OFFERED.toPageResult()
        } else {
            MediaDeckPages.commandOf(actionId) ?: return MediaDeckControlOutcome.NOT_OFFERED.toPageResult()
        }
        if (typed != null && MediaDeckCommand.of(typed) != command) return MediaDeckControlOutcome.NOT_OFFERED.toPageResult()
        if (!shownTargets.containsKey(revision)) return MediaDeckControlOutcome.STALE_REVISION.toPageResult()
        return hub.control(shownTargets[revision], command).toPageResult()
    }

    fun onVisibility(pageId: String, isVisible: Boolean, leaseMs: Long?) {
        if (pageId != MediaDeckPages.MAIN || current == null) return
        cancelLease?.invoke()
        cancelLease = null
        visible = isVisible && leaseMs != null
        if (!visible) {
            cancelPublish?.invoke()
            cancelPublish = null
            unwatch()
            return
        }
        // The hub stops renewing a page it no longer shows; the watch ends with the lease.
        cancelLease = schedule(leaseMs ?: 0L) {
            cancelLease = null
            visible = false
            unwatch()
        }
        watch()
        stateChanged()
    }

    fun onClosed(pageId: String) {
        if (pageId == MediaDeckPages.MAIN) forget()
    }

    /** Link, registration or service gone: nothing more is sent for the old request. */
    fun forget() {
        cancelPublish?.invoke()
        cancelPublish = null
        cancelLease?.invoke()
        cancelLease = null
        current = null
        visible = false
        dirty = false
        shownKey = null
        shownRevision = null
        unwatch()
    }

    private fun stateChanged() {
        if (answering) return
        val request = current ?: return
        if (MediaDeckPages.contentKey(hub.state) == shownKey) {
            // The same session, item and phase under a new state revision (the monitor restarted):
            // what the wearer sees is still true, so its actions now bind to the new revision.
            shownRevision?.let { shown -> shownTargets[shown] = MediaDeckTarget.of(hub.state) }
            return
        }
        dirty = true
        if (!visible) return
        val last = lastPublishAt
        val wait = if (last == null) 0L else (last + MIN_INTERVAL_MS - now()).coerceAtLeast(0L)
        if (wait > 0L) {
            if (cancelPublish == null) {
                cancelPublish = schedule(wait) {
                    cancelPublish = null
                    stateChanged()
                }
            }
            return
        }
        val nextRevision = nextRevision(null)
        val state = hub.state
        val page = MediaDeckPages.page(MediaDeckPages.MAIN, nextRevision, state, extended())
        if (publish(request, page) == NexusSdkResult.SENT) {
            remember(nextRevision, state)
            dirty = false
            lastPublishAt = now()
        }
        // Refused: covered, closed or out of lease; the next request answers afresh.
    }

    private fun show(pageRevision: Long): NexusPage {
        val state = hub.state
        remember(pageRevision, state)
        return MediaDeckPages.page(MediaDeckPages.MAIN, pageRevision, state, extended())
    }

    private fun remember(pageRevision: Long, state: MediaDeckState) {
        shownKey = MediaDeckPages.contentKey(state)
        shownRevision = pageRevision
        shownTargets[pageRevision] = MediaDeckTarget.of(state)
        while (shownTargets.size > MAX_SHOWN_REVISIONS) shownTargets.remove(shownTargets.keys.first())
    }

    private fun watch() {
        if (watching) return
        watching = true
        hub.subscribe(MediaDeckSubscriber.PAGE, listener)
    }

    private fun unwatch() {
        if (!watching) return
        watching = false
        hub.unsubscribe(MediaDeckSubscriber.PAGE)
    }

    private fun nextRevision(floor: Long?): Long {
        // An equal revision would keep the glasses' old body; only identical content may reuse it.
        revision = maxOf(revision + 1, (floor ?: -1) + 1)
        return revision
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L

        /** The hub's own page lease: a page whose visibility never came is not watched longer. */
        const val PENDING_LEASE_MS = 120_000L
        private const val MAX_SHOWN_REVISIONS = 16
    }
}
