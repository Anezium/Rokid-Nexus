package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesHotspotCredentials
import com.anezium.rokidbus.shared.GlassesRepairHotspotContract as Wire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RecoveryNetworkActivityTest {
    @Test fun `an activity without the current recovery id cannot acquire a network`() {
        val controller = Robolectric.buildActivity(RecoveryNetworkActivity::class.java).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertFalse(SelfArmHotspotRecovery.isActive)
        controller.destroy()
    }

    @Test fun `unsolicited and stale offers cannot open a recovery activity`() {
        val app = RuntimeEnvironment.getApplication()
        SelfArmHotspotRecovery.onEnvelope(BusEnvelope(path = Wire.PATH, id = "a".repeat(32),
            payload = Wire.offer(GlassesFilesHotspotCredentials("temporary", "temporary-password", "wpa2"))))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(SelfArmHotspotRecovery.isActive)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `an unsupported phone leaves the existing repair path in charge`() {
        val app = RuntimeEnvironment.getApplication()
        SelfArmHotspotRecovery.onPeerLost()
        shadowOf(Looper.getMainLooper()).idle()
        var callbackCalled = false
        assertFalse(SelfArmHotspotRecovery.start(app, automatic = false) { callbackCalled = true })
        assertFalse(callbackCalled)
        assertFalse(SelfArmHotspotRecovery.isActive)
    }
}
