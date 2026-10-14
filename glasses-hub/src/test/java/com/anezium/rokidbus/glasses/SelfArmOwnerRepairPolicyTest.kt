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
    fun `an absent record is unknown and never answered healthy unmeasured`() {
        val record = SelfArmOwnerRepairPolicy.helperRecord(
            armedBootInstantMs = null,
            armedBridgeRunning = false,
            currentBootInstantMs = boot,
            bridgeDemandPending = false,
        )
        assertEquals(HelperRecord.ABSENT, record)
        assertFalse(SelfArmOwnerRepairPolicy.mayAnswerHealthyWithoutMeasuring(record))
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
        assertTrue(SelfArmOwnerRepairPolicy.mayAnswerHealthyWithoutMeasuring(HelperRecord.ALIVE_THIS_BOOT))
        assertFalse(SelfArmOwnerRepairPolicy.mayAnswerHealthyWithoutMeasuring(HelperRecord.NOT_ALIVE))
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
}
