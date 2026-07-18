package com.anezium.rokidbus.plugin.transit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.concurrent.Executors

class TransitSettingsActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var permissionAction: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var favoritesList: LinearLayout
    private val favoritesStore by lazy { TransitFavoritesStore(applicationContext) }
    private val repository = TransitRepository()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val onboardingPreferences by lazy {
        getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        maybeRequestPreciseLocationOnFirstOpen()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    override fun onDestroy() {
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) renderState()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        permissionStatus = NexusUi.cardBody(this, "")
        searchStatus = NexusUi.cardBody(this, "")
        favoritesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchInput = NexusUi.field(this, "Search for a stop").apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    searchStops()
                    true
                } else {
                    false
                }
            }
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(this@TransitSettingsActivity, "Nearby departures and favorite stops on your glasses."),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@TransitSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@TransitSettingsActivity, "Permissions"), NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(
                NexusUi.card(this@TransitSettingsActivity).apply {
                    addView(permissionStatus)
                    permissionAction = LinearLayout(this@TransitSettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(BusTheme.gap(this@TransitSettingsActivity, 12))
                        addView(
                            NexusUi.pillButton(this@TransitSettingsActivity, "Set up location").apply {
                                setOnClickListener { continueLocationSetup() }
                            },
                            NexusUi.block(),
                        )
                    }
                    addView(permissionAction, NexusUi.block())
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@TransitSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@TransitSettingsActivity, "Saved stops"), NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(favoritesList, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@TransitSettingsActivity, "Add a stop"), NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(searchInput, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(
                NexusUi.pillButton(this@TransitSettingsActivity, "Search").apply {
                    setOnClickListener { searchStops() }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(searchStatus, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(resultsList, NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@TransitSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@TransitSettingsActivity,
                    NexusPluginIcons.drawableFor("bus"),
                    "Transit",
                    "Stops and departures · v1.0",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@TransitSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderState()
    }

    private fun renderState() {
        val access = transitLocationAccess()
        permissionStatus.text = when (access) {
            TransitLocationAccess.MISSING_PRECISE ->
                "Precise location is required. Near Me requests one current position each time you open it."
            TransitLocationAccess.MISSING_BACKGROUND ->
                "Allow location all the time so the glasses can request that one position while the phone screen is off. " +
                    "Transit stops location immediately after the fix."
            TransitLocationAccess.READY ->
                "Location ready. Near Me takes one precise fix per opening, then refreshes departures without tracking you."
        }
        permissionAction.visibility = if (access == TransitLocationAccess.READY) View.GONE else View.VISIBLE
        renderFavorites()
    }

    private fun maybeRequestPreciseLocationOnFirstOpen() {
        if (transitLocationAccess() != TransitLocationAccess.MISSING_PRECISE) return
        if (onboardingPreferences.getBoolean(KEY_PRECISE_REQUESTED, false)) return
        onboardingPreferences.edit().putBoolean(KEY_PRECISE_REQUESTED, true).apply()
        requestPreciseLocation()
    }

    private fun continueLocationSetup() {
        when (transitLocationAccess()) {
            TransitLocationAccess.MISSING_PRECISE -> requestPreciseLocation()
            TransitLocationAccess.MISSING_BACKGROUND -> openLocationSettings()
            TransitLocationAccess.READY -> Unit
        }
    }

    private fun requestPreciseLocation() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            PERMISSION_REQUEST,
        )
    }

    private fun openLocationSettings() {
        val optionLabel = packageManager.backgroundPermissionOptionLabel
        Toast.makeText(
            this,
            "Choose $optionLabel. Transit only uses it for one Near Me fix from your glasses.",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    private fun searchStops() {
        val query = searchInput.text.toString().trim()
        resultsList.removeAllViews()
        if (query.isBlank()) {
            searchStatus.text = "Enter a stop name."
            return
        }
        hideKeyboard()
        searchStatus.text = "Searching..."
        searchExecutor.execute {
            val result = runCatching { repository.searchStops(query) }
            mainHandler.post {
                if (isDestroyed) return@post
                result.onSuccess(::renderResults).onFailure {
                    searchStatus.text = "Search failed. Check the phone network connection."
                }
            }
        }
    }

    private fun renderResults(matches: List<TransitStopMatch>) {
        resultsList.removeAllViews()
        searchStatus.text = if (matches.isEmpty()) "No stops found." else "${matches.size} results"
        matches.forEachIndexed { index, match ->
            if (index > 0) resultsList.addView(BusTheme.gap(this, 8))
            resultsList.addView(stopRow(match.stop.name, match.city, "Add") {
                val alreadySaved = favoritesStore.list().any { stop -> stop.id == match.stop.id }
                favoritesStore.add(match.stop)
                searchStatus.text = if (alreadySaved) "Already saved." else "Stop added."
                renderFavorites()
            }, NexusUi.block())
        }
    }

    private fun renderFavorites() {
        favoritesList.removeAllViews()
        val favorites = favoritesStore.list()
        if (favorites.isEmpty()) {
            favoritesList.addView(NexusUi.cardBody(this, "No saved stops. Search below to add one."), NexusUi.block())
            return
        }
        favorites.forEachIndexed { index, stop ->
            if (index > 0) favoritesList.addView(BusTheme.gap(this, 8))
            favoritesList.addView(stopRow(stop.name, "Saved on this phone", "Remove") {
                favoritesStore.remove(stop.id)
                searchStatus.text = "Stop removed."
                renderFavorites()
            }, NexusUi.block())
        }
    }

    private fun stopRow(title: String, subtitle: String, action: String, onClick: () -> Unit) =
        NexusUi.pressableCard(this).apply {
            val labels = LinearLayout(this@TransitSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@TransitSettingsActivity, title))
                addView(BusTheme.gap(this@TransitSettingsActivity, 4))
                addView(NexusUi.rowSub(this@TransitSettingsActivity, subtitle.ifBlank { "Transit stop" }))
            }
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                NexusUi.textButton(this@TransitSettingsActivity, action, danger = action == "Remove").apply {
                    setOnClickListener { onClick() }
                },
            )
        }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Transit") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val PERMISSION_REQUEST = 70
        const val ONBOARDING_PREFERENCES = "transit_location_onboarding"
        const val KEY_PRECISE_REQUESTED = "precise_requested"
    }
}
