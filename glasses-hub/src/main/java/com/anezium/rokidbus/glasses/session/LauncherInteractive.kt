package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.QuickActionPolicy

/**
 * Whether a tap uses quick wire mode (plan 028a §3.2). The glasses check is never the only gate:
 * the phone checks owner, revision, type and policy again at invocation.
 */
internal fun interface QuickGate {
    fun allows(ownerPluginId: String, typed: PageActionType?, hubQuick: Boolean): Boolean

    companion object {
        /** No action is ever quick: what a reducer or resolver built without the host's gate gets. */
        val NEVER = QuickGate { _, _, _ -> false }
    }
}

/**
 * The production gate: the launcher bundle negotiated, the phone's `hubQuick` stamp on the
 * action, a quick-eligible type, and the latest `/core/policy/quick-actions` approving its family
 * for that owner. The fresh-contact rule is the reducer's, on top of this.
 */
internal class LauncherQuickGate(
    private val negotiatedVersion: () -> Int,
    private val policy: () -> QuickActionPolicy,
) : QuickGate {
    override fun allows(ownerPluginId: String, typed: PageActionType?, hubQuick: Boolean): Boolean {
        if (!LauncherInteractiveContract.isActive(negotiatedVersion()) || !hubQuick) return false
        val family = typed?.family ?: return false
        return policy().approves(ownerPluginId, family)
    }
}

/**
 * The glasses' copy of the user's quick-action approvals. A policy replaces the one held only if
 * its revision is newer, so a reordered message never brings back a revoked approval. Link loss
 * resets it to no approval at all until the phone sends it again on link-up.
 */
internal class QuickPolicyState {
    private var current: QuickActionPolicy = QuickActionPolicy.NONE
    private var lastRevision: Long? = null

    val policy: QuickActionPolicy get() = current

    /** True when [policy] was taken. */
    fun apply(policy: QuickActionPolicy): Boolean {
        val last = lastRevision
        if (last != null && policy.revision <= last) return false
        current = policy
        lastRevision = policy.revision
        return true
    }

    /** True when something was approved before. */
    fun reset(): Boolean {
        val had = current.approvals.isNotEmpty()
        current = QuickActionPolicy.NONE
        lastRevision = null
        return had
    }
}

/** One slot of an activity card's command strip: a plugin command, or the hub's Open app. */
internal data class CommandSlotModel(
    val key: String,
    val label: String,
    val glyph: String,
    /** The plugin command, or null for the hub's Open app slot. */
    val action: ActivityAction?,
    /** False for an offline entry's commands: they are drawn, never run. */
    val enabled: Boolean,
    val destination: PageDestination? = null,
) {
    val hub: Boolean get() = action == null
}

/** The rules of an activity card's commands, shared by the reducer and the screens. */
internal object ActivityCommands {
    /** A space, which no wire id contains, keeps the hub slot out of the plugin ids' namespace. */
    const val OPEN_APP_KEY = "hub open-app"
    const val OPEN_APP = "Open app"
    const val OPEN_APP_GLYPH = "grid"
    const val OPEN_PAGE_KEY = "hub open-page"
    const val OPEN_PAGE_GLYPH = "open-app"

    /**
     * How long the glasses wait for a result: the phone's own deadline plus a margin for the hop
     * back, so the phone always gives up first and a late answer is never mistaken for an
     * unconfirmed one.
     */
    const val RESULT_DEADLINE_MS = ActivitySurfaceContract.COMMAND_DEADLINE_MS + 1_500L

    /**
     * The plugin's commands, in order, then the hub's Open app when the owner's full app is in
     * the Applications catalog. An offline entry's commands are inert.
     */
    fun slots(stop: ActivityStop): List<CommandSlotModel> = buildList {
        stop.commands.forEach { add(CommandSlotModel(it.id, it.label, it.glyph, it, enabled = !stop.lastKnown)) }
        val media = stop.commands.any { it.typed?.isMedia == true }
        stop.destination?.let { destination ->
            add(CommandSlotModel(OPEN_PAGE_KEY, if (media) "Open player" else "Open page", OPEN_PAGE_GLYPH,
                null, enabled = true, destination = destination))
        }
        if (stop.launchable && stop.ownerPluginId != null && !(media && stop.destination != null)) {
            add(CommandSlotModel(OPEN_APP_KEY, OPEN_APP, OPEN_APP_GLYPH, null, enabled = true))
        }
    }

