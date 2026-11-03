package com.anezium.rokidbus.plugin.foodlog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Phone-side dashboard and management surface for the local Food Log journal. */
class FoodLogActivity : Activity() {
    private lateinit var store: FoodLogStore
    private val factsClient = FoodFactsClient()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val healthBridge by lazy { FoodLogHealthConnectBridge(applicationContext) }
    private val healthPermissionContract by lazy {
        PermissionController.createRequestPermissionResultContract()
    }
    private val preferences by lazy {
        getSharedPreferences(FOOD_LOG_PREFERENCES, MODE_PRIVATE)
    }

    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var entriesList: LinearLayout
    private lateinit var favoritesList: LinearLayout
    private lateinit var recipesList: LinearLayout
    private lateinit var weeklyList: LinearLayout
    private lateinit var remindersList: LinearLayout
    private lateinit var productCatalog: LinearLayout

    private lateinit var barcodeField: EditText
    private lateinit var quantityField: EditText
    private lateinit var mealButton: Button
    private lateinit var addButton: View
    private lateinit var contributionButton: Button

    private lateinit var customNameField: EditText
    private lateinit var customCaloriesField: EditText
    private lateinit var customProteinField: EditText
    private lateinit var customCarbsField: EditText
    private lateinit var customFatField: EditText
    private lateinit var customSaturatedFatField: EditText
    private lateinit var customSodiumField: EditText
    private lateinit var customPotassiumField: EditText
    private lateinit var customCalciumField: EditText
    private lateinit var customIronField: EditText
    private lateinit var customCaffeineField: EditText
    private lateinit var customCholesterolField: EditText

    private lateinit var recipeNameField: EditText
    private lateinit var recipeServingsField: EditText
    private lateinit var recipeIngredientsField: EditText

    private lateinit var goalCaloriesField: EditText
    private lateinit var goalProteinField: EditText
    private lateinit var goalCarbsField: EditText
    private lateinit var goalFatField: EditText

    private lateinit var healthSwitch: Switch
    private lateinit var healthStatus: TextView
    private var updatingHealthSwitch = false
    private var healthConsentGeneration = 0L
    private var pendingHealthPermissionGeneration: Long? = null

    private lateinit var reminderLabelField: EditText
    private lateinit var reminderMinutesField: EditText
    private lateinit var reminderKindButton: Button

