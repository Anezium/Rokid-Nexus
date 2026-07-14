package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLinkTransferPolicyTest {
    @Test
    fun `frozen mode blocks every live frame until explicitly ended with a new key frame`() {
        val policy = CameraLinkTransferPolicy()

        assertTrue(policy.shouldAdmitVideo(isKeyFrame = false))
        policy.beginFrozenMode(requestId = 41L)
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = false))
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = true))

        // Sending the frozen image does not alter policy state. Only the explicit end below does.
        assertTrue(policy.frozenModeActive)
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = true))

        assertTrue(policy.endFrozenMode(requestId = 41L))
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = false))
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = true))

        policy.onVideoWriteStarted(isKeyFrame = true)
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = false))
    }

    @Test
    fun `overlapping frozen modes resume only after the final explicit end`() {
        val policy = CameraLinkTransferPolicy()
        policy.beginFrozenMode(requestId = 10L)
        policy.beginFrozenMode(requestId = 11L)

        assertFalse(policy.endFrozenMode(requestId = 10L))
        assertFalse(policy.shouldAdmitVideo(isKeyFrame = true))
        assertFalse(policy.endFrozenMode(requestId = 99L))
        assertTrue(policy.endFrozenMode(requestId = 11L))
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = true))
    }

    @Test
    fun `reset restores ordinary video admission`() {
        val policy = CameraLinkTransferPolicy()
        policy.beginFrozenMode(requestId = 7L)
        policy.reset()

        assertFalse(policy.frozenModeActive)
        assertTrue(policy.shouldAdmitVideo(isKeyFrame = false))
    }
}
