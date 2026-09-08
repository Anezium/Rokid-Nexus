package com.anezium.rokidbus.plugin.foodlog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class FoodHealthSyncCoordinatorTest {
    private val food = FoodProduct("12345678", "Oats", "", null, null, null, null,
        NutrientsPer100g(200.0, null, null, null, null, null, null), 1)
    private val entry = FoodEntry(1, 100, 50.0, food, "00000000-0000-4000-8000-000000000001", revision = 10)

    @Test fun aStaleBatchWritesTheCurrentPersistedEntryRevision() = runBlocking {
        val edited = entry.copy(quantityGrams = 150.0, mealType = MealType.DINNER, revision = 11)
        val coordinator = FoodHealthSyncCoordinator({ true }, { edited }, Mutex())
        var written: FoodEntry? = null
        coordinator.sync(entry, true) { written = it; FoodLogHealthConnectSyncResult.Synced }
        assertEquals(edited, written)
        assertEquals(11L, written!!.healthConnectClientRecordVersion())
    }

    @Test fun aQueuedBatchCannotRecreateAnEntryDeletedDuringAnotherWrite() = runBlocking {
        var current: FoodEntry? = entry
        val coordinator = FoodHealthSyncCoordinator({ true }, { current }, Mutex())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val first = async { coordinator.sync(entry, true) {
            writes++; started.complete(Unit); release.await(); FoodLogHealthConnectSyncResult.Synced
        } }
        started.await()
        val stale = async { coordinator.sync(entry, true) { writes++; FoodLogHealthConnectSyncResult.Synced } }
        yield()
        current = null
        val deletion = async { coordinator.delete(entry, true) { FoodLogHealthConnectSyncResult.Synced } }
        release.complete(Unit)
        first.await()
        assertEquals(FoodLogHealthConnectSyncResult.Skipped, stale.await())
        assertEquals(FoodLogHealthConnectSyncResult.Synced, deletion.await())
        assertEquals(1, writes)
    }

    @Test fun disablingConsentStopsQueuedWritesAndDeletes() = runBlocking {
        var enabled = true
        val coordinator = FoodHealthSyncCoordinator({ enabled }, { entry }, Mutex())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { coordinator.sync(entry, true) {
            started.complete(Unit); release.await(); FoodLogHealthConnectSyncResult.Synced
        } }
        started.await()
        val queued = async { coordinator.sync(entry, true) { throw AssertionError("Write after opt-out") } }
        enabled = false
        release.complete(Unit); first.await()
        assertEquals(FoodLogHealthConnectSyncResult.NotOptedIn, queued.await())
        assertEquals(FoodLogHealthConnectSyncResult.NotOptedIn,
            coordinator.delete(entry, true) { throw AssertionError("Delete after opt-out") })
    }

    @Test fun aReimportedIdentityCannotBeDeletedByAnOlderPendingRemoval() = runBlocking {
        val coordinator = FoodHealthSyncCoordinator({ true }, { entry.copy(revision = 99) }, Mutex())
        assertEquals(FoodLogHealthConnectSyncResult.Skipped,
            coordinator.delete(entry, true) { throw AssertionError("Deleted a restored entry") })
    }

    @Test fun persistedVersionsAdvanceEvenWhenTheWallClockMovesBackwards() {
        assertEquals(5_001L, nextFoodEntryRevision(previous = 5_000L, nowMillis = 2L))
        assertEquals(10_000L, nextFoodEntryRevision(previous = 5_000L, nowMillis = 10L))
    }
}
