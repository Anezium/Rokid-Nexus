package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.*
import org.junit.Test

class YoutubeInstallStatusTest {
    private val signer = "a".repeat(64)
    private val confirmed = YoutubeInstalledApk(200, YoutubeApkPolicy.STOCK_YOUTUBE_VERSION, signer)
    private fun inventory(packageName: String = YoutubeSetupContract.YOUTUBE, version: Long = 200,
                          signingKey: String = signer) = YoutubeInventory("request", 32,
        YoutubeSetupContract.PACKAGES.map {
            if (it == packageName) YoutubePackage(it, version, signingKey, true) else YoutubePackage(it)
        })

    @Test fun onlyExactPackageAndConfirmedPinnedVersionAreDone() {
        assertEquals("Done", youtubeInstallStatus(inventory(), confirmed))
        assertEquals("To do", youtubeInstallStatus(inventory(YoutubeSetupContract.YOUTUBE_TEST), confirmed))
        assertEquals("To do", youtubeInstallStatus(inventory(YoutubeSetupContract.MICROG), confirmed))
        assertTrue(youtubeInstallStatus(null, confirmed).contains("refresh"))
    }

    @Test fun missingOrDifferentSignerNeedsAttentionEvenAtTheExpectedVersion() {
        for (key in listOf("", "b".repeat(64))) {
            assertEquals("Needs attention — signing key not confirmed by Nexus",
                youtubeInstallStatus(inventory(signingKey = key), confirmed))
        }
    }

    @Test fun missingInstallRecordAsksForConfirmationWithoutBlamingTheSigner() {
        for (key in listOf("", signer, "b".repeat(64))) {
            assertEquals("Needs attention — install not recorded on this phone; reinstall through Nexus to confirm",
                youtubeInstallStatus(inventory(signingKey = key), null))
        }
    }

    @Test fun differentOrUnknownVersionNeedsAttention() {
        for (record in listOf(confirmed.copy(versionCode = 100), confirmed.copy(versionName = "21.32.2"),
                              confirmed.copy(versionName = null))) {
            assertEquals("Needs attention — version not confirmed as ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}",
                youtubeInstallStatus(inventory(), record))
        }
    }
}
