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
