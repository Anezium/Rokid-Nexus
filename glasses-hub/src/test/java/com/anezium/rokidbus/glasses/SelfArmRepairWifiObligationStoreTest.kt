package com.anezium.rokidbus.glasses

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmRepairWifiObligationStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val boot = 1_700_000_000_000L

    @Before
    @After
    fun reset() {
        context.getSharedPreferences("selfarm_wireless", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `nothing is owed until a repair records it`() {
        assertFalse(SelfArmRepairWifiObligationStore.isOwed(context, boot))
    }

    @Test
    fun `a recorded debt survives until the radio is seen off`() {
        assertTrue(SelfArmRepairWifiObligationStore.recordBeforeEnable(context, boot))

        // Read back from storage, as a hub restarted mid-repair would.
        assertTrue(SelfArmRepairWifiObligationStore.isOwed(context, boot + 1_000L))

        SelfArmRepairWifiObligationStore.clear(context)
        assertFalse(SelfArmRepairWifiObligationStore.isOwed(context, boot))
    }

    @Test
    fun `a reboot settles the debt by itself`() {
        SelfArmRepairWifiObligationStore.recordBeforeEnable(context, boot)

        assertFalse(SelfArmRepairWifiObligationStore.isOwed(context, boot + 3_600_000L))
    }

    @Test
    fun `the debt is never hub ownership`() {
        SelfArmRepairWifiObligationStore.recordBeforeEnable(context, boot)

        // Ownership blocks the background re-arm, and that re-arm revives the bridge the
        // restore goes through.
        assertFalse(SelfArmSetupWifiOwnershipStore.isNexusOwned(context))
    }
}
