package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.SetupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSetupPreflightPolicyTest {
    @Test
    fun `a unit already armed is not walked through a wizard`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            glassesAppRunning = true,
            accessibilityEnabled = true,
            wifiReady = false,
            developerOptionsReady = false,
            coreReady = true,
        )
        assertTrue(preflight.alreadyComplete)
        assertNull(preflight.blocking)
    }

    @Test
    fun `the link is asked for before anything that depends on it`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = false,
            glassesAppRunning = true,
            accessibilityEnabled = false,
            wifiReady = false,
            developerOptionsReady = false,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.LINK, preflight.blocking)
    }

    @Test
    fun `satisfied prerequisites are skipped`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            glassesAppRunning = true,
            accessibilityEnabled = true,
            wifiReady = false,
            developerOptionsReady = false,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.WIFI, preflight.blocking)
    }

    @Test
    fun `developer options never block - Nexus turns them on itself`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            glassesAppRunning = true,
            accessibilityEnabled = true,
            wifiReady = true,
            developerOptionsReady = false,
            coreReady = false,
        )
        assertNull(preflight.blocking)
        assertFalse(preflight.alreadyComplete)
        assertFalse(preflight.checks.first { it.id == GuidedCheckId.DEVELOPER }.satisfied)
    }

    @Test
    fun `every check is reported so the owner sees what was inspected`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            glassesAppRunning = true,
            accessibilityEnabled = false,
            wifiReady = true,
            developerOptionsReady = true,
            coreReady = false,
        )
        // The glasses' own pairing is only inspected by Re-pair glasses.
        assertEquals(
            GuidedCheckId.entries.toSet() - GuidedCheckId.SELF_PAIRING,
            preflight.checks.map { it.id }.toSet(),
        )
        assertEquals(GuidedCheckId.ACCESSIBILITY, preflight.blocking)
        val repair = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            glassesAppRunning = true,
            accessibilityEnabled = false,
            wifiReady = true,
            developerOptionsReady = true,
            coreReady = false,
            repair = true,
        )
        assertEquals(GuidedCheckId.entries.toSet(), repair.checks.map { it.id }.toSet())
    }

    /**
     * A lens that has never reported a stage has not told us anything is fine. Reading that
     * silence as "all clear" offered a pairing that could not possibly work, then blamed the link
     * when the command reached nobody. Silence means the glasses app is not up yet.
     */
    @Test
    fun `silence from the glasses blocks on the lens app rather than passing`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = "",
            coreReady = false,
        )
        assertEquals(GuidedCheckId.GLASSES_APP, preflight.blocking)
        assertFalse(preflight.checks.first { it.id == GuidedCheckId.GLASSES_APP }.satisfied)
        assertFalse(preflight.checks.first { it.id == GuidedCheckId.ACCESSIBILITY }.satisfied)
    }

    /** Once the lens speaks, the app is by definition up and the blocker moves on. */
    @Test
    fun `a lens that reports its accessibility wait has its app running`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.WAITING_FOR_ACCESSIBILITY,
            coreReady = false,
        )
        assertTrue(preflight.checks.first { it.id == GuidedCheckId.GLASSES_APP }.satisfied)
    }

    @Test
    fun `the lens app is asked for before the switch the wearer must flip`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            glassesAppRunning = false,
            accessibilityEnabled = false,
            wifiReady = false,
            developerOptionsReady = true,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.GLASSES_APP, preflight.blocking)
    }

    @Test
    fun `an armed lens passes even when it has reported no stage`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = "",
            coreReady = true,
        )
        assertNull(preflight.blocking)
        assertTrue(preflight.alreadyComplete)
    }

    @Test
    fun `a reported stage past accessibility stops blocking on it`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.PAIRING_LOCALLY,
            coreReady = false,
        )
        assertNull(preflight.blocking)
        assertFalse(preflight.alreadyComplete)
    }

    @Test
    fun `a lens waiting on its accessibility switch blocks there`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.WAITING_FOR_ACCESSIBILITY,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.ACCESSIBILITY, preflight.blocking)
    }

    @Test
    fun `a lens still joining Wi-Fi blocks on Wi-Fi`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.WAITING_FOR_WIFI,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.WIFI, preflight.blocking)
    }

    @Test
    fun `re-pair stays open when the glasses are core-ready but never paired themselves`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.COMPLETE,
            coreReady = true,
            repair = true,
            maintenanceReady = false,
        )
        assertFalse(preflight.alreadyComplete)
        assertTrue(preflight.needsPairing)
        assertNull(preflight.blocking)
        assertFalse(preflight.checks.first { it.id == GuidedCheckId.SELF_PAIRING }.satisfied)
    }

    @Test
    fun `re-pair reads done only once the glasses hold their own pairing`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.COMPLETE,
            coreReady = true,
            repair = true,
            maintenanceReady = true,
        )
        assertTrue(preflight.alreadyComplete)
        assertFalse(preflight.needsPairing)
    }

    @Test
    fun `re-pair still clears real blockers first`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = false,
            reportedStage = SetupStage.COMPLETE,
            coreReady = false,
            repair = true,
            maintenanceReady = false,
        )
        assertEquals(GuidedCheckId.LINK, preflight.blocking)
        assertTrue(preflight.needsPairing)
    }

    @Test
    fun `plain manual setup keeps its core-ready verdict and shows no pairing check`() {
        val preflight = GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = true,
            reportedStage = SetupStage.COMPLETE,
            coreReady = true,
            maintenanceReady = false,
        )
        assertTrue(preflight.alreadyComplete)
        assertFalse(preflight.needsPairing)
        assertTrue(preflight.checks.none { it.id == GuidedCheckId.SELF_PAIRING })
    }
}
