package com.anezium.rokidbus.glasses

/** Ambient content yields its presentation, retaining its clock and owner. */
internal object WidgetEligibility {
    fun visible(
        hasContent: Boolean,
        foregroundSurface: Boolean,
        launcher: Boolean,
        notice: Boolean,
        camera: Boolean,
        assistant: Boolean,
        foreignFullscreen: Boolean,
        stale: Boolean,
    ): Boolean = hasContent && !foregroundSurface && !launcher && !notice &&
        !camera && !assistant && !foreignFullscreen && !stale
}
