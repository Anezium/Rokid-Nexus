package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class YoutubePackageInventoryTest {
    @Test fun `detects microG without a launcher and does not report unrelated packages`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val pm = shadowOf(context.packageManager)
        for (name in listOf(YoutubeSetupContract.MICROG, "com.google.android.gms")) {
            pm.installPackage(PackageInfo().apply {
                packageName = name
                setLongVersionCode(123)
                applicationInfo = ApplicationInfo().apply { packageName = name }
            })
        }
        val result = YoutubeSetupContract.parseResult(YoutubePackageInventory.result(context, "request-id"))!!
        assertEquals(YoutubeSetupContract.PACKAGES, result.apps.map { it.packageName })
        val microG = result.apps.first()
        assertTrue(microG.installed)
        assertFalse(microG.launchable)
        assertEquals(123L, microG.versionCode)
        assertTrue(result.apps.drop(1).none { it.installed })
    }
}
