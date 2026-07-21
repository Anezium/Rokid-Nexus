package com.anezium.rokidbus.phone

internal object GlassesControlLevelPolicy {
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 100

    fun parseAndClamp(value: Any?): Int? =
        (value as? Int)?.coerceIn(MIN_LEVEL, MAX_LEVEL)
}
