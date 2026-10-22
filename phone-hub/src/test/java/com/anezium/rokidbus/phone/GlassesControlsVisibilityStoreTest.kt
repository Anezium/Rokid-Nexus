package com.anezium.rokidbus.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GlassesControlsVisibilityStoreTest {
    private fun store() = GlassesControlsVisibilityStore(RuntimeEnvironment.getApplication())

    @Test
    fun `both controls are offered until someone turns one off`() {
        val store = store()

        assertTrue(store.isRemoteVisible())
        assertTrue(store.isNativeAppsVisible())
        assertTrue(store.isSectionVisible())
    }

    @Test
    fun `each switch only governs its own card`() {
        val store = store()

        store.setRemoteVisible(false)

        assertFalse(store.isRemoteVisible())
        assertTrue(store.isNativeAppsVisible())
        assertTrue(store.isSectionVisible())
    }

    @Test
    fun `the section disappears once every card is hidden`() {
        val store = store()

        store.setRemoteVisible(false)
        store.setNativeAppsVisible(false)
        assertTrue(store.isSectionVisible())

        store.setFilesVisible(false)
        assertFalse(store.isSectionVisible())
    }

    @Test
    fun `a hidden card comes back`() {
        val store = store()

        store.setNativeAppsVisible(false)
        store.setNativeAppsVisible(true)

        assertTrue(store.isNativeAppsVisible())
        assertTrue(store.isSectionVisible())
    }
}
