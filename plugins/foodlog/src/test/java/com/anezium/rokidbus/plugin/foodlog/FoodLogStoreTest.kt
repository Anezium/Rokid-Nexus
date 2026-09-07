package com.anezium.rokidbus.plugin.foodlog

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class FoodLogStoreTest {
    private lateinit var context: Context
    private lateinit var store: FoodLogStore
    private val food = FoodProduct("12345678", "Oats", "Farm", "One bowl", 40.0, null, null,
        NutrientsPer100g(200.0, 10.0, null, 5.0, null, null, null), 123)
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private val recipeUuid = "00000000-0000-4000-8000-000000000002"
    private val breakfast = Instant.parse("2026-09-08T06:30:00Z").toEpochMilli()

    @Before fun createStore() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("food-log.db")
        store = FoodLogStore(context)
    }

    @After fun closeStore() { store.close(); context.deleteDatabase("food-log.db") }

    @Test fun exactEditSurvivesReopenWithoutReplacingNutritionSnapshot() {
        val id = store.addEntry(food, 75.25, breakfast, MealType.BREAKFAST, FoodEntrySource.SEARCHED, uuid = uuid)
        val original = requireNotNull(store.entry(id))
        store.upsertProduct(food.copy(name = "New label", nutrients = food.nutrients.copy(caloriesKcal = 900.0)))
        val dinner = breakfast + 36 * 60 * 60 * 1_000L
        assertTrue(store.updateEntry(original, 150.5, dinner, MealType.DINNER))
        store.close(); store = FoodLogStore(context)
        val edited = requireNotNull(store.entry(id))
        assertEquals(uuid, edited.uuid)
        assertEquals(original.product, edited.product)
        assertEquals(150.5, edited.quantityGrams, 0.0)
        assertEquals(MealType.DINNER, edited.mealType)
        assertEquals(dinner, edited.consumedAtMillis)
        assertEquals(301.0, store.dailySummary(dinner).caloriesKcal.knownValue, 0.0)
        assertEquals(0, store.entriesForDay(breakfast).size)
        assertEquals(900.0, store.product(food.barcode)!!.nutrients.caloriesKcal!!, 0.0)
    }

    @Test fun staleEntryCannotOverwriteOrDeleteAnAlreadyEditedRow() {
        val id = store.addEntry(food, 100.0, breakfast, MealType.BREAKFAST, FoodEntrySource.SEARCHED, uuid = uuid)
        val original = requireNotNull(store.entry(id))
        assertTrue(store.updateEntry(original, 50.0, breakfast, MealType.LUNCH))
        assertFalse(store.updateEntry(original, 200.0, breakfast, MealType.SNACK))
        assertFalse(store.deleteEntry(original))
        assertEquals(50.0, store.entry(id)!!.quantityGrams, 0.0)
        val current = requireNotNull(store.entry(id))
        assertTrue(store.deleteEntry(current))
        assertNull(store.entry(id))
    }

    @Test fun recipesKeepTheirIngredientSnapshotAfterCatalogRefreshAndReopen() {
        val recipe = FoodRecipe(recipeUuid, "Porridge", 2.0, listOf(RecipeIngredient(food, 80.0)), breakfast)
        store.saveRecipe(recipe)
        store.upsertProduct(food.copy(nutrients = food.nutrients.copy(caloriesKcal = 999.0)))
        store.close(); store = FoodLogStore(context)
        val restored = store.recipes().single()
        assertEquals(recipe.ingredients, restored.ingredients)
        assertEquals(80.0, restored.nutrientsPerServing().caloriesKcal!!, 0.0)
        assertNull(restored.nutrientsPerServing().carbohydrateGrams)
        store.saveRecipe(restored.copy(name = "Morning porridge"))
        assertEquals(999.0, store.product(food.barcode)!!.nutrients.caloriesKcal!!, 0.0)
    }

    @Test fun archiveMergeIsIdempotentAndPreservesSeparateEntryAndRecipeSnapshots() {
        val recipe = FoodRecipe(recipeUuid, "Porridge", 2.0, listOf(RecipeIngredient(food, 80.0)), breakfast)
        store.saveRecipe(recipe)
        store.addEntry(recipe.asProduct(), 40.0, breakfast, MealType.BREAKFAST, FoodEntrySource.RECIPE, recipeUuid, uuid)
        store.setFavorite(food.barcode, true)
        store.upsertProduct(food.copy(nutrients = food.nutrients.copy(caloriesKcal = 999.0)))
        val json = store.exportJson(emptyList())
        store.close(); context.deleteDatabase("food-log.db"); store = FoodLogStore(context)
        assertEquals(1, store.importJson(json).insertedEntries)
        assertEquals(0, store.importJson(json).insertedEntries)
        assertEquals(uuid, store.entriesForDay(breakfast).single().uuid)
        assertEquals(80.0, store.dailySummary(breakfast).caloriesKcal.knownValue, 0.0)
        assertEquals(200.0, store.recipes().single().ingredients.single().product.nutrients.caloriesKcal!!, 0.0)
        assertEquals(999.0, store.product(food.barcode)!!.nutrients.caloriesKcal!!, 0.0)
        assertEquals(food.barcode, store.favoriteProducts().single().barcode)
    }

    @Test fun malformedIngredientIdentityRejectsWholeImportBeforeAnyMutation() {
        store.saveRecipe(FoodRecipe(recipeUuid, "Porridge", 2.0, listOf(RecipeIngredient(food, 80.0)), breakfast))
        store.saveGoals(NutritionGoals(caloriesKcal = 2_000.0))
        val before = store.exportJson(emptyList())
        val altered = JSONObject(before)
        altered.getJSONObject("goals").put("calories", 1_000)
        altered.getJSONArray("recipes").getJSONObject(0).getJSONArray("ingredients").getJSONObject(0)
            .getJSONObject("product").put("barcode", "87654321")
        assertTrue(runCatching { store.importJson(altered.toString()) }.isFailure)
        assertEquals(before, store.exportJson(emptyList()))
    }

    @Test fun localDayQueryIncludesBothDstHoursAndExcludesNextMidnight() {
        val paris = ZoneId.of("Europe/Paris")
        listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z", "2026-10-25T23:00:00Z").forEach { timestamp ->
            store.addEntry(food, 50.0, Instant.parse(timestamp).toEpochMilli(), MealType.SNACK, FoodEntrySource.SEARCHED)
        }
        val entries = store.entriesForDay(Instant.parse("2026-10-25T12:00:00Z").toEpochMilli(), paris)
        assertEquals(2, entries.size)
        assertEquals(200.0, journalMeals(entries).single { it.meal == MealType.SNACK }.totals.caloriesKcal.knownValue, 0.0)
    }

    @Test fun searchableCustomFoodsRetainTheirFullIdentityAndServing() {
        val custom = store.createCustomFood("Rice and beans", food.nutrients, servingGrams = 125.0)
        assertEquals(custom, store.product(custom.barcode))
        assertEquals(custom, store.searchProducts("rice").single())
        assertEquals(custom, store.searchProducts(custom.barcode).single())
        assertTrue(store.searchProducts("%").isEmpty())
        assertEquals(125.0, store.product(custom.barcode)!!.servingGrams!!, 0.0)
    }
}
