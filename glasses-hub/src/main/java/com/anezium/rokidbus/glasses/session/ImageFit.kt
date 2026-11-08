package com.anezium.rokidbus.glasses.session

/** Where an image page's picture lands in the box its column leaves it: scaled to fit, aspect kept, centred. */
internal data class FittedImage(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

internal object ImageFit {
    /**
     * Fits [imageWidth] x [imageHeight] inside [boxWidth] x [boxHeight]. A picture smaller than
     * the box on both edges is still scaled up to the box: on the HUD a 240 px capture preview
     * is a glance, not a thumbnail. One-bit art is scaled by whole factors only, so its pixels
     * stay square and its dither pattern is not blurred into grey.
     */
    fun fit(imageWidth: Int, imageHeight: Int, boxWidth: Int, boxHeight: Int, wholeFactors: Boolean = false): FittedImage? {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return null
        var scale = minOf(boxWidth.toFloat() / imageWidth, boxHeight.toFloat() / imageHeight)
        if (wholeFactors && scale >= 1f) scale = kotlin.math.floor(scale)
        val width = (imageWidth * scale).toInt().coerceIn(1, boxWidth)
        val height = (imageHeight * scale).toInt().coerceIn(1, boxHeight)
        return FittedImage((boxWidth - width) / 2, (boxHeight - height) / 2, width, height)
    }
}
