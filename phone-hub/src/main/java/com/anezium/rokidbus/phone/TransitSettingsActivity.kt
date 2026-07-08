package com.anezium.rokidbus.phone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.plugin.transit.TransitFavoritesStore
import com.anezium.rokidbus.plugin.transit.TransitRepository
import com.anezium.rokidbus.plugin.transit.TransitStop
import com.anezium.rokidbus.plugin.transit.TransitStopMatch
import java.util.concurrent.Executors

/**
 * Transit plugin settings: manage the favorite stops shown on the glasses.
 * Search runs off the main thread; favorites persist in TransitFavoritesStore.
 */
class TransitSettingsActivity : Activity() {
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var favoritesList: LinearLayout
    private lateinit var favoritesStore: TransitFavoritesStore
    private val repository = TransitRepository()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favoritesStore = TransitFavoritesStore(applicationContext)
        buildUi()
    }

    override fun onDestroy() {
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        searchInput = NexusUi.field(this, "Search for a stop").apply {
            setOnEditorActionListener { _, actionId, event ->
                val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH
                val isEnter = event == null || event.action == KeyEvent.ACTION_DOWN
                if (isSearch || isEnter) {
                    searchStops()
                    true
                } else {
                    false
                }
            }
        }
        val searchButton = NexusUi.pillButton(this, "Search").apply {
            setOnClickListener { searchStops() }
        }
        searchStatus = NexusUi.rowSub(this, "")
        resultsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        favoritesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                searchInput,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = NexusUi.dp(this@TransitSettingsActivity, 12)
                },
            )
            addView(
                searchButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = NexusUi.column(this).apply {
            addView(backRow(), NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 14))
            addView(NexusUi.hero(this@TransitSettingsActivity, sizeSp = 30f).apply { text = "Transit" })
            addView(BusTheme.gap(this@TransitSettingsActivity, 6))
            addView(
                NexusUi.statusLine(this@TransitSettingsActivity).apply {
                    text = "Nearby departures on your glasses"
                },
            )
            addView(BusTheme.gap(this@TransitSettingsActivity, 30))
            addView(NexusUi.sectionLabel(this@TransitSettingsActivity, "Your stops"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 12))
            addView(favoritesList, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 28))
            addView(NexusUi.sectionLabel(this@TransitSettingsActivity, "Add a stop"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 12))
            addView(searchRow, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(searchStatus, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(resultsList, NexusUi.block())
        }

        renderFavorites()
        setContentView(NexusUi.screen(this, root))
    }

    private fun backRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@TransitSettingsActivity).apply {
                    text = "‹"
                    textSize = 26f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(NexusUi.TEXT)
                    background = NexusUi.pressed(
                        this@TransitSettingsActivity,
                        android.graphics.Color.TRANSPARENT,
                        22,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@TransitSettingsActivity, 44),
                    NexusUi.dp(this@TransitSettingsActivity, 44),
                ).apply { marginStart = -NexusUi.dp(this@TransitSettingsActivity, 12) },
            )
        }

    // ----------------------------------------------------------- search

    private fun searchStops() {
        val query = searchInput.text.toString().trim()
        resultsList.removeAllViews()
        if (query.isBlank()) {
            searchStatus.text = "Enter a stop name."
            return
        }
        hideKeyboard()
        searchStatus.text = "Searching…"
        searchExecutor.execute {
            val result = runCatching { repository.searchStops(query) }
            mainHandler.post {
                if (isDestroyed) return@post
                result
                    .onSuccess { matches -> renderResults(matches) }
                    .onFailure { searchStatus.text = "Search failed — check your connection." }
            }
        }
    }

    private fun renderResults(matches: List<TransitStopMatch>) {
        resultsList.removeAllViews()
        if (matches.isEmpty()) {
            searchStatus.text = "No stops found."
            return
        }
        searchStatus.text = "${matches.size} result${if (matches.size == 1) "" else "s"}"
        matches.forEachIndexed { index, match ->
            resultsList.addView(
                resultRow(match),
                NexusUi.block().apply {
                    if (index > 0) topMargin = NexusUi.dp(this@TransitSettingsActivity, 10)
                },
            )
        }
    }

    private fun resultRow(match: TransitStopMatch): LinearLayout {
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(NexusUi.rowTitle(this@TransitSettingsActivity, match.stop.name))
            if (match.city.isNotBlank()) {
                addView(BusTheme.gap(this@TransitSettingsActivity, 2))
                addView(NexusUi.rowSub(this@TransitSettingsActivity, match.city))
            }
        }
        return NexusUi.pressableCard(this).apply {
            addView(
                textColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(this@TransitSettingsActivity).apply {
                    text = "+"
                    textSize = 20f
                    includeFontPadding = false
                    setTextColor(NexusUi.ACCENT)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = NexusUi.dp(this@TransitSettingsActivity, 12) },
            )
            setOnClickListener {
                val alreadySaved = favoritesStore.list().any { it.id == match.stop.id }
                favoritesStore.add(match.stop)
                renderFavorites()
                searchStatus.text = if (alreadySaved) {
                    "Already saved."
                } else {
                    "Added ${match.stop.name}."
                }
            }
        }
    }

    // -------------------------------------------------------- favorites

    private fun renderFavorites() {
        favoritesList.removeAllViews()
        val favorites = favoritesStore.list()
        if (favorites.isEmpty()) {
            favoritesList.addView(
                NexusUi.rowSub(this, "No stops yet — search below to add one."),
                NexusUi.block(),
            )
            return
        }
        favorites.forEachIndexed { index, stop ->
            favoritesList.addView(
                favoriteRow(stop),
                NexusUi.block().apply {
                    if (index > 0) topMargin = NexusUi.dp(this@TransitSettingsActivity, 10)
                },
            )
        }
    }

    private fun favoriteRow(stop: TransitStop): LinearLayout =
        NexusUi.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                NexusUi.dp(this@TransitSettingsActivity, 18),
                NexusUi.dp(this@TransitSettingsActivity, 8),
                NexusUi.dp(this@TransitSettingsActivity, 8),
                NexusUi.dp(this@TransitSettingsActivity, 8),
            )
            addView(
                NexusUi.rowTitle(this@TransitSettingsActivity, stop.name),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = NexusUi.dp(this@TransitSettingsActivity, 12)
                },
            )
            addView(
                NexusUi.textButton(this@TransitSettingsActivity, "Remove", danger = true).apply {
                    setOnClickListener {
                        favoritesStore.remove(stop.id)
                        searchStatus.text = "Removed ${stop.name}."
                        renderFavorites()
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }
}
