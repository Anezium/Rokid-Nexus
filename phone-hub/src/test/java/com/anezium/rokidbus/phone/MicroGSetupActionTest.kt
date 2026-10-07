package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.assertEquals
import org.junit.Test

class MicroGSetupActionTest {
    private val installed = YoutubePackage(YoutubeSetupContract.MICROG, 200, "a".repeat(64), true)

    @Test fun missingMicroGOffersInstall() {
        assertEquals("Install MicroG", MicroGSetupAction.choose(null, null).label)
        assertEquals("Install MicroG", MicroGSetupAction.choose(YoutubePackage(YoutubeSetupContract.MICROG), 200).label)
    }

    @Test fun installedMicroGOpensUnlessANewerReleaseIsKnown() {
        for (latest in listOf(null, 100L, 200L)) {
            assertEquals("Open MicroG", MicroGSetupAction.choose(installed, latest).label)
        }
        assertEquals("Update MicroG", MicroGSetupAction.choose(installed, 201).label)
    }

    @Test fun noiconMicroGOffersAnUpdateWithALauncher() {
        assertEquals("Update MicroG", MicroGSetupAction.choose(installed.copy(launchable = false), null).label)
    }
}
