package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.PluginCustomIcon
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.LinkStateBits

private const val TAG = "RokidNexusHome"
private const val BLUETOOTH_PERMISSION_REQUEST = 20
private const val NOTIFICATION_PERMISSION_REQUEST = 22
private const val PREF_NOTIFICATIONS_ANSWERED = "onboarding_notifications_answered"

/** Companion home: fixed status/settings menubar, setup cards, plugin list, store entry and hub toggle. */
class MainActivity : Activity() {
    private val developerModeStore by lazy { DeveloperModeStore(this) }
    private var renderedPluginUpdateIds: Set<String> = emptySet()
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
    private val glassesAppStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (NexusPhoneState.updateGlassesAppInstallState(intent)) rebuildSetupSection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NexusPhoneState.restore(this)
        buildUi()
        hubUiClient = BusClient(
            context = applicationContext,
            clientId = "hub-ui",
            pathPrefixes = emptyList(),
        ) { event -> handleHubEvent(event) }.also { it.connect() }
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
        queryGlassesAppIfConnected()
        NexusUpdateManager.checkForUpdates(applicationContext)
        val applyUpdates = { updates: List<PluginUpdateInfo> ->
            if (!isDestroyed && !isFinishing &&
                updates.mapTo(mutableSetOf()) { it.pluginId } != renderedPluginUpdateIds
            ) {
                rebuildPluginSection()
            }
        }
        // Reflect the registry we already have on disk right away (the Store keeps it current),
        // then let the throttled network refresh pick up anything newer.
        PluginUpdateChecker.recomputeFromCachedRegistry(applicationContext, applyUpdates)
        PluginUpdateChecker.refreshIfStale(applicationContext, onResult = applyUpdates)
    }

    override fun onStart() {
        super.onStart()
        NexusPhoneState.addUpdateListener(updateStateListener)
        updateStateListener()
        ContextCompat.registerReceiver(
            this,
            glassesAppStatusReceiver,
            IntentFilter(NexusPhoneState.ACTION_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        NexusPhoneState.removeUpdateListener(updateStateListener)
        runCatching { unregisterReceiver(glassesAppStatusReceiver) }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted &&
                BusHubService.canRunHub(this) &&
                savedToken().isNotBlank() &&
                BusHubService.isEnabled(this)
            ) {
                BusHubService.start(this)
            }
            rebuildSetupSection()
            refreshToggle()
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            recordNotificationsAnswered()
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
        var hasContent = false

        fun addBlock(view: View) {
            if (hasContent) updateSection.addView(BusTheme.gap(this, 10))
            updateSection.addView(view, NexusUi.block())
            hasContent = true
        }

        if (NexusPhoneState.updateAvailable) {
            addBlock(
                NexusUi.updateBanner(
                    context = this,
                    versionLabel = NexusPhoneState.updateVersionLabel,
                    title = "Phone app update",
                    actionLabel = NexusPhoneState.updateActionLabel(),
                    actionEnabled = NexusPhoneState.updateActionEnabled(),
                ) { NexusUpdateManager.performUpdateAction(applicationContext) },
            )
        }
        val glassesUpdateLabel = NexusPhoneState.glassesUpdateVersionLabel()
        if (glassesUpdateLabel != null) {
            val cxrReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
            addBlock(
                NexusUi.updateBanner(
                    context = this,
                    versionLabel = glassesUpdateLabel,
                    title = "Glasses app update",
                    actionLabel = NexusPhoneState.glassesUpdateActionLabel(),
                    actionEnabled = cxrReady && NexusPhoneState.glassesUpdateActionEnabled(),
                ) { BusHubService.installGlassesApp(applicationContext) },
            )
        } else {
            NexusPhoneState.glassesInstalledStatusLabel()?.let { status ->
                addBlock(NexusUi.sectionRow(this, "Glasses app", status))
            }
        }
        if (hasContent) updateSection.addView(BusTheme.gap(this, 14))
    }

    private fun rebuildPluginSection() {
        if (!::pluginSection.isInitialized) return
        pluginSection.removeAllViews()
        val pluginUpdates = PluginUpdateChecker.cachedUpdates(this).associateBy { it.pluginId }
        renderedPluginUpdateIds = pluginUpdates.keys
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
                        badge = when {
                            entry.id in pluginUpdates -> "UPDATE"
                            developerModeStore.isEnabled() &&
                                entry.provenance == PluginProvenance.LOCAL &&
                                entry.principal != null -> "DEV"
                            else -> null
                        },
                        badgeColor = if (entry.id in pluginUpdates) NexusUi.GREEN else NexusUi.AMBER,
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
        val storeSubtitle = when (pluginUpdates.size) {
            0 -> StoreTeaser.subtitle(feed, installedPackages)
            1 -> "1 update available"
            else -> "${pluginUpdates.size} updates available"
        }
        pluginSection.addView(
            storeRow(storeSubtitle, highlight = pluginUpdates.isNotEmpty()),
            NexusUi.block(),
        )
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
        badgeColor: Int = NexusUi.AMBER,
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
                                addView(rowBadge(badge, badgeColor))
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

    private fun rowBadge(label: String, color: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(color)
            background = NexusUi.bordered(
                this@MainActivity,
                NexusUi.alpha(color, 24),
                NexusUi.alpha(color, 90),
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

    private fun storeRow(subtitle: String, highlight: Boolean = false): LinearLayout =
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
                    addView(
                        NexusUi.rowSub(this@MainActivity, subtitle).apply {
                            if (highlight) setTextColor(NexusUi.GREEN)
                        },
                    )
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

    private enum class StepState { DONE, ACTIVE, PENDING }

    private data class OnboardingStep(
        val title: String,
        val body: String,
        val done: Boolean,
        val actionLabel: String? = null,
        val actionEnabled: Boolean = true,
        val onAction: (() -> Unit)? = null,
        val statusLine: String? = null,
        val secondaryActionLabel: String? = null,
        val onSecondaryAction: (() -> Unit)? = null,
        val onGuide: (() -> Unit)? = null,
    )

    private fun rebuildSetupSection() {
        if (!::setupSection.isInitialized) return
        setupSection.removeAllViews()

        val cxrReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
        val glassesAppState = NexusPhoneState.glassesAppInstallState
        val hasPlugin = BusHubService.pluginCatalog(this).entries.any { it.state == PluginCatalogState.ENABLED }

        val glassesStatus = if (!cxrReady && !NexusPhoneState.glassesAppInstalled) {
            "Connect your glasses first."
        } else {
            when (glassesAppState) {
                GlassesAppInstallState.Unknown -> "Ready to check the glasses."
                GlassesAppInstallState.Querying -> "Checking whether Nexus is installed..."
                GlassesAppInstallState.NotInstalled -> "Ready to install over Hi Rokid."
                GlassesAppInstallState.Resolving -> "Finding the latest glasses app..."
                is GlassesAppInstallState.Downloading -> glassesAppState.totalBytes
                    ?.takeIf { it > 0L }
                    ?.let { total ->
                        "Downloading... ${(glassesAppState.downloadedBytes * 100L / total).coerceIn(0L, 100L)}%"
                    }
                    ?: "Downloading the glasses app..."
                GlassesAppInstallState.Installing -> "Installing on your glasses..."
                GlassesAppInstallState.Installed -> "Nexus is installed on your glasses."
                is GlassesAppInstallState.Error -> glassesAppState.message
            }
        }
        val glassesActionLabel = when (glassesAppState) {
            GlassesAppInstallState.Unknown -> "Install Nexus"
            GlassesAppInstallState.Querying -> "Checking..."
            GlassesAppInstallState.NotInstalled -> "Install Nexus"
            GlassesAppInstallState.Resolving -> "Finding release..."
            is GlassesAppInstallState.Downloading -> "Downloading..."
            GlassesAppInstallState.Installing -> "Installing..."
            GlassesAppInstallState.Installed -> null
            is GlassesAppInstallState.Error -> if (glassesAppState.retry == GlassesAppRetry.QUERY) {
                "Check again"
            } else {
                "Retry install"
            }
        }
        val glassesActionEnabled = cxrReady && when (glassesAppState) {
            GlassesAppInstallState.Unknown,
            GlassesAppInstallState.NotInstalled,
            is GlassesAppInstallState.Error,
            -> true
            else -> false
        }
        val glassesAction = {
            if (glassesAppState is GlassesAppInstallState.Error &&
                glassesAppState.retry == GlassesAppRetry.QUERY
            ) {
                BusHubService.queryGlassesApp(this)
            } else {
                BusHubService.installGlassesApp(this)
            }
        }
        // The APK reaches the glasses over a direct Wi-Fi link the phone must join, so an
        // install started with Wi-Fi off always fails with an opaque CXR error.
        val installNeedsWifi = when (glassesAppState) {
            GlassesAppInstallState.Unknown,
            GlassesAppInstallState.NotInstalled,
            -> true
            is GlassesAppInstallState.Error -> glassesAppState.retry == GlassesAppRetry.INSTALL
            else -> false
        }
        // Fail open: if Wi-Fi state cannot be read, keep the normal install action.
        val wifiReady = runCatching {
            getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(true)
        val wifiBlocksInstall = cxrReady && installNeedsWifi && !wifiReady

        val steps = listOf(
            OnboardingStep(
                title = "Connect your glasses",
                body = "Authorize Nexus with the Hi Rokid app so it can reach your glasses.",
                done = savedToken().isNotBlank(),
                actionLabel = "Authorize",
                onAction = { startAuthorization() },
            ),
            OnboardingStep(
                title = "Allow Bluetooth",
                body = "Nexus needs the nearby-devices permission to hold the glasses link.",
                done = !needsBluetoothPermission(),
                actionLabel = "Allow",
                onAction = { requestBluetoothConnectIfNeeded() },
            ),
            OnboardingStep(
                title = "Allow notifications",
                body = "Nexus keeps a quiet status notification while it holds the " +
                    "glasses link.",
                done = notificationsSettled(),
                actionLabel = "Allow",
                onAction = { requestNotificationsIfNeeded() },
            ),
            OnboardingStep(
                title = "Install Nexus on your glasses",
                body = "Nexus downloads the latest glasses app and installs it over Hi Rokid.",
                done = NexusPhoneState.glassesAppInstalled,
                actionLabel = if (wifiBlocksInstall) "Turn on Wi-Fi" else glassesActionLabel,
                actionEnabled = if (wifiBlocksInstall) true else glassesActionEnabled,
                onAction = if (wifiBlocksInstall) ::openWifiPanel else glassesAction,
                statusLine = if (wifiBlocksInstall) {
                    "The app reaches the glasses over Wi-Fi — turn it on first."
                } else {
                    glassesStatus
                },
            ),
            OnboardingStep(
                title = "Set up your glasses",
                body = "Put the glasses on and follow the two prompts on the lens — Nexus " +
                    "arms itself and the plugin launcher appears.",
                done = NexusPhoneState.glassesSetupComplete,
                actionLabel = "Open glasses app",
                actionEnabled = cxrReady,
                onAction = { BusHubService.openGlassesApp(this) },
                statusLine = if (NexusPhoneState.glassesAppInstalled &&
                    !NexusPhoneState.glassesSetupComplete
                ) {
                    "Waiting for the glasses to finish setup..."
                } else {
                    null
                },
                onGuide = {
                    startActivity(Intent(this, GlassesSetupGuideActivity::class.java))
                },
            ),
            OnboardingStep(
                title = "Allow app installs",
                body = "Plugins and app updates install like regular Android apps, so " +
                    "Android asks you to approve Nexus as an install source.",
                done = canInstallApps(),
                actionLabel = "Open settings",
                onAction = { openInstallSourceSettings() },
            ),
            OnboardingStep(
                title = "Add your first plugin",
                body = "Open the Store and install a plugin, then approve its access.",
                done = hasPlugin,
                actionLabel = "Open the Store",
                onAction = { startActivity(Intent(this, StoreActivity::class.java)) },
            ),
        )

        if (steps.all { it.done }) {
            setupSection.visibility = View.GONE
            return
        }
        setupSection.visibility = View.VISIBLE
        setupSection.addView(NexusUi.sectionRow(this, "Get started"), NexusUi.block())
        setupSection.addView(BusTheme.gap(this, 12))
        val activeIndex = steps.indexOfFirst { !it.done }
        steps.forEachIndexed { index, step ->
            if (index > 0) setupSection.addView(BusTheme.gap(this, 10))
            val state = when {
                step.done -> StepState.DONE
                index == activeIndex -> StepState.ACTIVE
                else -> StepState.PENDING
            }
            setupSection.addView(onboardingStepCard(index + 1, state, step), NexusUi.block())
        }
        setupSection.addView(BusTheme.gap(this, 28))
    }

    private fun onboardingStepCard(number: Int, state: StepState, step: OnboardingStep): LinearLayout =
        NexusUi.card(this).apply {
            if (state == StepState.PENDING) alpha = 0.55f
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(stepBadge(number, state))
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(NexusUi.cardTitle(this@MainActivity, step.title))
                            addView(BusTheme.gap(this@MainActivity, 4))
                            addView(NexusUi.cardBody(this@MainActivity, step.body))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = NexusUi.dp(this@MainActivity, 12)
                        },
                    )
                },
                NexusUi.block(),
            )
            step.onGuide?.takeIf { state == StepState.ACTIVE }?.let { guide ->
                addView(BusTheme.gap(this@MainActivity, 8))
                addView(
                    NexusUi.metaLabel(this@MainActivity, "HOW IT WORKS  ›", NexusUi.GREEN).apply {
                        textSize = 10.5f
                        letterSpacing = 0.12f
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { guide() }
                    },
                    NexusUi.block(),
                )
            }
            step.statusLine?.let { status ->
                addView(BusTheme.gap(this@MainActivity, 6))
                addView(NexusUi.rowSub(this@MainActivity, status), NexusUi.block())
            }
            val hasPrimaryAction = step.actionLabel != null && step.onAction != null
            val hasSecondaryAction = step.secondaryActionLabel != null && step.onSecondaryAction != null
            if (state == StepState.ACTIVE && (hasPrimaryAction || hasSecondaryAction)) {
                addView(BusTheme.gap(this@MainActivity, 8))
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        if (hasSecondaryAction) {
                            addView(
                                NexusUi.textButton(
                                    this@MainActivity,
                                    requireNotNull(step.secondaryActionLabel),
                                ).apply {
                                    setOnClickListener { step.onSecondaryAction?.invoke() }
                                },
                            )
                        }
                        if (hasPrimaryAction) {
                            if (hasSecondaryAction) {
                                // Horizontal spacer: BusTheme.gap is MATCH_PARENT wide and
                                // would shove the primary button off-screen in this row.
                                addView(
                                    View(this@MainActivity),
                                    LinearLayout.LayoutParams(NexusUi.dp(this@MainActivity, 8), 0),
                                )
                            }
                            addView(
                                NexusUi.textButton(
                                    this@MainActivity,
                                    requireNotNull(step.actionLabel),
                                ).apply {
                                    isEnabled = step.actionEnabled
                                    setOnClickListener { step.onAction?.invoke() }
                                },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

    private fun stepBadge(number: Int, state: StepState): TextView =
        TextView(this).apply {
            text = if (state == StepState.DONE) "✓" else number.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (state == StepState.DONE) NexusUi.ON_ACCENT else NexusUi.INK)
            val size = NexusUi.dp(this@MainActivity, 26)
            background = if (state == StepState.DONE) {
                NexusUi.rounded(this@MainActivity, NexusUi.GREEN, 999)
            } else {
                NexusUi.bordered(
                    this@MainActivity,
                    if (state == StepState.ACTIVE) NexusUi.alpha(NexusUi.GREEN, 30) else NexusUi.PANEL,
                    if (state == StepState.ACTIVE) NexusUi.GREEN else NexusUi.LINE,
                    999,
                )
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
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
        if (BusHubService.isEnabled(this) && BusHubService.canRunHub(this)) {
            logLine("Stopping hub")
            BusHubService.stop(this)
            setToggle(enabled = false)
            renderLinkState(hubEnabled = false)
        } else {
            logLine("Starting hub")
            BusHubService.start(this)
            val startRequested = BusHubService.canRunHub(this)
            setToggle(enabled = startRequested)
            renderLinkState(hubEnabled = startRequested)
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
        !BusHubService.canRunHub(this)

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

    private fun notificationsSettled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
                .getBoolean(PREF_NOTIFICATIONS_ANSWERED, false)

    private fun recordNotificationsAnswered() {
        getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NOTIFICATIONS_ANSWERED, true)
            .apply()
    }

    private fun canInstallApps(): Boolean = packageManager.canRequestPackageInstalls()

    private fun openWifiPanel() {
        runCatching {
            startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
        }
    }

    private fun openInstallSourceSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun savedToken(): String =
        getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
            .getString(NexusPhoneState.PREF_TOKEN, "")
            .orEmpty()

    private fun refreshToggle() {
        setToggle(BusHubService.isEnabled(this) && BusHubService.canRunHub(this))
    }

    private fun setToggle(enabled: Boolean) {
        toggleButton.text = if (enabled) "STOP HUB" else "START HUB"
    }

    private fun handleHubEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> {
                val cxrWasReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
                lastLinkState = event.state
                renderLinkState()
                renderUpdateSection()
                refreshToggle()
                rebuildSetupSection()
                val cxrIsReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
                if (cxrIsReady && !cxrWasReady) BusHubService.queryGlassesApp(this)
            }
            is BusEvent.Error -> logLine("hub-ui: ${event.message}")
            is BusEvent.Message -> Unit
            is BusEvent.Binary -> Unit
        }
    }

    private fun queryGlassesAppIfConnected() {
        if (lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0) {
            BusHubService.queryGlassesApp(this)
        }
    }

    private fun renderLinkState(hubEnabled: Boolean = BusHubService.isEnabled(this)) {
        val cxrUp = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
        val sppUp = lastLinkState and LinkStateBits.SPP_DATA_UP != 0
        val connected = hubEnabled && cxrUp && sppUp
        val updateAvailable = NexusPhoneState.updateAvailable ||
            NexusPhoneState.glassesAppUpdateState is GlassesAppUpdateState.UpdateAvailable
        val tint = when {
            updateAvailable -> NexusUi.AMBER
            connected -> NexusUi.GREEN
            else -> NexusUi.INK3
        }
        if (::gearIcon.isInitialized) {
            gearIcon.imageTintList = ColorStateList.valueOf(tint)
            gearPip.visibility = if (updateAvailable) View.VISIBLE else View.GONE
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
