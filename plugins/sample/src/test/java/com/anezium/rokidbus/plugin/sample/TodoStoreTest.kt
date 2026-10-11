package com.anezium.rokidbus.plugin.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoStoreTest {
    private class MemoryStorage(var raw: String? = null) : TodoStorage {
        var writes = 0

        override fun read(): TodoState? = raw?.let(TodoCodec::decode)

        override fun write(state: TodoState) {
            writes++
            raw = TodoCodec.encode(state)
        }
    }

    private fun store(storage: MemoryStorage = MemoryStorage(), clock: Long = 1_000L) = TodoStore(storage) { clock }

    @Test
    fun seedsGroceriesWithFourLeftAtTheWallClockRevision() {
        val list = store(clock = 42_000L).list()

        assertEquals(42_000L, list.revision)
        assertEquals("Groceries", list.title)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), list.tasks.map(TodoTask::id))
        assertEquals(4, list.remaining)
    }

    @Test
    fun setCheckedAppliesTheExplicitStateAndMovesTheRevision() {
        val store = store()
        val before = store.list()

        val outcome = store.setChecked("t1", checked = true, expectedRevision = before.revision)

        val done = outcome as SetCheckedOutcome.Done
        assertEquals(before.revision + 1, done.list.revision)
        assertTrue(done.list.task("t1")!!.checked)
        assertEquals(done.list, store.list())
    }

    @Test
    fun theSameDesiredStateTwiceNeverTogglesBack() {
        val store = store()
        val first = store.setChecked("t1", true, store.list().revision) as SetCheckedOutcome.Done

        val second = store.setChecked("t1", true, first.list.revision) as SetCheckedOutcome.Done

        assertTrue(second.list.task("t1")!!.checked)
    }

    @Test
    fun aRevisionTheListMovedPastIsStaleAndChangesNothing() {
        val store = store()
        val shown = store.list()
        store.setChecked("t2", true, shown.revision)
        val now = store.list()

        val outcome = store.setChecked("t1", true, shown.revision)

        assertSame(SetCheckedOutcome.Stale, outcome)
        assertEquals(now, store.list())
        assertFalse(store.list().task("t1")!!.checked)
    }

    @Test
    fun aDeletedTaskIsMissingWhateverTheRevision() {
        val store = store()
        val shown = store.list()
        val afterRemove = store.remove("t3")!!

        assertSame(SetCheckedOutcome.Missing, store.setChecked("t3", true, shown.revision))
        assertSame(SetCheckedOutcome.Missing, store.setChecked("t3", true, afterRemove.revision))
        assertEquals(afterRemove, store.list())
    }

    @Test
    fun anIdIsNeverHandedOutAgainAfterItsTaskWasRemoved() {
        val store = store()
        store.remove("t5")

        val added = store.add("  Oat   milk ")!!

        assertEquals("t6", added.tasks.last().id)
        assertEquals("Oat milk", added.tasks.last().title)
        assertNull(store.add("   "))
    }

    @Test
    fun theListSurvivesARestartThroughItsPreferences() {
        val storage = MemoryStorage()
        val first = store(storage)
        first.setChecked("t1", true, first.list().revision)
        first.add("Tea")

        val reopened = store(storage, clock = 99_999_999L)

        assertEquals(first.list(), reopened.list())
    }

    @Test
    fun aPageFloorAboveTheStoreMovesTheRevisionPastIt() {
        val storage = MemoryStorage()
        val store = store(storage, clock = 10L)

        val list = store.listAbove(500L)

        assertEquals(501L, list.revision)
        assertEquals(501L, store(storage).list().revision)
        assertEquals(501L, store.listAbove(400L).revision)
        assertEquals(501L, store.listAbove(null).revision)
    }

    @Test
    fun aStoredListThatCouldReuseAnIdIsDiscarded() {
        val tasks = listOf(TodoTask("t1", "Milk", false), TodoTask("t4", "Eggs", true))

        assertNotNull(TodoCodec.decode(TodoCodec.encode(TodoState(7L, 5L, tasks))))
        assertNull(TodoCodec.decode(TodoCodec.encode(TodoState(7L, 4L, tasks))))
        assertNull(TodoCodec.decode(TodoCodec.encode(TodoState(7L, 9L, tasks + TodoTask("t1", "Again", false)))))
        assertNull(TodoCodec.decode(TodoCodec.encode(TodoState(7L, 9L, listOf(TodoTask("x", "Foreign", false))))))
        assertNull(TodoCodec.decode("not json"))
    }
}
