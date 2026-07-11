package com.anezium.rokidbus.plugin.transit

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.ui.BusTheme
import java.util.concurrent.Executors

class TransitSettingsActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var nexusStatus: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var favoritesList: LinearLayout
    private val favoritesStore by lazy { TransitFavoritesStore(applicationContext) }
    private val statusStore by lazy { TransitPluginStatusStore(applicationContext) }
    private val repository = TransitRepository()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg
        permissionStatus = BusTheme.heroSub(this, "")
        nexusStatus = BusTheme.heroSub(this, "")
        searchStatus = BusTheme.heroSub(this, "")
        favoritesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchInput = EditText(this).apply {
            hint = "Search for a stop"
            setHintTextColor(BusTheme.dim)
            setTextColor(BusTheme.text)
            setSingleLine(true)
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

        val content = BusTheme.root(this).apply {
            addView(BusTheme.wordmark(this@TransitSettingsActivity, "Nexus plugin"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 12))
            addView(BusTheme.hero(this@TransitSettingsActivity).apply { text = "Transit" })
            addView(BusTheme.gap(this@TransitSettingsActivity, 8))
            addView(BusTheme.heroSub(this@TransitSettingsActivity, "Nearby departures and favorite stops on your glasses."))
            addView(BusTheme.gap(this@TransitSettingsActivity, 22))
            addView(BusTheme.tinyLabel(this@TransitSettingsActivity, "Permissions"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 8))
            addView(permissionStatus)
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(BusTheme.pill(this@TransitSettingsActivity, "Allow location and notifications").apply {
                setOnClickListener { requestTransitPermissions() }
            })
            addView(BusTheme.gap(this@TransitSettingsActivity, 18))
            addView(BusTheme.tinyLabel(this@TransitSettingsActivity, "Nexus status"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 8))
            addView(nexusStatus)
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(BusTheme.pill(this@TransitSettingsActivity, "Open Nexus plugin access").apply {
                setOnClickListener { openNexusPluginAccess() }
            })
            addView(BusTheme.gap(this@TransitSettingsActivity, 24))
            addView(BusTheme.tinyLabel(this@TransitSettingsActivity, "Saved stops"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(favoritesList)
            addView(BusTheme.gap(this@TransitSettingsActivity, 24))
            addView(BusTheme.tinyLabel(this@TransitSettingsActivity, "Add a stop"))
            addView(BusTheme.gap(this@TransitSettingsActivity, 8))
            addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(BusTheme.gap(this@TransitSettingsActivity, 8))
            addView(BusTheme.pill(this@TransitSettingsActivity, "Search").apply {
                setOnClickListener { searchStops() }
            })
            addView(BusTheme.gap(this@TransitSettingsActivity, 8))
            addView(searchStatus)
            addView(BusTheme.gap(this@TransitSettingsActivity, 10))
            addView(resultsList)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BusTheme.bg)
                isFillViewport = true
                addView(content)
            },
        )
        renderState()
    }

    private fun renderState() {
        permissionStatus.text = if (hasLocationPermission()) {
            "Location granted. Near Me can run during an active glasses session."
        } else {
            "Location is required for Near Me. Nexus never requests it on Transit’s behalf."
        }
        nexusStatus.text = statusStore.summary()
        renderFavorites()
    }

    private fun requestTransitPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun openNexusPluginAccess() {
        val intent = Intent().setComponent(
            ComponentName(
                HubTarget.PHONE.packageName,
                "com.anezium.rokidbus.phone.PluginPermissionsActivity",
            ),
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Install or update Rokid Nexus first", Toast.LENGTH_SHORT).show()
        }
    }

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
                result.onSuccess(::renderResults).onFailure {
                    searchStatus.text = "Search failed. Check the phone network connection."
                }
            }
        }
    }

    private fun renderResults(matches: List<TransitStopMatch>) {
        resultsList.removeAllViews()
        searchStatus.text = if (matches.isEmpty()) "No stops found." else "${matches.size} results"
        matches.forEach { match ->
            resultsList.addView(row(match.stop.name, match.city, "Add") {
                val alreadySaved = favoritesStore.list().any { stop -> stop.id == match.stop.id }
                favoritesStore.add(match.stop)
                searchStatus.text = if (alreadySaved) "Already saved." else "Stop added."
                renderFavorites()
            })
            resultsList.addView(BusTheme.gap(this, 8))
        }
    }

    private fun renderFavorites() {
        favoritesList.removeAllViews()
        val favorites = favoritesStore.list()
        if (favorites.isEmpty()) {
            favoritesList.addView(BusTheme.heroSub(this, "No saved stops. Search below to add one."))
            return
        }
        favorites.forEach { stop ->
            favoritesList.addView(row(stop.name, "Saved on this phone", "Remove") {
                favoritesStore.remove(stop.id)
                searchStatus.text = "Stop removed."
                renderFavorites()
            })
            favoritesList.addView(BusTheme.gap(this, 8))
        }
    }

    private fun row(title: String, subtitle: String, action: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, BusTheme.dp(this@TransitSettingsActivity, 8), 0, BusTheme.dp(this@TransitSettingsActivity, 8))
            addView(
                LinearLayout(this@TransitSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@TransitSettingsActivity).apply {
                        text = title
                        textSize = 16f
                        setTextColor(BusTheme.text)
                    })
                    addView(BusTheme.heroSub(this@TransitSettingsActivity, subtitle.ifBlank { "Transit stop" }))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(BusTheme.pill(this@TransitSettingsActivity, action, dangerVariant = action == "Remove").apply {
                setOnClickListener { onClick() }
            })
        }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private companion object {
        const val PERMISSION_REQUEST = 70
    }
}
