package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraP2pProfileRecoveryPolicyTest {
    private val policy = CameraP2pProfileRecoveryPolicy()

    @Test
    fun `first recreation reuses stable credentials`() {
        assertEquals(
            CameraP2pProfileRecoveryAction.REUSE,
            policy.actionFor(completedRecreates = 0),
        )
    }

    @Test
    fun `second recreation rotates after the reused profile window fails`() {
        assertEquals(
            CameraP2pProfileRecoveryAction.ROTATE,
            policy.actionFor(completedRecreates = 1),
        )
    }
}
