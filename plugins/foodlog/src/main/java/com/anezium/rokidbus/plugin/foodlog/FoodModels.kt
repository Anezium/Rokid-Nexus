package com.anezium.rokidbus.plugin.foodlog

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal data class NutrientsPer100g(
    val caloriesKcal: Double?,
    val proteinGrams: Double?,
    val carbohydrateGrams: Double?,
    val fatGrams: Double?,
    val sugarsGrams: Double?,
    val fiberGrams: Double?,
    val saltGrams: Double?,
)

internal data class FoodProduct(
    val barcode: String,
    val name: String,
    val brand: String,
    val servingLabel: String?,
    val servingGrams: Double?,
    val nutritionGrade: String?,
    val novaGroup: Int?,
    val nutrients: NutrientsPer100g,
    val fetchedAtMillis: Long,
)

internal data class FoodEntry(
    val id: Long,
    val consumedAtMillis: Long,
    val quantityGrams: Double,
    val product: FoodProduct,
)

internal data class NutritionTotal(
    val knownValue: Double,
    val complete: Boolean,
)

internal data class DailyNutritionTotals(
    val entryCount: Int,
    val caloriesKcal: NutritionTotal,
    val proteinGrams: NutritionTotal,
    val carbohydrateGrams: NutritionTotal,
    val fatGrams: NutritionTotal,
)

internal fun aggregateNutrition(entries: List<FoodEntry>): DailyNutritionTotals = DailyNutritionTotals(
    entryCount = entries.size,
    caloriesKcal = aggregate(entries) { it.product.nutrients.caloriesKcal },
    proteinGrams = aggregate(entries) { it.product.nutrients.proteinGrams },
    carbohydrateGrams = aggregate(entries) { it.product.nutrients.carbohydrateGrams },
    fatGrams = aggregate(entries) { it.product.nutrients.fatGrams },
)

private fun aggregate(
    entries: List<FoodEntry>,
    value: (FoodEntry) -> Double?,
): NutritionTotal {
    var sum = 0.0
    var complete = true
    entries.forEach { entry ->
        val per100g = value(entry)
        if (per100g == null) {
            complete = false
        } else {
            sum += per100g * entry.quantityGrams / 100.0
        }
    }
    return NutritionTotal(sum, complete)
}

internal fun scaledValue(per100g: Double?, quantityGrams: Double): Double? =
    per100g?.times(quantityGrams)?.div(100.0)

internal fun NutritionTotal.display(unit: String): String = when {
    !complete && knownValue == 0.0 -> "unknown"
    !complete -> "≥ ${formatNutritionNumber(knownValue)} $unit"
    else -> "${formatNutritionNumber(knownValue)} $unit"
}

internal fun Double?.displayPer100g(unit: String): String =
    this?.let { "${formatNutritionNumber(it)} $unit" } ?: "unknown"

internal fun formatNutritionNumber(value: Double): String {
    val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
    return DecimalFormat("0.#", symbols).format(value.coerceAtLeast(0.0))
}

internal fun normalizeBarcode(raw: String): String? {
    val normalized = raw.filter(Char::isDigit)
    return normalized.takeIf { it.length in 4..14 }
}

internal fun dayBounds(
    atMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): LongRange {
    val date = Instant.ofEpochMilli(atMillis).atZone(zoneId).toLocalDate()
    val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endExclusive = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    return start until endExclusive
}
