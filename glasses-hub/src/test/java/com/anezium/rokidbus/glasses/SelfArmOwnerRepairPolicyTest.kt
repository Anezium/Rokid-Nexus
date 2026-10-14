package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.SelfArmOwnerRepairPolicy.HelperRecord
import com.anezium.rokidbus.glasses.SelfArmOwnerRepairPolicy.SessionDisposition
import com.anezium.rokidbus.shared.GlassesRepairContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmOwnerRepairPolicyTest {
    private val boot = 1_700_000_000_000L

    @Test
    fun `an absent record is unknown and goes straight to the popup`() {
        val record = SelfArmOwnerRepairPolicy.helperRecord(
            armedBootInstantMs = null,
            armedBridgeRunning = false,
            currentBootInstantMs = boot,
            bridgeDemandPending = false,
        )
        assertEquals(HelperRecord.ABSENT, record)
        assertFalse(SelfArmOwnerRepairPolicy.mayTryBridgeBeforePopup(record))
    }

    @Test
    fun `only an arm that reached the helper and failed contradicts the record`() {
        assertTrue(SelfArmOwnerRepairPolicy.armContradictsRecord(SelfArmWatchdogEnsureResult.FAILED))
        assertFalse(
            SelfArmOwnerRepairPolicy.armContradictsRecord(SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE),
        )
        assertFalse(SelfArmOwnerRepairPolicy.armContradictsRecord(SelfArmWatchdogEnsureResult.READY))
    }

    @Test
    fun `only an arm this boot with its bridge running and no failed demand reads alive`() {
        fun record(armedAt: Long, bridge: Boolean, demand: Boolean) =
            SelfArmOwnerRepairPolicy.helperRecord(armedAt, bridge, boot, demand)

        assertEquals(HelperRecord.ALIVE_THIS_BOOT, record(boot, bridge = true, demand = false))
        assertEquals(HelperRecord.NOT_ALIVE, record(boot - 3_600_000L, bridge = true, demand = false))
        assertEquals(HelperRecord.NOT_ALIVE, record(boot, bridge = false, demand = false))
        assertEquals(HelperRecord.NOT_ALIVE, record(boot, bridge = true, demand = true))
        assertEquals(
            HelperRecord.NOT_ALIVE,
            SelfArmOwnerRepairPolicy.helperRecord(boot, true, boot, false, armFailedSince = true),
        )
        assertTrue(SelfArmOwnerRepairPolicy.mayTryBridgeBeforePopup(HelperRecord.ALIVE_THIS_BOOT))
        assertFalse(SelfArmOwnerRepairPolicy.mayTryBridgeBeforePopup(HelperRecord.NOT_ALIVE))
    }

    @Test
    fun `a measured arm decides between healthy, repaired and partial`() {
        fun verdict(bridge: Boolean, before: HelperRecord) =
            SelfArmOwnerRepairPolicy.verdict(armed = true, bridgeRunning = bridge, before = before, pairing = null)

        assertEquals(GlassesRepairContract.RESULT_ALREADY_HEALTHY, verdict(true, HelperRecord.ALIVE_THIS_BOOT))
        assertEquals(GlassesRepairContract.RESULT_REPAIRED, verdict(true, HelperRecord.NOT_ALIVE))
        // Unknown before, measured running now: it is running, which is what the owner is told.
        assertEquals(GlassesRepairContract.RESULT_REPAIRED, verdict(true, HelperRecord.ABSENT))
        assertEquals(GlassesRepairContract.RESULT_PARTIAL, verdict(false, HelperRecord.ALIVE_THIS_BOOT))
        assertEquals(GlassesRepairContract.RESULT_PARTIAL, verdict(false, HelperRecord.ABSENT))
    }

    @Test
    fun `what the arm found before it acted wins over the record`() {
        fun verdict(bridge: Boolean, before: HelperRecord, wasRunning: Boolean?) =
            SelfArmOwnerRepairPolicy.verdict(
                armed = true,
                bridgeRunning = bridge,
                before = before,
                pairing = null,
                helperRunningBeforeArm = wasRunning,
            )

        // Device test 8.2: alive on record, both stopped by hand, restarted by the arm.
        assertEquals(
            GlassesRepairContract.RESULT_REPAIRED,
            verdict(true, HelperRecord.ALIVE_THIS_BOOT, wasRunning = false),
        )
        assertEquals(
            GlassesRepairContract.RESULT_ALREADY_HEALTHY,
            verdict(true, HelperRecord.ALIVE_THIS_BOOT, wasRunning = true),
        )
        assertEquals(
            GlassesRepairContract.RESULT_ALREADY_HEALTHY,
            verdict(true, HelperRecord.NOT_ALIVE, wasRunning = true),
        )
        assertEquals(
            GlassesRepairContract.RESULT_REPAIRED,
            verdict(true, HelperRecord.ABSENT, wasRunning = false),
        )
        // Partial stays partial whatever was running before.
        assertEquals(
            GlassesRepairContract.RESULT_PARTIAL,
            verdict(false, HelperRecord.ALIVE_THIS_BOOT, wasRunning = true),
        )
        assertEquals(
            GlassesRepairContract.RESULT_PARTIAL,
            verdict(false, HelperRecord.ALIVE_THIS_BOOT, wasRunning = false),
        )
        // An arm that could not tell falls back to the record, as before.
        assertEquals(
            GlassesRepairContract.RESULT_ALREADY_HEALTHY,
            verdict(true, HelperRecord.ALIVE_THIS_BOOT, wasRunning = null),
        )
        assertEquals(
            GlassesRepairContract.RESULT_REPAIRED,
            verdict(true, HelperRecord.NOT_ALIVE, wasRunning = null),
        )
        // A failed arm is never rescued by the measurement either.
        assertEquals(
            GlassesRepairContract.RESULT_ARM_FAILED,
            SelfArmOwnerRepairPolicy.verdict(
                armed = false,
                bridgeRunning = false,
                before = HelperRecord.ALIVE_THIS_BOOT,
                pairing = null,
                helperRunningBeforeArm = true,
            ),
        )
    }

    @Test
    fun `no arm means pairing pending, pairing required or a failed arm`() {
        fun verdict(pairing: SelfPairingOutcome?) =
            SelfArmOwnerRepairPolicy.verdict(
                armed = false,
                bridgeRunning = false,
                before = HelperRecord.ABSENT,
                pairing = pairing,
            )

        assertEquals(GlassesRepairContract.RESULT_PAIRING_PENDING, verdict(SelfPairingOutcome.STARTED))
        assertEquals(GlassesRepairContract.RESULT_PAIRING_PENDING, verdict(SelfPairingOutcome.ALREADY_IN_FLIGHT))
        assertEquals(GlassesRepairContract.RESULT_PAIRING_REQUIRED, verdict(SelfPairingOutcome.SERVICE_UNAVAILABLE))
        assertEquals(GlassesRepairContract.RESULT_PAIRING_REQUIRED, verdict(SelfPairingOutcome.BUDGET_EXHAUSTED))
        // Paired but the arm still failed: nothing about pairing to report.
        assertEquals(GlassesRepairContract.RESULT_ARM_FAILED, verdict(null))
        // The record never rescues a failed measurement.
        assertEquals(
            GlassesRepairContract.RESULT_ARM_FAILED,
            SelfArmOwnerRepairPolicy.verdict(false, false, HelperRecord.ALIVE_THIS_BOOT, null),
        )
    }

    @Test
    fun `an arm that never reached the helper says the glasses could not be reached`() {
        fun verdict(pairing: SelfPairingOutcome?) = SelfArmOwnerRepairPolicy.verdict(
            armed = false,
            bridgeRunning = false,
            before = HelperRecord.ALIVE_THIS_BOOT,
            pairing = pairing,
            sessionUnreachable = true,
        )

        // Device test 8.9: helper running, Wi-Fi up, session not yet reachable — not "could not restore".
        assertEquals(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE, verdict(null))
        // A credential the daemon refused and the arm dropped still asks for a pairing.
        assertEquals(GlassesRepairContract.RESULT_PAIRING_PENDING, verdict(SelfPairingOutcome.STARTED))
        // Reachable later in the same repair, a measured arm decides as usual.
        assertEquals(
            GlassesRepairContract.RESULT_REPAIRED,
            SelfArmOwnerRepairPolicy.verdict(
                armed = true,
                bridgeRunning = true,
                before = HelperRecord.NOT_ALIVE,
                pairing = null,
                sessionUnreachable = true,
            ),
        )
    }

    @Test
    fun `the arm waits for the network, then for the tls port`() {
        val clock = FakeClock()
        val networkAt = 3_000L
        val portAt = 5_000L
        var wirelessDebuggingAskedAt: Long? = null

        val readiness = SelfArmOwnerRepairPolicy.awaitArmReadiness(
            networkReady = { clock.nowMs >= networkAt },
            enableWirelessDebugging = { wirelessDebuggingAskedAt = clock.nowMs },
            readTlsPort = { if (clock.nowMs >= portAt) 37_111 else 0 },
            nowMs = clock::now,
            sleep = clock::sleep,
        )

        assertEquals(SelfArmOwnerRepairPolicy.ArmReadiness.READY, readiness)
        assertEquals(3_000L, wirelessDebuggingAskedAt)
        assertEquals(5_000L, clock.nowMs)
    }

    @Test
    fun `readiness gives up at its bound and says what was missing`() {
        val noNetwork = FakeClock()
        assertEquals(
            SelfArmOwnerRepairPolicy.ArmReadiness.NO_NETWORK,
            SelfArmOwnerRepairPolicy.awaitArmReadiness(
                networkReady = { false },
                enableWirelessDebugging = { error("never reached") },
                readTlsPort = { error("never reached") },
                nowMs = noNetwork::now,
                sleep = noNetwork::sleep,
            ),
        )
        assertEquals(SelfArmOwnerRepairPolicy.WIFI_READY_TIMEOUT_MS, noNetwork.nowMs)

        val noPort = FakeClock()
        assertEquals(
            SelfArmOwnerRepairPolicy.ArmReadiness.NO_TLS_PORT,
            SelfArmOwnerRepairPolicy.awaitArmReadiness(
                networkReady = { true },
                enableWirelessDebugging = {},
                readTlsPort = { 0 },
                nowMs = noPort::now,
                sleep = noPort::sleep,
            ),
        )
        assertEquals(SelfArmOwnerRepairPolicy.WIFI_READY_TIMEOUT_MS, noPort.nowMs)
    }

    @Test
    fun `a ready radio does not wait at all`() {
        val clock = FakeClock()

        val readiness = SelfArmOwnerRepairPolicy.awaitArmReadiness(
            networkReady = { true },
            enableWirelessDebugging = {},
            readTlsPort = { 37_111 },
            nowMs = clock::now,
            sleep = clock::sleep,
        )

        assertEquals(SelfArmOwnerRepairPolicy.ArmReadiness.READY, readiness)
        assertEquals(0L, clock.nowMs)
    }

    @Test
    fun `an unreachable arm right after the bring-up is retried, boundedly`() {
        fun retry(
            result: SelfArmWatchdogEnsureResult? = SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE,
            attempt: Int = 1,
            msSinceWifiUp: Long? = 12_000L,
            bootstrapComplete: Boolean = true,
        ) = SelfArmOwnerRepairPolicy.mayRetryArm(result, attempt, msSinceWifiUp, bootstrapComplete)

        assertTrue(retry())
        assertTrue(retry(attempt = SelfArmOwnerRepairPolicy.UNREACHABLE_ARM_ATTEMPTS - 1))
        // Another arm held the single flight and did not land: this repair measured nothing.
        assertTrue(retry(result = null))

        assertFalse(retry(attempt = SelfArmOwnerRepairPolicy.UNREACHABLE_ARM_ATTEMPTS))
        assertFalse(retry(msSinceWifiUp = SelfArmOwnerRepairPolicy.UNREACHABLE_RETRY_WINDOW_MS))
        // The radio was already up: unreachable is the answer, not a race with the bring-up.
        assertFalse(retry(msSinceWifiUp = null))
        // A measurement is final.
        assertFalse(retry(result = SelfArmWatchdogEnsureResult.READY))
        assertFalse(retry(result = SelfArmWatchdogEnsureResult.FAILED))
        // A refused credential the arm just dropped needs a pairing, not another arm.
        assertFalse(retry(bootstrapComplete = false))
    }

    @Test
    fun `only a heartbeating run or a leased manual arm makes the owner wait`() {
        fun disposition(active: Boolean, running: Boolean, manual: Boolean) =
            SelfArmOwnerRepairPolicy.sessionDisposition(active, running, manual)

        assertEquals(SessionDisposition.PROCEED, disposition(active = false, running = false, manual = false))
        assertEquals(SessionDisposition.BUSY, disposition(active = true, running = true, manual = false))
        assertEquals(SessionDisposition.BUSY, disposition(active = true, running = false, manual = true))
        // Paused, never started, running on an expired lease, or a manual flow left without a
        // lease: nothing is driving it.
        assertEquals(SessionDisposition.SUPERSEDE, disposition(active = true, running = false, manual = false))
    }

    private class FakeClock {
        var nowMs = 0L

        fun now(): Long = nowMs

        fun sleep(ms: Long) {
            nowMs += ms
        }
    }
}
