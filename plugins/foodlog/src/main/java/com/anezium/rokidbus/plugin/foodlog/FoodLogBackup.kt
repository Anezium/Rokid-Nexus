package com.anezium.rokidbus.plugin.foodlog

import org.json.JSONArray
import org.json.JSONObject

internal data class FoodLogArchive(
    val entries: List<FoodEntry>,
    val products: List<FoodProduct>,
    val favoriteProductIds: Set<String>,
    val goals: NutritionGoals?,
    val recipes: List<FoodRecipe>,
    val reminders: List<FoodLogReminder>,
)

/** Bounded, versioned local backup codec. Parsing validates the whole archive before import. */
internal object FoodLogBackup {
    const val SCHEMA_VERSION = 3
    private const val LEGACY_SCHEMA_VERSION = 2
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PRODUCTS = 20_000
    private const val MAX_RECIPES = 5_000
    private const val MAX_REMINDERS = 5_000
    private const val MAX_TEXT = 300
    private const val MAX_BACKUP_CHARS = 12_000_000

    fun encode(archive: FoodLogArchive): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("products", JSONArray().apply { archive.products.forEach { put(it.toJson()) } })
        put("entries", JSONArray().apply { archive.entries.forEach { put(it.toJson()) } })
        put("favorites", JSONArray().apply { archive.favoriteProductIds.sorted().forEach(::put) })
        put("goals", archive.goals?.toJson() ?: JSONObject.NULL)
        put("recipes", JSONArray().apply { archive.recipes.forEach { put(it.toJson()) } })
        put("reminders", JSONArray().apply { archive.reminders.forEach { put(it.toJson()) } })
    }.toString()

    fun decode(text: String): FoodLogArchive {
        require(text.length <= MAX_BACKUP_CHARS) { "Backup is too large" }
        val root = JSONObject(text)
        return when (root.optInt("schemaVersion", -1)) {
            SCHEMA_VERSION -> decodeV3(root)
            LEGACY_SCHEMA_VERSION -> decodeV2(root)
            else -> throw IllegalArgumentException("Unsupported backup schema")
        }
    }

    private fun decodeV3(root: JSONObject): FoodLogArchive {
        val products = decodeProducts(requiredArray(root, "products"))
        val productById = products.associateBy(FoodProduct::barcode)
        require(productById.size == products.size) { "Duplicate product ID" }

        val entries = decodeEntries(requiredArray(root, "entries"))
        val favoritesArray = requiredArray(root, "favorites")
        require(favoritesArray.length() <= MAX_PRODUCTS) { "Too many favorites" }
        val favorites = buildSet {
            for (index in 0 until favoritesArray.length()) {
                val id = favoritesArray.getString(index)
                require(productId(id) == id && productById.containsKey(id)) { "Invalid favorite product" }
                require(add(id)) { "Duplicate favorite product" }
            }
        }
        val goals = if (root.isNull("goals")) null else goalsFromJson(root.getJSONObject("goals"))
        val recipes = decodeRecipes(requiredArray(root, "recipes"), productById)
        val recipeIds = recipes.mapTo(hashSetOf(), FoodRecipe::uuid)
        require(entries.all { it.recipeId == null || it.recipeId in recipeIds }) { "Missing entry recipe" }
        val reminders = decodeReminders(requiredArray(root, "reminders"))
        return FoodLogArchive(entries, products, favorites, goals, recipes, reminders)
    }

    private fun decodeV2(root: JSONObject): FoodLogArchive {
        val entries = decodeEntries(requiredArray(root, "entries"))
        val products = entries.asReversed().distinctBy { it.product.barcode }.map(FoodEntry::product)
        return FoodLogArchive(entries, products, emptySet(), null, emptyList(), emptyList())
    }

    private fun decodeProducts(array: JSONArray): List<FoodProduct> {
        require(array.length() <= MAX_PRODUCTS) { "Too many products" }
        return List(array.length()) { productFromJson(array.getJSONObject(it)) }
    }

    private fun decodeEntries(array: JSONArray): List<FoodEntry> {
        require(array.length() <= MAX_ENTRIES) { "Too many entries" }
        val seen = HashSet<String>()
        return List(array.length()) { index ->
            entryFromJson(array.getJSONObject(index)).also {
                require(seen.add(it.uuid)) { "Duplicate entry UUID" }
            }
        }
    }

    private fun decodeRecipes(array: JSONArray, products: Map<String, FoodProduct>): List<FoodRecipe> {
        require(array.length() <= MAX_RECIPES) { "Too many recipes" }
        val seen = HashSet<String>()
        return List(array.length()) { index ->
            val value = array.getJSONObject(index)
            val uuid = requiredText(value, "uuid", 36)
            require(seen.add(uuid)) { "Duplicate recipe UUID" }
            val ingredientsArray = requiredArray(value, "ingredients")
            require(ingredientsArray.length() in 1..MAX_RECIPE_INGREDIENTS) { "Invalid recipe ingredients" }
            val ingredients = List(ingredientsArray.length()) { ingredientIndex ->
                val ingredient = ingredientsArray.getJSONObject(ingredientIndex)
                val productId = requiredText(ingredient, "productId", 70)
                val product = products[productId] ?: throw IllegalArgumentException("Missing recipe product")
                RecipeIngredient(product, requiredNumber(ingredient, "grams", MIN_QUANTITY_GRAMS, MAX_RECIPE_INGREDIENT_GRAMS))
            }
            FoodRecipe(
                uuid = uuid,
                name = requiredText(value, "name", MAX_RECIPE_NAME_CHARS),
                servings = requiredNumber(value, "servings", 0.25, 100.0),
                ingredients = ingredients,
                createdAtMillis = requiredPositiveLong(value, "createdAtMillis"),
            )
        }
    }

    private fun decodeReminders(array: JSONArray): List<FoodLogReminder> {
        require(array.length() <= MAX_REMINDERS) { "Too many reminders" }
        val seen = HashSet<String>()
        return List(array.length()) { index ->
            val value = array.getJSONObject(index)
            val id = requiredText(value, "id", 36)
            require(FOOD_UUID_PATTERN.matches(id) && seen.add(id)) { "Invalid reminder UUID" }
            FoodLogReminder(
                id = id,
                kind = requiredEnum(value, "kind"),
                label = requiredText(value, "label", FoodLogReminderStore.MAX_LABEL_CHARS),
                epochMillis = requiredPositiveLong(value, "epochMillis"),
                enabled = value.getBoolean("enabled"),
            )
        }
    }

    private fun FoodEntry.toJson() = JSONObject().apply {
        put("uuid", uuid)
        put("consumedAtMillis", consumedAtMillis)
        put("quantityGrams", quantityGrams)
        put("mealType", mealType.name)
        put("source", source.name)
        put("recipeId", recipeId ?: JSONObject.NULL)
        put("product", product.toJson())
    }

    private fun FoodProduct.toJson() = JSONObject().apply {
        put("barcode", barcode)
        put("name", name)
        put("brand", brand)
        put("servingLabel", servingLabel ?: JSONObject.NULL)
        put("servingGrams", servingGrams ?: JSONObject.NULL)
        put("nutritionGrade", nutritionGrade ?: JSONObject.NULL)
        put("novaGroup", novaGroup ?: JSONObject.NULL)
        put("fetchedAtMillis", fetchedAtMillis)
        put("nutrients", JSONObject().apply {
            nutrientValues().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        })
    }

    private fun NutritionGoals.toJson() = JSONObject().apply {
        put("calories", caloriesKcal ?: JSONObject.NULL)
        put("protein", proteinGrams ?: JSONObject.NULL)
        put("carbohydrate", carbohydrateGrams ?: JSONObject.NULL)
        put("fat", fatGrams ?: JSONObject.NULL)
    }

    private fun FoodRecipe.toJson() = JSONObject().apply {
        put("uuid", uuid)
        put("name", name)
        put("servings", servings)
        put("createdAtMillis", createdAtMillis)
        put("ingredients", JSONArray().apply {
            ingredients.forEach { ingredient ->
                put(JSONObject().put("productId", ingredient.product.barcode).put("grams", ingredient.grams))
            }
        })
    }

    private fun FoodLogReminder.toJson() = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("label", label)
        .put("epochMillis", epochMillis)
        .put("enabled", enabled)

    private fun FoodProduct.nutrientValues() = listOf(
        "calories" to nutrients.caloriesKcal,
        "protein" to nutrients.proteinGrams,
        "carbohydrate" to nutrients.carbohydrateGrams,
        "fat" to nutrients.fatGrams,
        "sugars" to nutrients.sugarsGrams,
        "fiber" to nutrients.fiberGrams,
        "salt" to nutrients.saltGrams,
        "saturatedFat" to nutrients.saturatedFatGrams,
        "sodium" to nutrients.sodiumMilligrams,
        "cholesterol" to nutrients.cholesterolMilligrams,
        "potassium" to nutrients.potassiumMilligrams,
        "calcium" to nutrients.calciumMilligrams,
        "iron" to nutrients.ironMilligrams,
        "caffeine" to nutrients.caffeineMilligrams,
    )

    private fun entryFromJson(value: JSONObject): FoodEntry {
        val uuid = requiredText(value, "uuid", 64)
        require(FOOD_ENTRY_ID_PATTERN.matches(uuid)) { "Invalid entry UUID" }
        val recipeId = optionalText(value, "recipeId", 36)?.also {
            require(FOOD_UUID_PATTERN.matches(it)) { "Invalid recipe UUID" }
        }
        return FoodEntry(
            id = 0,
            consumedAtMillis = requiredPositiveLong(value, "consumedAtMillis"),
            quantityGrams = requiredNumber(value, "quantityGrams", MIN_QUANTITY_GRAMS, MAX_QUANTITY_GRAMS),
            product = productFromJson(value.getJSONObject("product")),
            uuid = uuid,
            mealType = requiredEnum(value, "mealType"),
            source = requiredEnum(value, "source"),
            recipeId = recipeId,
        )
    }

    private fun productFromJson(value: JSONObject): FoodProduct {
        val barcode = requiredText(value, "barcode", 70)
        require(productId(barcode) == barcode) { "Invalid product ID" }
        val servingGrams = optionalNumber(value, "servingGrams", 0.01, MAX_RECIPE_INGREDIENT_GRAMS)
        val novaGroup = if (!value.has("novaGroup") || value.isNull("novaGroup")) null else value.getInt("novaGroup").also {
            require(it in 1..4) { "Invalid NOVA group" }
        }
        val fetchedAt = value.optLong("fetchedAtMillis", 0L)
        require(fetchedAt >= 0L) { "Invalid fetched time" }
        return FoodProduct(
            barcode = barcode,
            name = requiredText(value, "name", MAX_TEXT),
            brand = boundedText(value, "brand", MAX_TEXT),
            servingLabel = optionalText(value, "servingLabel", MAX_TEXT),
            servingGrams = servingGrams,
            nutritionGrade = optionalText(value, "nutritionGrade", 20),
            novaGroup = novaGroup,
            nutrients = nutrients(value.getJSONObject("nutrients")),
            fetchedAtMillis = fetchedAt,
        )
    }

    private fun goalsFromJson(value: JSONObject) = NutritionGoals(
        caloriesKcal = optionalNumber(value, "calories", 1.0, 20_000.0),
        proteinGrams = optionalNumber(value, "protein", 1.0, 2_000.0),
        carbohydrateGrams = optionalNumber(value, "carbohydrate", 1.0, 3_000.0),
        fatGrams = optionalNumber(value, "fat", 1.0, 2_000.0),
    )

    private fun nutrients(value: JSONObject): NutrientsPer100g = NutrientsPer100g(
        caloriesKcal = nutrient(value, "calories"),
        proteinGrams = nutrient(value, "protein"),
        carbohydrateGrams = nutrient(value, "carbohydrate"),
        fatGrams = nutrient(value, "fat"),
        sugarsGrams = nutrient(value, "sugars"),
        fiberGrams = nutrient(value, "fiber"),
        saltGrams = nutrient(value, "salt"),
        saturatedFatGrams = nutrient(value, "saturatedFat"),
        sodiumMilligrams = nutrient(value, "sodium"),
        cholesterolMilligrams = nutrient(value, "cholesterol"),
        potassiumMilligrams = nutrient(value, "potassium"),
        calciumMilligrams = nutrient(value, "calcium"),
        ironMilligrams = nutrient(value, "iron"),
        caffeineMilligrams = nutrient(value, "caffeine"),
    )

    private fun nutrient(value: JSONObject, key: String): Double? =
        optionalNumber(value, key, 0.0, 1_000_000_000.0)

    private fun requiredArray(value: JSONObject, key: String): JSONArray =
        value.optJSONArray(key) ?: throw IllegalArgumentException("Missing $key")

    private fun requiredPositiveLong(value: JSONObject, key: String): Long = value.getLong(key).also {
        require(it > 0L) { "Invalid $key" }
    }

    private fun requiredNumber(value: JSONObject, key: String, minimum: Double, maximum: Double): Double =
        value.getDouble(key).also { require(it.isFinite() && it in minimum..maximum) { "Invalid $key" } }

    private fun optionalNumber(value: JSONObject, key: String, minimum: Double, maximum: Double): Double? {
        if (!value.has(key) || value.isNull(key)) return null
        return requiredNumber(value, key, minimum, maximum)
    }

    private fun requiredText(value: JSONObject, key: String, maximum: Int): String =
        boundedText(value, key, maximum).also { require(it.isNotEmpty()) { "Missing $key" } }

    private fun boundedText(value: JSONObject, key: String, maximum: Int): String =
        value.getString(key).trim().also { require(it.length <= maximum) { "$key is too long" } }

    private fun optionalText(value: JSONObject, key: String, maximum: Int): String? {
        if (!value.has(key) || value.isNull(key)) return null
        return boundedText(value, key, maximum).takeIf(String::isNotEmpty)
    }

    private inline fun <reified T : Enum<T>> requiredEnum(value: JSONObject, key: String): T {
        val raw = requiredText(value, key, 40)
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Invalid $key")
    }
}
