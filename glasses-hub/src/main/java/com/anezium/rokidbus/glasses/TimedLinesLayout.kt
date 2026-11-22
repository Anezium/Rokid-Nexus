package com.anezium.rokidbus.glasses

/** Text sizes, in sp, the timed-lines renderer uses for one [TimedTextSize]. */
internal data class TimedTextSizes(
    val bodyMaxSp: Int,
    val bodyMinSp: Int,
    val previousSp: Float,
    val nextSp: Float,
)

internal fun TimedTextSize.sizes(): TimedTextSizes = when (this) {
    TimedTextSize.SMALL -> TimedTextSizes(bodyMaxSp = 20, bodyMinSp = 12, previousSp = 13f, nextSp = 14f)
    TimedTextSize.NORMAL -> TimedTextSizes(bodyMaxSp = 25, bodyMinSp = 14, previousSp = 15f, nextSp = 17f)
    TimedTextSize.LARGE -> TimedTextSizes(bodyMaxSp = 30, bodyMinSp = 14, previousSp = 17f, nextSp = 20f)
}

/**
 * The largest size from [maxSp] down to [minSp] whose wrapped text stays within
 * [maxLines]. A top-placed line wraps its own height, so platform auto-size has
 * no fixed box to fit into; the line budget stands in for one.
 */
internal fun fitTimedLineSp(
    maxSp: Int,
    minSp: Int,
    maxLines: Int,
    lineCountAt: (Int) -> Int,
): Int {
    var sp = maxSp
    while (sp > minSp && lineCountAt(sp) > maxLines) sp--
    return sp
}
