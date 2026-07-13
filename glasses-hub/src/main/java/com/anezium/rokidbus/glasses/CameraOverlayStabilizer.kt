package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import com.anezium.rokidbus.shared.CameraOverlayItem

/** Damps id-addressed legacy items; adaptive paragraphs stay on current source geometry. */
internal class CameraOverlayStabilizer {
    private var current: List<CameraOverlayItem> = emptyList()

    fun update(next: List<CameraOverlayItem>): List<CameraOverlayItem> {
        val previousById = current.mapNotNull { item -> item.id?.let { it to item } }.toMap()
        current = next.map { item ->
            if (item.isAdaptiveParagraph()) return@map item
            val id = item.id ?: return@map item
            val previous = previousById[id]?.takeUnless { it.isAdaptiveParagraph() } ?: return@map item
            item.copy(box = previous.box.dampToward(item.box))
        }
        return current
    }

    private fun CameraOverlayBounds.dampToward(next: CameraOverlayBounds): CameraOverlayBounds =
        CameraOverlayBounds(
            left = left + (next.left - left) * POSITION_DAMPING_ALPHA,
            top = top + (next.top - top) * POSITION_DAMPING_ALPHA,
            right = right + (next.right - right) * POSITION_DAMPING_ALPHA,
            bottom = bottom + (next.bottom - bottom) * POSITION_DAMPING_ALPHA,
        )

    private companion object {
        const val POSITION_DAMPING_ALPHA = 0.35f
    }
}
