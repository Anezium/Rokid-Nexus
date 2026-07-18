package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensPersistentGroupJanitorTest {
    @Test
    fun `name filter selects only Nexus camera groups`() {
        assertTrue(LensPersistentGroupPolicy.isOwnedGroup("DIRECT-RN-ABC123"))
        assertTrue(LensPersistentGroupPolicy.isOwnedGroup("DIRECT-RN-"))
        assertFalse(LensPersistentGroupPolicy.isOwnedGroup("DIRECT-RN"))
        assertFalse(LensPersistentGroupPolicy.isOwnedGroup("DIRECT-XY-ABC123"))
        assertFalse(LensPersistentGroupPolicy.isOwnedGroup("Samsung Smart View"))
        assertFalse(LensPersistentGroupPolicy.isOwnedGroup("DIRECT-rn-ABC123"))
    }

    @Test
    fun `retention keeps at most two identifiable owned groups`() {
        val groups = listOf(
            LensPersistentGroup("DIRECT-RN-OLDEST", 1),
            LensPersistentGroup("DIRECT-RN-LAST", 2),
            LensPersistentGroup("Samsung Smart View", 3),
            LensPersistentGroup("DIRECT-RN-CURRENT", 4),
            LensPersistentGroup("DIRECT-RN-STALE", 5),
        )

        val networkIds = LensPersistentGroupPolicy.networkIdsToDelete(
            groups = groups,
            retainedNetworkNames = listOf(
                "DIRECT-RN-CURRENT",
                "DIRECT-RN-LAST",
                "DIRECT-RN-OLDEST",
            ),
        )

        assertEquals(listOf(1, 5), networkIds)
    }

    @Test
    fun `unidentifiable owned groups are all deleted while foreign groups are untouched`() {
        val networkIds = LensPersistentGroupPolicy.networkIdsToDelete(
            groups = listOf(
                LensPersistentGroup("DIRECT-RN-ONE", 1),
                LensPersistentGroup("DIRECT-XY-MIRACAST", 2),
                LensPersistentGroup("DIRECT-RN-TWO", 3),
            ),
            retainedNetworkNames = listOf("DIRECT-RN-NOT-PRESENT"),
        )

        assertEquals(listOf(1, 3), networkIds)
    }

    @Test
    fun `janitor deletes only selected ids from a fake group list`() {
        val store = FakePersistentGroupStore(
            listOf(
                LensPersistentGroup("DIRECT-RN-ABC123", 4),
                LensPersistentGroup("DIRECT-XY-ABC123", 8),
                LensPersistentGroup("Samsung Smart View", 15),
                LensPersistentGroup("DIRECT-RN-XYZ789", 16),
            ),
        )
        val logs = mutableListOf<String>()

        LensPersistentGroupJanitor(logs::add).clean(store)

        assertEquals(listOf(4, 16), store.deletedNetworkIds)
        assertEquals(listOf("lensLinkJanitor deleted=2 of=2"), logs)
    }

    @Test
    fun `janitor preserves current and last-known groups`() {
        val store = FakePersistentGroupStore(
            listOf(
                LensPersistentGroup("DIRECT-RN-OLD", 4),
                LensPersistentGroup("DIRECT-RN-LAST", 8),
                LensPersistentGroup("DIRECT-RN-CURRENT", 15),
                LensPersistentGroup("Samsung Smart View", 16),
            ),
        )
        val logs = mutableListOf<String>()

        LensPersistentGroupJanitor(logs::add).clean(
            store = store,
            retainedNetworkNames = listOf("DIRECT-RN-CURRENT", "DIRECT-RN-LAST"),
        )

        assertEquals(listOf(4), store.deletedNetworkIds)
        assertEquals(listOf("lensLinkJanitor deleted=1 of=1"), logs)
    }

    @Test
    fun `unavailable reflection reports zero deletions and completes`() {
        val logs = mutableListOf<String>()
        var completed = false
        val unavailableStore = object : LensPersistentGroupStore {
            override fun requestGroups(
                onGroups: (List<LensPersistentGroup>) -> Unit,
                onFailure: (Throwable) -> Unit,
            ) = onFailure(NoSuchMethodException("hidden API denied"))

            override fun deleteGroup(
                networkId: Int,
                onResult: (Boolean) -> Unit,
                onFailure: (Throwable) -> Unit,
            ) = error("deleteGroup must not be reached")
        }

        LensPersistentGroupJanitor(logs::add).clean(unavailableStore) { completed = true }

        assertTrue(completed)
        assertTrue("missing zero-deletion summary in $logs", "lensLinkJanitor deleted=0 of=0" in logs)
    }

    private class FakePersistentGroupStore(
        private val groups: List<LensPersistentGroup>,
    ) : LensPersistentGroupStore {
        val deletedNetworkIds = mutableListOf<Int>()

        override fun requestGroups(
            onGroups: (List<LensPersistentGroup>) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) = onGroups(groups)

        override fun deleteGroup(
            networkId: Int,
            onResult: (Boolean) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            deletedNetworkIds += networkId
            onResult(true)
        }
    }
}
