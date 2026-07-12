package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

class StoreActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var registryClient: RegistryClient
    private lateinit var pluginInstaller: PluginInstaller
    private lateinit var postInstallCoordinator: PluginPostInstallCoordinator
    private var registrySnapshot: RegistrySnapshot? = null
    private var registryLoading = true
    private var registryFailure: Throwable? = null
    private var selectedCategory = ALL_CATEGORY
    private val installStates = mutableMapOf<String, PluginInstallState>()
    private val installOperations = mutableMapOf<String, PluginInstallOperation>()
    private val hostVersionCode: Long by lazy {
        installedVersionCodes(setOf(packageName))[packageName] ?: 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registryClient = RegistryClient.create(applicationContext)
        postInstallCoordinator = PluginPostInstallCoordinator(
            discoverPackage = PhonePluginDiscovery(packageManager)::discoverPackage,
            grantState = PluginGrantStore(applicationContext)::stateFor,
            refreshCatalog = ::renderCatalog,
        )
        pluginInstaller = PluginInstaller.create(
            context = applicationContext,
            hostVersionCode = hostVersionCode,
        )
        buildUi()
        refreshRegistry()
    }

    override fun onResume() {
        super.onResume()
        renderCatalog()
    }

    private fun refreshRegistry() {
        registryLoading = true
        registryFailure = null
        renderCatalog()
        registryClient.refresh { result ->
            if (isFinishing || isDestroyed) return@refresh
            registryLoading = false
            when (result) {
                is RegistryLoadResult.Success -> registrySnapshot = result.snapshot
                is RegistryLoadResult.Failure -> {
                    registrySnapshot = null
                    registryFailure = result.error
                }
            }
            renderCatalog()
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        list = NexusUi.contentColumn(this, topDp = 8)
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                NexusUi.dp(this@StoreActivity, 22),
                NexusUi.dp(this@StoreActivity, 16),
                NexusUi.dp(this@StoreActivity, 22),
                NexusUi.dp(this@StoreActivity, 4),
            )
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val categories = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(storeHeader(), NexusUi.block())
            addView(categories, NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderCatalog()
    }

    private fun renderCatalog() {
        if (!::list.isInitialized) return
        val feed = registrySnapshot?.feed ?: RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList())
        val local = BusHubService.pluginCatalog(this)
        val packageNames = buildSet {
            feed.plugins.forEach { add(it.artifact.packageName) }
            local.entries.mapNotNullTo(this) {
                it.principal?.packageName ?: it.settingsComponent?.packageName
            }
        }
        val catalog = StoreCatalog.build(
            feed = feed,
            localCatalog = local,
            installedVersionCodes = installedVersionCodes(packageNames),
            hostVersionCode = hostVersionCode,
            logger = { Log.w(TAG, it) },
        )

        renderChips(feed)
        list.removeAllViews()
        renderCatalogueStatus(feed)
        val visible = catalog.entries.filter { entry ->
            selectedCategory == ALL_CATEGORY || entry.category == selectedCategory
        }
        if (visible.isEmpty() && feed.plugins.isNotEmpty()) {
            addEmptyCard("No plugins here", "Choose another category to see available plugins.")
        }
        visible.forEachIndexed { index, entry ->
            if (index > 0 || list.childCount > 0) list.addView(BusTheme.gap(this, 10))
            list.addView(storeEntryCard(entry), NexusUi.block())
        }
    }

    private fun renderCatalogueStatus(feed: RegistryFeed) {
        when {
            registryLoading && registrySnapshot == null -> addEmptyCard(
                "Refreshing catalogue",
                "Checking the Nexus plugin registry. Installed plugins remain available below.",
            )
            registryFailure != null && registrySnapshot == null -> addEmptyCard(
                "Catalogue unavailable",
                "Nexus could not reach the registry and no saved catalogue is available. Try again later.",
                action = "Retry" to ::refreshRegistry,
            )
            feed.plugins.isEmpty() -> addEmptyCard(
                "Catalogue is ready",
                "No registry plugins have been published yet. Installed plugins remain available below.",
            )
        }
    }

    private fun renderChips(feed: RegistryFeed) {
        val categories = listOf(ALL_CATEGORY) + feed.plugins
            .map(RegistryPlugin::category)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        if (selectedCategory !in categories) selectedCategory = ALL_CATEGORY
        chipRow.removeAllViews()
        categories.forEachIndexed { index, label ->
            chipRow.addView(
                chip(label, selected = label == selectedCategory),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) marginStart = NexusUi.dp(this@StoreActivity, 8)
                },
            )
        }
    }

    private fun addEmptyCard(
        title: String,
        body: String,
        action: Pair<String, () -> Unit>? = null,
    ) {
        if (list.childCount > 0) list.addView(BusTheme.gap(this, 10))
        list.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.cardTitle(this@StoreActivity, title), NexusUi.block())
                addView(BusTheme.gap(this@StoreActivity, 7))
                addView(NexusUi.cardBody(this@StoreActivity, body), NexusUi.block())
                action?.let { (label, onClick) ->
                    addView(BusTheme.gap(this@StoreActivity, 12))
                    addView(storeButton(label, filled = false, enabled = true, onClick = onClick))
                }
            },
            NexusUi.block(),
        )
    }

    private fun storeEntryCard(entry: StoreEntry): LinearLayout {
        val action = actionFor(entry)
        return storeCard(
            iconRes = iconFor(entry.id),
            title = entry.displayName,
            meta = metaFor(entry),
            description = entry.summary,
            size = sizeFor(entry),
            button = action.label,
            buttonEnabled = action.enabled,
            buttonFilled = action.filled,
            onClick = action.onClick,
        )
    }

    private data class StoreAction(
        val label: String,
        val enabled: Boolean,
        val filled: Boolean,
        val onClick: () -> Unit,
    )

    private fun actionFor(entry: StoreEntry): StoreAction {
        val installState = installStates[entry.id]
        if (installState != null) {
            return when (installState) {
                is PluginInstallState.Downloading -> StoreAction(
                    label = installState.totalBytes?.takeIf { it > 0 }?.let {
                        "${(installState.downloadedBytes * 100 / it).coerceIn(0, 100)}% · Cancel"
                    } ?: "Cancel",
                    enabled = true,
                    filled = false,
                    onClick = { installOperations[entry.id]?.cancel() },
                )
                PluginInstallState.Verifying -> StoreAction("Verifying", false, false) {}
                PluginInstallState.Installing -> StoreAction("Preparing", false, false) {}
                PluginInstallState.AwaitingUserConfirmation -> StoreAction("Confirm install", false, false) {}
                PluginInstallState.Cancelled -> StoreAction("Retry", true, false) { beginInstall(entry) }
                is PluginInstallState.Failure -> StoreAction("Retry", true, false) { beginInstall(entry) }
                is PluginInstallState.Success -> baseAction(entry)
            }
        }
        return baseAction(entry)
    }

    private fun baseAction(entry: StoreEntry): StoreAction = when (entry.state) {
        StoreEntryState.AVAILABLE -> StoreAction("Install", true, true) { beginInstall(entry) }
        StoreEntryState.UPDATE_AVAILABLE -> StoreAction("Update", true, true) { beginInstall(entry) }
        StoreEntryState.REQUIRES_HOST -> StoreAction("Unavailable", false, false) {}
        StoreEntryState.INSTALLED,
        StoreEntryState.SIDELOADED,
        -> {
            val review = entry.localGrantState in REVIEW_STATES
            StoreAction(if (review) "Review" else "Open", true, false) {
                openCatalogEntry(entry.localEntry)
            }
        }
    }

    private fun beginInstall(entry: StoreEntry) {
        installStates.remove(entry.id)
        installOperations[entry.id] = pluginInstaller.install(entry) { state ->
            installStates[entry.id] = state
            if (state is PluginInstallState.Failure) {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            } else if (state == PluginInstallState.Cancelled) {
                Toast.makeText(this, "Installation cancelled", Toast.LENGTH_SHORT).show()
            } else if (state is PluginInstallState.Success) {
                installOperations.remove(entry.id)
                when (val handoff = postInstallCoordinator.onInstalled(state.packageName, state.pluginId)) {
                    is PluginPostInstallResult.Ready -> {
                        Toast.makeText(this, "${entry.displayName} installed. Review its access.", Toast.LENGTH_SHORT).show()
                        startActivity(PluginPermissionsActivity.intent(this, handoff.target))
                    }
                    is PluginPostInstallResult.Failure -> {
                        Toast.makeText(this, handoff.reason, Toast.LENGTH_LONG).show()
                    }
                }
            }
            renderCatalog()
        }
    }

    private fun metaFor(entry: StoreEntry): String = when (entry.state) {
        StoreEntryState.AVAILABLE -> "${entry.category} · ${entry.registryPlugin?.artifact?.versionName.orEmpty()}"
        StoreEntryState.UPDATE_AVAILABLE ->
            "Update available · ${entry.registryPlugin?.artifact?.versionName.orEmpty()}"
        StoreEntryState.INSTALLED -> if (entry.updateBlockedByHost) {
            "Installed · Update requires Nexus host ${entry.registryPlugin?.nexus?.minHostVersionCode ?: "?"}"
        } else {
            "Installed · ${grantLabel(entry.localGrantState)}"
        }
        StoreEntryState.SIDELOADED -> "Local · ${grantLabel(entry.localGrantState)}"
        StoreEntryState.REQUIRES_HOST ->
            "Requires Nexus host ${entry.registryPlugin?.nexus?.minHostVersionCode ?: "?"}"
    }

    private fun grantLabel(state: PluginCatalogState?): String = when (state) {
        PluginCatalogState.BUILT_IN -> "built in"
        PluginCatalogState.PENDING -> "pending approval"
        PluginCatalogState.ENABLED -> "enabled"
        PluginCatalogState.DISABLED -> "disabled"
        PluginCatalogState.DENIED -> "denied"
        PluginCatalogState.INVALID -> "invalid"
        PluginCatalogState.MISSING_CAPABILITY -> "missing access"
        null -> "installed"
    }

    private fun sizeFor(entry: StoreEntry): String {
        val bytes = entry.registryPlugin?.artifact?.sizeBytes ?: return "Local"
        if (bytes <= 0) return entry.registryPlugin.artifact.versionName
        return if (bytes >= 1024 * 1024) {
            String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        } else {
            "${(bytes + 1023) / 1024} KB"
        }
    }

    private fun installedVersionCodes(packageNames: Set<String>): Map<String, Long> = buildMap {
        packageNames.forEach { packageName ->
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
            }.getOrNull() ?: return@forEach
            put(packageName, info.longVersionCode)
        }
    }

    private fun iconFor(id: String): Int = when (id) {
        "lyrics" -> BusClientR.drawable.ic_plugin_music
        "media" -> BusClientR.drawable.ic_plugin_disc
        "transit" -> BusClientR.drawable.ic_plugin_bus
        "lens" -> BusClientR.drawable.ic_plugin_lens
        else -> BusClientR.drawable.ic_plugin_send
    }

    private fun openCatalogEntry(entry: PluginCatalogEntry?) {
        if (entry == null) return
        if (entry.principal != null && entry.state != PluginCatalogState.ENABLED) {
            startActivity(Intent(this, PluginPermissionsActivity::class.java))
            return
        }
        val target = entry.settingsComponent
        if (target == null) {
            if (entry.principal != null) {
                startActivity(Intent(this, PluginPermissionsActivity::class.java))
            } else {
                Toast.makeText(this, "No settings available", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val intent = Intent().setComponent(ComponentName(target.packageName, target.className))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Plugin settings are unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun storeHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            NexusUi.dp(this@StoreActivity, 22),
            NexusUi.dp(this@StoreActivity, 16),
            NexusUi.dp(this@StoreActivity, 22),
            0,
        )
        addView(
            NexusUi.metaLabel(this@StoreActivity, "‹ Home", NexusUi.INK3).apply {
                letterSpacing = 0.2f
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
                background = NexusUi.pressed(this@StoreActivity, android.graphics.Color.TRANSPARENT, 12)
                setPadding(0, NexusUi.dp(this@StoreActivity, 8), NexusUi.dp(this@StoreActivity, 8), NexusUi.dp(this@StoreActivity, 8))
            },
        )
        addView(BusTheme.gap(this@StoreActivity, 8))
        addView(
            NexusUi.hero(this@StoreActivity, 30f).apply {
                val label = "Store."
                text = SpannableString(label).apply {
                    setSpan(
                        ForegroundColorSpan(NexusUi.GREEN),
                        label.lastIndex,
                        label.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            },
        )
        addView(BusTheme.gap(this@StoreActivity, 5))
        addView(NexusUi.rowSub(this@StoreActivity, "Plugins for your glasses"))
    }

    private fun chip(label: String, selected: Boolean): TextView =
        NexusUi.metaLabel(this, label, if (selected) NexusUi.ON_ACCENT else NexusUi.INK2).apply {
            setPadding(
                NexusUi.dp(this@StoreActivity, 13),
                NexusUi.dp(this@StoreActivity, 8),
                NexusUi.dp(this@StoreActivity, 13),
                NexusUi.dp(this@StoreActivity, 8),
            )
            background = if (selected) {
                NexusUi.rounded(this@StoreActivity, NexusUi.GREEN, 20)
            } else {
                NexusUi.bordered(this@StoreActivity, android.graphics.Color.TRANSPARENT, NexusUi.LINE2, 20)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedCategory = label
                renderCatalog()
            }
        }

    private fun storeCard(
        iconRes: Int,
        title: String,
        meta: String,
        description: String,
        size: String,
        button: String,
        buttonEnabled: Boolean,
        buttonFilled: Boolean,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@StoreActivity, NexusUi.PANEL, NexusUi.LINE2, 16)
        setPadding(
            NexusUi.dp(this@StoreActivity, 15),
            NexusUi.dp(this@StoreActivity, 15),
            NexusUi.dp(this@StoreActivity, 15),
            NexusUi.dp(this@StoreActivity, 15),
        )
        addView(
            LinearLayout(this@StoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(NexusUi.iconTileImage(this@StoreActivity, iconRes, 40))
                addView(
                    LinearLayout(this@StoreActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(NexusUi.cardTitle(this@StoreActivity, title))
                        addView(BusTheme.gap(this@StoreActivity, 5))
                        addView(NexusUi.metaLabel(this@StoreActivity, meta, NexusUi.INK3))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = NexusUi.dp(this@StoreActivity, 13)
                    },
                )
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@StoreActivity, 11))
        addView(
            NexusUi.cardBody(this@StoreActivity, description).apply {
                textSize = 12.5f
                maxLines = 2
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@StoreActivity, 13))
        addView(
            LinearLayout(this@StoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.metaLabel(this@StoreActivity, size, NexusUi.INK4),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(storeButton(button, buttonFilled, buttonEnabled, onClick))
            },
            NexusUi.block(),
        )
    }

    private fun storeButton(
        label: String,
        filled: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
        setAllCaps(false)
        stateListAnimator = null
        minHeight = NexusUi.dp(this@StoreActivity, 38)
        minimumHeight = NexusUi.dp(this@StoreActivity, 38)
        minWidth = 0
        minimumWidth = 0
        includeFontPadding = false
        setPadding(NexusUi.dp(this@StoreActivity, 18), 0, NexusUi.dp(this@StoreActivity, 18), 0)
        isEnabled = enabled
        setTextColor(
            when {
                !enabled -> NexusUi.INK4
                filled -> NexusUi.ON_ACCENT
                else -> NexusUi.GREEN
            },
        )
        background = if (filled && enabled) {
            NexusUi.rounded(this@StoreActivity, NexusUi.GREEN, 20)
        } else {
            NexusUi.bordered(
                this@StoreActivity,
                android.graphics.Color.TRANSPARENT,
                if (enabled) 0xFF2C4A37.toInt() else NexusUi.LINE2,
                20,
            )
        }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "NexusStore"
        private const val ALL_CATEGORY = "All"
        private val REVIEW_STATES = setOf(
            PluginCatalogState.PENDING,
            PluginCatalogState.DENIED,
            PluginCatalogState.DISABLED,
            PluginCatalogState.MISSING_CAPABILITY,
            PluginCatalogState.INVALID,
        )
    }
}
