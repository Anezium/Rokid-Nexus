package com.anezium.rokidbus.plugin.foodlog

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotCallbacks
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSnapshotSession
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class FoodLogPluginService : NexusPluginService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val store by lazy { FoodLogStore(applicationContext) }
    private val foodFacts = FoodFactsClient()
    private val barcodeScanner by lazy { FoodBarcodeScanner(ioExecutor) }

    private var surface: NexusSurfaceSession? = null
    private var snapshotSession: NexusSnapshotSession? = null
    private var speechSession: NexusSpeechSession? = null
    private var generation = 0L
    private var screen = Screen.HOME
    private var selectedIndex = 0
    private var selectedProduct: FoodProduct? = null
    private var quantityGrams = DEFAULT_QUANTITY_GRAMS
    private var todayEntries: List<FoodEntry> = emptyList()
    private var recentProducts: List<FoodProduct> = emptyList()
    private var undoCandidate: FoodEntry? = null
    private var messageTitle = "Food Log"
    private var messageLines = emptyList<String>()
    private val voiceFinalSegments = mutableListOf<String>()
    private var voicePartial = ""
    private var voiceStatus = "Starting…"
    private var stopVoiceWhenStarted = false
    private val speechCallbacks = object : NexusSpeechCallbacks {
        override fun onSpeechStarted(realtime: Boolean) {
            val active = speechSession ?: return
            if (stopVoiceWhenStarted || screen != Screen.VOICE) {
                active.stop()
                return
            }
            voiceStatus = if (realtime) "Listening…" else "Listening in batch mode…"
            render(show = false)
        }

        override fun onSpeechState(state: NexusSpeechState) {
            if (screen != Screen.VOICE) return
            voiceStatus = when (state) {
                NexusSpeechState.LISTENING -> "Listening…"
                NexusSpeechState.RECOGNIZING -> "Recognizing…"
                NexusSpeechState.PROCESSING -> "Processing…"
            }
            render(show = false)
        }

        override fun onSpeechPartial(text: String) {
            if (screen != Screen.VOICE) return
            voicePartial = text.trim().take(MAX_VOICE_TRANSCRIPT_CHARS)
            render(show = false)
        }

        override fun onSpeechFinal(text: String) {
            if (screen != Screen.VOICE) return
            text.trim().takeIf(String::isNotBlank)?.let { segment ->
                voiceFinalSegments += segment.take(MAX_VOICE_TRANSCRIPT_CHARS)
            }
            voicePartial = ""
            render(show = false)
        }

        override fun onSpeechStopped(reason: NexusSpeechStopReason, error: NexusSpeechError?) {
            speechSession = null
            stopVoiceWhenStarted = false
            if (screen != Screen.VOICE) return
            if (reason != NexusSpeechStopReason.COMPLETED && voiceTranscript().isBlank()) {
                showMessage("Voice entry", voiceStopMessage(reason, error))
                return
            }
            resolveVoiceTranscript()
        }
    }

    override fun onNexusOpen() {
        generation += 1
        screen = Screen.HOME
        selectedIndex = 0
        selectedProduct = null
        undoCandidate = null
        clearVoice()
        refreshLocalState()
        surface = nexusSurfaceSession(SURFACE_ID)
        render(show = true)
    }

    override fun onNexusClose() {
        generation += 1
        snapshotSession?.cancel()
        snapshotSession = null
        stopVoiceWhenStarted = true
        speechSession?.stop()
        speechSession = null
        surface?.hide()
        surface = null
        selectedProduct = null
        undoCandidate = null
        screen = Screen.HOME
        clearVoice()
    }

    override fun onDestroy() {
        generation += 1
        snapshotSession?.cancel()
        snapshotSession = null
        stopVoiceWhenStarted = true
        speechSession?.stop()
        speechSession = null
        barcodeScanner.close()
        ioExecutor.shutdownNow()
        store.close()
        super.onDestroy()
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> activate()
            KeyEvent.KEYCODE_BACK -> back()
        }
    }

    private fun move(delta: Int) {
        when (screen) {
            Screen.HOME -> {
                selectedIndex = Math.floorMod(selectedIndex + delta, HOME_ITEMS.size)
                render(show = false)
            }
            Screen.RECENTS -> if (recentProducts.isNotEmpty()) {
                selectedIndex = Math.floorMod(selectedIndex + delta, recentProducts.size)
                render(show = false)
            }
            Screen.PORTION -> {
                quantityGrams = (quantityGrams + delta * QUANTITY_STEP_GRAMS)
                    .coerceIn(MIN_QUANTITY_GRAMS, MAX_QUANTITY_GRAMS)
                render(show = false)
            }
            else -> Unit
        }
    }

    private fun activate() {
        when (screen) {
            Screen.HOME -> when (selectedIndex) {
                0 -> startSnapshot()
                1 -> startVoiceEntry()
                2 -> showToday()
                3 -> showRecents()
                4 -> confirmUndo()
            }
            Screen.PRODUCT -> {
                screen = Screen.PORTION
                render(show = false)
            }
            Screen.PORTION -> addSelectedProduct()
            Screen.RECENTS -> recentProducts.getOrNull(selectedIndex)?.let(::showProduct)
            Screen.CONFIRM_UNDO -> deleteUndoCandidate()
            Screen.VOICE -> {
                stopVoiceWhenStarted = true
                speechSession?.stop()
            }
            Screen.MESSAGE -> showHome()
            Screen.TODAY,
            Screen.SCANNING,
            -> Unit
        }
    }

    private fun back() {
        if (screen == Screen.HOME) {
            surface?.hide()
            return
        }
        generation += 1
        snapshotSession?.cancel()
        snapshotSession = null
        stopVoiceWhenStarted = true
        speechSession?.stop()
        speechSession = null
        showHome()
    }

    private fun showHome() {
        refreshLocalState()
        screen = Screen.HOME
        selectedIndex = 0
        selectedProduct = null
        undoCandidate = null
        clearVoice()
        render(show = false)
    }

    private fun showToday() {
        refreshLocalState()
        screen = Screen.TODAY
        render(show = false)
    }

    private fun showRecents() {
        refreshLocalState()
        if (recentProducts.isEmpty()) {
            showMessage("Recent foods", "Nothing logged yet.")
            return
        }
        selectedIndex = 0
        screen = Screen.RECENTS
        render(show = false)
    }

    private fun confirmUndo() {
        val candidate = store.latestEntryForDay()
        if (candidate == null) {
            showMessage("Undo last", "Nothing logged today.")
            return
        }
        undoCandidate = candidate
        screen = Screen.CONFIRM_UNDO
        render(show = false)
    }

    private fun deleteUndoCandidate() {
        val candidate = undoCandidate ?: return showHome()
        val removed = store.deleteEntry(candidate.id)
        undoCandidate = null
        refreshLocalState()
        showMessage(
            "Undo last",
            if (removed) "Removed ${candidate.product.name}." else "That entry no longer exists.",
        )
    }

    private fun startSnapshot() {
        generation += 1
        val operationGeneration = generation
        screen = Screen.SCANNING
        messageTitle = "Scan barcode"
        messageLines = listOf("Point at one EAN or UPC barcode.", "Hold still while the photo is taken.")
        render(show = false)
        val callbacks = object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) {
                snapshotSession = null
                if (!isCurrent(operationGeneration)) return
                messageLines = listOf("Reading barcode…")
                render(show = false)
                barcodeScanner.scan(jpeg) { result ->
                    mainHandler.post {
                        if (!isCurrent(operationGeneration)) return@post
                        when (result) {
                            is FoodBarcodeScanner.Result.Found -> loadProduct(result.code, operationGeneration)
                            FoodBarcodeScanner.Result.NotFound -> showMessage(
                                "No barcode found",
                                "Move closer, keep the label sharp, then try again.",
                            )
                            is FoodBarcodeScanner.Result.Ambiguous -> showMessage(
                                "Several barcodes found",
                                "Frame a single product and try again.",
                            )
                            is FoodBarcodeScanner.Result.Failure -> showMessage(
                                "Scan failed",
                                "The image could not be read. Try again.",
                            )
                        }
                    }
                }
            }

            override fun onSnapshotError(error: NexusSnapshotError) {
                snapshotSession = null
                if (!isCurrent(operationGeneration)) return
                val message = when (error) {
                    NexusSnapshotError.BUSY -> "The glasses camera is busy."
                    NexusSnapshotError.LINK_DOWN -> "The glasses link is unavailable."
                    NexusSnapshotError.TIMEOUT -> "The camera did not answer in time."
                    NexusSnapshotError.CANCELLED -> "Capture cancelled."
                    NexusSnapshotError.CAPTURE_FAILED,
                    NexusSnapshotError.ERROR,
                    -> "The photo could not be captured."
                }
                showMessage("Scan unavailable", message)
            }
        }
        val session = nexusSnapshotSession(callbacks)
        snapshotSession = session
        val result = session?.capture()
        if (result != NexusSdkResult.SENT) {
            snapshotSession = null
            session?.cancel()
            val message = when (result) {
                NexusSdkResult.CAPABILITY_NOT_GRANTED ->
                    "Grant Glasses camera access to Food Log in Nexus settings."
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> "Camera snapshots are not supported by this hub."
                else -> "The glasses camera is not ready."
            }
            showMessage("Scan unavailable", message)
        }
    }

    private fun startVoiceEntry() {
        refreshLocalState()
        if (recentProducts.isEmpty()) {
            showMessage(
                "Voice entry",
                "Log or create a food on the phone first, then say its name.",
            )
            return
        }
        clearVoice()
        screen = Screen.VOICE
        render(show = false)
        val session = nexusSpeechSession(speechCallbacks)
        speechSession = session
        val result = session?.start()
        if (result != NexusSdkResult.SENT) {
            speechSession = null
            val message = when (result) {
                NexusSdkResult.CAPABILITY_NOT_GRANTED ->
                    "Grant Speech to text access to Food Log in Nexus settings."
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> "Speech recognition is unavailable."
                else -> "Speech recognition could not start."
            }
            showMessage("Voice entry", message)
        }
    }

    private fun resolveVoiceTranscript() {
        when (val match = FoodVoiceParser.match(voiceTranscript(), recentProducts)) {
            is FoodVoiceMatch.Matched -> {
                selectedProduct = match.product
                quantityGrams = match.quantityGrams
                screen = Screen.PORTION
                render(show = false)
            }
            is FoodVoiceMatch.Ambiguous -> showMessage(
                "Several foods match",
                match.products.joinToString(" · ") { it.name }.hud(240),
                "Try again with a more precise name.",
            )
            FoodVoiceMatch.InvalidQuantity -> showMessage(
                "Invalid amount",
                "Say an amount between 1 and 5,000 grams.",
            )
            FoodVoiceMatch.NoProduct -> showMessage(
                "Food not recognized",
                "Try the exact name of a recent food.",
            )
        }
    }

    private fun voiceTranscript(): String =
        (voiceFinalSegments + listOfNotNull(voicePartial.takeIf(String::isNotBlank)))
            .joinToString(" ")
            .trim()
            .take(MAX_VOICE_TRANSCRIPT_CHARS)

    private fun clearVoice() {
        voiceFinalSegments.clear()
        voicePartial = ""
        voiceStatus = "Starting…"
        stopVoiceWhenStarted = false
    }

    private fun voiceStopMessage(reason: NexusSpeechStopReason, error: NexusSpeechError?): String = when (reason) {
        NexusSpeechStopReason.NO_SPEECH -> "No speech was detected."
        NexusSpeechStopReason.LINK_LOST -> "The glasses link was lost."
        NexusSpeechStopReason.REVOKED -> "Speech access was revoked."
        NexusSpeechStopReason.DENIED_BUSY -> "Speech recognition is already busy."
        NexusSpeechStopReason.DENIED_NO_LINK -> "The glasses link is unavailable."
        NexusSpeechStopReason.DENIED_NOT_READY -> "Configure speech recognition in Nexus first."
        NexusSpeechStopReason.CANCELLED -> "Voice entry cancelled."
        else -> error?.kind?.takeIf(String::isNotBlank)?.let { "Speech failed: $it" }
            ?: "Speech recognition failed."
    }

    private fun loadProduct(barcode: String, operationGeneration: Long) {
        store.product(barcode)?.let {
            showProduct(it)
            return
        }
        messageTitle = "Open Food Facts"
        messageLines = listOf("Looking up product…")
        render(show = false)
        ioExecutor.execute {
            val result = runCatching { foodFacts.product(barcode) }
            result.getOrNull()?.let(store::upsertProduct)
            mainHandler.post {
                if (!isCurrent(operationGeneration)) return@post
                result.fold(
                    onSuccess = { product ->
                        if (product == null) {
                            showMessage(
                                "Product not found",
                                "Add it manually from Food Log settings on the phone.",
                            )
                        } else {
                            showProduct(product)
                        }
                    },
                    onFailure = {
                        showMessage(
                            "Lookup failed",
                            "Check the phone network connection and try again.",
                        )
                    },
                )
            }
        }
    }

    private fun showProduct(product: FoodProduct) {
        selectedProduct = product
        quantityGrams = product.servingGrams
            ?.takeIf { it in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS }
            ?: DEFAULT_QUANTITY_GRAMS
        screen = Screen.PRODUCT
        render(show = false)
    }

    private fun addSelectedProduct() {
        val product = selectedProduct ?: return showHome()
        store.addEntry(product, quantityGrams)
        refreshLocalState()
        val totals = aggregateNutrition(todayEntries)
        showMessage(
            "Added",
            "${product.name} · ${formatNutritionNumber(quantityGrams)} g",
            "Today: ${totals.caloriesKcal.display("kcal")}",
        )
    }

    private fun showMessage(title: String, vararg lines: String) {
        messageTitle = title
        messageLines = lines.toList()
        screen = Screen.MESSAGE
        render(show = false)
    }

    private fun refreshLocalState() {
        todayEntries = store.entriesForDay()
        recentProducts = store.recentProducts()
    }

    private fun isCurrent(operationGeneration: Long): Boolean =
        operationGeneration == generation && isNexusSessionOpen

    private fun render(show: Boolean) {
        val card = when (screen) {
            Screen.HOME -> homeCard()
            Screen.SCANNING -> messageCard(messageTitle, messageLines, "back to cancel")
            Screen.VOICE -> voiceCard()
            Screen.PRODUCT -> productCard(selectedProduct)
            Screen.PORTION -> portionCard(selectedProduct)
            Screen.TODAY -> todayCard()
            Screen.RECENTS -> recentsCard()
            Screen.CONFIRM_UNDO -> undoCard(undoCandidate)
            Screen.MESSAGE -> messageCard(messageTitle, messageLines, "tap or back")
        }
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private fun homeCard(): NexusCard {
        val totals = aggregateNutrition(todayEntries)
        return NexusCard(
            title = "Food Log",
            subtitle = "Today · ${totals.entryCount} ${if (totals.entryCount == 1) "entry" else "entries"}",
            lines = emptyList(),
            richLines = HOME_ITEMS.mapIndexed { index, item ->
                NexusCardLine(
                    text = item.first,
                    sub = if (index == 2) {
                        "${totals.caloriesKcal.display("kcal")} · P ${totals.proteinGrams.display("g")}"
                    } else {
                        item.second
                    },
                    selected = index == selectedIndex,
                )
            },
            footer = "swipe · tap · back",
            contentKey = "foodlog-home-v1",
        )
    }

    private fun voiceCard(): NexusCard = NexusCard(
        title = "Add by voice",
        subtitle = voiceStatus.hud(240),
        lines = listOf(
            voiceTranscript().ifBlank { "Say: 200 grams of rice" }.hud(240),
            "Food names are matched locally against your recent foods.",
        ),
        footer = "tap to stop · back to cancel",
        contentKey = "foodlog-voice",
        handlesBack = true,
    )

    private fun productCard(product: FoodProduct?): NexusCard {
        if (product == null) return messageCard("Product", listOf("Product unavailable."), "back")
        val nutrients = product.nutrients
        return NexusCard(
            title = product.name.hud(120),
            subtitle = product.brand.hud(240).ifBlank { "Open Food Facts" },
            lines = listOf(
                "Energy · ${nutrients.caloriesKcal.displayPer100g("kcal / 100 g")}",
                "Protein · ${nutrients.proteinGrams.displayPer100g("g / 100 g")}",
                "Carbs · ${nutrients.carbohydrateGrams.displayPer100g("g / 100 g")}",
                "Fat · ${nutrients.fatGrams.displayPer100g("g / 100 g")}",
                productBadges(product),
            ),
            footer = "tap to choose amount · back",
            contentKey = "foodlog-product",
            handlesBack = true,
        )
    }

    private fun portionCard(product: FoodProduct?): NexusCard {
        if (product == null) return messageCard("Amount", listOf("Product unavailable."), "back")
        val calories = scaledValue(product.nutrients.caloriesKcal, quantityGrams)
        return NexusCard(
            title = "How much?",
            subtitle = product.name.hud(240),
            lines = emptyList(),
            richLines = listOf(
                NexusCardLine(
                    text = "${formatNutritionNumber(quantityGrams)} g",
                    sub = calories?.let { "${formatNutritionNumber(it)} kcal" }
                        ?: "Energy unknown",
                    tone = NexusRowTone.ALERT,
                    selected = true,
                ),
            ),
            footer = "swipe ±10 g · tap to add · back",
            contentKey = "foodlog-portion",
            handlesBack = true,
        )
    }

    private fun todayCard(): NexusCard {
        val totals = aggregateNutrition(todayEntries)
        val rows = buildList {
            add(
                NexusCardLine(
                    text = totals.caloriesKcal.display("kcal"),
                    sub = "P ${totals.proteinGrams.display("g")} · C ${totals.carbohydrateGrams.display("g")} · F ${totals.fatGrams.display("g")}",
                    tone = NexusRowTone.ALERT,
                ),
            )
            todayEntries.take(MAX_HUD_ENTRIES).forEach { entry ->
                val calories = scaledValue(entry.product.nutrients.caloriesKcal, entry.quantityGrams)
                add(
                    NexusCardLine(
                        text = entry.product.name.hud(240),
                        badge = "${formatNutritionNumber(entry.quantityGrams)}g".hud(24),
                        sub = "${entry.consumedTime()} · ${calories?.let(::formatNutritionNumber) ?: "?"} kcal",
                    ),
                )
            }
            if (todayEntries.isEmpty()) {
                add(NexusCardLine("Nothing logged today.", tone = NexusRowTone.DIM))
            } else if (todayEntries.size > MAX_HUD_ENTRIES) {
                add(NexusCardLine("${todayEntries.size - MAX_HUD_ENTRIES} more on phone", tone = NexusRowTone.DIM))
            }
        }
        return NexusCard(
            title = "Today",
            subtitle = "${todayEntries.size} ${if (todayEntries.size == 1) "entry" else "entries"}",
            lines = emptyList(),
            richLines = rows,
            footer = "back",
            contentKey = "foodlog-today",
            handlesBack = true,
        )
    }

    private fun recentsCard(): NexusCard = NexusCard(
        title = "Recent foods",
        lines = emptyList(),
        richLines = recentProducts.mapIndexed { index, product ->
            NexusCardLine(
                text = product.name.hud(240),
                sub = product.brand.hud(240).ifBlank { product.barcode },
                selected = index == selectedIndex,
            )
        },
        footer = "swipe · tap · back",
        contentKey = "foodlog-recents",
        handlesBack = true,
    )

    private fun undoCard(entry: FoodEntry?): NexusCard {
        if (entry == null) return messageCard("Undo last", listOf("Nothing to remove."), "back")
        return NexusCard(
            title = "Remove last entry?",
            subtitle = entry.consumedTime(),
            lines = listOf(
                entry.product.name.hud(240),
                "${formatNutritionNumber(entry.quantityGrams)} g",
                "This removes exactly this log entry.",
            ),
            footer = "tap to remove · back to keep",
            contentKey = "foodlog-undo",
            handlesBack = true,
        )
    }

    private fun messageCard(title: String, lines: List<String>, footer: String): NexusCard = NexusCard(
        title = title.hud(120),
        lines = lines.map { it.hud(240) },
        footer = footer,
        contentKey = "foodlog-message",
        handlesBack = true,
    )

    private fun productBadges(product: FoodProduct): String = buildList {
        product.nutritionGrade?.uppercase()?.let { add("Nutri-Score $it") }
        product.novaGroup?.let { add("NOVA $it") }
        if (isEmpty()) add("Community data may be incomplete")
    }.joinToString(" · ").hud(240)

    private enum class Screen {
        HOME,
        SCANNING,
        VOICE,
        PRODUCT,
        PORTION,
        TODAY,
        RECENTS,
        CONFIRM_UNDO,
        MESSAGE,
    }

    private companion object {
        const val SURFACE_ID = "foodlog-main"
        const val DEFAULT_QUANTITY_GRAMS = 100.0
        const val QUANTITY_STEP_GRAMS = 10.0
        const val MAX_HUD_ENTRIES = 8
        const val MAX_VOICE_TRANSCRIPT_CHARS = 240
        val HOME_ITEMS = listOf(
            "Scan product" to "EAN or UPC with the glasses camera",
            "Add by voice" to "Say an amount and a recent food",
            "Today" to "Daily nutrition and entries",
            "Recent foods" to "Log something again",
            "Undo last" to "Exact last entry from today",
        )
    }
}

private fun String.hud(limit: Int): String = trim().take(limit)

private fun FoodEntry.consumedTime(): String =
    Instant.ofEpochMilli(consumedAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
