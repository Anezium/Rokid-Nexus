package com.anezium.rokidbus.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraContractTest {
    @Test
    fun `all camera contract paths are protected with segment boundaries`() {
        listOf(
            BusPaths.CAMERA_SESSION_STATE,
            BusPaths.CAMERA_LINK_OFFER,
            BusPaths.CAMERA_FREEZE_RESULT,
            BusPaths.CAMERA_OVERLAY,
        ).forEach { path ->
            assertTrue(path, BusPaths.isProtectedCameraPath(path))
            assertTrue("$path/future", BusPaths.isProtectedCameraPath("$path/future"))
        }
        assertFalse(BusPaths.isProtectedCameraPath("/camera/session/stateful"))
        assertFalse(BusPaths.isProtectedCameraPath(BusPaths.LENS_LINK_OFFER))
    }

    @Test
    fun `camera readiness has an independent feature bit`() {
        assertTrue(BusCapabilityBits.CAMERA_CONSUMER_READY != BusCapabilityBits.PROTECTED_LENS_LINK)
        assertTrue(BusCapabilityBits.CAMERA_CONSUMER_READY != BusCapabilityBits.IMAGE_SURFACE)
    }
}
