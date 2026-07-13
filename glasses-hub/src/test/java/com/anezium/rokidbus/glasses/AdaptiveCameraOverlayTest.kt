package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import com.anezium.rokidbus.shared.CameraOverlayItem
import com.anezium.rokidbus.shared.CameraOverlayLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCameraOverlayTest {
    @Test
    fun validParagraphMetadataOptsIn() {
        assertTrue(item(layout()).isAdaptiveParagraph())
    }

    @Test
    fun invalidParagraphMetadataKeepsLegacyPath() {
        assertFalse(item(layout(kind = "line")).isAdaptiveParagraph())
        assertFalse(item(layout(version = 2)).isAdaptiveParagraph())
        assertFalse(item(layout(lineHeight = 0f)).isAdaptiveParagraph())
        assertFalse(item(layout(growDown = Float.NaN)).isAdaptiveParagraph())
        assertFalse(item(layout(column = -1)).isAdaptiveParagraph())
    }

    private fun item(layout: CameraOverlayLayout) = CameraOverlayItem(
        "translation", CameraOverlayBounds(0.1f, 0.1f, 0.4f, 0.2f), "translation", layout = layout,
    )

    private fun layout(
        kind: String = "paragraph",
        version: Int = 1,
        lineHeight: Float = 0.03f,
        growDown: Float = 0.08f,
        column: Int? = null,
    ) = CameraOverlayLayout(kind, version, lineHeight, growDown, column)
}
