package com.anezium.rokidbus.glasses

import android.os.Looper
import com.anezium.rokidbus.shared.GlassesRepairContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SelfArmOwnerRepairStaleRecordTest {
    private val context = RuntimeEnvironment.getApplication()

    /**
     * The Robolectric android jar here lacks Context.getSystemService(Class), so the radio reads
     * off exactly as SelfArmWirelessAdbController.isWifiEnabled fails closed on a real error.
     * The bridge probe answers from its own thread, so the main looper is drained until the
     * repair has answered.
     */
    private fun ownerRepairWithWifiOff(): String? {
        assertFalse(SelfArmWirelessAdbController.isWifiEnabled(context))
        var answer: String? = null
        SelfArmBootRepairCoordinator.runOwnerRepair(context) { answer = it }
        val deadline = System.currentTimeMillis() + 10_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (answer != null) break
            Thread.sleep(20L)
        } while (System.currentTimeMillis() < deadline)
        return answer
    }

    @Test
    fun `a radio-off repair never answers healthy from the record alone`() {
        // No bridge secret and no accessibility service exist here: the bridge cannot answer and
        // the measuring popup cannot run, so every state has to end unmeasured, never healthy.
        SelfArmLocalAdbBootstrapper.recordBootstrapComplete(context)
        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 37_000, bridgeRunning = true)
        assertEquals(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE, ownerRepairWithWifiOff())

        // The helper died later and the owner's arm could not bring it back.
        SelfArmBridgeLivenessStore.recordArmFailed(context)
        assertEquals(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE, ownerRepairWithWifiOff())

        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 37_000, bridgeRunning = true)
        assertEquals(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE, ownerRepairWithWifiOff())
    }
}
