package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubePatcherContractTest {
    @Test fun `activity belongs to the explicit plugin package`() {
        assertTrue(YoutubePatcherContract.PATCH_ACTIVITY.startsWith(YoutubePatcherContract.PACKAGE + "."))
        assertTrue(YoutubePatcherContract.ACTION_PATCH.startsWith(YoutubePatcherContract.PACKAGE + "."))
    }

    @Test fun `stock signer is a lowercase SHA256 certificate digest`() {
        assertTrue(YoutubePatcherContract.STOCK_SIGNER_SHA256.matches(Regex("[a-f0-9]{64}")))
        assertEquals("com.google.android.youtube", YoutubePatcherContract.STOCK_PACKAGE)
    }

    @Test fun `bundle sources are pinned to the owner fork`() {
        assertEquals("https://raw.githubusercontent.com/Anezium/morphe-patches/rokid/patches-bundle.json", YoutubePatcherContract.BUNDLE_METADATA_URL)
        assertEquals("https://github.com/Anezium/morphe-patches/releases/download/", YoutubePatcherContract.BUNDLE_DOWNLOAD_PREFIX)
    }
}
