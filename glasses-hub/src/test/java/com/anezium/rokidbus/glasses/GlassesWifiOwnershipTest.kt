package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesWifiOwnershipTest {
    @Test
    fun `acquire persists the camera lease before requesting the radio`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence) { 1_234L }

        val result = ownership.acquire("camera-1", wifiCurrentlyEnabled = false) {
            assertEquals("camera-1", persistence.read()?.sessionId)
            true
        }

        assertTrue(result.applied)
        assertTrue(result.hubOwned)
        assertEquals(1_234L, persistence.read()?.acquiredAtMillis)
        assertTrue(persistence.read()?.nexusEnabledWifi == true)
    }

    @Test
    fun `acquire when wifi is already on never creates ownership`() {
        val persistence = FakePersistence()
        var requested = false

        val result = GlassesWifiOwnership(persistence).acquire(
            sessionId = "camera-1",
            wifiCurrentlyEnabled = true,
        ) {
            requested = true
            true
        }

        assertFalse(requested)
        assertFalse(result.applied)
        assertFalse(result.hubOwned)
        assertNull(persistence.read())
    }

    @Test
    fun `camera enable is not attempted when the durable lease cannot be written`() {
        val persistence = FakePersistence(writeSucceeds = false)
        var requested = false

        val result = GlassesWifiOwnership(persistence).acquire(
            sessionId = "camera-1",
            wifiCurrentlyEnabled = false,
        ) {
            requested = true
            true
        }

        assertFalse(requested)
        assertFalse(result.applied)
        assertFalse(result.hubOwned)
    }

    @Test
    fun `a new owner instance recovers the durable lease after process death`() {
        val persistence = FakePersistence()
        GlassesWifiOwnership(persistence) { 100L }
            .acquire("camera-1", wifiCurrentlyEnabled = false) { true }

        val recovered = GlassesWifiOwnership(persistence) { 200L }

        assertTrue(recovered.isHubOwned())
        assertEquals("camera-1", recovered.currentLease()?.sessionId)
    }

    @Test
    fun `a recent durable lease protects an enable command crossing process death`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence) { 100L }
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { true }

        val recovered = GlassesWifiOwnership(persistence) { 200L }

        assertTrue(recovered.isEnableRequestPossiblyInFlight())
        assertFalse(recovered.isEnableRequestPossiblyInFlight(currentTimeMillis = 30_100L))
    }

    @Test
    fun `quick reopen transfers the lease without losing the original enable timestamp`() {
        val persistence = FakePersistence()
        var now = 100L
        val ownership = GlassesWifiOwnership(persistence) { now }
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { true }
        now = 200L
        var requested = false

        val result = ownership.acquire("camera-2", wifiCurrentlyEnabled = true) {
            requested = true
            true
        }

        assertFalse(requested)
        assertFalse(result.applied)
        assertEquals(GlassesWifiLease("camera-2", true, 100L), persistence.read())
    }

    @Test
    fun `release clears only after wifi is observed off`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence)
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { true }
        var requested = false

        val result = ownership.release(
            wifiCurrentlyEnabled = true,
            requestWifiDisable = {
                requested = true
                true
            },
            readWifiEnabled = { false },
        )

        assertTrue(requested)
        assertTrue(result.applied)
        assertFalse(result.hubOwned)
        assertNull(persistence.read())
    }

    @Test
    fun `failed disable leaves the lease for the next sweep`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence)
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { true }

        val result = ownership.release(
            wifiCurrentlyEnabled = true,
            requestWifiDisable = { false },
            readWifiEnabled = { true },
        )

        assertFalse(result.applied)
        assertTrue(result.hubOwned)
        assertTrue(GlassesWifiOwnership(persistence).isHubOwned())
    }

    @Test
    fun `unverifiable disable leaves the lease for the next sweep`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence)
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { true }

        val result = ownership.release(
            wifiCurrentlyEnabled = true,
            requestWifiDisable = { true },
            readWifiEnabled = { null },
        )

        assertFalse(result.applied)
        assertTrue(result.hubOwned)
    }

    @Test
    fun `an already off radio clears the lease without another disable`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence)
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { true }
        var requested = false

        val result = ownership.release(
            wifiCurrentlyEnabled = false,
            requestWifiDisable = {
                requested = true
                true
            },
            readWifiEnabled = { false },
        )

        assertFalse(requested)
        assertFalse(result.hubOwned)
        assertNull(persistence.read())
    }

    @Test
    fun `reconciliation keeps every live radio consumer`() {
        assertEquals(
            WifiOwnershipReconciliationAction.NONE,
            reconcile(cameraSessionActive = true),
        )
        assertEquals(
            WifiOwnershipReconciliationAction.NONE,
            reconcile(setupSessionActive = true),
        )
        assertEquals(
            WifiOwnershipReconciliationAction.NONE,
            reconcile(mediaSyncSessionActive = true),
        )
        assertEquals(
            WifiOwnershipReconciliationAction.NONE,
            reconcile(selfArmOperationActive = true),
        )
        assertEquals(
            WifiOwnershipReconciliationAction.NONE,
            reconcile(setupEnableRequestActive = true),
        )
    }

    @Test
    fun `camera ownership receives grace before disable`() {
        assertEquals(
            WifiOwnershipReconciliationAction.SCHEDULE_CAMERA_GRACE,
            reconcile(),
        )
        assertEquals(
            WifiOwnershipReconciliationAction.DISABLE_NOW,
            reconcile(cameraGraceSatisfied = true),
        )
    }

    @Test
    fun `setup ownership disables immediately unless camera requested grace`() {
        assertEquals(
            WifiOwnershipReconciliationAction.DISABLE_NOW,
            reconcile(cameraLeaseOwned = false, setupWifiOwned = true),
        )
        assertEquals(
            WifiOwnershipReconciliationAction.SCHEDULE_CAMERA_GRACE,
            reconcile(
                cameraLeaseOwned = false,
                setupWifiOwned = true,
                cameraGraceRequested = true,
            ),
        )
    }

    @Test
    fun `a pending grace is never duplicated`() {
        assertEquals(
            WifiOwnershipReconciliationAction.NONE,
            reconcile(cameraGracePending = true),
        )
    }

    private fun reconcile(
        cameraLeaseOwned: Boolean = true,
        setupWifiOwned: Boolean = false,
        cameraSessionActive: Boolean = false,
        setupSessionActive: Boolean = false,
        mediaSyncSessionActive: Boolean = false,
        selfArmOperationActive: Boolean = false,
        setupEnableRequestActive: Boolean = false,
        cameraGraceRequested: Boolean = false,
        cameraGracePending: Boolean = false,
        cameraGraceSatisfied: Boolean = false,
    ): WifiOwnershipReconciliationAction = WifiOwnershipReconciliationPolicy.decide(
        cameraLeaseOwned = cameraLeaseOwned,
        setupWifiOwned = setupWifiOwned,
        cameraSessionActive = cameraSessionActive,
        setupSessionActive = setupSessionActive,
        mediaSyncSessionActive = mediaSyncSessionActive,
        selfArmOperationActive = selfArmOperationActive,
        setupEnableRequestActive = setupEnableRequestActive,
        cameraGraceRequested = cameraGraceRequested,
        cameraGracePending = cameraGracePending,
        cameraGraceSatisfied = cameraGraceSatisfied,
    )

    private class FakePersistence(private val writeSucceeds: Boolean = true) : GlassesWifiLeasePersistence {
        private var lease: GlassesWifiLease? = null

        override fun read(): GlassesWifiLease? = lease

        override fun write(lease: GlassesWifiLease): Boolean {
            if (!writeSucceeds) return false
            this.lease = lease
            return true
        }

        override fun clear() {
            lease = null
        }
    }

    @Test
    fun `handing wifi to the user clears the lease without touching the radio`() {
        val persistence = FakePersistence()
        val ownership = GlassesWifiOwnership(persistence, nowMillis = { 1_000L })
        assertFalse(ownership.handOverToUser())
        var enableRequests = 0
        ownership.acquire("camera-1", wifiCurrentlyEnabled = false) { enableRequests++; true }
        assertTrue(ownership.isHubOwned())
        assertTrue(ownership.handOverToUser())
        assertFalse(ownership.isHubOwned())
        assertEquals(1, enableRequests)
        assertNull(persistence.read())
    }
}
