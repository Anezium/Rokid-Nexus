package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.shared.HeadTiltContract
import com.anezium.rokidbus.shared.HeadTiltSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSensor
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class HeadTiltLifecycleTest {
    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }
    private lateinit var service: TestService
    private lateinit var sensors: SensorManager

    @Before fun setup() {
        service = Robolectric.buildService(TestService::class.java).create().get()
        service.getSharedPreferences("head_tilt", Context.MODE_PRIVATE).edit().clear().commit()
        sensors = service.getSystemService(SensorManager::class.java)
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR))
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        HeadTiltController.start(service)
    }
    @After fun cleanup() { HeadTiltController.stop() }
    private fun request(action: String, id: String = "test") {
        HeadTiltController.request(JSONObject().put("v", 1).put("sessionId", id).put("action", action))
    }

    @Test fun `off by default and uncalibrated enable does not register sensors`() {
        assertFalse(shadowOf(sensors).hasListener(HeadTiltController))
        HeadTiltController.configure(service, HeadTiltContract.toJson(HeadTiltSettings(enabled = true)))
        assertFalse(shadowOf(sensors).hasListener(HeadTiltController))
    }

    @Test fun `preview expires and an old session cannot stop its replacement`() {
        request("preview", "old")
        request("preview")
        assertTrue(shadowOf(sensors).hasListener(HeadTiltController))
        request("stop", "old")
        assertTrue(shadowOf(sensors).hasListener(HeadTiltController))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HeadTiltContract.PREVIEW_MILLIS + 1))
        assertFalse(shadowOf(sensors).hasListener(HeadTiltController))
    }

    @Test fun `screen off and service destruction release the sensor`() {
        request("preview")
        assertTrue(shadowOf(sensors).hasListener(HeadTiltController))
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(sensors).hasListener(HeadTiltController))
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        request("preview")
        HeadTiltController.stop()
        assertFalse(shadowOf(sensors).hasListener(HeadTiltController))
    }

    @Test fun `link loss ends preview while disabled`() {
        request("preview")
        HeadTiltController.onLinkLost()
        assertFalse(shadowOf(sensors).hasListener(HeadTiltController))
    }
}
