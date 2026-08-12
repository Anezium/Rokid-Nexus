package com.anezium.rokidbus.plugin.foodlog

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, versioned local backup codec. Parsing never accepts partial data. */
internal object FoodLogBackup {
    const val SCHEMA_VERSION = 2
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TEXT = 300

    fun encode(entries: List<FoodEntry>): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }.toString()

    fun decode(text: String): List<FoodEntry> {
        require(text.length <= 12_000_000) { "Backup is too large" }
        val root = JSONObject(text)
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Unsupported backup schema" }
        val array = root.optJSONArray("entries") ?: throw IllegalArgumentException("Missing entries")
        require(array.length() <= MAX_ENTRIES) { "Too many entries" }
        val seen = HashSet<String>()
        return List(array.length()) { index -> entryFromJson(array.getJSONObject(index)).also { require(seen.add(it.uuid)) { "Duplicate entry UUID" } } }
    }

    private fun FoodEntry.toJson() = JSONObject().apply {
        put("uuid", uuid); put("consumedAtMillis", consumedAtMillis); put("quantityGrams", quantityGrams)
        put("mealType", mealType.name); put("source", source.name); put("recipeId", recipeId); put("product", product.toJson())
    }
    private fun FoodProduct.toJson() = JSONObject().apply {
        put("barcode", barcode); put("name", name); put("brand", brand); put("fetchedAtMillis", fetchedAtMillis)
        put("nutrients", JSONObject().apply { nutrientsValues().forEach { put(it.first, it.second) } })
    }
    private fun FoodProduct.nutrientsValues() = listOf("calories" to nutrients.caloriesKcal,"protein" to nutrients.proteinGrams,"carbohydrate" to nutrients.carbohydrateGrams,"fat" to nutrients.fatGrams,"sugars" to nutrients.sugarsGrams,"fiber" to nutrients.fiberGrams,"salt" to nutrients.saltGrams,"saturatedFat" to nutrients.saturatedFatGrams,"sodium" to nutrients.sodiumMilligrams,"cholesterol" to nutrients.cholesterolMilligrams,"potassium" to nutrients.potassiumMilligrams,"calcium" to nutrients.calciumMilligrams,"iron" to nutrients.ironMilligrams,"caffeine" to nutrients.caffeineMilligrams)
    private fun entryFromJson(o: JSONObject): FoodEntry {
        val uuid = text(o,"uuid",64); require(uuid.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val quantity=o.optDouble("quantityGrams",Double.NaN); require(quantity in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS)
        val consumed=o.optLong("consumedAtMillis",Long.MIN_VALUE); require(consumed > 0)
        val p=o.optJSONObject("product") ?: throw IllegalArgumentException("Missing product")
        val barcode = text(p,"barcode",70); require(productId(barcode) != null)
        return FoodEntry(0,consumed,quantity,FoodProduct(barcode,text(p,"name",MAX_TEXT),text(p,"brand",MAX_TEXT),null,null,null,null,nutrients(p.optJSONObject("nutrients") ?: throw IllegalArgumentException("Missing nutrients")),p.optLong("fetchedAtMillis",0)),uuid,enum(o,"mealType",MealType.UNKNOWN),enum(o,"source",FoodEntrySource.UNKNOWN),o.optString("recipeId").takeIf { it.isNotBlank() && it.length <= 64 })
    }
    private fun nutrients(o: JSONObject): NutrientsPer100g { fun n(k:String)=if(!o.has(k)||o.isNull(k))null else o.optDouble(k,Double.NaN).takeIf { it.isFinite() && it>=0 }; return NutrientsPer100g(n("calories"),n("protein"),n("carbohydrate"),n("fat"),n("sugars"),n("fiber"),n("salt"),n("saturatedFat"),n("sodium"),n("cholesterol"),n("potassium"),n("calcium"),n("iron"),n("caffeine")) }
    private fun text(o:JSONObject,k:String,max:Int):String=o.optString(k).trim().also { require(it.isNotEmpty() && it.length<=max) }
    private inline fun <reified T: Enum<T>> enum(o:JSONObject,k:String,default:T):T = o.optString(k).let { runCatching { enumValueOf<T>(it) }.getOrDefault(default) }
}
