package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.PluginCustomIcon
import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.LinkStateBits

private const val TAG = "RokidNexusHome"
private const val BLUETOOTH_PERMISSION_REQUEST = 20
private const val NOTIFICATION_PERMISSION_REQUEST = 22

/** Companion home: fixed status/settings menubar, setup cards, plugin list, store entry and hub toggle. */
class MainActivity : Activity() {
    private val developerModeStore by lazy { DeveloperModeStore(this) }
    private lateinit var updateSection: LinearLayout
    private lateinit var setupSection: LinearLayout
    private lateinit var pluginSection: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var gearIcon: ImageView
    private lateinit var gearPip: View
    private var hubUiClient: BusClient? = null
    private var lastLinkState = 0
    private val updateStateListener: () -> Unit = {
        renderUpdateSection()
        renderLinkState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        hubUiClient = BusClient(
            context = applicationContext,
            clientId = "hub-ui",
            pathPrefixes = emptyList(),
        ) { event -> handleHubEvent(event) }.also { it.connect() }
        requestBluetoothConnectIfNeeded()
        requestNotificationsIfNeeded()
        if (savedToken().isNotBlank() && BusHubService.isEnabled(this)) {
            logLine("Saved Hi Rokid token present")
            BusHubService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeRecoveredNexusUpdateInstall()
        resumeRecoveredPluginInstall()
        rebuildSetupSection()
        rebuildPluginSection()
        refreshToggle()
        renderLinkState()
        NexusUpdateManager.checkForUpdates(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        NexusPhoneState.addUpdateListener(updateStateListener)
        updateStateListener()
    }

    override fun onStop() {
        NexusPhoneState.removeUpdateListener(updateStateListener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            rebuildSetupSection()
        }
    }

    override fun onDestroy() {
        hubUiClient?.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != NexusPhoneState.AUTH_REQUEST) return
        when (val result = CxrLAuth.parseAuthorizationResult(resultCode, data)) {
            is CxrLAuth.Result.Success -> {
                getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(NexusPhoneState.PREF_TOKEN, result.token)
                    .apply()
                logLine("Hi Rokid authorization succeeded")
                BusHubService.startWithToken(this, result.token)
                rebuildSetupSection()
            }
            is CxrLAuth.Result.Fail -> logLine("Authorization failed: ${result.reason}")
            CxrLAuth.Result.Cancel -> logLine("Authorization canceled")
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        setupSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toggleButton = NexusUi.outlinePillButton(this, "START HUB").apply {
            setOnClickListener { toggleHub() }
        }

        pluginSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updateSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = NexusUi.contentColumn(this).apply {
            addView(updateSection, NexusUi.block())
            addView(setupSection, NexusUi.block())
            addView(pluginSection, NexusUi.block())
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(header(), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(footer(), NexusUi.block())
        }

        renderLinkState()
        refreshToggle()
        rebuildSetupSection()
        rebuildPluginSection()
        renderUpdateSection()
        setContentView(root)
    }

    private fun renderUpdateSection() {
        if (!::updateSection.isInitialized) return
        updateSection.removeAllViews()
        if (NexusPhoneState.updateAvailable) {
            updateSection.addView(
                NexusUi.updateBanner(
                    context = this,
                    versionLabel = NexusPhoneState.updateVersionLabel,
                    actionLabel = NexusPhoneState.updateActionLabel(),
                    actionEnabled = NexusPhoneState.updateActionEnabled(),
                ) { NexusUpdateManager.performUpdateAction(applicationContext) },
                NexusUi.block(),
            )
            updateSection.addView(BusTheme.gap(this, 14))
        }
    }

    private fun rebuildPluginSection() {
        if (!::pluginSection.isInitialized) return
        pluginSection.removeAllViews()
        val catalog = BusHubService.pluginCatalog(this)
        val activeCount = catalog.entries.count {
            it.state == PluginCatalogState.BUILT_IN || it.state == PluginCatalogState.ENABLED
        }
        pluginSection.addView(
            NexusUi.sectionRow(this, "Plugins", "$activeCount Active"),
            NexusUi.block(),
        )
        pluginSection.addView(BusTheme.gap(this, 14))
        if (catalog.entries.isEmpty()) {
            pluginSection.addView(
                NexusUi.navCard(
                    this,
                    "No plugins installed",
                    "Nexus is ready. Install a phone plugin, then approve its access.",
                ),
                NexusUi.block(),
            )
            pluginSection.addView(BusTheme.gap(this, 9))
        } else {
            catalog.entries.forEachIndexed { index, entry ->
                if (index > 0) pluginSection.addView(BusTheme.gap(this, 9))
                pluginSection.addView(
                    pluginRow(
                        icon = NexusPluginIcons.resolve(
                            context = this,
                            iconKey = entry.iconKey,
                            customIcon = entry.iconDrawableResId?.let { resId ->
                                entry.principal?.packageName?.let { packageName ->
                                    PluginCustomIcon(packageName, resId)
                                }
                            },
                            pluginId = entry.id,
                        ),
                        title = entry.displayName,
                        subtitle = catalogStateLabel(entry),
                        badge = "DEV".takeIf {
                            developerModeStore.isEnabled() &&
                                entry.provenance == PluginProvenance.LOCAL &&
                                entry.principal != null
                        },
                    ) { openCatalogEntry(entry) },
                    NexusUi.block(),
                )
            }
            pluginSection.addView(BusTheme.gap(this, 6))
        }
        val feed = RegistryClient.create(applicationContext).cachedSnapshot()?.feed
            ?: RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList())
        val installedPackages = feed.plugins
            .map { it.artifact.packageName }
            .filterTo(linkedSetOf(), ::isPackageInstalled)
        pluginSection.addView(storeRow(StoreTeaser.subtitle(feed, installedPackages)), NexusUi.block())
    }

    private fun catalogStateLabel(entry: PluginCatalogEntry): String = when (entry.state) {
        PluginCatalogState.BUILT_IN -> if (entry.launchable) "Built in · enabled" else "Built in · service"
        PluginCatalogState.PENDING -> "Pending approval"
        PluginCatalogState.ENABLED -> "Installed · enabled"
        PluginCatalogState.DISABLED -> "Installed · disabled"
        PluginCatalogState.DENIED -> "Access denied"
        PluginCatalogState.INVALID -> "Invalid plugin${entry.detail?.let { " · $it" }.orEmpty()}"
        PluginCatalogState.MISSING_CAPABILITY -> "Missing surfaces access"
    }

    private fun openCatalogEntry(entry: PluginCatalogEntry) {
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

    private fun header(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@MainActivity, 22),
                        NexusUi.dp(this@MainActivity, 14),
                        NexusUi.dp(this@MainActivity, 18),
                        NexusUi.dp(this@MainActivity, 14),
                    )
                    addView(
                        NexusUi.wordmark(this@MainActivity, "Rokid Nexus"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(gearButton())
                },
                NexusUi.block(),
            )
            addView(line())
        }

    private fun gearButton(): FrameLayout {
        gearIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_gear)
            imageTintList = ColorStateList.valueOf(NexusUi.INK3)
        }
        gearPip = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NexusUi.AMBER)
                setStroke(NexusUi.dp(this@MainActivity, 2), NexusUi.BG)
            }
        }
        return FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(this@MainActivity, android.graphics.Color.TRANSPARENT, 22)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            addView(
                gearIcon,
                FrameLayout.LayoutParams(
                    NexusUi.dp(this@MainActivity, 24),
                    NexusUi.dp(this@MainActivity, 24),
                    Gravity.CENTER,
                ),
            )
            addView(
                gearPip,
                FrameLayout.LayoutParams(
                    NexusUi.dp(this@MainActivity, 8),
                    NexusUi.dp(this@MainActivity, 8),
                    Gravity.TOP or Gravity.END,
                ).apply {
                    topMargin = NexusUi.dp(this@MainActivity, 7)
                    marginEnd = NexusUi.dp(this@MainActivity, 7)
                },
            )
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@MainActivity, 44),
                NexusUi.dp(this@MainActivity, 44),
            )
        }
    }

    private fun footer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(line())
            addView(
                toggleButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = NexusUi.dp(this@MainActivity, 22)
                    topMargin = NexusUi.dp(this@MainActivity, 12)
                    rightMargin = NexusUi.dp(this@MainActivity, 22)
                    bottomMargin = NexusUi.dp(this@MainActivity, 24)
                },
            )
        }

    private fun line(): View =
        View(this).apply {
            setBackgroundColor(NexusUi.LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NexusUi.dp(this@MainActivity, 1),
            )
        }

    private fun pluginRow(
        icon: Drawable,
        title: String,
        subtitle: String,
        badge: String? = null,
        onClick: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.pressedBordered(this@MainActivity, NexusUi.PANEL, 15)
            setPadding(
                NexusUi.dp(this@MainActivity, 15),
                NexusUi.dp(this@MainActivity, 14),
                NexusUi.dp(this@MainActivity, 15),
                NexusUi.dp(this@MainActivity, 14),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                NexusUi.iconTileDrawable(this@MainActivity, icon),
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@MainActivity, 34),
                    NexusUi.dp(this@MainActivity, 34),
                ),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    if (badge == null) {
                        addView(NexusUi.rowTitle(this@MainActivity, title))
                    } else {
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(NexusUi.rowTitle(this@MainActivity, title))
                                addView(devBadge(badge))
                            },
                        )
                    }
                    addView(BusTheme.gap(this@MainActivity, 4))
                    addView(NexusUi.rowSub(this@MainActivity, subtitle))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = NexusUi.dp(this@MainActivity, 13)
                },
            )
            addView(NexusUi.chevron(this@MainActivity))
        }

    private fun devBadge(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(NexusUi.AMBER)
            background = NexusUi.bordered(
                this@MainActivity,
                NexusUi.alpha(NexusUi.AMBER, 24),
                NexusUi.alpha(NexusUi.AMBER, 90),
                7,
            )
            setPadding(
                NexusUi.dp(this@MainActivity, 6),
                NexusUi.dp(this@MainActivity, 2),
                NexusUi.dp(this@MainActivity, 6),
                NexusUi.dp(this@MainActivity, 2),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = NexusUi.dp(this@MainActivity, 8) }
        }

    private fun storeRow(subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.bordered(
                context = this@MainActivity,
                fill = NexusUi.alpha(NexusUi.GREEN, 0x08),
                stroke = 0xFF2C4A37.toInt(),
                radius = 15,
                dashWidthDp = 5,
                dashGapDp = 4,
            )
            setPadding(
                NexusUi.dp(this@MainActivity, 15),
                NexusUi.dp(this@MainActivity, 16),
                NexusUi.dp(this@MainActivity, 15),
                NexusUi.dp(this@MainActivity, 16),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, StoreActivity::class.java)) }
            addView(
                TextView(this@MainActivity).apply {
                    text = "+"
                    textSize = 18f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(NexusUi.GREEN)
                    background = NexusUi.bordered(
                        this@MainActivity,
                        android.graphics.Color.TRANSPARENT,
                        0xFF2C4A37.toInt(),
                        9,
                    )
                },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@MainActivity, 34),
                    NexusUi.dp(this@MainActivity, 34),
                ),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@MainActivity, "Browse the Store").apply { textSize = 14f })
                    addView(BusTheme.gap(this@MainActivity, 4))
                    addView(NexusUi.rowSub(this@MainActivity, subtitle))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = NexusUi.dp(this@MainActivity, 13)
                },
            )
            addView(NexusUi.chevron(this@MainActivity).apply { setTextColor(NexusUi.GREEN) })
        }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun rebuildSetupSection() {
        if (!::setupSection.isInitialized) return
        setupSection.removeAllViews()
        val cards = buildList {
            if (savedToken().isBlank()) {
                add(
                    setupCard(
                        title = "Connect your glasses",
                        body = "Authorize Nexus with the Hi Rokid app so it can talk to your glasses.",
                        action = "Authorize",
                    ) { startAuthorization() },
                )
            }
            if (needsBluetoothPermission()) {
                add(
                    setupCard(
                        title = "Nearby devices",
                        body = "Bluetooth permission is needed to reach the glasses.",
                        action = "Allow",
                    ) { requestBluetoothConnectIfNeeded() },
                )
            }
        }
        if (cards.isEmpty()) {
            setupSection.visibility = View.GONE
            return
        }
        setupSection.visibility = View.VISIBLE
        setupSection.addView(NexusUi.sectionRow(this, "Set up"), NexusUi.block())
        setupSection.addView(BusTheme.gap(this, 12))
        cards.forEachIndexed { index, card ->
            if (index > 0) setupSection.addView(BusTheme.gap(this, 10))
            setupSection.addView(card, NexusUi.block())
        }
        setupSection.addView(BusTheme.gap(this, 28))
    }

    private fun setupCard(
        title: String,
        body: String,
        action: String,
        onClick: () -> Unit,
    ): LinearLayout =
        NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(this@MainActivity, title))
            addView(BusTheme.gap(this@MainActivity, 6))
            addView(NexusUi.cardBody(this@MainActivity, body))
            addView(BusTheme.gap(this@MainActivity, 8))
            addView(
                NexusUi.textButton(this@MainActivity, action).apply {
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END },
            )
        }

    private fun toggleHub() {
        if (BusHubService.isEnabled(this)) {
            logLine("Stopping hub")
            BusHubService.stop(this)
            setToggle(enabled = false)
            renderLinkState(hubEnabled = false)
        } else {
            logLine("Starting hub")
            BusHubService.start(this)
            setToggle(enabled = true)
            renderLinkState(hubEnabled = true)
        }
    }

    private fun startAuthorization() {
        when (val result = CxrLAuth.requestAuthorization(this, NexusPhoneState.AUTH_REQUEST)) {
            null -> logLine("Hi Rokid authorization opened")
            is CxrLAuth.Result.Fail -> logLine("Authorization failed: ${result.reason}")
            CxrLAuth.Result.Cancel -> logLine("Authorization canceled")
            is CxrLAuth.Result.Success -> Unit
        }
    }

    private fun requestBluetoothConnectIfNeeded() {
        if (needsBluetoothPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_PERMISSION_REQUEST,
            )
        }
    }

    private fun needsBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun savedToken(): String =
        getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
            .getString(NexusPhoneState.PREF_TOKEN, "")
            .orEmpty()

    private fun refreshToggle() {
        setToggle(BusHubService.isEnabled(this))
    }

    private fun setToggle(enabled: Boolean) {
        toggleButton.text = if (enabled) "STOP HUB" else "START HUB"
    }

    private fun handleHubEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> {
                lastLinkState = event.state
                renderLinkState()
                refreshToggle()
            }
            is BusEvent.Error -> logLine("hub-ui: ${event.message}")
            is BusEvent.Message -> Unit
            is BusEvent.Binary -> Unit
        }
    }

    private fun renderLinkState(hubEnabled: Boolean = BusHubService.isEnabled(this)) {
        val cxrUp = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
        val sppUp = lastLinkState and LinkStateBits.SPP_DATA_UP != 0
        val connected = hubEnabled && cxrUp && sppUp
        val tint = when {
            NexusPhoneState.updateAvailable -> NexusUi.AMBER
            connected -> NexusUi.GREEN
            else -> NexusUi.INK3
        }
        if (::gearIcon.isInitialized) {
            gearIcon.imageTintList = ColorStateList.valueOf(tint)
            gearPip.visibility = if (NexusPhoneState.updateAvailable) View.VISIBLE else View.GONE
        }
    }

    private fun logLine(line: String) {
        if (line.isBlank()) return
        Log.i(TAG, line)
        sendBroadcast(
            Intent(NexusPhoneState.ACTION_LOG)
                .setPackage(packageName)
                .putExtra("line", line),
        )
    }
}
