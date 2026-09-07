package com.anezium.rokidbus.plugin.foodlog

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import androidx.health.connect.client.PermissionController
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Phone-side dashboard and management surface for the local Food Log journal. */
class FoodLogActivity : ComponentActivity() {
    private val navigationBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = navigateBack()
    }
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

    private lateinit var status: TextView
    private lateinit var favoritesList: LinearLayout
    private lateinit var recipesList: LinearLayout
    private lateinit var weeklyList: LinearLayout
    private lateinit var remindersList: LinearLayout
    private lateinit var productCatalog: LinearLayout

    private lateinit var barcodeField: EditText
    private lateinit var addButton: View
    private lateinit var contributionButton: Button

    private lateinit var customNameField: EditText
    private lateinit var customCaloriesField: EditText
    private lateinit var customProteinField: EditText
    private lateinit var customCarbsField: EditText
    private lateinit var customFatField: EditText
    private lateinit var customServingField: EditText
    private lateinit var customSaveButton: Button
    private lateinit var customSaturatedFatField: EditText
    private lateinit var customSodiumField: EditText
    private lateinit var customPotassiumField: EditText
    private lateinit var customCalciumField: EditText
    private lateinit var customIronField: EditText
    private lateinit var customCaffeineField: EditText
    private lateinit var customCholesterolField: EditText

    private lateinit var journal: FoodJournalView
    private lateinit var body: FrameLayout
    private lateinit var catalogQuery: EditText
    private lateinit var foodDateLabel: TextView
    private val pages = linkedMapOf<String, ScrollView>()
    private val tabs = linkedMapOf<String, Button>()
    private var currentTab = "Journal"
    private var selectedDate = LocalDate.now()
    private var entryEditor: FoodEntryEditor? = null
    private var recipeEditor: FoodRecipeEditor? = null
    private var refreshGeneration = 0
    private var catalogGeneration = 0
    private var barcodeLookupGeneration = 0

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
        onBackPressedDispatcher.addCallback(this, navigationBack)
        store = FoodLogStore(applicationContext)
        selectedDate = savedInstanceState?.getString("journalDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        currentTab = savedInstanceState?.getString("tab")?.takeIf { it in listOf("Journal", "Foods", "Settings") } ?: "Journal"
        buildUi()
        refreshAll()
        refreshHealthState()
        savedInstanceState?.getBundle("entryDraft")?.let { state ->
            runCatching {
                val product = FoodLogBackup.readProductSnapshot(requireNotNull(state.getString("product")))
                val original = state.getString("original")?.let(FoodLogBackup::readEntrySnapshot)
                openEntryEditor(product, original)
                entryEditor?.restoreState(state)
            }.onFailure { closeEditor(); report("The unsaved entry could not be restored. Your saved journal is unchanged.") }
        }
        savedInstanceState?.getBundle("recipeDraft")?.let { state ->
            runCatching {
                openRecipeEditor(state.getString("original")?.let(FoodLogBackup::readRecipeSnapshot))
                recipeEditor?.restoreState(state)
            }.onFailure { closeEditor(); report("The unsaved recipe could not be restored. Saved recipes are unchanged.") }
        }
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        // Let an in-flight local transaction finish before closing its database.
        if (::store.isInitialized) worker.execute { store.close() }
        worker.shutdown()
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
            REQUEST_EXPORT -> if (resultCode == RESULT_OK) data?.data?.let(::writeBackup)
            REQUEST_IMPORT -> if (resultCode == RESULT_OK) data?.data?.let(::readBackup)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("journalDate", selectedDate.toString())
        outState.putString("tab", currentTab)
        entryEditor?.let { outState.putBundle("entryDraft", it.saveState()) }
        recipeEditor?.let { outState.putBundle("recipeDraft", it.saveState()) }
        super.onSaveInstanceState(outState)
    }

    private fun navigateBack() {
        if (entryEditor?.isSaving == true || recipeEditor?.isSaving == true) return report("Finishing the local save…")
        if (entryEditor != null || recipeEditor != null) {
            AlertDialog.Builder(this).setTitle("Discard unsaved changes?")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard") { _, _ -> closeEditor() }.show()
        } else if (currentTab != "Journal") showTab("Journal")
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        status = NexusUi.statusLine(this).apply { visibility = View.GONE }
        favoritesList = verticalList()
        recipesList = verticalList()
        weeklyList = verticalList()
        remindersList = verticalList()
        productCatalog = verticalList()
        catalogQuery = textField("Search saved foods or brands").apply {
            watch { refreshCatalog() }
        }
        foodDateLabel = NexusUi.rowSub(this, "")
        buildQuickAddControls()
        buildCustomFoodControls()
        buildGoalControls()
        buildHealthControls()
        buildReminderControls()
        journal = FoodJournalView(this, selectedDate,
            onDate = { selectedDate = it; refreshAll() },
            onAdd = { selectedMeal = it; showTab("Foods") },
            onEdit = { openEntryEditor(it.product, it) },
            onDelete = ::confirmDeleteEntry,
        )
        val foods = NexusUi.contentColumn(this).apply {
            addView(foodDateLabel, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 12))
            addView(catalogQuery, NexusUi.block())
            addView(productCatalog, NexusUi.block())
            section(this, "Favorites", favoritesList)
            section(this, "Barcode", collapsed("Look up a barcode", quickAddCard()))
            section(this, "Custom food", collapsed("Create custom food", customFoodCard()))
            section(this, "Recipes",
                NexusUi.outlinePillButton(this@FoodLogActivity, "Create recipe").apply { setOnClickListener { openRecipeEditor() } },
                recipesList)
        }
        val settings = NexusUi.contentColumn(this).apply {
            section(this, "Optional daily goals", goalsCard())
            section(this, "7-day statistics", collapsed("View daily totals", weeklyList))
            section(this, "Health Connect", collapsed("Manage Health Connect", healthCard()))
            section(this, "Reminders", collapsed("Manage reminders", verticalList().apply {
                addView(remindersCard(), NexusUi.block()); addView(remindersList, NexusUi.block())
            }))
            section(this, "Backup", collapsed("Export or import journal", backupCard()))
            section(this, "Data", NexusUi.cardBody(this@FoodLogActivity,
                "Your journal, foods and recipes stay on this phone. Open Food Facts data can be incomplete; check the package label. Unknown nutrition stays unknown."))
            section(this, "Plugin", uninstallRow())
        }
        pages["Journal"] = NexusUi.screen(this, journal.view)
        pages["Foods"] = NexusUi.screen(this, foods)
        pages["Settings"] = NexusUi.screen(this, settings)
        body = FrameLayout(this)
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@FoodLogActivity, R.drawable.nexus_glyph_foodlog,
                "Food Log", "Local nutrition journal · v3.1"), NexusUi.block())
            addView(LinearLayout(this@FoodLogActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                pages.keys.forEach { label ->
                    val button = NexusUi.textButton(this@FoodLogActivity, label).apply {
                        setOnClickListener { showTab(label) }
                    }
                    tabs[label] = button
                    addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }, NexusUi.block())
            addView(status, NexusUi.block())
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        showTab(currentTab)
    }

    private fun collapsed(label: String, content: View): LinearLayout = verticalList().apply {
        val toggle = NexusUi.outlinePillButton(this@FoodLogActivity, label)
        content.visibility = View.GONE
        toggle.setOnClickListener {
            val expanded = content.visibility != View.VISIBLE
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = if (expanded) "Hide ${label.lowercase()}" else label
        }
        addView(toggle, NexusUi.block())
        addView(content, NexusUi.block())
    }

    private fun showTab(label: String) {
        if (entryEditor != null || recipeEditor != null) return
        if (label != "Foods") {
            barcodeLookupGeneration += 1
            addButton.isEnabled = true
        }
        currentTab = label
        navigationBack.isEnabled = label != "Journal"
        foodDateLabel.text = "Adding to ${selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))} · ${selectedMeal.displayName}"
        body.removeAllViews()
        body.addView(pages.getValue(label))
        tabs.forEach { (name, button) ->
            button.isSelected = name == label
            button.setTextColor(if (name == label) NexusUi.ACCENT else NexusUi.MUTED)
        }
    }

    private fun showEditor(view: LinearLayout) {
        navigationBack.isEnabled = true
        tabs.values.forEach { it.isEnabled = false }
        body.removeAllViews()
        body.addView(NexusUi.screen(this, view))
    }

    private fun closeEditor() {
        entryEditor = null
        recipeEditor = null
        tabs.values.forEach { it.isEnabled = true }
        showTab(currentTab)
    }

    private fun openEntryEditor(product: FoodProduct, original: FoodEntry? = null) {
        val editor = FoodEntryEditor(this, product, selectedDate, selectedMeal, original,
            onSave = { portion -> savePortion(product, original, portion) }, onCancel = ::closeEditor)
        entryEditor = editor
        showEditor(editor.view)
    }

    private fun savePortion(product: FoodProduct, original: FoodEntry?, portion: FoodEntryPortion) {
        val editor = entryEditor
        worker.execute {
            val result = runCatching {
                val id = if (original == null) store.addEntry(product, portion.grams, portion.consumedAtMillis,
                    portion.meal, sourceFor(product), product.barcode.removePrefix("recipe-").takeIf { product.barcode.startsWith("recipe-") },
                    uuid = requireNotNull(editor).entryUuid)
                else {
                    check(store.updateEntry(original, portion.grams, portion.consumedAtMillis, portion.meal)) {
                        "This entry changed or was removed. Cancel and reopen it before editing."
                    }
                    original.id
                }
                store.entry(id) ?: error("The saved entry could not be read.")
            }
            post { result.fold(onSuccess = { entry ->
                closeEditor()
                currentTab = "Journal"
                selectedDate = Instant.ofEpochMilli(entry.consumedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                journal.choose(selectedDate)
                showTab("Journal")
                report(if (original == null) "Added ${product.name}." else "Saved changes to ${product.name}.")
                syncEntryIfEnabled(entry)
            }, onFailure = { editor?.showError(it.message ?: "Entry could not be saved.") }) }
        }
    }

    private fun openRecipeEditor(original: FoodRecipe? = null) {
        val editor = FoodRecipeEditor(this, original,
            search = { query, callback -> worker.execute {
                val products = runCatching { store.searchProducts(query, 500) }.getOrDefault(emptyList())
                post { callback(products) }
            } },
            onSave = { recipe -> worker.execute {
                val result = runCatching { store.saveRecipe(recipe) }
                post { result.fold(onSuccess = {
                    closeEditor(); report("Saved recipe ${recipe.name}."); refreshAll()
                }, onFailure = { recipeEditor?.showError("Recipe could not be saved. Existing data was preserved.") }) }
            } }, onCancel = ::closeEditor)
        recipeEditor = editor
        showEditor(editor.view)
    }

    private fun confirmDeleteEntry(entry: FoodEntry) {
        AlertDialog.Builder(this).setTitle("Delete this entry?")
            .setMessage("${entry.product.name} · ${formatNutritionNumber(entry.quantityGrams)} g\n${entry.mealType.displayName} · ${formatReminderTime(entry.consumedAtMillis)}\nOnly this entry will be deleted.")
            .setNegativeButton("Keep entry", null)
            .setPositiveButton("Delete entry") { _, _ -> deleteEntry(entry) }.show()
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
        barcodeField = textField("Barcode, 4–14 digits").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        addButton = NexusUi.pillButton(this, "Look up product").apply { setOnClickListener { addFromBarcode() } }
        contributionButton = NexusUi.outlinePillButton(this, "Add product to Open Food Facts").apply {
            visibility = View.GONE
            setOnClickListener { missingBarcode?.let(::openFoodFactsContribution) }
        }
    }

    private fun quickAddCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowSub(this@FoodLogActivity, "Scan the barcode from the glasses, or enter it here. Review the product and portion before adding."))
        addView(barcodeField, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(addButton, NexusUi.block())
        addView(contributionButton, NexusUi.block())
    }

    private fun buildCustomFoodControls() {
        customNameField = textField("Food name")
        customCaloriesField = numberField("Calories / 100 g")
        customProteinField = numberField("Protein g / 100 g")
        customCarbsField = numberField("Carbohydrate g / 100 g")
        customFatField = numberField("Fat g / 100 g")
        customServingField = numberField("One serving in grams (optional)")
        customSaturatedFatField = numberField("Saturated fat g / 100 g (optional)")
        customSodiumField = numberField("Sodium mg / 100 g (optional)")
        customPotassiumField = numberField("Potassium mg / 100 g (optional)")
        customCalciumField = numberField("Calcium mg / 100 g (optional)")
        customIronField = numberField("Iron mg / 100 g (optional)")
        customCaffeineField = numberField("Caffeine mg / 100 g (optional)")
        customCholesterolField = numberField("Cholesterol mg / 100 g (optional)")
    }

    private fun customFoodCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowSub(this@FoodLogActivity, "Copy the nutrition per 100 g from a label. Leave unknown values blank."))
        listOf(customNameField, customCaloriesField, customProteinField, customCarbsField, customFatField, customServingField).forEach { field ->
            addView(BusTheme.gap(this@FoodLogActivity, 8)); addView(field, NexusUi.block())
        }
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        addView(collapsed("Additional nutrition (optional)", verticalList().apply {
            listOf(customSaturatedFatField, customSodiumField, customPotassiumField, customCalciumField,
                customIronField, customCaffeineField, customCholesterolField).forEach { field ->
                addView(BusTheme.gap(this@FoodLogActivity, 8)); addView(field, NexusUi.block())
            }
        }), NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 10))
        customSaveButton = NexusUi.pillButton(this@FoodLogActivity, "Save food and choose portion").apply { setOnClickListener { saveCustomFood() } }
        addView(customSaveButton, NexusUi.block())
    }

    private fun buildGoalControls() {
        goalCaloriesField = numberField("Daily calories")
        goalProteinField = numberField("Daily protein g")
        goalCarbsField = numberField("Daily carbohydrate g")
        goalFatField = numberField("Daily fat g")
    }

    private fun goalsCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowSub(this@FoodLogActivity, "Set only the goals you choose. Leave fields blank to remove goals."))
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
        when {
            barcode == null -> report("Enter a 4–14 digit barcode.")
            else -> {
                val request = ++barcodeLookupGeneration
                addButton.isEnabled = false
                missingBarcode = null
                contributionButton.visibility = View.GONE
                report("Looking up product…")
                worker.execute {
                    val result = runCatching {
                        store.product(barcode) ?: factsClient.product(barcode)?.also(store::upsertProduct)
                    }
                    post {
                        if (request != barcodeLookupGeneration || currentTab != "Foods" || entryEditor != null || recipeEditor != null) return@post
                        addButton.isEnabled = true
                        result.fold(
                            onSuccess = { product ->
                                if (product == null) {
                                    missingBarcode = barcode
                                    contributionButton.visibility = View.VISIBLE
                                    report("Product not found. You can add it to Open Food Facts.")
                                } else {
                                    openEntryEditor(product)
                                    barcodeField.text?.clear()
                                }
                            },
                            onFailure = { report(if (it is FoodFactsLookupException) it.message.orEmpty() else "Lookup failed. Check the phone network connection.") },
                        )
                    }
                }
            }
        }
    }

    private fun saveCustomFood() {
        val name = customNameField.text.toString().trim()
        if (name.isBlank() || name.length > 300) return report("Enter a food name of 1–300 characters.")
        val fields = listOf(customCaloriesField, customProteinField, customCarbsField, customFatField,
            customSaturatedFatField, customSodiumField, customPotassiumField, customCalciumField,
            customIronField, customCaffeineField, customCholesterolField)
        val values = runCatching { fields.map { it.optionalNumber() } }.getOrElse { return report("Enter valid nutrition values, or leave unknown values blank.") }
        if (values.take(4).all { it == null }) return report("Add at least one calorie or macro value.")
        if (values.any { it != null && it !in 0.0..100_000.0 }) return report("Nutrition values must be between 0 and 100,000.")
        if (values.slice(1..4).any { it != null && it > 100.0 }) return report("Nutrients measured in grams cannot exceed 100 g per 100 g.")
        val serving = runCatching { customServingField.optionalNumber() }.getOrElse { return report("Enter a serving weight in grams, or leave it blank.") }
        if (serving != null && serving !in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS) return report("One serving must weigh 1–5,000 g.")
        customSaveButton.isEnabled = false
        worker.execute {
            val result = runCatching {
                store.createCustomFood(name, NutrientsPer100g(values[0], values[1], values[2], values[3], null, null, null,
                    saturatedFatGrams = values[4], sodiumMilligrams = values[5], potassiumMilligrams = values[6],
                    calciumMilligrams = values[7], ironMilligrams = values[8], caffeineMilligrams = values[9], cholesterolMilligrams = values[10]),
                    servingGrams = serving).also { store.setFavorite(it.barcode, true) }
            }
            post {
                customSaveButton.isEnabled = true
                result.fold(onSuccess = { product ->
                    (fields + customNameField + customServingField).forEach { it.text?.clear() }
                    report("Saved ${product.name} to your foods and favorites.")
                    refreshAll(); openEntryEditor(product)
                }, onFailure = { report("Custom food could not be saved. Check the values and try again.") })
            }
        }
    }

    private fun saveGoals() {
        val result = runCatching {
            NutritionGoals(
                caloriesKcal = goalCaloriesField.optionalNumber(),
                proteinGrams = goalProteinField.optionalNumber(),
                carbohydrateGrams = goalCarbsField.optionalNumber(),
                fatGrams = goalFatField.optionalNumber(),
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

    private fun deleteEntry(entry: FoodEntry) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                store.deleteEntry(entry)
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
                !deleted -> "The entry changed or was removed. Reopen it before deleting."
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
        val request = ++refreshGeneration
        val date = selectedDate
        val dayMillis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        worker.execute {
            val result = runCatching {
                val entries = store.entriesForDay(dayMillis)
                val goals = store.goals()
                val favorites = store.favoriteProducts()
                val recipes = store.recipes()
                val week = store.dailySummariesForWeek(dayMillis)
                val reminders = runCatching { FoodLogReminderStore(applicationContext).all() }
                post {
                    if (request != refreshGeneration) return@post
                    journal.render(entries, goals)
                    renderFavorites(favorites)
                    renderRecipes(recipes)
                    renderWeek(week)
                    reminders.fold(onSuccess = ::renderReminders, onFailure = {
                        remindersList.removeAllViews()
                        remindersList.emptyCard("Reminders could not be read. Existing data was preserved.")
                    })
                    renderGoalFields(goals)
                    refreshCatalog()
                }
            }
            if (result.isFailure) post {
                if (request == refreshGeneration) { journal.showError(); report("Journal could not be loaded. Existing data was preserved.") }
            }
        }
    }

    private fun renderFavorites(products: List<FoodProduct>) {
        favoritesList.removeAllViews()
        if (products.isEmpty()) return favoritesList.emptyCard("Save a favorite from the food catalog for faster logging.")
        products.forEach { product ->
            favoritesList.addView(actionRow(product.name, product.brand.ifBlank { "Saved food" }, "Log") {
                openEntryEditor(product)
            }, NexusUi.block())
        }
    }

    private fun renderRecipes(recipes: List<FoodRecipe>) {
        recipesList.removeAllViews()
        if (recipes.isEmpty()) return recipesList.emptyCard("Combine saved foods into a recipe, then log a portion whenever you eat it.")
        recipes.forEach { recipe ->
            recipesList.addView(NexusUi.card(this).apply {
                addView(NexusUi.rowTitle(this@FoodLogActivity, recipe.name))
                addView(NexusUi.rowSub(this@FoodLogActivity, "${recipe.ingredients.size} ingredients · ${formatNutritionNumber(recipe.servings)} servings"))
                addView(horizontalActions(
                    NexusUi.textButton(this@FoodLogActivity, "Log portion").apply { setOnClickListener { openEntryEditor(recipe.asProduct()) } },
                    NexusUi.textButton(this@FoodLogActivity, "Edit recipe").apply { setOnClickListener { openRecipeEditor(recipe) } },
                ), NexusUi.block())
            }, NexusUi.block())
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

    private fun refreshCatalog() {
        val request = ++catalogGeneration
        val query = catalogQuery.text.toString()
        worker.execute {
            val result = runCatching { store.searchProducts(query, 101) to store.favoriteProducts().mapTo(hashSetOf(), FoodProduct::barcode) }
            post {
                if (request != catalogGeneration) return@post
                result.fold(onSuccess = { (products, favoriteIds) -> renderProductCatalog(products, favoriteIds) },
                    onFailure = { productCatalog.removeAllViews(); productCatalog.emptyCard("Saved foods could not be read. Change the search to retry.") })
            }
        }
    }

    private fun renderProductCatalog(products: List<FoodProduct>, favoriteIds: Set<String>) {
        productCatalog.removeAllViews()
        if (products.isEmpty()) return productCatalog.emptyCard(
            if (catalogQuery.text.isNullOrBlank()) "Your food catalog is empty. Look up a barcode below or create a custom food." else "No matching saved food. Try another name or add it below.")
        products.take(100).forEach { product ->
            productCatalog.addView(NexusUi.card(this).apply {
                addView(NexusUi.rowTitle(this@FoodLogActivity, product.name))
                addView(NexusUi.rowSub(this@FoodLogActivity, listOf(product.brand, "${product.nutrients.caloriesKcal.displayPer100g("kcal")} / 100 g").filter(String::isNotBlank).joinToString(" · ")))
                addView(horizontalActions(
                    NexusUi.textButton(this@FoodLogActivity, "Log portion").apply { setOnClickListener { openEntryEditor(product) } },
                    NexusUi.textButton(this@FoodLogActivity, if (product.barcode in favoriteIds) "Unfavorite" else "Favorite").apply {
                        setOnClickListener { toggleFavorite(product, product.barcode in favoriteIds) }
                    },
                ), NexusUi.block())
            }, NexusUi.block())
        }
        if (products.size > 100) productCatalog.addView(NexusUi.rowSub(this, "Showing the first 100 foods. Search by name to find more."))
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
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { report("No browser is available to open Open Food Facts.") }
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

    private fun EditText.optionalNumber(): Double? {
        if (text.toString().isBlank()) return null
        return requireNotNull(numberOrNull()?.takeIf(Double::isFinite)) { "Invalid number" }
    }

    private fun EditText.setNumber(value: Double?) {
        setText(value?.let(::editableNutritionNumber).orEmpty())
    }

    private fun sourceFor(product: FoodProduct): FoodEntrySource = when {
        product.barcode.startsWith("custom-") -> FoodEntrySource.CUSTOM
        product.barcode.startsWith("recipe-") -> FoodEntrySource.RECIPE
        else -> FoodEntrySource.SEARCHED
    }

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
        const val REQUEST_HEALTH_PERMISSION = 301
        const val REQUEST_EXPORT = 302
        const val REQUEST_IMPORT = 303
        const val REQUEST_NOTIFICATIONS = 304
        const val MAX_BACKUP_CHARS = 12_000_000
        val REMINDER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
}
