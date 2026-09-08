package com.anezium.rokidbus.plugin.foodlog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All bridge instances share one provider queue; stale batches resolve current local identity inside it. */
internal class FoodHealthSyncCoordinator(
    private val enabled: () -> Boolean,
    private val currentEntry: suspend (String) -> FoodEntry?,
    private val mutex: Mutex = foodHealthProviderMutex,
) {
    suspend fun sync(entry: FoodEntry, userOptedIn: Boolean, write: suspend (FoodEntry) -> FoodLogHealthConnectSyncResult): FoodLogHealthConnectSyncResult = mutex.withLock {
        if (!userOptedIn || !enabled()) return@withLock FoodLogHealthConnectSyncResult.NotOptedIn
        val current = currentEntry(entry.uuid) ?: return@withLock FoodLogHealthConnectSyncResult.Skipped
        if (!enabled()) return@withLock FoodLogHealthConnectSyncResult.NotOptedIn
        write(current)
    }

    suspend fun delete(entry: FoodEntry, userOptedIn: Boolean, remove: suspend (FoodEntry) -> FoodLogHealthConnectSyncResult): FoodLogHealthConnectSyncResult = mutex.withLock {
        if (!userOptedIn || !enabled()) return@withLock FoodLogHealthConnectSyncResult.NotOptedIn
        if (currentEntry(entry.uuid) != null) return@withLock FoodLogHealthConnectSyncResult.Skipped
        if (!enabled()) return@withLock FoodLogHealthConnectSyncResult.NotOptedIn
        remove(entry)
    }
}

private val foodHealthProviderMutex = Mutex()
