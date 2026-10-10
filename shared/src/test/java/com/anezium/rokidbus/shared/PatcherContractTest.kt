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

    @Test fun `hub setup entry is a hub component limited to hub-owned setups`() {
        assertTrue(PatcherContract.HUB_SETUP_ACTIVITY.startsWith(PatcherContract.HUB_PACKAGE + "."))
        assertTrue(PatcherContract.ACTION_OPEN_SETUP.startsWith(PatcherContract.HUB_PACKAGE + "."))
        assertEquals(setOf(PatcherContract.TARGET_YOUTUBE, PatcherContract.TARGET_REDDIT), PatcherContract.SETUP_TARGETS)
    }

    @Test fun `a hub's declared setups are clipped to the fixed allowlist`() {
        val both = setOf(PatcherContract.TARGET_YOUTUBE, PatcherContract.TARGET_REDDIT)
        assertEquals(both, PatcherContract.hubSetupTargets("youtube,reddit"))
        assertEquals(both, PatcherContract.hubSetupTargets(" reddit , youtube "))
        // A hub without the declaration predates Reddit's hub-owned setup.
        assertEquals(setOf(PatcherContract.TARGET_YOUTUBE), PatcherContract.hubSetupTargets(null))
        assertEquals(setOf(PatcherContract.TARGET_REDDIT), PatcherContract.hubSetupTargets("reddit,other,../x,"))
        assertEquals(emptySet<String>(), PatcherContract.hubSetupTargets(""))
    }

    @Test fun `job hints outside the fixed vocabulary are dropped`() {
        assertEquals(PatcherContract.JOB_RUNNING, PatcherContract.jobState("running"))
        for (bad in listOf(null, "", "RUNNING", "installed", "ready ")) assertEquals(null, PatcherContract.jobState(bad))
    }
}