    /** Where command focus starts: the plugin's default command, never the hub slot unless alone. */
    fun initialIndex(stop: ActivityStop, slots: List<CommandSlotModel>): Int {
        val wanted = stop.defaultActionId?.takeIf { id -> stop.commands.any { it.id == id } } ?: stop.commands.firstOrNull()?.id
        return slots.indexOfFirst { it.key == wanted }.takeIf { it >= 0 } ?: 0
    }

    /** [focus] carried onto the slots an update left: same key if it is still there, else clamped. */
    fun reconcile(focus: CommandFocus, slots: List<CommandSlotModel>): CommandFocus? {
        if (slots.isEmpty()) return null
        val index = slots.indexOfFirst { it.key == focus.key }
        if (index >= 0) return focus.copy(index = index)
        val clamped = focus.index.coerceIn(0, slots.lastIndex)
        return focus.copy(key = slots[clamped].key, index = clamped)
    }

    /** The hub's final row on an activity's destination page: Open app, on a fresh tap. */
    fun openAppEntry(openApp: OpenApp): PageEntry =
        PageEntry(OPEN_APP_KEY, PageItem.Launch(openApp.pluginId, openApp.label), OPEN_APP)

    /** [openAppEntry] as the screens draw it. */
    fun openAppRow(openApp: OpenApp): TypedRow =
        TypedRow(OPEN_APP_KEY, OPEN_APP, null, true, PageItem.Launch(openApp.pluginId, openApp.label))

    /**
     * One hub rule for a page opened for a launchable owner: it ends with the hub's Open app, and
     * the owner's own immersion rows go, so its full app is one row behind one launch step. Without
     * [openApp] the page keeps the provider's rows as they are. The reducer and the screens both
     * read the page through this, so SELECT and the outline agree.
     */
    fun withOpenApp(entries: List<PageEntry>, openApp: OpenApp?): List<PageEntry> =
        if (openApp == null) entries else entries.filterNot { it.item.launches(openApp) } + openAppEntry(openApp)

    /** [withOpenApp] for the rows the screens draw. */
    fun withOpenAppRows(rows: List<TypedRow>, openApp: OpenApp?): List<TypedRow> =
        if (openApp == null) rows else rows.filterNot { it.item.launches(openApp) } + openAppRow(openApp)

    private fun PageItem.launches(openApp: OpenApp): Boolean = this is PageItem.Launch && pluginId == openApp.pluginId
}

/**
 * What the screens read about activities beside the session state: the collection as last
 * received (the Activities page draws its rows from it), and per owner the pending command and
 * the last answer.
 */
internal data class CommandFacts(
    val activities: List<ActivityStop> = emptyList(),
    val pending: Map<String, PendingCommand> = emptyMap(),
    val outcomes: Map<String, CommandOutcome> = emptyMap(),
    /** The notification history, for the Notifications page's sources, tags and ages. */
    val history: NotificationHistory = NotificationHistory(),
    /** The draft, the capture and the send in flight, for conversation pages. */
    val composer: ComposerFacts = ComposerFacts(),
    /** The map view chosen per route owner for this session. */
    val mapViews: Map<String, MapView> = emptyMap(),
) {
    fun activity(ownerPluginId: String): ActivityStop? = activities.firstOrNull { it.ownerPluginId == ownerPluginId }

    companion object {
        fun of(model: SessionModel) = CommandFacts(
            activities = model.queuedActivities + model.activities.filter { member -> model.queuedActivities.none { it.id == member.id } },
            pending = model.pendingCommands,
            outcomes = model.commandOutcomes,
            history = model.history,
            composer = ComposerFacts(model.draft, model.capture, model.micUnavailable, model.pendingSend, model.handoffs),
            mapViews = model.mapViews,
        )
    }
}

