package com.anezium.rokidbus.phone

import android.Manifest
import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GlassesRepairPermissionTest {
    @Test fun `Android 12 requests coarse and precise location together without repeating a denial`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(GlassesRepairPermission.request(activity, once = true))
        val request = shadowOf(activity).lastRequestedPermission
        assertEquals(GlassesRepairPermission.REQUEST_CODE, request.requestCode)
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION), request.requestedPermissions)
        assertFalse(GlassesRepairPermission.request(activity, once = true))
        assertTrue(GlassesRepairPermission.request(activity, once = false))
    }

    @Test @Config(sdk = [33]) fun `Android 13 requests nearby Wi-Fi without location`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(GlassesRepairPermission.request(activity, once = true))
        assertArrayEquals(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            shadowOf(activity).lastRequestedPermission.requestedPermissions)
    }

    @Test fun `disabled automatic repair does not request permission until the owner asks`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        GlassesRepairSettingsStore(activity).setAutoRepairEnabled(false)
        assertFalse(GlassesRepairPermission.request(activity, once = true))
        assertTrue(GlassesRepairPermission.request(activity, once = false))
    }

    @Test fun `an existing grant does not show another permission prompt`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(GlassesRepairPermission.request(activity, once = true))
        assertFalse(GlassesRepairPermission.request(activity, once = false))
    }
}
