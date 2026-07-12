package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import com.anezium.rokidbus.shared.CameraOverlayItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOverlayStabilizerTest {
    @Test
    fun idAddressedItemReusesAndDampsItsPreviousBox() {
        val stabilizer = CameraOverlayStabilizer()
        stabilizer.update(listOf(item("track", left = 0.1f)))
        val updated = stabilizer.update(listOf(item("track", left = 0.3f))).single()
        assertEquals(0.17f, updated.box.left, 0.0001f)
    }

    @Test
    fun anonymousItemKeepsLegacyImmediatePosition() {
        val stabilizer = CameraOverlayStabilizer()
        stabilizer.update(listOf(item(null, left = 0.1f)))
        val updated = stabilizer.update(listOf(item(null, left = 0.3f))).single()
        assertEquals(0.3f, updated.box.left, 0f)
    }

    private fun item(id: String?, left: Float): CameraOverlayItem =
        CameraOverlayItem(
            text = "text",
            box = CameraOverlayBounds(left, 0.1f, left + 0.2f, 0.2f),
            role = "source",
            id = id,
        )
}
