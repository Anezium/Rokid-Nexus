package com.anezium.rokidbus.glasses

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LauncherReceiverPermissionTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `the ring bridge can still open the launcher while only adb can switch its backend`() {
        val packages = app.packageManager
        // The R08 Access Bridge is an ordinary app: it opens the launcher with this broadcast.
        assertNull(packages.getReceiverInfo(ComponentName(app, OpenLauncherReceiver::class.java), 0).permission)
        assertEquals(
            "android.permission.DUMP",
            packages.getReceiverInfo(ComponentName(app, SetLauncherBackendReceiver::class.java), 0).permission,
        )
    }
}
