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
     */
    private fun ownerRepairWithWifiOff(): String? {
        assertFalse(SelfArmWirelessAdbController.isWifiEnabled(context))
        var answer: String? = null
        SelfArmBootRepairCoordinator.runOwnerRepair(context) { answer = it }
        shadowOf(Looper.getMainLooper()).idle()
        return answer
    }

    @Test
    fun `a failed arm after a success this boot sends the next radio-off repair to measure`() {
        SelfArmLocalAdbBootstrapper.recordBootstrapComplete(context)
        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 37_000, bridgeRunning = true)
        assertEquals(GlassesRepairContract.RESULT_ALREADY_HEALTHY, ownerRepairWithWifiOff())

        // The helper died later and the owner's arm could not bring it back.
        SelfArmBridgeLivenessStore.recordArmFailed(context)

        // No accessibility service is connected here, so the measuring popup cannot run; what
        // matters is that the stale success no longer answers for it.
        assertEquals(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE, ownerRepairWithWifiOff())

        // A later successful arm is fresh evidence again.
        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 37_000, bridgeRunning = true)
        assertEquals(GlassesRepairContract.RESULT_ALREADY_HEALTHY, ownerRepairWithWifiOff())
    }
}