    private var selectedMeal = inferredMealType(System.currentTimeMillis())
    private var reminderKind = FoodLogReminderKind.MEAL
    private var missingBarcode: String? = null
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FoodLogStore(applicationContext)
        buildUi()
        refreshAll()
        refreshHealthState()
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        worker.shutdownNow()
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android; retained for document and Health Connect contracts.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_HEALTH_PERMISSION -> {
                val granted = healthPermissionContract.parseResult(resultCode, data)
                    .contains(FoodLogHealthConnectBridge.WRITE_NUTRITION_PERMISSION)
                val requestGeneration = pendingHealthPermissionGeneration
                pendingHealthPermissionGeneration = null
                val accepted = granted && requestGeneration != null && requestGeneration == healthConsentGeneration
                preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, accepted).apply()
                report(if (accepted) "Health Connect sync enabled." else "Health Connect permission was not granted or sync was turned off.")
                refreshHealthState()
            }
            REQUEST_EXPORT -> data?.data?.let(::writeBackup)
            REQUEST_IMPORT -> data?.data?.let(::readBackup)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            report(
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    "Reminder notifications enabled."
                } else {
                    "Reminder saved; phone notifications are disabled."
                },
            )
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        summary = NexusUi.cardBody(this, "Loading today’s journal…")
        status = NexusUi.statusLine(this).apply { visibility = View.GONE }
        entriesList = verticalList()
        favoritesList = verticalList()
        recipesList = verticalList()
        weeklyList = verticalList()
        remindersList = verticalList()
        productCatalog = verticalList()
        buildQuickAddControls()
        buildCustomFoodControls()
        buildRecipeControls()
        buildGoalControls()
        buildHealthControls()
        buildReminderControls()

        val content = NexusUi.contentColumn(this).apply {
            introAndToday()
            section(this, "Quick add", quickAddCard())
            section(this, "Favorites", favoritesList)
            section(this, "Entries", entriesList)
            section(this, "7-day statistics", weeklyList)
            section(this, "Goals", goalsCard())
            section(this, "Custom food", customFoodCard())
            section(this, "Recipes", recipesCard(), recipesList)
            section(this, "Product IDs for recipes", productCatalog)
            section(this, "Health Connect", healthCard())
            section(this, "Reminders", remindersCard(), remindersList)
            section(this, "Backup", backupCard())
            section(
                this,
                "Data",
                NexusUi.cardBody(
                    this@FoodLogActivity,
                    "Food history and recipes stay on this phone. Open Food Facts is collaborative and can be incomplete; check the package label when nutrition data matters.",
                ),
            )
            section(this, "Plugin", uninstallRow())
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(
                    NexusUi.pluginHeader(
                        this@FoodLogActivity,
                        NexusPluginIcons.drawableFor("heart"),
                        "Food Log",
                        "Local nutrition journal · v0.3",
                    ),
                    NexusUi.block(),
                )
                addView(
                    NexusUi.screen(this@FoodLogActivity, content),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun LinearLayout.introAndToday() {
        addView(
            NexusUi.cardBody(
                this@FoodLogActivity,
                "Scan from the glasses, add by voice, or manage a complete food journal here.",
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@FoodLogActivity, 18))
        addView(NexusUi.sectionRow(this@FoodLogActivity, "Today"), NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(NexusUi.card(this@FoodLogActivity).apply { addView(summary) }, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(status, NexusUi.block())
    }

    private fun section(
        parent: LinearLayout,
        title: String,
        vararg views: View,
    ) {
        parent.addView(BusTheme.gap(this, 24))
        parent.addView(NexusUi.sectionRow(this, title), NexusUi.block())
        parent.addView(BusTheme.gap(this, 10))
        views.forEachIndexed { index, view ->
            if (index > 0) parent.addView(BusTheme.gap(this, 8))
            parent.addView(view, NexusUi.block())
        }
    }

    private fun buildQuickAddControls() {
        barcodeField = textField("Barcode")
        quantityField = numberField("Quantity in grams").apply {
            setText(DEFAULT_QUANTITY_GRAMS)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addFromBarcode()
                    true
                } else {
                    false
                }
            }
        }
        mealButton = NexusUi.outlinePillButton(this, selectedMeal.displayName).apply {
            setOnClickListener { cycleMeal() }
        }
        addButton = NexusUi.pillButton(this, "Look up and add").apply {
            setOnClickListener { addFromBarcode() }
        }
        contributionButton = NexusUi.outlinePillButton(this, "Add product to Open Food Facts").apply {
            visibility = View.GONE
            setOnClickListener { missingBarcode?.let(::openFoodFactsContribution) }
        }
    }

    private fun quickAddCard(): LinearLayout = NexusUi.card(this).apply {
        addView(barcodeField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(quantityField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(mealButton, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(addButton, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(contributionButton, NexusUi.block())
    }

    private fun buildCustomFoodControls() {
        customNameField = textField("Food name")
        customCaloriesField = numberField("Calories / 100 g")
        customProteinField = numberField("Protein g / 100 g")
        customCarbsField = numberField("Carbohydrate g / 100 g")
        customFatField = numberField("Fat g / 100 g")
        customSaturatedFatField = numberField("Saturated fat g / 100 g (optional)")
        customSodiumField = numberField("Sodium mg / 100 g (optional)")
        customPotassiumField = numberField("Potassium mg / 100 g (optional)")
        customCalciumField = numberField("Calcium mg / 100 g (optional)")
        customIronField = numberField("Iron mg / 100 g (optional)")
        customCaffeineField = numberField("Caffeine mg / 100 g (optional)")
        customCholesterolField = numberField("Cholesterol mg / 100 g (optional)")
    }

    private fun customFoodCard(): LinearLayout = NexusUi.card(this).apply {
        listOf(
            customNameField,
            customCaloriesField,
            customProteinField,
            customCarbsField,
            customFatField,
            customSaturatedFatField,
            customSodiumField,
            customPotassiumField,
            customCalciumField,
            customIronField,
            customCaffeineField,
            customCholesterolField,
        ).forEachIndexed { index, field ->
            if (index > 0) addView(BusTheme.gap(this@FoodLogActivity, 8))
            addView(field, NexusUi.block())
        }
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(
            NexusUi.pillButton(this@FoodLogActivity, "Save custom food").apply {
                setOnClickListener { saveCustomFood() }
            },
            NexusUi.block(),
        )
    }

    private fun buildRecipeControls() {
        recipeNameField = textField("Recipe name")
        recipeServingsField = numberField("Number of servings").apply { setText("2") }
        recipeIngredientsField = textField("product-id:grams; product-id:grams")
    }

    private fun recipesCard(): LinearLayout = NexusUi.card(this).apply {
        addView(
            NexusUi.cardBody(
                this@FoodLogActivity,
                "Use product IDs listed below. Example: 3017620422003:40; custom-…:120",
            ),
        )
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(recipeNameField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(recipeServingsField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(recipeIngredientsField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(
            NexusUi.pillButton(this@FoodLogActivity, "Save recipe").apply {
                setOnClickListener { saveRecipe() }
            },
            NexusUi.block(),
        )
    }

    private fun buildGoalControls() {
        goalCaloriesField = numberField("Daily calories")
        goalProteinField = numberField("Daily protein g")
        goalCarbsField = numberField("Daily carbohydrate g")
        goalFatField = numberField("Daily fat g")
    }

    private fun goalsCard(): LinearLayout = NexusUi.card(this).apply {
        listOf(goalCaloriesField, goalProteinField, goalCarbsField, goalFatField).forEachIndexed { index, field ->
            if (index > 0) addView(BusTheme.gap(this@FoodLogActivity, 8))
            addView(field, NexusUi.block())
        }
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(
            NexusUi.pillButton(this@FoodLogActivity, "Save goals").apply {
                setOnClickListener { saveGoals() }
            },
            NexusUi.block(),
        )
    }

    private fun buildHealthControls() {
        healthStatus = NexusUi.cardBody(this, "Checking Health Connect…")
        healthSwitch = NexusUi.switch(this).apply {
            isChecked = preferences.getBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingHealthSwitch) setHealthSyncEnabled(checked)
            }
        }
    }

    private fun healthCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.switchRow(this@FoodLogActivity, "Write nutrition", "Optional; Food Log never reads Health Connect", healthSwitch))
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(healthStatus)
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(
            NexusUi.outlinePillButton(this@FoodLogActivity, "Sync today now").apply {
                setOnClickListener { syncTodayToHealthConnect() }
            },
            NexusUi.block(),
        )
    }

    private fun buildReminderControls() {
        reminderLabelField = textField("Reminder label")
        reminderMinutesField = numberField("Minutes from now").apply { setText("60") }
        reminderKindButton = NexusUi.outlinePillButton(this, "Meal").apply {
            setOnClickListener {
                reminderKind = if (reminderKind == FoodLogReminderKind.MEAL) {
                    FoodLogReminderKind.HYDRATION
                } else {
                    FoodLogReminderKind.MEAL
                }
                text = if (reminderKind == FoodLogReminderKind.MEAL) "MEAL" else "HYDRATION"
            }
        }
    }

    private fun remindersCard(): LinearLayout = NexusUi.card(this).apply {
        addView(
            NexusUi.cardBody(
                this@FoodLogActivity,
                "Only reminders you explicitly create here can wake Food Log.",
            ),
        )
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(reminderLabelField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(reminderMinutesField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(reminderKindButton, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(
            NexusUi.pillButton(this@FoodLogActivity, "Schedule reminder").apply {
                setOnClickListener { scheduleReminder() }
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(
            NexusUi.textButton(this@FoodLogActivity, "Exact-alarm settings").apply {
                setOnClickListener { openExactAlarmSettings() }
            },
            NexusUi.block(),
        )
    }

    private fun backupCard(): LinearLayout = NexusUi.card(this).apply {
        addView(
            NexusUi.cardBody(
                this@FoodLogActivity,
                "Export a versioned JSON copy of your journal or merge one back by stable entry ID.",
            ),
        )
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(
            NexusUi.outlinePillButton(this@FoodLogActivity, "Export journal").apply {
                setOnClickListener { chooseExportDestination() }
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(
            NexusUi.outlinePillButton(this@FoodLogActivity, "Import journal").apply {
                setOnClickListener { chooseImportSource() }
            },
            NexusUi.block(),
        )
    }

    private fun addFromBarcode() {
        val barcode = normalizeBarcode(barcodeField.text.toString())
        val quantity = quantityField.numberOrNull()
        when {
            barcode == null -> report("Enter a 4–14 digit barcode.")
            quantity == null || quantity !in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS ->
                report("Quantity must be between 1 and 5,000 g.")
            else -> {
                addButton.isEnabled = false
                missingBarcode = null
                contributionButton.visibility = View.GONE
                report("Looking up product…")
                worker.execute {
                    val result = runCatching {
                        store.product(barcode) ?: factsClient.product(barcode)?.also(store::upsertProduct)
                    }
                    post {
                        addButton.isEnabled = true
                        result.fold(
                            onSuccess = { product ->
                                if (product == null) {
                                    missingBarcode = barcode
                                    contributionButton.visibility = View.VISIBLE
                                    report("Product not found. You can add it to Open Food Facts.")
                                } else {
                                    addProduct(product, quantity, selectedMeal, FoodEntrySource.SEARCHED)
                                    barcodeField.text?.clear()
                                }
                            },
                            onFailure = { report("Lookup failed. Check the phone network connection.") },
                        )
                    }
                }
            }
        }
    }

    private fun addProduct(
        product: FoodProduct,
        quantityGrams: Double,
        mealType: MealType,
        source: FoodEntrySource,
        recipeId: String? = null,
    ) {
        worker.execute {
            val id = store.addEntry(
                product = product,
                quantityGrams = quantityGrams,
                consumedAtMillis = System.currentTimeMillis(),
                mealType = mealType,
                source = source,
                recipeId = recipeId,
            )
            val entry = store.entry(id)
            post {
                report("Added ${product.name} to ${mealType.displayName.lowercase()}.")
                refreshAll()
                entry?.let(::syncEntryIfEnabled)
            }
        }
    }

    private fun saveCustomFood() {
        val name = customNameField.text.toString().trim()
        if (name.isBlank()) return report("Enter a custom food name.")
        val core = listOf(customCaloriesField, customProteinField, customCarbsField, customFatField)
            .map { it.numberOrNull() }
        val saturatedFat = customSaturatedFatField.numberOrNull()
        val sodium = customSodiumField.numberOrNull()
        val potassium = customPotassiumField.numberOrNull()
        val calcium = customCalciumField.numberOrNull()
        val iron = customIronField.numberOrNull()
        val caffeine = customCaffeineField.numberOrNull()
        val cholesterol = customCholesterolField.numberOrNull()
        if (core.all { it == null }) return report("Add at least one calorie or macro value.")
        if ((core + listOf(saturatedFat, sodium, potassium, calcium, iron, caffeine, cholesterol))
            .any { it != null && (it < 0.0 || it > 100_000.0) }
        ) {
            return report("Nutrition values must be between 0 and 100,000.")
        }
        worker.execute {
            val product = store.createCustomFood(
                name = name,
                nutrients = NutrientsPer100g(
                    caloriesKcal = core[0],
                    proteinGrams = core[1],
                    carbohydrateGrams = core[2],
                    fatGrams = core[3],
                    sugarsGrams = null,
                    fiberGrams = null,
                    saltGrams = null,
                    saturatedFatGrams = saturatedFat,
                    sodiumMilligrams = sodium,
                    cholesterolMilligrams = cholesterol,
                    potassiumMilligrams = potassium,
                    calciumMilligrams = calcium,
                    ironMilligrams = iron,
                    caffeineMilligrams = caffeine,
                ),
            )
            store.setFavorite(product.barcode, true)
            post {
                customNameField.text?.clear()
                report("Saved ${product.name} and added it to favorites.")
                refreshAll()
            }
        }
    }

    private fun saveRecipe() {
        val name = recipeNameField.text.toString().trim()
        val servings = recipeServingsField.numberOrNull()
        val tokens = recipeIngredientsField.text.toString()
            .split(';')
            .map(String::trim)
            .filter(String::isNotBlank)
        if (name.isBlank()) return report("Enter a recipe name.")
        if (servings == null || servings !in 0.25..100.0) return report("Servings must be between 0.25 and 100.")
        if (tokens.isEmpty() || tokens.size > MAX_RECIPE_INGREDIENTS) return report("Add 1–64 recipe ingredients.")
        worker.execute {
            val result = runCatching {
                val ingredients = tokens.map { token ->
                    val separator = token.lastIndexOf(':')
                    require(separator > 0) { "Use product-id:grams for each ingredient." }
                    val id = token.substring(0, separator).trim()
                    val grams = token.substring(separator + 1).trim().replace(',', '.').toDoubleOrNull()
                    require(grams != null && grams in MIN_QUANTITY_GRAMS..MAX_RECIPE_INGREDIENT_GRAMS) {
                        "Each ingredient must be between 1 and 20,000 g."
                    }
                    val product = store.product(id) ?: error("Unknown product ID: $id")
                    RecipeIngredient(product, grams)
                }
                val recipe = FoodRecipe(
                    uuid = UUID.randomUUID().toString(),
                    name = name,
                    servings = servings,
                    ingredients = ingredients,
                    createdAtMillis = System.currentTimeMillis(),
                )
                store.saveRecipe(recipe)
                recipe
            }
            post {
                result.fold(
                    onSuccess = { recipe ->
                        recipeNameField.text?.clear()
                        recipeIngredientsField.text?.clear()
                        report("Saved recipe ${recipe.name}.")
                        refreshAll()
                    },
                    onFailure = { report(it.message ?: "Recipe could not be saved.") },
                )
            }
        }
    }

    private fun saveGoals() {
        val result = runCatching {
            NutritionGoals(
                caloriesKcal = goalCaloriesField.numberOrNull(),
                proteinGrams = goalProteinField.numberOrNull(),
                carbohydrateGrams = goalCarbsField.numberOrNull(),
                fatGrams = goalFatField.numberOrNull(),
            )
        }
        result.fold(
            onSuccess = { goals ->
                worker.execute {
                    store.saveGoals(goals)
                    post {
                        report("Daily goals saved.")
                        refreshAll()
                    }
                }
            },
            onFailure = { report("Goals must be positive and within supported limits.") },
        )
    }

    private fun cycleMeal() {
        val values = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER, MealType.SNACK)
        selectedMeal = values[(values.indexOf(selectedMeal).coerceAtLeast(0) + 1) % values.size]
        mealButton.text = selectedMeal.displayName.uppercase()
    }

    private fun cycleEntryMeal(entry: FoodEntry) {
        val values = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER, MealType.SNACK)
        val next = values[(values.indexOf(entry.mealType).coerceAtLeast(0) + 1) % values.size]
        worker.execute {
            val updated = store.updateEntryMeal(entry.uuid, next)
            val updatedEntry = if (updated) store.entry(entry.id) else null
            post {
                report(if (updated) "Moved entry to ${next.displayName.lowercase()}." else "Entry no longer exists.")
                updatedEntry?.let(::syncEntryIfEnabled)
                refreshAll()
            }
        }
    }

    private fun deleteEntry(entry: FoodEntry) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                if (entry.uuid.isNotBlank()) store.deleteEntry(entry.uuid) else store.deleteEntry(entry.id)
            }
            val healthResult = if (deleted && preferences.getBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false)) {
                healthBridge.deleteEntry(entry, userOptedIn = true)
            } else {
                null
            }
            if (healthResult == FoodLogHealthConnectSyncResult.PermissionRequired) {
                preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false).apply()
                refreshHealthState()
            }
            val message = when {
                !deleted -> "Entry was already removed."
                healthResult is FoodLogHealthConnectSyncResult.Failed -> "Deleted locally; Health Connect removal failed."
                healthResult == FoodLogHealthConnectSyncResult.PermissionRequired -> "Deleted locally; Health Connect permission was revoked."
                else -> "Deleted exactly ${entry.product.name}."
            }
            report(message)
            refreshAll()
        }
    }

    private fun toggleFavorite(product: FoodProduct, favorite: Boolean) {
        worker.execute {
            store.setFavorite(product.barcode, !favorite)
            post {
                report(if (favorite) "Removed from favorites." else "Added to favorites.")
                refreshAll()
            }
        }
    }

    private fun refreshAll() {
        worker.execute {
            val entries = store.entriesForDay()
            val goals = store.goals()
            val favorites = store.favoriteProducts()
            val recipes = store.recipes()
            val week = store.dailySummariesForWeek()
            val products = store.allProducts()
            val reminders = runCatching { FoodLogReminderStore(applicationContext).all() }
            post {
                renderSummary(entries, goals)
                renderEntries(entries, favorites.map(FoodProduct::barcode).toSet())
                renderFavorites(favorites)
                renderRecipes(recipes)
                renderWeek(week)
                renderProductCatalog(products)
                reminders.fold(
                    onSuccess = ::renderReminders,
                    onFailure = {
                        remindersList.removeAllViews()
                        remindersList.emptyCard("Reminder storage could not be read; existing data was preserved.")
                        report("Reminder storage needs recovery or a valid archive import.")
                    },
                )
                renderGoalFields(goals)
            }
        }
    }

    private fun renderSummary(entries: List<FoodEntry>, goals: NutritionGoals?) {
        val totals = aggregateNutrition(entries)
        summary.text = buildString {
            append("${totals.entryCount} ${if (totals.entryCount == 1) "entry" else "entries"} today\n")
            append(progress("Energy", totals.caloriesKcal, goals?.caloriesKcal, "kcal"))
            append("\n${progress("Protein", totals.proteinGrams, goals?.proteinGrams, "g")}")
            append(" · ${progress("Carbs", totals.carbohydrateGrams, goals?.carbohydrateGrams, "g")}")
            append(" · ${progress("Fat", totals.fatGrams, goals?.fatGrams, "g")}")
            append("\nFiber ${aggregateOptional(entries) { it.fiberGrams }.display("g")}")
            append(" · Sodium ${totals.sodiumMilligrams.display("mg")}")
            append(" · Iron ${totals.ironMilligrams.display("mg")}")
            append(" · Calcium ${totals.calciumMilligrams.display("mg")}")
        }
    }

    private fun renderEntries(entries: List<FoodEntry>, favoriteCodes: Set<String>) {
        entriesList.removeAllViews()
        if (entries.isEmpty()) return entriesList.emptyCard("No food logged today.")
        entries.forEachIndexed { index, entry ->
            if (index > 0) entriesList.addView(BusTheme.gap(this, 8))
            entriesList.addView(
                NexusUi.card(this).apply {
                    addView(NexusUi.rowTitle(this@FoodLogActivity, entry.product.name))
                    addView(
                        NexusUi.rowSub(
                            this@FoodLogActivity,
                            "${formatEntryTime(entry.consumedAtMillis)} · ${entry.mealType.displayName} · ${formatNutritionNumber(entry.quantityGrams)} g",
                        ),
                    )
                    addView(BusTheme.gap(this@FoodLogActivity, 8))
                    val calories = scaledValue(entry.product.nutrients.caloriesKcal, entry.quantityGrams)
                    addView(NexusUi.metaLabel(this@FoodLogActivity, "${calories.value("kcal")} · ${entry.product.barcode}"))
                    addView(BusTheme.gap(this@FoodLogActivity, 8))
                    addView(
                        horizontalActions(
                            NexusUi.textButton(this@FoodLogActivity, entry.mealType.displayName).apply {
                                setOnClickListener { cycleEntryMeal(entry) }
                            },
                            NexusUi.textButton(
                                this@FoodLogActivity,
                                if (entry.product.barcode in favoriteCodes) "Unfavorite" else "Favorite",
                            ).apply {
                                setOnClickListener {
                                    toggleFavorite(entry.product, entry.product.barcode in favoriteCodes)
                                }
                            },
                            NexusUi.textButton(this@FoodLogActivity, "Delete", danger = true).apply {
                                setOnClickListener { deleteEntry(entry) }
                            },
                        ),
                    )
                },
                NexusUi.block(),
            )
        }
    }

    private fun renderFavorites(products: List<FoodProduct>) {
        favoritesList.removeAllViews()
        if (products.isEmpty()) return favoritesList.emptyCard("Favorite an entry or create a custom food.")
        products.forEachIndexed { index, product ->
            if (index > 0) favoritesList.addView(BusTheme.gap(this, 8))
            favoritesList.addView(actionRow(product.name, product.brand.ifBlank { product.barcode }, "Add") {
                val amount = product.servingGrams?.takeIf { it in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS } ?: 100.0
                addProduct(product, amount, selectedMeal, sourceFor(product))
            }, NexusUi.block())
        }
    }

    private fun renderRecipes(recipes: List<FoodRecipe>) {
        recipesList.removeAllViews()
        if (recipes.isEmpty()) return recipesList.emptyCard("No saved recipes.")
        recipes.forEachIndexed { index, recipe ->
            if (index > 0) recipesList.addView(BusTheme.gap(this, 8))
            val product = recipe.asProduct()
            recipesList.addView(
                actionRow(
                    recipe.name,
                    "${recipe.ingredients.size} ingredients · ${formatNutritionNumber(recipe.servings)} servings",
                    "Log serving",
                ) {
                    addProduct(
                        product,
                        product.servingGrams ?: 100.0,
                        selectedMeal,
                        FoodEntrySource.RECIPE,
                        recipe.uuid,
                    )
                },
                NexusUi.block(),
            )
        }
    }

    private fun renderWeek(days: List<Pair<java.time.LocalDate, DailyNutritionTotals>>) {
        weeklyList.removeAllViews()
        val formatter = DateTimeFormatter.ofPattern("EEE d")
        days.forEachIndexed { index, (date, totals) ->
            if (index > 0) weeklyList.addView(BusTheme.gap(this, 8))
            weeklyList.addView(
                NexusUi.card(this).apply {
                    addView(NexusUi.rowTitle(this@FoodLogActivity, date.format(formatter)))
                    addView(
                        NexusUi.rowSub(
                            this@FoodLogActivity,
                            "${totals.caloriesKcal.display("kcal")} · P ${totals.proteinGrams.display("g")} · ${totals.entryCount} entries",
                        ),
                    )
                },
                NexusUi.block(),
            )
        }
    }

    private fun renderProductCatalog(products: List<FoodProduct>) {
        productCatalog.removeAllViews()
        if (products.isEmpty()) return productCatalog.emptyCard("No products stored yet.")
        products.take(MAX_CATALOG_PRODUCTS).forEachIndexed { index, product ->
            if (index > 0) productCatalog.addView(BusTheme.gap(this, 6))
            productCatalog.addView(
                NexusUi.card(this).apply {
                    addView(NexusUi.rowTitle(this@FoodLogActivity, product.name))
                    addView(NexusUi.rowSub(this@FoodLogActivity, product.barcode))
                },
                NexusUi.block(),
            )
        }
        if (products.size > MAX_CATALOG_PRODUCTS) {
            productCatalog.addView(NexusUi.cardBody(this, "${products.size - MAX_CATALOG_PRODUCTS} more products omitted."))
        }
    }

    private fun renderReminders(reminders: List<FoodLogReminder>) {
        remindersList.removeAllViews()
        if (reminders.isEmpty()) return remindersList.emptyCard("No scheduled reminders.")
        reminders.forEachIndexed { index, reminder ->
            if (index > 0) remindersList.addView(BusTheme.gap(this, 8))
            remindersList.addView(NexusUi.card(this).apply {
                addView(NexusUi.rowTitle(this@FoodLogActivity, reminder.label))
                addView(BusTheme.gap(this@FoodLogActivity, 4))
                addView(NexusUi.rowSub(
                    this@FoodLogActivity,
                    "${reminder.kind.name.lowercase().replaceFirstChar(Char::uppercase)} · ${formatReminderTime(reminder.epochMillis)} · ${if (reminder.enabled) "active" else "paused"}",
                ))
                addView(BusTheme.gap(this@FoodLogActivity, 8))
                addView(horizontalActions(
                    NexusUi.textButton(this@FoodLogActivity, if (reminder.enabled) "Pause" else "Resume").apply {
                        setOnClickListener { setReminderEnabled(reminder, !reminder.enabled) }
                    },
                    NexusUi.textButton(this@FoodLogActivity, "Cancel", true).apply {
                        setOnClickListener { cancelReminder(reminder.id) }
                    },
                ))
            }, NexusUi.block())
        }
    }

    private fun renderGoalFields(goals: NutritionGoals?) {
        if (goalCaloriesField.hasFocus() || goalProteinField.hasFocus() || goalCarbsField.hasFocus() || goalFatField.hasFocus()) return
        goalCaloriesField.setNumber(goals?.caloriesKcal)
        goalProteinField.setNumber(goals?.proteinGrams)
        goalCarbsField.setNumber(goals?.carbohydrateGrams)
        goalFatField.setNumber(goals?.fatGrams)
    }

    private fun scheduleReminder() {
        val label = reminderLabelField.text.toString().trim()
        val minutes = reminderMinutesField.numberOrNull()
        if (label.isBlank()) return report("Enter a reminder label.")
        if (minutes == null || minutes !in 1.0..525_600.0) return report("Reminder time must be 1–525,600 minutes.")
        val kind = reminderKind
        worker.execute {
            val result = runCatching {
                val reminderStore = FoodLogReminderStore(applicationContext)
                val reminder = reminderStore.create(
                    kind = kind,
                    label = label,
                    epochMillis = System.currentTimeMillis() + (minutes * 60_000.0).toLong(),
                )
                try {
                    foodLogReminderScheduler(applicationContext).schedule(reminder)
                    reminder
                } catch (exception: Exception) {
                    reminderStore.delete(reminder.id)
                    throw exception
                }
            }
            post {
                if (result.isSuccess) {
                    reminderLabelField.text?.clear()
                    maybeRequestNotificationPermission()
                    report("Reminder scheduled${if (canScheduleExactAlarm()) " exactly" else " with inexact timing"}.")
                    refreshAll()
                } else {
                    report("Reminder could not be scheduled.")
                }
            }
        }
    }

    private fun cancelReminder(id: String) {
        worker.execute {
            val result = runCatching {
                val removed = FoodLogReminderStore(applicationContext).cancel(id)
                if (removed != null) runCatching { foodLogReminderScheduler(applicationContext).cancel(id) }
                removed
            }
            post {
                report(result.fold(
                    onSuccess = { if (it != null) "Cancelled exactly that reminder." else "Reminder no longer exists." },
                    onFailure = { "Reminder could not be cancelled; existing data was preserved." },
                ))
                refreshAll()
            }
        }
    }

    private fun setReminderEnabled(reminder: FoodLogReminder, enabled: Boolean) {
        worker.execute {
            val reminderStore = FoodLogReminderStore(applicationContext)
            val scheduler = foodLogReminderScheduler(applicationContext)
            val updated = reminder.copy(enabled = enabled)
            val result = runCatching {
                check(reminderStore.update(updated)) { "Reminder no longer exists" }
                if (enabled) {
                    try {
                        scheduler.reschedule(updated)
                    } catch (exception: Exception) {
                        reminderStore.update(reminder)
                        throw exception
                    }
                } else {
                    runCatching { scheduler.cancel(reminder.id) }
                }
            }
            post {
                report(
                    if (result.isSuccess) {
                        if (enabled) "Reminder resumed." else "Reminder paused."
                    } else {
                        "Reminder could not be updated."
                    },
                )
                refreshAll()
            }
        }
    }

    private fun setHealthSyncEnabled(enabled: Boolean) {
        if (!enabled) {
            healthConsentGeneration += 1L
            pendingHealthPermissionGeneration = null
            preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false).apply()
            healthStatus.text = "Sync is off. Existing Health Connect records are not deleted."
            return
        }
        val requestGeneration = ++healthConsentGeneration
        scope.launch {
            when (healthBridge.availability()) {
                FoodLogHealthConnectAvailability.Available -> {
                    if (healthBridge.hasWriteNutritionPermission()) {
                        if (requestGeneration != healthConsentGeneration) return@launch
                        preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, true).apply()
                        report("Health Connect sync enabled.")
                        refreshHealthState()
                    } else {
                        if (requestGeneration != healthConsentGeneration) return@launch
                        updatingHealthSwitch = true
                        healthSwitch.isChecked = false
                        updatingHealthSwitch = false
                        val intent = healthPermissionContract.createIntent(
                            this@FoodLogActivity,
                            setOf(FoodLogHealthConnectBridge.WRITE_NUTRITION_PERMISSION),
                        )
                        pendingHealthPermissionGeneration = requestGeneration
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQUEST_HEALTH_PERMISSION)
                    }
                }
                FoodLogHealthConnectAvailability.ProviderUpdateRequired -> {
                    setHealthSwitch(false)
                    healthStatus.text = "Health Connect needs an update."
                }
                FoodLogHealthConnectAvailability.Unavailable -> {
                    setHealthSwitch(false)
                    healthStatus.text = "Health Connect is unavailable on this phone."
                }
            }
        }
    }

    private fun refreshHealthState() {
        scope.launch {
            val optedIn = preferences.getBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false)
            when (healthBridge.availability()) {
                FoodLogHealthConnectAvailability.Available -> {
                    val granted = healthBridge.hasWriteNutritionPermission()
                    if (optedIn && !granted) preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false).apply()
                    setHealthSwitch(optedIn && granted)
                    healthStatus.text = when {
                        optedIn && granted -> "Enabled. New entries are written after local save."
                        granted -> "Permission granted, but sync is off."
                        else -> "Off. Enable to request write-only nutrition access."
                    }
                }
                FoodLogHealthConnectAvailability.ProviderUpdateRequired -> {
                    setHealthSwitch(false)
                    healthStatus.text = "Health Connect needs an update."
                }
                FoodLogHealthConnectAvailability.Unavailable -> {
                    setHealthSwitch(false)
                    healthStatus.text = "Health Connect is unavailable on this phone."
                }
            }
        }
    }

    private fun setHealthSwitch(checked: Boolean) {
        updatingHealthSwitch = true
        healthSwitch.isChecked = checked
        updatingHealthSwitch = false
    }

    private fun syncEntryIfEnabled(entry: FoodEntry) {
        if (!preferences.getBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false)) return
        scope.launch {
            when (val result = healthBridge.syncEntry(entry, userOptedIn = true)) {
                FoodLogHealthConnectSyncResult.Synced -> Unit
                FoodLogHealthConnectSyncResult.PermissionRequired -> {
                    preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false).apply()
                    report("Saved locally; Health Connect permission was revoked.")
                    refreshHealthState()
                }
                is FoodLogHealthConnectSyncResult.Failed -> report("Saved locally; Health Connect sync failed.")
                else -> Unit
            }
        }
    }

    private fun syncTodayToHealthConnect() {
        if (!preferences.getBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false)) return report("Enable Health Connect first.")
        scope.launch {
            val entries = withContext(Dispatchers.IO) { store.entriesForDay() }
            var synced = 0
            var permissionRevoked = false
            var failed = 0
            for (entry in entries) {
                when (healthBridge.syncEntry(entry, userOptedIn = true)) {
                    FoodLogHealthConnectSyncResult.Synced -> synced += 1
                    FoodLogHealthConnectSyncResult.PermissionRequired -> {
                        permissionRevoked = true
                        preferences.edit().putBoolean(FOOD_LOG_HEALTH_SYNC_KEY, false).apply()
                        break
                    }
                    is FoodLogHealthConnectSyncResult.Failed -> failed += 1
                    else -> Unit
                }
            }
            if (permissionRevoked) refreshHealthState()
            report(
                when {
                    permissionRevoked -> "Synced $synced entries; Health Connect permission was revoked."
                    failed > 0 -> "Synced $synced of ${entries.size} entries; $failed failed."
                    else -> "Synced $synced of ${entries.size} entries to Health Connect."
                },
            )
        }
    }

    private fun chooseExportDestination() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "food-log-backup.json")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun chooseImportSource() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    private fun writeBackup(uri: Uri) {
        worker.execute {
            val result = runCatching {
                val reminders = FoodLogReminderStore(applicationContext).all()
                val json = store.exportJson(reminders)
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                    ?: error("Destination unavailable")
            }
            post { report(if (result.isSuccess) "Food Log archive exported." else "Export failed.") }
        }
    }

    private fun readBackup(uri: Uri) {
        worker.execute {
            val result = runCatching {
                val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use(::readBounded)
                    ?: error("Source unavailable")
                val imported = store.importJson(json)
                val reminders = FoodLogReminderStore(applicationContext).merge(imported.reminders)
                val scheduler = foodLogReminderScheduler(applicationContext)
                val scheduleFailures = reminders.count { reminder ->
                    reminder.enabled && runCatching { scheduler.reschedule(reminder) }.isFailure
                }
                imported to scheduleFailures
            }
            post {
                result.fold(
                    onSuccess = { (imported, scheduleFailures) ->
                        val warning = if (scheduleFailures == 0) "" else " $scheduleFailures reminders need rescheduling."
                        report("Imported ${imported.insertedEntries} new entries and ${imported.reminders.size} reminders; stable duplicates were merged.$warning")
                        refreshAll()
                    },
                    onFailure = { report("Import rejected: ${it.message ?: "invalid backup"}") },
                )
            }
        }
    }

    private fun openFoodFactsContribution(barcode: String) {
        val uri = Uri.parse("https://world.openfoodfacts.org/cgi/product.pl")
            .buildUpon()
            .appendQueryParameter("type", "edit")
            .appendQueryParameter("code", barcode)
            .build()
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun readBounded(reader: java.io.Reader): String {
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(output.length + count <= MAX_BACKUP_CHARS) { "Backup is too large" }
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return report("Exact alarms are already available.")
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }.onFailure { report("Exact-alarm settings are unavailable on this phone.") }
    }

    private fun maybeRequestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun canScheduleExactAlarm(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    private fun actionRow(
        title: String,
        subtitle: String,
        action: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout = NexusUi.pressableCard(this).apply {
        addView(
            LinearLayout(this@FoodLogActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@FoodLogActivity, title))
                addView(BusTheme.gap(this@FoodLogActivity, 4))
                addView(NexusUi.rowSub(this@FoodLogActivity, subtitle))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.textButton(this@FoodLogActivity, action, danger).apply { setOnClickListener { onClick() } })
    }

    private fun horizontalActions(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { button -> addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun verticalList() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun LinearLayout.emptyCard(message: String) {
        addView(NexusUi.cardBody(this@FoodLogActivity, message), NexusUi.block())
    }

    private fun textField(hint: String): EditText = NexusUi.field(this, hint)

    private fun numberField(hint: String): EditText = NexusUi.field(this, hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun EditText.numberOrNull(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun EditText.setNumber(value: Double?) {
        setText(value?.let(::formatNutritionNumber).orEmpty())
    }

    private fun progress(label: String, total: NutritionTotal, goal: Double?, unit: String): String {
        val value = total.display(unit)
        val percent = goal?.takeIf { it > 0.0 }?.let { total.knownValue / it * 100.0 }
        return if (percent == null) "$label $value" else "$label $value / ${formatNutritionNumber(goal)} $unit (${formatNutritionNumber(percent)}%)"
    }

    private fun aggregateOptional(entries: List<FoodEntry>, selector: (NutrientsPer100g) -> Double?): NutritionTotal {
        var total = 0.0
        var complete = true
        entries.forEach { entry ->
            val value = selector(entry.product.nutrients)
            if (value == null) complete = false else total += value * entry.quantityGrams / 100.0
        }
        return NutritionTotal(total, complete)
    }

    private fun sourceFor(product: FoodProduct): FoodEntrySource = when {
        product.barcode.startsWith("custom-") -> FoodEntrySource.CUSTOM
        product.barcode.startsWith("recipe-") -> FoodEntrySource.RECIPE
        else -> FoodEntrySource.SEARCHED
    }

    private fun Double?.value(unit: String): String = this?.let { "${formatNutritionNumber(it)} $unit" } ?: "unknown"

    private fun formatEntryTime(millis: Long): String =
        ENTRY_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun formatReminderTime(millis: Long): String =
        REMINDER_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun report(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun post(block: () -> Unit) {
        runOnUiThread { if (!destroyed) block() }
    }

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Food Log") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val DEFAULT_QUANTITY_GRAMS = "100"
        const val REQUEST_HEALTH_PERMISSION = 301
        const val REQUEST_EXPORT = 302
        const val REQUEST_IMPORT = 303
        const val REQUEST_NOTIFICATIONS = 304
        const val MAX_CATALOG_PRODUCTS = 50
        const val MAX_BACKUP_CHARS = 12_000_000
        val ENTRY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val REMINDER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
}
