package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionMode
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageTarget

/** One task and the state the wearer asked it to have, as a page action id carries them. */
internal data class SetCheckedRequest(val taskId: String, val checked: Boolean)

/**
 * The sample's two pages. `hello:main` is where the session's Applications row lands: the list's
 * summary and the labelled way into the demos. `hello:todo` is the list itself, the activity's
 * destination: one row per task, in the order the user made them, each targeting an action that
 * names the task and the state it should end up in.
 *
 * With the launcher bundle the rows carry a checkbox and their actions the `setChecked` type, so
 * the user may let them run on one tap. Without it they are the base template's plain rows that
 * spell out the state, and every action is confirmed.
 */
internal object TodoPages {
    const val MAIN = "hello:main"
    const val TODO = "hello:todo"
    const val OPEN_DEMOS = "open-demos"

    private const val SET_PREFIX = "set:"
    private const val MAIN_TITLE = "Hello Nexus"
    private const val TODO_ROW = "todo"

    fun actionId(taskId: String, checked: Boolean): String = "$SET_PREFIX$taskId:${if (checked) 1 else 0}"

    fun parseAction(actionId: String): SetCheckedRequest? {
        if (!actionId.startsWith(SET_PREFIX)) return null
        val checked = when (actionId.substringAfterLast(':')) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val taskId = actionId.removePrefix(SET_PREFIX).substringBeforeLast(':')
        return SetCheckedRequest(taskId, checked).takeIf { taskId.isNotEmpty() }
    }

    fun page(pageId: String, list: TodoList, extended: Boolean): NexusPage = when (pageId) {
        MAIN -> main(list)
        TODO -> todo(list, extended)
        else -> NexusPage.Unavailable
    }

    fun todo(list: TodoList, extended: Boolean): NexusPage.Content = NexusPage.Content(
        revision = list.revision,
        title = list.title,
        body = PageBody.SelectableList(
            items = list.tasks.map { task ->
                PageRow(
                    id = task.id,
                    label = task.title,
                    sub = if (extended) null else if (task.checked) "done · tap to uncheck" else "to do · tap to check",
                    target = PageTarget.Action(actionId(task.id, !task.checked)),
                    checked = if (extended) task.checked else null,
                )
            },
            emptyText = "No tasks. Add some on the phone.",
        ),
        actions = list.tasks.map { task ->
            val desired = !task.checked
            NexusPageActionSpec(
                id = actionId(task.id, desired),
                // The confirmation names the task and the state it will end up in.
                label = clip("${if (desired) "Check" else "Uncheck"} ${task.title}", MAX_ACTION_LABEL),
                kind = NexusPageActionKind.PLUGIN,
                typed = if (extended) NexusActionType.SetChecked(desired) else null,
            )
        },
        live = true,
    )

    fun main(list: TodoList): NexusPage.Content = NexusPage.Content(
        revision = list.revision,
        title = MAIN_TITLE,
        body = PageBody.SelectableList(
            items = listOf(
                PageRow(TODO_ROW, list.title, sub = progress(list), target = PageTarget.Page(TODO)),
                PageRow(OPEN_DEMOS, "Open the demos", sub = "exclusive surface", target = PageTarget.Action(OPEN_DEMOS)),
            ),
        ),
        actions = listOf(NexusPageActionSpec(OPEN_DEMOS, "Open the demos", NexusPageActionKind.IMMERSION, confirm = false)),
        live = true,
    )

    private fun progress(list: TodoList): String = when {
        list.tasks.isEmpty() -> "no tasks"
        list.remaining == 0 -> "all done"
        else -> "${list.remaining} of ${list.tasks.size} left"
    }

    private const val MAX_ACTION_LABEL = 24

