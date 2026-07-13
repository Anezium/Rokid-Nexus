package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayContract
import com.anezium.rokidbus.shared.CameraOverlayItem
import com.anezium.rokidbus.shared.CameraOverlayLayout

internal fun CameraOverlayItem.isAdaptiveParagraph(): Boolean = layout.isValidParagraphLayout()

internal fun CameraOverlayLayout?.isValidParagraphLayout(): Boolean {
    val value = this ?: return false
    return value.kind == CameraOverlayContract.PARAGRAPH_LAYOUT_KIND &&
        value.version == CameraOverlayContract.PARAGRAPH_LAYOUT_VERSION &&
        value.medianLineHeight.isFinite() && value.medianLineHeight > 0f && value.medianLineHeight <= 1f &&
        value.growDown.isFinite() && value.growDown in 0f..1f &&
        (value.column == null || value.column in 0..CameraOverlayContract.MAX_LAYOUT_COLUMN)
}