/** The latest known entry of an owner: a queued start or update is newer than a root member. */
internal fun SessionModel.latestActivity(ownerPluginId: String): ActivityStop? =
    queuedActivities.firstOrNull { it.ownerPluginId == ownerPluginId } ?: activities.firstOrNull { it.ownerPluginId == ownerPluginId }

internal fun SessionModel.latestActivityById(stopId: String): ActivityStop? =
    queuedActivities.firstOrNull { it.id == stopId } ?: activities.firstOrNull { it.id == stopId }

/**
 * The fresh-contact rule (plan 028a §5.3). An Enter that would cause an effect counts only if its
 * press began strictly after the focused target was displayed, as the host acknowledged with
 * [SessionEvent.FocusShown]. The target's identity is [tokenOf]: a new revision, a new
 * selection, a panel, a command focus or a change of what one tap does each make a new one, and
 * an acknowledgement of an older one never counts.
 */
internal object SessionFocus {
    fun tokenOf(model: SessionModel): String? {
        val prefix = "g${model.generation}/e${model.factsEpoch}"
        return when (val state = model.state) {
            SessionState.Closed, is SessionState.Opening, is SessionState.Launching -> null
            is SessionState.Root -> {
                val stop = state.stops.getOrNull(state.selected)
                val seq = stop?.activity?.seq
                "$prefix/root/${state.selected}/${stop?.id}/s$seq" + focusPart(state.focus)
            }
            is SessionState.InPage -> {
                val top = state.frames.last()
                val status = when (val status = top.status) {
                    is FrameStatus.Loading -> "loading/${status.requestId}"
                    is FrameStatus.Shown -> "shown/${status.requestId}/r${status.revision}"
                    is FrameStatus.Unavailable -> "unavailable/${status.reason}"
                }
                val commandSeq = top.commandFocus?.takeIf { it.stopId != CommandFocus.MEDIA }
                    ?.let { model.latestActivityById(it.stopId)?.seq }
                "$prefix/f${state.frames.size}/${top.pageId}/$status/${top.mode}/p${top.page}" +
                    "/${top.selected}/${top.selectedKey}/${top.tombstone != null}/epoch${top.providerEpoch}/s$commandSeq" +
                    focusPart(top.commandFocus) + panelPart(top.panel) + composerPart(model, top) +
                    top.fullMap?.let { "/map/$it" }.orEmpty()
            }
        }
    }

    /**
     * A composer focus, a capture, and what Send would hand off: a new draft, a new draft state
     * or a send in flight makes a new target, so Send needs a press begun after it was drawn.
     */
    private fun composerPart(model: SessionModel, top: Frame): String {
        val focus = top.composerFocus?.let { "/compose/${it.key}/${it.index}" }.orEmpty()
        val capture = model.capture?.let { "/capture/${it.captureId}/${it.finishing}" }.orEmpty()
        if (focus.isEmpty() && capture.isEmpty()) return ""
        val draft = model.draft?.let { "/draft/${it.ownerPluginId}/${it.threadKey}/${it.state}/${it.reopened}/${it.text.hashCode()}" }.orEmpty()
        val send = model.pendingSend?.let { "/sending/${it.invocationId}" }.orEmpty()
        return focus + capture + draft + send + "/mic${model.micUnavailable}"
    }

    /** Whether a press that began at [pressStartMs] began after the current target was drawn. */
    fun isFresh(model: SessionModel, pressStartMs: Long): Boolean {
        val shown = model.shownFocus ?: return false
        return shown.token == tokenOf(model) && pressStartMs > shown.atMs
    }

    private fun focusPart(focus: CommandFocus?): String = focus?.let { "/cmd/${it.stopId}/${it.key}/${it.index}" }.orEmpty()

    private fun panelPart(panel: FramePanel?): String = when (panel) {
        null -> ""
        is FramePanel.Display -> "/display/${panel.ownerPluginId}/${panel.selected}"
    }
}
