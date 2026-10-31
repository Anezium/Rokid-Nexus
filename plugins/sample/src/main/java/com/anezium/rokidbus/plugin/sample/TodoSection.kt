package com.anezium.rokidbus.plugin.sample

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The todo example on the phone: the list, edited here, and the "Show in activities" switch
 * with where it stands. A check from here is an explicit desired state on the revision the row
 * showed, exactly like one from the glasses: a list that changed meanwhile is redrawn, never
 * toggled blindly.
 */
internal class TodoSection(private val activity: Activity) {
    private val tasks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val field = NexusUi.field(activity, "Add a task").apply {
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { done -> if (done) add() }
        }
    }
    private val toggle = NexusUi.switch(activity)
    private val status = NexusUi.statusLine(activity)
    private val listener: () -> Unit = { render() }

    private val store: TodoStore get() = TodoControl.store(activity)

    fun addTo(column: LinearLayout) {
        column.addView(NexusUi.sectionRow(activity, "Todo example"), NexusUi.block())
        column.addView(BusTheme.gap(activity, 10))
        column.addView(
            NexusUi.cardBody(
                activity,
                "A list the glasses open inside Nexus as a page. Tap a task there to check it; " +
                    "approve the checklist family in Rokid Nexus to skip the confirmation.",
            ),
            NexusUi.block(),
        )
        column.addView(BusTheme.gap(activity, 10))
        column.addView(tasks, NexusUi.block())
        column.addView(BusTheme.gap(activity, 8))
        column.addView(addRow(), NexusUi.block())
        column.addView(BusTheme.gap(activity, 16))
        column.addView(
            NexusUi.activitySettingRow(
                activity,
                toggle,
                "Open this list from Nexus anytime. Turn off to remove it; your items stay saved.",
            ),
            NexusUi.block(),
        )
        column.addView(BusTheme.gap(activity, 8))
        column.addView(NexusUi.activityDisplaySettingsRow(activity, "hello"), NexusUi.block())
        column.addView(status, NexusUi.block())
        // Drawing the saved state back into the switch is not a change of it.
        toggle.setOnCheckedChangeListener { _, on ->
            if (on != TodoControl.showInActivities(activity)) TodoControl.setShowInActivities(activity, on)
        }
    }

    fun start() {
        TodoControl.addListener(listener)
        render()
        TodoControl.refresh(activity)
    }

    fun stop() {
        TodoControl.removeListener(listener)
    }

    private fun render() {
        val list = store.list()
        tasks.removeAllViews()
        if (list.tasks.isEmpty()) tasks.addView(NexusUi.rowSub(activity, "No tasks yet."))
        list.tasks.forEachIndexed { index, task ->
            if (index > 0) tasks.addView(BusTheme.gap(activity, 8))
            tasks.addView(taskRow(list, task), NexusUi.block())
        }
        toggle.isChecked = TodoControl.showInActivities(activity)
        status.text = statusText(TodoControl.status(activity))
    }

    private fun taskRow(list: TodoList, task: TodoTask): LinearLayout = NexusUi.pressableCard(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(activity, task.title))
                addView(NexusUi.rowSub(activity, if (task.checked) "Done · tap to uncheck" else "To do · tap to check"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.textButton(activity, "Remove", danger = true).apply { setOnClickListener { remove(task) } })
        setOnClickListener { setChecked(list, task) }
    }

    private fun addRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(NexusUi.textButton(activity, "Add").apply { setOnClickListener { add() } })
    }

    private fun setChecked(shown: TodoList, task: TodoTask) {
        when (store.setChecked(task.id, !task.checked, shown.revision)) {
            is SetCheckedOutcome.Done -> TodoControl.changed(activity)
            SetCheckedOutcome.Stale, SetCheckedOutcome.Missing -> render()
        }
    }

    private fun add() {
        val text = field.text?.toString().orEmpty()
        if (store.add(text) == null) {
            status.text = if (TodoStore.cleanTitle(text) == null) "Type a task first." else "The list is full."
            return
        }
        field.setText("")
        TodoControl.changed(activity)
    }

    private fun remove(task: TodoTask) {
        if (store.remove(task.id) != null) TodoControl.changed(activity) else render()
    }

    private fun statusText(status: TodoActivityStatus): String = when (status) {
        TodoActivityStatus.OFF -> "Off: the list is not among the glasses' activities."
        TodoActivityStatus.WAITING -> "On: waiting for Rokid Nexus…"
        TodoActivityStatus.NOT_APPROVED -> "Approve Hello Nexus in Rokid Nexus first."
        TodoActivityStatus.SHOWN -> "Shown. Nexus keeps it while this plugin sleeps."
        TodoActivityStatus.UNAVAILABLE -> "Needs the Surfaces grant and glasses that show activities."
        TodoActivityStatus.NEEDS_NEWER_NEXUS ->
            "Needs Rokid Nexus with the new launcher on phone and glasses, connected once."
        TodoActivityStatus.DISABLED_IN_NEXUS -> "Turned off for this plugin in Rokid Nexus."
        TodoActivityStatus.REPLACED -> "Nexus made room for another activity; the next change brings it back."
        TodoActivityStatus.PAUSED_FOR_DEMO -> "Paused while the demo route runs."
    }
}