    private fun clip(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take(limit - 1).trimEnd() + "…"
}

/** A page frame of the one glasses session. */
internal data class TodoFrame(val generation: Long, val index: Int)

/**
 * Serves [TodoPages] and their actions from [store], and keeps a visible page current with live
 * revisions when the list changes elsewhere (the phone, or another page). A page's revision is
 * the list's own revision, so the revision an action was decided on says exactly which state of
 * the list the wearer saw.
 *
 * An action is applied only as the explicit state it names, for the task it names, on the list
 * revision it was shown at; anything else is stale or rejected and changes nothing. Success is
 * reported only after the store took it, with the list at its new revision as the replacement.
 * An invocation is handled once: if its result was lost, the same invocation arriving again
 * never applies a second time.
 *
 * Main thread only, like the page callbacks.
 */
internal class TodoPageProvider<R : Any>(
    private val store: () -> TodoStore,
    private val extended: () -> Boolean,
    private val pageIdOf: (R) -> String,
    private val frameOf: (R) -> TodoFrame,
    private val floorOf: (R) -> Long?,
    private val publish: (R, NexusPage) -> NexusSdkResult,
    /** The list changed through a page; the rest of the plugin follows it. */
    private val changed: () -> Unit,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
) {
    private class Frame<R>(val pageId: String, var request: R, var visible: Boolean, var shownRevision: Long)

    private val frames = linkedMapOf<TodoFrame, Frame<R>>()
    private val handledInvocations = ArrayDeque<String>()
    private var lastPublishAt: Long? = null

    fun onRequest(request: R): NexusPage {
        val pageId = pageIdOf(request)
        if (pageId != TodoPages.MAIN && pageId != TodoPages.TODO) return NexusPage.Unavailable
        val key = frameOf(request)
        frames.keys.filter { it.generation != key.generation }.forEach(frames::remove)
        val list = store().listAbove(floorOf(request))
        // A refresh keeps the frame's visibility; its lease did not end.
        val existing = frames[key]?.takeIf { it.pageId == pageId }
        if (existing != null) {
            existing.request = request
            existing.shownRevision = list.revision
        } else {
            frames.remove(key)
            frames[key] = Frame(pageId, request, visible = false, shownRevision = list.revision)
            while (frames.size > MAX_FRAMES) frames.remove(frames.keys.first())
        }
        return TodoPages.page(pageId, list, extended())
    }

    fun onVisibility(frame: TodoFrame, pageId: String, visible: Boolean) {
        val current = frames[frame]?.takeIf { it.pageId == pageId } ?: return
        current.visible = visible
        if (visible) listChanged()
    }

    fun onClosed(frame: TodoFrame, pageId: String) {
        if (frames[frame]?.pageId == pageId) frames.remove(frame)
    }

    fun onAction(
        frame: TodoFrame,
        pageId: String,
        revision: Long,
        actionId: String,
        invocationId: String,
        mode: NexusPageActionMode,
        typed: NexusActionType?,
    ): NexusPageResult {
        if (!rememberInvocation(invocationId)) return NexusPageResult.rejected("Already handled")
        if (pageId != TodoPages.TODO || mode == NexusPageActionMode.COMPOSE) return NexusPageResult.rejected()
        val request = TodoPages.parseAction(actionId) ?: return NexusPageResult.rejected()
        // The type this plugin declared must agree with the state the id names; only a typed
        // action can arrive on one tap.
        if (typed != null && typed != NexusActionType.SetChecked(request.checked)) return NexusPageResult.rejected()
        if (mode == NexusPageActionMode.QUICK && typed == null) return NexusPageResult.rejected()
        return when (val outcome = store().setChecked(request.taskId, request.checked, revision)) {
            SetCheckedOutcome.Missing -> NexusPageResult.stale("That task was removed")
            SetCheckedOutcome.Stale -> NexusPageResult.stale("The list changed")
            is SetCheckedOutcome.Done -> {
                frames[frame]?.takeIf { it.pageId == pageId }?.shownRevision = outcome.list.revision
                changed()
                NexusPageResult.done(
                    message = if (request.checked) "Checked" else "Unchecked",
                    replacement = TodoPages.todo(outcome.list, extended()),
                )
            }
        }
    }

    /**
     * The list changed: every visible page still showing an older revision gets the new one,
     * at most two revisions a second. A covered page catches up when it is shown again.
     */
    fun listChanged() {
        val list = store().list()
        val behind = frames.values.filter { it.visible && it.shownRevision < list.revision }
        if (behind.isEmpty()) return
        val last = lastPublishAt
        val wait = if (last == null) 0L else (last + MIN_INTERVAL_MS - now()).coerceAtLeast(0L)
        if (wait > 0) {
            cancelScheduled()
            schedule(wait) { listChanged() }
            return
        }
        val extended = extended()
        behind.forEach { frame ->
            // Refused means covered, closed or out of lease: the next request answers afresh.
            if (publish(frame.request, TodoPages.page(frame.pageId, list, extended)) == NexusSdkResult.SENT) {
                frame.shownRevision = list.revision
                lastPublishAt = now()
            }
        }
    }

    /** The registration or the link is gone: nothing more is sent for the old requests. */
    fun forget() {
        cancelScheduled()
        frames.clear()
    }

    private fun rememberInvocation(id: String): Boolean {
        if (id in handledInvocations) return false
        handledInvocations.addLast(id)
        while (handledInvocations.size > MAX_INVOCATIONS) handledInvocations.removeFirst()
        return true
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L
        private const val MAX_FRAMES = 6
        private const val MAX_INVOCATIONS = 64
    }
}
