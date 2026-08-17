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
    val saturatedFatGrams: Double? = null,
    val sodiumMilligrams: Double? = null,
    val cholesterolMilligrams: Double? = null,
    val potassiumMilligrams: Double? = null,
    val calciumMilligrams: Double? = null,
    val ironMilligrams: Double? = null,
    val caffeineMilligrams: Double? = null,
)

internal enum class MealType(val displayName: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack"),
    UNKNOWN("Unassigned"),
}

internal enum class FoodEntrySource { SCANNED, SEARCHED, CUSTOM, RECIPE, IMPORTED, UNKNOWN }

internal data class NutritionGoals(
    val caloriesKcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val fatGrams: Double? = null,
) {
    init {
        require(caloriesKcal == null || caloriesKcal in 1.0..20_000.0)
        require(proteinGrams == null || proteinGrams in 1.0..2_000.0)
        require(carbohydrateGrams == null || carbohydrateGrams in 1.0..3_000.0)
        require(fatGrams == null || fatGrams in 1.0..2_000.0)
    }
}

internal data class RecipeIngredient(val product: FoodProduct, val grams: Double) {
    init {
        require(grams in MIN_QUANTITY_GRAMS..MAX_RECIPE_INGREDIENT_GRAMS)
    }
}

internal data class FoodRecipe(
    val uuid: String,
    val name: String,
    val servings: Double,
    val ingredients: List<RecipeIngredient>,
    val createdAtMillis: Long,
) {
    init {
        require(uuid.matches(FOOD_UUID_PATTERN))
        require(name.isNotBlank() && name.length <= MAX_RECIPE_NAME_CHARS)
        require(servings in 0.25..100.0)
        require(ingredients.size in 1..MAX_RECIPE_INGREDIENTS)
        require(ingredients.map { it.product.barcode }.toSet().size == ingredients.size)
    }

    val totalIngredientGrams: Double
        get() = ingredients.sumOf(RecipeIngredient::grams)

    fun nutrientsPerServing(): NutrientsPer100g = nutrientsForIngredients(ingredients, servings)

    fun asProduct(fetchedAtMillis: Long = createdAtMillis): FoodProduct = FoodProduct(
        barcode = "recipe-$uuid",
        name = name,
        brand = "Recipe",
        servingLabel = "1 serving",
        servingGrams = totalIngredientGrams / servings,
        nutritionGrade = null,
        novaGroup = null,
        nutrients = nutrientsForIngredients(ingredients, totalIngredientGrams / 100.0),
        fetchedAtMillis = fetchedAtMillis,
    )
}

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
    val uuid: String = "",
    val mealType: MealType = MealType.UNKNOWN,
    val source: FoodEntrySource = FoodEntrySource.UNKNOWN,
    val recipeId: String? = null,
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
    val saturatedFatGrams: NutritionTotal = NutritionTotal(0.0, true),
    val sodiumMilligrams: NutritionTotal = NutritionTotal(0.0, true),
    val cholesterolMilligrams: NutritionTotal = NutritionTotal(0.0, true),
    val potassiumMilligrams: NutritionTotal = NutritionTotal(0.0, true),
    val calciumMilligrams: NutritionTotal = NutritionTotal(0.0, true),
    val ironMilligrams: NutritionTotal = NutritionTotal(0.0, true),
    val caffeineMilligrams: NutritionTotal = NutritionTotal(0.0, true),
)

internal fun aggregateNutrition(entries: List<FoodEntry>): DailyNutritionTotals = DailyNutritionTotals(
    entryCount = entries.size,
    caloriesKcal = aggregate(entries) { it.product.nutrients.caloriesKcal },
    proteinGrams = aggregate(entries) { it.product.nutrients.proteinGrams },
    carbohydrateGrams = aggregate(entries) { it.product.nutrients.carbohydrateGrams },
    fatGrams = aggregate(entries) { it.product.nutrients.fatGrams },
    saturatedFatGrams = aggregate(entries) { it.product.nutrients.saturatedFatGrams },
    sodiumMilligrams = aggregate(entries) { it.product.nutrients.sodiumMilligrams },
    cholesterolMilligrams = aggregate(entries) { it.product.nutrients.cholesterolMilligrams },
    potassiumMilligrams = aggregate(entries) { it.product.nutrients.potassiumMilligrams },
    calciumMilligrams = aggregate(entries) { it.product.nutrients.calciumMilligrams },
    ironMilligrams = aggregate(entries) { it.product.nutrients.ironMilligrams },
    caffeineMilligrams = aggregate(entries) { it.product.nutrients.caffeineMilligrams },
)

internal fun nutrientsForIngredients(ingredients: List<RecipeIngredient>, servings: Double): NutrientsPer100g {
    require(servings > 0.0)
    fun value(selector: (NutrientsPer100g) -> Double?): Double? {
        var unknown = false; var total = 0.0
        ingredients.forEach { ingredient ->
            if (ingredient.grams <= 0.0) unknown = true
            val v = selector(ingredient.product.nutrients)
            if (v == null) unknown = true else total += v * ingredient.grams / 100.0
        }
        return if (unknown) null else total / servings
    }
    return NutrientsPer100g(value { it.caloriesKcal }, value { it.proteinGrams }, value { it.carbohydrateGrams }, value { it.fatGrams }, value { it.sugarsGrams }, value { it.fiberGrams }, value { it.saltGrams }, value { it.saturatedFatGrams }, value { it.sodiumMilligrams }, value { it.cholesterolMilligrams }, value { it.potassiumMilligrams }, value { it.calciumMilligrams }, value { it.ironMilligrams }, value { it.caffeineMilligrams })
}

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

internal fun inferredMealType(
    atMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): MealType = when (Instant.ofEpochMilli(atMillis).atZone(zoneId).hour) {
    in 5..10 -> MealType.BREAKFAST
    in 11..15 -> MealType.LUNCH
    in 16..21 -> MealType.DINNER
    else -> MealType.SNACK
}

internal val FOOD_UUID_PATTERN = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)
internal val FOOD_ENTRY_ID_PATTERN = Regex("(?:${FOOD_UUID_PATTERN.pattern}|[0-9a-f]{32})")
internal const val MAX_RECIPE_NAME_CHARS = 120
internal const val MAX_RECIPE_INGREDIENTS = 64
internal const val MAX_RECIPE_INGREDIENT_GRAMS = 20_000.0
