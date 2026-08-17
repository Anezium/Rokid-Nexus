package com.anezium.rokidbus.plugin.foodlog

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.ZoneId
import java.util.UUID

internal const val MIN_QUANTITY_GRAMS = 1.0
internal const val MAX_QUANTITY_GRAMS = 5_000.0

internal data class FoodLogDatabaseImportResult(
    val insertedEntries: Int,
    val reminders: List<FoodLogReminder>,
)

/** The entry table deliberately contains a full nutrition snapshot. Product edits never rewrite history. */
internal class FoodLogStore(context: Context) : AutoCloseable {
    private val helper = FoodLogDatabase(context.applicationContext)

    @Synchronized fun product(barcode: String): FoodProduct? {
        val normalized = productId(barcode) ?: return null
        return helper.readableDatabase.query("products", PRODUCT_COLUMNS, "barcode=?", arrayOf(normalized), null, null, null).use { if (it.moveToFirst()) it.toProduct() else null }
    }
    @Synchronized fun upsertProduct(product: FoodProduct) { helper.writableDatabase.insertWithOnConflict("products", null, product.values(), SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun createCustomFood(
        name: String,
        nutrients: NutrientsPer100g,
        brand: String = "",
    ): FoodProduct {
        require(name.isNotBlank() && name.length <= 300)
        return FoodProduct("custom-" + UUID.randomUUID(), name.trim(), brand.trim(), null, null, null, null, nutrients, System.currentTimeMillis()).also(::upsertProduct)
    }
    @Synchronized fun addEntry(product: FoodProduct, quantityGrams: Double, consumedAtMillis: Long = System.currentTimeMillis()): Long =
        addEntry(product, quantityGrams, consumedAtMillis, MealType.UNKNOWN, FoodEntrySource.UNKNOWN)
    @Synchronized fun addEntry(product: FoodProduct, quantityGrams: Double, consumedAtMillis: Long, mealType: MealType, source: FoodEntrySource, recipeId: String? = null, uuid: String = UUID.randomUUID().toString()): Long {
        require(quantityGrams in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS)
        require(consumedAtMillis > 0L)
        require(FOOD_ENTRY_ID_PATTERN.matches(uuid))
        require(recipeId == null || FOOD_UUID_PATTERN.matches(recipeId))
        val db = helper.writableDatabase; db.beginTransaction()
        return try { db.insertWithOnConflict("products", null, product.values(), SQLiteDatabase.CONFLICT_REPLACE)
            db.insertOrThrow("entries", null, product.values().apply { remove("fetched_at"); put("consumed_at", consumedAtMillis); put("quantity_grams", quantityGrams); put("entry_uuid", uuid); put("meal_type", mealType.name); put("source", source.name); putNullable("recipe_id", recipeId) }).also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }
    @Synchronized fun entriesForDay(atMillis: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()): List<FoodEntry> { val b=dayBounds(atMillis,zoneId); return entriesBetween(b.first,b.last) }
    @Synchronized fun entriesBetween(startMillis: Long, endMillisInclusive: Long): List<FoodEntry> {
        require(startMillis <= endMillisInclusive)
        return helper.readableDatabase.query("entries", ENTRY_COLUMNS, "consumed_at>=? AND consumed_at<=?", arrayOf(startMillis.toString(),endMillisInclusive.toString()),null,null,"consumed_at DESC,id DESC").use { it.rows(Cursor::toEntry) }
    }
    @Synchronized fun dailySummary(atMillis: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()) = aggregateNutrition(entriesForDay(atMillis, zoneId))
    @Synchronized fun weeklySummary(
        atMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DailyNutritionTotals {
        val currentDate = java.time.Instant.ofEpochMilli(atMillis).atZone(zoneId).toLocalDate()
        val start = currentDate.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = dayBounds(atMillis, zoneId).last
        return aggregateNutrition(entriesBetween(start, end))
    }
    @Synchronized fun dailySummariesForWeek(
        atMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<java.time.LocalDate, DailyNutritionTotals>> {
        val currentDate = java.time.Instant.ofEpochMilli(atMillis).atZone(zoneId).toLocalDate()
        return (6L downTo 0L).map { daysAgo ->
            val date = currentDate.minusDays(daysAgo)
            val midday = date.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
            date to aggregateNutrition(entriesForDay(midday, zoneId))
        }
    }
    @Synchronized fun allProducts(limit: Int = 500): List<FoodProduct> =
        helper.readableDatabase.query(
            "products",
            PRODUCT_COLUMNS,
            null,
            null,
            null,
            null,
            "product_name COLLATE NOCASE",
            limit.coerceIn(1, 2_000).toString(),
        ).use { it.rows(Cursor::toProduct) }
    @Synchronized fun recentProducts(limit: Int = 8): List<FoodProduct> = helper.readableDatabase.rawQuery("SELECT ${PRODUCT_COLUMNS.joinToString { "p.$it" }} FROM products p INNER JOIN entries e ON e.barcode=p.barcode GROUP BY p.barcode ORDER BY MAX(e.consumed_at) DESC LIMIT ?", arrayOf(limit.coerceIn(1,32).toString())).use { it.rows(Cursor::toProduct) }
    @Synchronized fun latestEntryForDay(atMillis: Long = System.currentTimeMillis()): FoodEntry? = entriesForDay(atMillis).firstOrNull()
    @Synchronized fun entry(id: Long): FoodEntry? = helper.readableDatabase.query(
        "entries",
        ENTRY_COLUMNS,
        "id=?",
        arrayOf(id.toString()),
        null,
        null,
        null,
        "1",
    ).use { if (it.moveToFirst()) it.toEntry() else null }
    @Synchronized fun deleteEntry(id: Long): Boolean = helper.writableDatabase.delete("entries", "id=?", arrayOf(id.toString())) == 1
    @Synchronized fun deleteEntry(uuid: String): Boolean {
        if (!FOOD_ENTRY_ID_PATTERN.matches(uuid)) return false
        return helper.writableDatabase.delete("entries", "entry_uuid=?", arrayOf(uuid)) == 1
    }
    @Synchronized fun updateEntryMeal(uuid: String, mealType: MealType): Boolean {
        if (!FOOD_ENTRY_ID_PATTERN.matches(uuid)) return false
        return helper.writableDatabase.update(
            "entries",
            ContentValues().apply { put("meal_type", mealType.name) },
            "entry_uuid=?",
            arrayOf(uuid),
        ) == 1
    }
    @Synchronized fun setFavorite(barcode: String, favorite: Boolean) { val code=productId(barcode)?:return; if(favorite) helper.writableDatabase.insertWithOnConflict("favorites",null,ContentValues().apply{put("barcode",code)},SQLiteDatabase.CONFLICT_IGNORE) else helper.writableDatabase.delete("favorites","barcode=?",arrayOf(code)) }
    @Synchronized fun favoriteProducts(): List<FoodProduct> = helper.readableDatabase.rawQuery("SELECT ${PRODUCT_COLUMNS.joinToString()} FROM products WHERE barcode IN (SELECT barcode FROM favorites) ORDER BY product_name",null).use { it.rows(Cursor::toProduct) }
    @Synchronized fun saveGoals(goals: NutritionGoals) { helper.writableDatabase.insertWithOnConflict("goals",null,ContentValues().apply { put("singleton",1); putNullable("calories",goals.caloriesKcal);putNullable("protein",goals.proteinGrams);putNullable("carbohydrate",goals.carbohydrateGrams);putNullable("fat",goals.fatGrams) },SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun goals(): NutritionGoals? = helper.readableDatabase.query("goals",arrayOf("calories","protein","carbohydrate","fat"),"singleton=1",null,null,null,null).use { if(it.moveToFirst()) NutritionGoals(it.d("calories"),it.d("protein"),it.d("carbohydrate"),it.d("fat")) else null }
    @Synchronized fun saveRecipe(recipe: FoodRecipe) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            recipe.ingredients.forEach { ingredient ->
                db.insertWithOnConflict(
                    "products",
                    null,
                    ingredient.product.values(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.insertWithOnConflict(
                "products",
                null,
                recipe.asProduct().values(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.insertWithOnConflict(
                "recipes",
                null,
                ContentValues().apply {
                    put("recipe_uuid", recipe.uuid)
                    put("name", recipe.name)
                    put("servings", recipe.servings)
                    put("created_at", recipe.createdAtMillis)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("recipe_ingredients", "recipe_uuid=?", arrayOf(recipe.uuid))
            recipe.ingredients.forEach { ingredient ->
                db.insertOrThrow(
                    "recipe_ingredients",
                    null,
                    ContentValues().apply {
                        put("recipe_uuid", recipe.uuid)
                        put("barcode", ingredient.product.barcode)
                        put("grams", ingredient.grams)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    @Synchronized fun recipes(): List<FoodRecipe> {
        val db = helper.readableDatabase
        return db.query(
            "recipes",
            arrayOf("recipe_uuid", "name", "servings", "created_at"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE",
        ).use { recipesCursor ->
            recipesCursor.rows { recipeCursor ->
                val uuid = recipeCursor.s("recipe_uuid")
                val ingredients = db.rawQuery(
                    "SELECT ${PRODUCT_COLUMNS.joinToString { "p.$it" }}, ri.grams AS ingredient_grams " +
                        "FROM recipe_ingredients ri INNER JOIN products p ON p.barcode=ri.barcode " +
                        "WHERE ri.recipe_uuid=? ORDER BY p.product_name COLLATE NOCASE",
                    arrayOf(uuid),
                ).use { ingredientCursor ->
                    ingredientCursor.rows {
                        RecipeIngredient(it.toProduct(), it.getDouble(it.getColumnIndexOrThrow("ingredient_grams")))
                    }
                }
                FoodRecipe(
                    uuid = uuid,
                    name = recipeCursor.s("name"),
                    servings = recipeCursor.getDouble(recipeCursor.getColumnIndexOrThrow("servings")),
                    ingredients = ingredients,
                    createdAtMillis = recipeCursor.getLong(recipeCursor.getColumnIndexOrThrow("created_at")),
                )
            }
        }
    }
    @Synchronized fun exportJson(reminders: List<FoodLogReminder>): String = FoodLogBackup.encode(
        FoodLogArchive(
            entries = entriesBetween(0, Long.MAX_VALUE),
            products = allProductsForBackup(),
            favoriteProductIds = favoriteProducts().mapTo(linkedSetOf(), FoodProduct::barcode),
            goals = goals(),
            recipes = recipes(),
            reminders = reminders,
        ),
    )
    /** Validates the complete payload before opening the write transaction; UUIDs make merging idempotent. */
    @Synchronized fun importJson(json: String): FoodLogDatabaseImportResult {
        val archive = FoodLogBackup.decode(json)
        val db = helper.writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            archive.products.forEach { product ->
                db.insertWithOnConflict("products", null, product.values(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            archive.entries.forEach { entry ->
                val exists = db.query("entries", arrayOf("id"), "entry_uuid=?", arrayOf(entry.uuid), null, null, null).use { it.moveToFirst() }
                if (!exists) {
                    db.insertOrThrow("entries", null, entry.product.values().apply {
                        remove("fetched_at")
                        put("consumed_at", entry.consumedAtMillis)
                        put("quantity_grams", entry.quantityGrams)
                        put("entry_uuid", entry.uuid)
                        put("meal_type", entry.mealType.name)
                        put("source", entry.source.name)
                        putNullable("recipe_id", entry.recipeId)
                    })
                    inserted++
                }
            }
            archive.favoriteProductIds.forEach { barcode ->
                db.insertWithOnConflict(
                    "favorites",
                    null,
                    ContentValues().apply { put("barcode", barcode) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            archive.goals?.let { importedGoals ->
                db.insertWithOnConflict(
                    "goals",
                    null,
                    ContentValues().apply {
                        put("singleton", 1)
                        putNullable("calories", importedGoals.caloriesKcal)
                        putNullable("protein", importedGoals.proteinGrams)
                        putNullable("carbohydrate", importedGoals.carbohydrateGrams)
                        putNullable("fat", importedGoals.fatGrams)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            archive.recipes.forEach { recipe ->
                db.insertWithOnConflict(
                    "recipes",
                    null,
                    ContentValues().apply {
                        put("recipe_uuid", recipe.uuid)
                        put("name", recipe.name)
                        put("servings", recipe.servings)
                        put("created_at", recipe.createdAtMillis)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.delete("recipe_ingredients", "recipe_uuid=?", arrayOf(recipe.uuid))
                recipe.ingredients.forEach { ingredient ->
                    db.insertOrThrow(
                        "recipe_ingredients",
                        null,
                        ContentValues().apply {
                            put("recipe_uuid", recipe.uuid)
                            put("barcode", ingredient.product.barcode)
                            put("grams", ingredient.grams)
                        },
                    )
                }
                db.insertWithOnConflict("products", null, recipe.asProduct().values(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return FoodLogDatabaseImportResult(inserted, archive.reminders)
    }

    private fun allProductsForBackup(): List<FoodProduct> = helper.readableDatabase.query(
        "products",
        PRODUCT_COLUMNS,
        null,
        null,
        null,
        null,
        "product_name COLLATE NOCASE",
    ).use { it.rows(Cursor::toProduct) }
    override fun close() = helper.close()
    private class FoodLogDatabase(context: Context): SQLiteOpenHelper(context,"food-log.db",null,2) {
        override fun onCreate(db: SQLiteDatabase) { createV1(db); upgradeTo2(db) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion < 2) upgradeTo2(db) }
        private fun createV1(db: SQLiteDatabase) { db.execSQL("CREATE TABLE products (barcode TEXT PRIMARY KEY NOT NULL, product_name TEXT NOT NULL, brand TEXT NOT NULL, serving_label TEXT, serving_grams REAL, nutrition_grade TEXT, nova_group INTEGER, calories_100g REAL, protein_100g REAL, carbohydrate_100g REAL, fat_100g REAL, sugars_100g REAL, fiber_100g REAL, salt_100g REAL, fetched_at INTEGER NOT NULL)"); db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, consumed_at INTEGER NOT NULL, quantity_grams REAL NOT NULL, barcode TEXT NOT NULL, product_name TEXT NOT NULL, brand TEXT NOT NULL, serving_label TEXT, serving_grams REAL, nutrition_grade TEXT, nova_group INTEGER, calories_100g REAL, protein_100g REAL, carbohydrate_100g REAL, fat_100g REAL, sugars_100g REAL, fiber_100g REAL, salt_100g REAL)"); db.execSQL("CREATE INDEX entries_consumed_at ON entries (consumed_at DESC)"); db.execSQL("CREATE INDEX entries_barcode ON entries (barcode)") }
        private fun upgradeTo2(db: SQLiteDatabase) { listOf("saturated_fat_100g REAL","sodium_100g REAL","cholesterol_100g REAL","potassium_100g REAL","calcium_100g REAL","iron_100g REAL","caffeine_100g REAL").forEach { c -> db.execSQL("ALTER TABLE products ADD COLUMN $c"); db.execSQL("ALTER TABLE entries ADD COLUMN $c") }; db.execSQL("ALTER TABLE entries ADD COLUMN entry_uuid TEXT"); db.execSQL("ALTER TABLE entries ADD COLUMN meal_type TEXT NOT NULL DEFAULT 'UNKNOWN'"); db.execSQL("ALTER TABLE entries ADD COLUMN source TEXT NOT NULL DEFAULT 'UNKNOWN'"); db.execSQL("ALTER TABLE entries ADD COLUMN recipe_id TEXT"); db.execSQL("UPDATE entries SET entry_uuid = lower(hex(randomblob(16))) WHERE entry_uuid IS NULL"); db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS entries_uuid ON entries(entry_uuid)"); db.execSQL("CREATE TABLE IF NOT EXISTS favorites (barcode TEXT PRIMARY KEY NOT NULL)"); db.execSQL("CREATE TABLE IF NOT EXISTS goals (singleton INTEGER PRIMARY KEY CHECK(singleton=1), calories REAL, protein REAL, carbohydrate REAL, fat REAL)"); db.execSQL("CREATE TABLE IF NOT EXISTS recipes (recipe_uuid TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, servings REAL NOT NULL, created_at INTEGER NOT NULL)"); db.execSQL("CREATE TABLE IF NOT EXISTS recipe_ingredients (recipe_uuid TEXT NOT NULL, barcode TEXT NOT NULL, grams REAL NOT NULL, PRIMARY KEY(recipe_uuid, barcode))") }
    }
    private companion object { val PRODUCT_COLUMNS=arrayOf("barcode","product_name","brand","serving_label","serving_grams","nutrition_grade","nova_group","calories_100g","protein_100g","carbohydrate_100g","fat_100g","sugars_100g","fiber_100g","salt_100g","saturated_fat_100g","sodium_100g","cholesterol_100g","potassium_100g","calcium_100g","iron_100g","caffeine_100g","fetched_at"); val ENTRY_COLUMNS=arrayOf("id","consumed_at","quantity_grams","entry_uuid","meal_type","source","recipe_id",*PRODUCT_COLUMNS.filterNot { it=="fetched_at" }.toTypedArray()) }
}
private fun FoodProduct.values()=ContentValues().apply { put("barcode",barcode);put("product_name",name);put("brand",brand);putNullable("serving_label",servingLabel);putNullable("serving_grams",servingGrams);putNullable("nutrition_grade",nutritionGrade);putNullable("nova_group",novaGroup); listOf("calories_100g" to nutrients.caloriesKcal,"protein_100g" to nutrients.proteinGrams,"carbohydrate_100g" to nutrients.carbohydrateGrams,"fat_100g" to nutrients.fatGrams,"sugars_100g" to nutrients.sugarsGrams,"fiber_100g" to nutrients.fiberGrams,"salt_100g" to nutrients.saltGrams,"saturated_fat_100g" to nutrients.saturatedFatGrams,"sodium_100g" to nutrients.sodiumMilligrams,"cholesterol_100g" to nutrients.cholesterolMilligrams,"potassium_100g" to nutrients.potassiumMilligrams,"calcium_100g" to nutrients.calciumMilligrams,"iron_100g" to nutrients.ironMilligrams,"caffeine_100g" to nutrients.caffeineMilligrams).forEach{putNullable(it.first,it.second)};put("fetched_at",fetchedAtMillis) }
internal fun productId(value: String): String? = normalizeBarcode(value)
    ?: value.takeIf { it.matches(Regex("(?:custom|recipe)-[A-Za-z0-9-]{1,64}")) }
private fun ContentValues.putNullable(k:String,v:String?){if(v==null)putNull(k)else put(k,v)}
private fun ContentValues.putNullable(k:String,v:Double?){if(v==null)putNull(k)else put(k,v)}
private fun ContentValues.putNullable(k:String,v:Int?){if(v==null)putNull(k)else put(k,v)}
private fun Cursor.toProduct(): FoodProduct {
    val fetchedAtIndex = getColumnIndex("fetched_at")
    return FoodProduct(
        s("barcode"),
        s("product_name"),
        s("brand"),
        ns("serving_label"),
        d("serving_grams"),
        ns("nutrition_grade"),
        i("nova_group"),
        NutrientsPer100g(
            d("calories_100g"),
            d("protein_100g"),
            d("carbohydrate_100g"),
            d("fat_100g"),
            d("sugars_100g"),
            d("fiber_100g"),
            d("salt_100g"),
            d("saturated_fat_100g"),
            d("sodium_100g"),
            d("cholesterol_100g"),
            d("potassium_100g"),
            d("calcium_100g"),
            d("iron_100g"),
            d("caffeine_100g"),
        ),
        if (fetchedAtIndex >= 0) getLong(fetchedAtIndex) else 0L,
    )
}
private fun Cursor.toEntry()=FoodEntry(getLong(getColumnIndexOrThrow("id")),getLong(getColumnIndexOrThrow("consumed_at")),getDouble(getColumnIndexOrThrow("quantity_grams")),toProduct(),ns("entry_uuid").orEmpty(),ns("meal_type")?.let { runCatching{MealType.valueOf(it)}.getOrDefault(MealType.UNKNOWN)}?:MealType.UNKNOWN,ns("source")?.let { runCatching{FoodEntrySource.valueOf(it)}.getOrDefault(FoodEntrySource.UNKNOWN)}?:FoodEntrySource.UNKNOWN,ns("recipe_id"))
private fun Cursor.s(n:String)=getString(getColumnIndexOrThrow(n)); private fun Cursor.ns(n:String)=getColumnIndex(n).takeIf{it>=0}?.let{if(isNull(it))null else getString(it)}; private fun Cursor.d(n:String)=getColumnIndex(n).takeIf{it>=0}?.let{if(isNull(it))null else getDouble(it)}; private fun Cursor.i(n:String)=getColumnIndex(n).takeIf{it>=0}?.let{if(isNull(it))null else getInt(it)}
private inline fun <T> Cursor.rows(f:(Cursor)->T)=buildList { while(moveToNext()) add(f(this@rows)) }
