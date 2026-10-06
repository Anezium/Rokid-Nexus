package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PatcherContractTest {
    @Test fun `activity belongs to the explicit plugin package`() {
        assertTrue(PatcherContract.PATCH_ACTIVITY.startsWith(PatcherContract.PACKAGE + "."))
        assertTrue(PatcherContract.ACTION_PATCH.startsWith(PatcherContract.PACKAGE + "."))
    }

    @Test fun targetIdsAreBoundedProtocolData() {
        assertTrue(PatcherContract.isTargetId(PatcherContract.TARGET_YOUTUBE))
        assertTrue(PatcherContract.isTargetId("second_app"))
        for (bad in listOf("", "../youtube", "UPPER", "a".repeat(65))) assertFalse(PatcherContract.isTargetId(bad))
        assertEquals("targetId", PatcherContract.EXTRA_TARGET_ID)
    }
}
