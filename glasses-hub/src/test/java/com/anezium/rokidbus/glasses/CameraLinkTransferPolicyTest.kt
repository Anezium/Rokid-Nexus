package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLinkTransferPolicyTest {
    @Test
    fun `frozen transfer blocks every live frame until a new key frame`() {
        val policy = CameraLinkTransferPolicy()

        assertTrue(policy.shouldAdmitVideo(isKeyFrame = false))
        policy.beginFrozen(requestId = 41L)
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = false))
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = true))

        assertTrue(policy.finishFrozen(requestId = 41L))
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = false))
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = true))

        policy.onVideoWriteStarted(isKeyFrame = true)
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = false))
    }

    @Test
    fun `overlapping frozen transfers resume only after the final packet`() {
        val policy = CameraLinkTransferPolicy()
        policy.beginFrozen(requestId = 10L)
        policy.beginFrozen(requestId = 11L)

        assertFalse(policy.finishFrozen(requestId = 10L))
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = true))
        assertFalse(policy.finishFrozen(requestId = 99L))
        assertTrue(policy.finishFrozen(requestId = 11L))
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = true))
    }

    @Test
    fun `reset restores ordinary video admission`() {
        val policy = CameraLinkTransferPolicy()
        policy.beginFrozen(requestId = 7L)
        policy.reset()

        assertFalse(policy.frozenTransferActive)
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = false))
    }
}
