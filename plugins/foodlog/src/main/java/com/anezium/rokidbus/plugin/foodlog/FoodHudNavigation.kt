package com.anezium.rokidbus.plugin.foodlog

/** Bounds wire rows while leaving viewport measurement and selected-row scrolling to the hub. */
internal fun <T> foodHudWindow(items: List<T>, selectedIndex: Int): List<IndexedValue<T>> {
    if (items.isEmpty()) return emptyList()
    val start = selectedIndex.coerceIn(0, items.lastIndex) / FOOD_HUD_PAGE_ROWS * FOOD_HUD_PAGE_ROWS
    return items.subList(start, minOf(start + FOOD_HUD_PAGE_ROWS, items.size))
        .mapIndexed { offset, item -> IndexedValue(start + offset, item) }
}

private const val FOOD_HUD_PAGE_ROWS = 20
