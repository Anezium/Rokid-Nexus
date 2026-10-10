package com.anezium.rokidbus.plugin.sample

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One task. [id] is stable for the task's whole life and never reused for another task. */
internal data class TodoTask(val id: String, val title: String, val checked: Boolean)

/** The list as it stood at [revision]. Every change to it moves the revision up. */
internal data class TodoList(val revision: Long, val title: String, val tasks: List<TodoTask>) {
    val remaining: Int get() = tasks.count { !it.checked }

    fun task(id: String): TodoTask? = tasks.firstOrNull { it.id == id }
}

/** The outcome of [TodoStore.setChecked]. Only [Done] changed anything. */
internal sealed interface SetCheckedOutcome {
    /** The task now has the desired state; [list] is the list at its new, higher revision. */
    data class Done(val list: TodoList) : SetCheckedOutcome

    /** The list moved past the revision the request was decided on. */
    data object Stale : SetCheckedOutcome

    /** No task has that id any more. */
    data object Missing : SetCheckedOutcome
}

/** What [TodoStore] persists. */
internal data class TodoState(val revision: Long, val nextId: Long, val tasks: List<TodoTask>)

internal interface TodoStorage {
    /** The saved state, or null when there is none or it cannot be read. */
    fun read(): TodoState?

    fun write(state: TodoState)
}

/**
 * The sample's one checklist, owned by the plugin on the phone. The glasses only ever see it
 * through a page or the activity, and change it only through [setChecked]: an explicit desired
 * state for one task, decided on one revision. There is no toggle, so an answer that never
 * arrived cannot be replayed into the opposite state.
 *
 * Safe from any thread; callers tell the rest of the plugin about a change themselves.
 */
internal class TodoStore(private val storage: TodoStorage, private val wallClock: () -> Long) {
    private val lock = Any()
    private var state: TodoState = storage.read() ?: seed().also(storage::write)

    fun list(): TodoList = synchronized(lock) { state.snapshot() }

    /**
     * The list at a revision a page may answer with. [floor] is the revision the glasses already
     * retain for that page: when it is above anything this store remembers (its data was
     * cleared), the revision jumps past it, since an equal one would tell them nothing changed.
     */
    fun listAbove(floor: Long?): TodoList = synchronized(lock) {
        if (floor != null && floor > state.revision) {
            state = state.copy(revision = floor + 1)
            storage.write(state)
        }
        state.snapshot()
    }

    /**
     * Sets [taskId] to [checked], only if the list is still at [expectedRevision]. A deleted task
     * is [SetCheckedOutcome.Missing] whatever the revision; any other change since is
     * [SetCheckedOutcome.Stale]. Success always moves the revision, so whatever was decided on
     * the old one is stale from now on.
     */
    fun setChecked(taskId: String, checked: Boolean, expectedRevision: Long): SetCheckedOutcome = synchronized(lock) {
        val index = state.tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return SetCheckedOutcome.Missing
        if (state.revision != expectedRevision) return SetCheckedOutcome.Stale
        val tasks = state.tasks.toMutableList().apply { this[index] = this[index].copy(checked = checked) }
        commit(state.copy(revision = state.revision + 1, tasks = tasks))
        SetCheckedOutcome.Done(state.snapshot())
    }

    /** Appends a task; null when [title] is blank or the list is full. */
    fun add(title: String): TodoList? = synchronized(lock) {
        val clean = cleanTitle(title) ?: return null
        if (state.tasks.size >= MAX_TASKS) return null
        val task = TodoTask(id = "$ID_PREFIX${state.nextId}", title = clean, checked = false)
        commit(state.copy(revision = state.revision + 1, nextId = state.nextId + 1, tasks = state.tasks + task))
        state.snapshot()
    }

    /** Deletes a task; null when there is none with that id. Its id is never handed out again. */
    fun remove(taskId: String): TodoList? = synchronized(lock) {
        if (state.tasks.none { it.id == taskId }) return null
        commit(state.copy(revision = state.revision + 1, tasks = state.tasks.filterNot { it.id == taskId }))
        state.snapshot()
    }

    private fun commit(next: TodoState) {
        state = next
        storage.write(next)
    }

    private fun TodoState.snapshot() = TodoList(revision, TITLE, tasks)

    // The first revision comes from the wall clock, so a store recreated after its data was
    // cleared never reuses a revision the glasses may still hold for one of its pages.
    private fun seed(): TodoState {
        val titles = listOf("Milk", "Eggs", "Bread", "Coffee", "Apples")
        val tasks = titles.mapIndexed { index, title ->
            TodoTask(id = "$ID_PREFIX${index + 1}", title = title, checked = index == titles.lastIndex)
        }
        return TodoState(revision = wallClock(), nextId = titles.size + 1L, tasks = tasks)
    }

    companion object {
        const val TITLE = "Groceries"

        /** One page action per task: well inside the 64 rows and actions a page may carry. */
        const val MAX_TASKS = 32

        /** A page row label's limit. */
        const val MAX_TITLE_CHARS = 120

        private const val ID_PREFIX = "t"

        fun cleanTitle(title: String): String? =
            title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS).trim().takeIf(String::isNotEmpty)
    }
}

/** The list's JSON form in the plugin's own preferences. */
internal object TodoCodec {
    fun encode(state: TodoState): String = JSONObject()
        .put("revision", state.revision)
        .put("nextId", state.nextId)
        .put(
            "tasks",
            JSONArray().apply {
                state.tasks.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("checked", it.checked)) }
            },
        )
        .toString()

    /** Null for anything that is not a whole, consistent list; the store then starts afresh. */
    fun decode(raw: String): TodoState? = runCatching {
        val json = JSONObject(raw)
        val array = json.getJSONArray("tasks")
        val tasks = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            TodoTask(entry.getString("id"), entry.getString("title"), entry.getBoolean("checked"))
        }
        require(tasks.map(TodoTask::id).toSet().size == tasks.size)
        require(tasks.size <= TodoStore.MAX_TASKS)
        TodoState(json.getLong("revision"), json.getLong("nextId"), tasks)
    }.getOrNull()
}

internal class PrefsTodoStorage(context: Context) : TodoStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): TodoState? = prefs.getString(KEY_STATE, null)?.let(TodoCodec::decode)

    override fun write(state: TodoState) {
        prefs.edit().putString(KEY_STATE, TodoCodec.encode(state)).apply()
    }

    private companion object {
        const val PREFS = "todo"
        const val KEY_STATE = "list"
    }
}
