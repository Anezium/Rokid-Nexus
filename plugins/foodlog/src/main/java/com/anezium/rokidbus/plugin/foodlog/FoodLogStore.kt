package com.anezium.rokidbus.plugin.foodlog

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.ZoneId

internal class FoodLogStore(context: Context) : AutoCloseable {
    private val helper = FoodLogDatabase(context.applicationContext)

    @Synchronized
    fun product(barcode: String): FoodProduct? {
        val normalized = normalizeBarcode(barcode) ?: return null
        return helper.readableDatabase.query(
            PRODUCTS,
            PRODUCT_COLUMNS,
            "$BARCODE = ?",
            arrayOf(normalized),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toProduct() else null }
    }

    @Synchronized
    fun upsertProduct(product: FoodProduct) {
        helper.writableDatabase.insertWithOnConflict(
            PRODUCTS,
            null,
            product.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun addEntry(
        product: FoodProduct,
        quantityGrams: Double,
        consumedAtMillis: Long = System.currentTimeMillis(),
    ): Long {
        require(quantityGrams in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS)
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            db.insertWithOnConflict(
                PRODUCTS,
                null,
                product.toValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            val values = product.toValues().apply {
                remove(FETCHED_AT)
                put(CONSUMED_AT, consumedAtMillis)
                put(QUANTITY_GRAMS, quantityGrams)
            }
            val id = db.insertOrThrow(ENTRIES, null, values)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun entriesForDay(
        atMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<FoodEntry> {
        val bounds = dayBounds(atMillis, zoneId)
        return helper.readableDatabase.query(
            ENTRIES,
            ENTRY_COLUMNS,
            "$CONSUMED_AT >= ? AND $CONSUMED_AT <= ?",
            arrayOf(bounds.first.toString(), bounds.last.toString()),
            null,
            null,
            "$CONSUMED_AT DESC, $ID DESC",
        ).use { cursor -> cursor.mapRows(Cursor::toEntry) }
    }

    @Synchronized
    fun recentProducts(limit: Int = 8): List<FoodProduct> =
        helper.readableDatabase.rawQuery(
            """
            SELECT ${PRODUCT_COLUMNS.joinToString(", ") { "p.$it" }}
            FROM $PRODUCTS p
            INNER JOIN $ENTRIES e ON e.$BARCODE = p.$BARCODE
            GROUP BY p.$BARCODE
            ORDER BY MAX(e.$CONSUMED_AT) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 32).toString()),
        ).use { cursor -> cursor.mapRows(Cursor::toProduct) }

    @Synchronized
    fun latestEntryForDay(atMillis: Long = System.currentTimeMillis()): FoodEntry? =
        entriesForDay(atMillis).firstOrNull()

    @Synchronized
    fun deleteEntry(id: Long): Boolean =
        helper.writableDatabase.delete(ENTRIES, "$ID = ?", arrayOf(id.toString())) == 1

    override fun close() {
        helper.close()
    }

    private class FoodLogDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $PRODUCTS (
                    $BARCODE TEXT PRIMARY KEY NOT NULL,
                    $PRODUCT_NAME TEXT NOT NULL,
                    $BRAND TEXT NOT NULL,
                    $SERVING_LABEL TEXT,
                    $SERVING_GRAMS REAL,
                    $NUTRITION_GRADE TEXT,
                    $NOVA_GROUP INTEGER,
                    $CALORIES_100G REAL,
                    $PROTEIN_100G REAL,
                    $CARBOHYDRATE_100G REAL,
                    $FAT_100G REAL,
                    $SUGARS_100G REAL,
                    $FIBER_100G REAL,
                    $SALT_100G REAL,
                    $FETCHED_AT INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $ENTRIES (
                    $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $CONSUMED_AT INTEGER NOT NULL,
                    $QUANTITY_GRAMS REAL NOT NULL,
                    $BARCODE TEXT NOT NULL,
                    $PRODUCT_NAME TEXT NOT NULL,
                    $BRAND TEXT NOT NULL,
                    $SERVING_LABEL TEXT,
                    $SERVING_GRAMS REAL,
                    $NUTRITION_GRADE TEXT,
                    $NOVA_GROUP INTEGER,
                    $CALORIES_100G REAL,
                    $PROTEIN_100G REAL,
                    $CARBOHYDRATE_100G REAL,
                    $FAT_100G REAL,
                    $SUGARS_100G REAL,
                    $FIBER_100G REAL,
                    $SALT_100G REAL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX entries_consumed_at ON $ENTRIES ($CONSUMED_AT DESC)")
            db.execSQL("CREATE INDEX entries_barcode ON $ENTRIES ($BARCODE)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "food-log.db"
        const val DATABASE_VERSION = 1
        const val PRODUCTS = "products"
        const val ENTRIES = "entries"
        const val ID = "id"
        const val CONSUMED_AT = "consumed_at"
        const val QUANTITY_GRAMS = "quantity_grams"
        const val BARCODE = "barcode"
        const val PRODUCT_NAME = "product_name"
        const val BRAND = "brand"
        const val SERVING_LABEL = "serving_label"
        const val SERVING_GRAMS = "serving_grams"
        const val NUTRITION_GRADE = "nutrition_grade"
        const val NOVA_GROUP = "nova_group"
        const val CALORIES_100G = "calories_100g"
        const val PROTEIN_100G = "protein_100g"
        const val CARBOHYDRATE_100G = "carbohydrate_100g"
        const val FAT_100G = "fat_100g"
        const val SUGARS_100G = "sugars_100g"
        const val FIBER_100G = "fiber_100g"
        const val SALT_100G = "salt_100g"
        const val FETCHED_AT = "fetched_at"

        val PRODUCT_COLUMNS = arrayOf(
            BARCODE,
            PRODUCT_NAME,
            BRAND,
            SERVING_LABEL,
            SERVING_GRAMS,
            NUTRITION_GRADE,
            NOVA_GROUP,
            CALORIES_100G,
            PROTEIN_100G,
            CARBOHYDRATE_100G,
            FAT_100G,
            SUGARS_100G,
            FIBER_100G,
            SALT_100G,
            FETCHED_AT,
        )
        val ENTRY_COLUMNS = arrayOf(
            ID,
            CONSUMED_AT,
            QUANTITY_GRAMS,
            *PRODUCT_COLUMNS.filterNot { it == FETCHED_AT }.toTypedArray(),
        )
    }
}

internal const val MIN_QUANTITY_GRAMS = 1.0
internal const val MAX_QUANTITY_GRAMS = 5_000.0

private fun FoodProduct.toValues(): ContentValues = ContentValues().apply {
    put("barcode", barcode)
    put("product_name", name)
    put("brand", brand)
    putNullable("serving_label", servingLabel)
    putNullable("serving_grams", servingGrams)
    putNullable("nutrition_grade", nutritionGrade)
    putNullable("nova_group", novaGroup)
    putNullable("calories_100g", nutrients.caloriesKcal)
    putNullable("protein_100g", nutrients.proteinGrams)
    putNullable("carbohydrate_100g", nutrients.carbohydrateGrams)
    putNullable("fat_100g", nutrients.fatGrams)
    putNullable("sugars_100g", nutrients.sugarsGrams)
    putNullable("fiber_100g", nutrients.fiberGrams)
    putNullable("salt_100g", nutrients.saltGrams)
    put("fetched_at", fetchedAtMillis)
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.toProduct(): FoodProduct = FoodProduct(
    barcode = string("barcode"),
    name = string("product_name"),
    brand = string("brand"),
    servingLabel = nullableString("serving_label"),
    servingGrams = nullableDouble("serving_grams"),
    nutritionGrade = nullableString("nutrition_grade"),
    novaGroup = nullableInt("nova_group"),
    nutrients = NutrientsPer100g(
        caloriesKcal = nullableDouble("calories_100g"),
        proteinGrams = nullableDouble("protein_100g"),
        carbohydrateGrams = nullableDouble("carbohydrate_100g"),
        fatGrams = nullableDouble("fat_100g"),
        sugarsGrams = nullableDouble("sugars_100g"),
        fiberGrams = nullableDouble("fiber_100g"),
        saltGrams = nullableDouble("salt_100g"),
    ),
    fetchedAtMillis = columnIndexOrNull("fetched_at")?.let(::getLong) ?: 0L,
)

private fun Cursor.toEntry(): FoodEntry = FoodEntry(
    id = getLong(getColumnIndexOrThrow("id")),
    consumedAtMillis = getLong(getColumnIndexOrThrow("consumed_at")),
    quantityGrams = getDouble(getColumnIndexOrThrow("quantity_grams")),
    product = toProduct(),
)

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))

private fun Cursor.nullableString(name: String): String? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.nullableDouble(name: String): Double? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getDouble(index)
}

private fun Cursor.nullableInt(name: String): Int? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getInt(index)
}

private fun Cursor.columnIndexOrNull(name: String): Int? = getColumnIndex(name).takeIf { it >= 0 }

private inline fun <T> Cursor.mapRows(transform: (Cursor) -> T): List<T> = buildList {
    while (moveToNext()) add(transform(this@mapRows))
}
