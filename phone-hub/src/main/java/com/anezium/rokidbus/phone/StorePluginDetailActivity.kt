package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * One plugin, in full: action, stats, what's new, screenshots, description.
 * The Store list stays an index; everything the feed publishes lands here.
 */
class StorePluginDetailActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var registryClient: RegistryClient
    private lateinit var iconLoader: StoreIconLoader
    private lateinit var pluginInstaller: PluginInstaller
    private lateinit var postInstallCoordinator: PluginPostInstallCoordinator
    private lateinit var pluginId: String
    private var registrySnapshot: RegistrySnapshot? = null
    private var installState: PluginInstallState? = null
    private var installOperation: PluginInstallOperation? = null
    private var notesExpanded = false
    private val hostVersionCode: Long by lazy {
        StoreScreens.installedVersionCodes(packageManager, setOf(packageName))[packageName] ?: 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run {
            finish()
            return
        }
        registryClient = RegistryClient.create(applicationContext)
        iconLoader = StoreIconLoader(applicationContext)
        postInstallCoordinator = PluginPostInstallCoordinator(
            discoverPackage = PhonePluginDiscovery(packageManager)::discoverPackage,
            grantState = PluginGrantStore(applicationContext)::stateFor,
            refreshCatalog = ::render,
        )
        pluginInstaller = PluginInstaller.create(
            context = applicationContext,
            hostVersionCode = hostVersionCode,
        )
        registrySnapshot = registryClient.cachedSnapshot()
        buildUi()
        registryClient.refresh { result ->
            if (isFinishing || isDestroyed) return@refresh
            if (result is RegistryLoadResult.Success) {
                registrySnapshot = result.snapshot
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeRecoveredPluginInstall()
        render()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this, horizontalDp = 0, topDp = 0, bottomDp = 24)
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
            addView(backRow(), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        render()
    }

    private fun backRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), 0)
        addView(
            NexusUi.metaLabel(this@StorePluginDetailActivity, "‹ Store", NexusUi.INK3).apply {
                letterSpacing = 0.2f
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
                background = NexusUi.pressed(this@StorePluginDetailActivity, Color.TRANSPARENT, 12)
                setPadding(0, dp(8), dp(8), dp(8))
            },
        )
    }

    private fun currentEntry(): StoreEntry? {
        val feed = registrySnapshot?.feed ?: RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList())
        return StoreScreens.buildCatalog(this, feed, hostVersionCode).entry(pluginId)
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val entry = currentEntry()
        if (entry == null) {
            content.addView(missingCard(), pad(NexusUi.block()))
            return
        }
        val plugin = entry.registryPlugin

        content.addView(header(entry), NexusUi.block())
        content.addView(BusTheme.gap(this, 15))
        content.addView(actionsRow(entry), pad(NexusUi.block()))
        hostBlockNote(entry)?.let {
            content.addView(BusTheme.gap(this, 10))
            content.addView(it, pad(NexusUi.block()))
        }
        if (plugin != null) {
            content.addView(BusTheme.gap(this, 16))
            content.addView(statsStrip(entry, plugin), pad(NexusUi.block()))
            val latest = plugin.releases.firstOrNull()
            if (latest != null) {
                content.addView(
                    sectionLabelRow(
                        "What's new — ${latest.version}",
                        if (plugin.releases.size > 1) "History ›" else null,
                    ) { openHistory() },
                )
                content.addView(whatsNewCard(latest), pad(NexusUi.block()))
            }
            if (plugin.screenshotUrls.isNotEmpty()) {
                content.addView(sectionLabelRow("Screenshots", null, null))
                content.addView(screenshotsRow(plugin), NexusUi.block())
            }
            val about = stripLeadingAboutHeading(plugin.listingDescriptionMarkdown.ifBlank { plugin.description })
            if (about.isNotBlank()) {
                content.addView(sectionLabelRow("About", null, null))
                content.addView(
                    NexusUi.cardBody(this, "").apply {
                        text = ReleaseNotesRenderer.render(this@StorePluginDetailActivity, about)
                    },
                    pad(NexusUi.block()),
                )
            }
            if (plugin.nexus.capabilities.isNotEmpty()) {
                content.addView(sectionLabelRow("Capabilities", null, null))
                content.addView(capabilityChips(plugin), NexusUi.block())
            }
            content.addView(sectionLabelRow("Details", null, null))
            content.addView(detailRows(entry, plugin), pad(NexusUi.block()))
        } else {
            entry.localEntry?.detail?.takeIf(String::isNotBlank)?.let { detail ->
                content.addView(sectionLabelRow("About", null, null))
                content.addView(NexusUi.cardBody(this, detail), pad(NexusUi.block()))
            }
        }
        uninstallTarget(entry)?.let { packageName ->
            content.addView(BusTheme.gap(this, 26))
            content.addView(
                NexusUi.uninstallCard(this, entry.displayName) {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                },
                pad(NexusUi.block()),
            )
        }
    }

    private fun header(entry: StoreEntry): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(6), dp(22), 0)
        addView(StoreScreens.iconView(this@StorePluginDetailActivity, iconLoader, entry, 48))
        addView(
            LinearLayout(this@StorePluginDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.cardTitle(this@StorePluginDetailActivity, entry.displayName).apply { textSize = 20f })
                addView(BusTheme.gap(this@StorePluginDetailActivity, 5))
                addView(
                    NexusUi.metaLabel(
                        this@StorePluginDetailActivity,
                        listOfNotNull(
                            entry.registryAuthor?.takeIf(String::isNotBlank),
                            entry.category.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                        NexusUi.INK3,
                    ),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            },
        )
    }

    private fun actionsRow(entry: StoreEntry): LinearLayout {
        val action = primaryAction(entry)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            bigButton(action.label, filled = action.filled, enabled = action.enabled, onClick = action.onClick),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // While an update is pending the installed build stays openable.
        if (entry.state == StoreEntryState.UPDATE_AVAILABLE && installState == null) {
            row.addView(
                bigButton("Open", filled = false, enabled = true) { openInstalled(entry) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f).apply {
                    marginStart = dp(9)
                },
            )
        }
        return row
    }

    private data class DetailAction(
        val label: String,
        val enabled: Boolean,
        val filled: Boolean,
        val onClick: () -> Unit,
    )

    private fun primaryAction(entry: StoreEntry): DetailAction {
        when (val state = installState) {
            is PluginInstallState.Downloading -> return DetailAction(
                label = state.totalBytes?.takeIf { it > 0 }?.let {
                    "${(state.downloadedBytes * 100 / it).coerceIn(0, 100)}% · Cancel"
                } ?: "Downloading · Cancel",
                enabled = true,
                filled = false,
            ) { installOperation?.cancel() }
            PluginInstallState.Verifying -> return DetailAction("Verifying", false, false) {}
            PluginInstallState.Installing -> return DetailAction("Preparing", false, false) {}
            PluginInstallState.AwaitingUserConfirmation ->
                return DetailAction("Confirm install", false, false) {}
            PluginInstallState.Cancelled -> return DetailAction("Retry", true, true) { beginInstall(entry) }
            is PluginInstallState.Failure -> return DetailAction("Retry", true, true) { beginInstall(entry) }
            is PluginInstallState.Success, null -> Unit
        }
        val latestVersion = entry.registryPlugin?.artifact?.versionName
        return when (entry.state) {
            StoreEntryState.AVAILABLE -> DetailAction("Install", true, true) { beginInstall(entry) }
            StoreEntryState.UPDATE_AVAILABLE -> DetailAction(
                latestVersion?.let { "Update to $it" } ?: "Update",
                true,
                true,
            ) { beginInstall(entry) }
            StoreEntryState.REQUIRES_HOST -> DetailAction("Requires Nexus update", false, false) {}
            StoreEntryState.INSTALLED,
            StoreEntryState.SIDELOADED,
            -> {
                val review = entry.localGrantState in REVIEW_STATES
                DetailAction(if (review) "Review access" else "Open", true, false) { openInstalled(entry) }
            }
        }
    }

    private fun beginInstall(entry: StoreEntry) {
        installState = null
        installOperation = pluginInstaller.install(entry) { state ->
            if (isDestroyed) return@install
            installState = state
            if (state is PluginInstallState.Failure) {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            } else if (state == PluginInstallState.Cancelled) {
                Toast.makeText(this, "Installation cancelled", Toast.LENGTH_SHORT).show()
            } else if (state is PluginInstallState.Success) {
                installOperation = null
                installState = null
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
            render()
        }
    }

    private fun openInstalled(entry: StoreEntry) {
        val local = entry.localEntry ?: return
        val pluginId = local.id
        if (pluginId != null &&
            pluginId == NexusPhoneState.backgroundAudioPluginId &&
            BusHubService.openPlugin(pluginId)
        ) {
            return
        }
        if (local.principal != null && local.state != PluginCatalogState.ENABLED) {
            startActivity(Intent(this, PluginPermissionsActivity::class.java))
            return
        }
        val target = local.settingsComponent
        if (target == null) {
            if (local.principal != null) {
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

    private fun hostBlockNote(entry: StoreEntry): TextView? {
        if (!entry.updateBlockedByHost && entry.state != StoreEntryState.REQUIRES_HOST) return null
        return NexusUi.cardBody(
            this,
            "This release needs a newer Rokid Nexus. Update the app first, then come back.",
        ).apply {
            textSize = 12f
            setTextColor(NexusUi.AMBER)
        }
    }

    private fun statsStrip(entry: StoreEntry, plugin: RegistryPlugin): LinearLayout {
        val latest = plugin.artifact.versionName
        val versionCell = when (entry.state) {
            StoreEntryState.UPDATE_AVAILABLE -> "Latest" to latest
            StoreEntryState.INSTALLED -> "Version" to latest
            else -> "Version" to latest
        }
        val updated = StoreScreens.formatReleaseDate(plugin.releases.firstOrNull()?.date ?: plugin.publishedAt)
        val cells = buildList {
            add(cell(versionCell.first, versionCell.second, null))
            StoreScreens.formatSize(plugin.artifact.sizeBytes)?.let { add(cell("Size", it, null)) }
            updated?.let { add(cell("Updated", it, null)) }
            add(cell("Source", "Open ›", plugin.sourceUrl))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = NexusUi.bordered(this@StorePluginDetailActivity, Color.TRANSPARENT, NexusUi.LINE2, 12)
            cells.forEachIndexed { index, cellView ->
                if (index > 0) {
                    addView(
                        View(this@StorePluginDetailActivity).apply { setBackgroundColor(NexusUi.LINE2) },
                        LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                }
                addView(cellView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private fun cell(label: String, value: String, link: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        addView(
            NexusUi.metaLabel(this@StorePluginDetailActivity, label, NexusUi.INK4).apply {
                textSize = 8.5f
                letterSpacing = 0.16f
            },
        )
        addView(BusTheme.gap(this@StorePluginDetailActivity, 4))
        addView(
            NexusUi.metaLabel(
                this@StorePluginDetailActivity,
                value,
                if (link != null) NexusUi.GREEN_DIM else NexusUi.INK2,
            ).apply {
                textSize = 10.5f
                letterSpacing = 0.04f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        if (link != null) {
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(this@StorePluginDetailActivity, Color.TRANSPARENT, 12)
            setOnClickListener { openUrl(link) }
        }
    }

    private fun sectionLabelRow(label: String, action: String?, onAction: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(22), dp(9))
            addView(
                NexusUi.sectionLabel(this@StorePluginDetailActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (action != null && onAction != null) {
                addView(
                    NexusUi.metaLabel(this@StorePluginDetailActivity, action, NexusUi.GREEN_DIM).apply {
                        isClickable = true
                        isFocusable = true
                        background = NexusUi.pressed(this@StorePluginDetailActivity, Color.TRANSPARENT, 10)
                        setPadding(dp(8), dp(6), 0, dp(6))
                        setOnClickListener { onAction() }
                    },
                )
            }
        }

    private fun whatsNewCard(latest: RegistryRelease): LinearLayout = NexusUi.card(this).apply {
        StoreScreens.formatReleaseDate(latest.date)?.let { date ->
            addView(NexusUi.metaLabel(this@StorePluginDetailActivity, date, NexusUi.INK4))
            addView(BusTheme.gap(this@StorePluginDetailActivity, 8))
        }
        val notes = NexusUi.cardBody(this@StorePluginDetailActivity, "").apply {
            text = ReleaseNotesRenderer.render(this@StorePluginDetailActivity, latest.notes)
            if (!notesExpanded) {
                maxLines = COLLAPSED_NOTES_LINES
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        addView(notes, NexusUi.block())
        notes.post {
            val layout = notes.layout
            val truncated = !notesExpanded && layout != null && notes.lineCount > 0 &&
                layout.getEllipsisCount(notes.lineCount - 1) > 0
            if (truncated || notesExpanded) {
                addView(BusTheme.gap(this@StorePluginDetailActivity, 9))
                addView(
                    NexusUi.metaLabel(
                        this@StorePluginDetailActivity,
                        if (notesExpanded) "Show less" else "Read more",
                        NexusUi.GREEN_DIM,
                    ).apply {
                        isClickable = true
                        isFocusable = true
                        background = NexusUi.pressed(this@StorePluginDetailActivity, Color.TRANSPARENT, 10)
                        setPadding(0, dp(4), dp(8), dp(4))
                        setOnClickListener {
                            notesExpanded = !notesExpanded
                            render()
                        }
                    },
                )
            }
        }
    }

    private fun screenshotsRow(plugin: RegistryPlugin): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), 0, dp(22), 0)
        }
        val height = dp(150)
        plugin.screenshotUrls.forEachIndexed { index, url ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = NexusUi.bordered(this@StorePluginDetailActivity, NexusUi.PANEL, NexusUi.LINE2, 10)
                clipToOutline = true
                contentDescription = "${plugin.name} screenshot ${index + 1}"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(
                        StoreScreenshotViewerActivity.intent(
                            this@StorePluginDetailActivity,
                            plugin.screenshotUrls,
                            index,
                        ),
                    )
                }
            }
            row.addView(
                image,
                LinearLayout.LayoutParams(dp(240), height).apply {
                    if (index > 0) marginStart = dp(9)
                },
            )
            image.tag = url
            iconLoader.load(url) { bitmap ->
                if (isFinishing || isDestroyed || image.tag != url) return@load
                image.setImageBitmap(bitmap)
                if (bitmap.height > 0) {
                    image.layoutParams = (image.layoutParams as LinearLayout.LayoutParams).apply {
                        width = (height.toLong() * bitmap.width / bitmap.height).toInt()
                    }
                }
            }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun capabilityChips(plugin: RegistryPlugin): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), 0, dp(22), 0)
        }
        plugin.nexus.capabilities.forEachIndexed { index, capability ->
            row.addView(
                NexusUi.metaLabel(this, capability, NexusUi.INK2).apply {
                    letterSpacing = 0.08f
                    background = NexusUi.bordered(this@StorePluginDetailActivity, Color.TRANSPARENT, NexusUi.LINE2, 8)
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) marginStart = dp(6) },
            )
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun detailRows(entry: StoreEntry, plugin: RegistryPlugin): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@StorePluginDetailActivity, NexusUi.PANEL, NexusUi.LINE2, 13)
        val rows = buildList {
            add(Triple("Publisher", plugin.author, null))
            add(Triple("Package", plugin.artifact.packageName, null))
            entry.installedVersionCode?.let { add(Triple("Installed build", it.toString(), null)) }
            add(Triple("Source code", "GitHub ›", plugin.sourceUrl))
        }
        rows.forEachIndexed { index, (label, value, link) ->
            if (index > 0) {
                addView(
                    View(this@StorePluginDetailActivity).apply { setBackgroundColor(NexusUi.LINE2) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                )
            }
            addView(
                LinearLayout(this@StorePluginDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    addView(
                        NexusUi.rowLabel(this@StorePluginDetailActivity, label).apply { textSize = 13f },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.metaLabel(
                            this@StorePluginDetailActivity,
                            value,
                            if (link != null) NexusUi.GREEN_DIM else NexusUi.INK3,
                        ).apply {
                            letterSpacing = 0.04f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                        },
                    )
                    if (link != null) {
                        isClickable = true
                        isFocusable = true
                        background = NexusUi.pressed(this@StorePluginDetailActivity, Color.TRANSPARENT, 13)
                        setOnClickListener { openUrl(link) }
                    }
                },
                NexusUi.block(),
            )
        }
    }

    private fun missingCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.cardTitle(this@StorePluginDetailActivity, "Plugin unavailable"), NexusUi.block())
        addView(BusTheme.gap(this@StorePluginDetailActivity, 7))
        addView(
            NexusUi.cardBody(
                this@StorePluginDetailActivity,
                "This plugin is not in the catalogue right now. Pull to refresh the Store and try again.",
            ),
            NexusUi.block(),
        )
    }

    private fun uninstallTarget(entry: StoreEntry): String? {
        if (entry.localGrantState == PluginCatalogState.BUILT_IN) return null
        if (entry.state != StoreEntryState.INSTALLED &&
            entry.state != StoreEntryState.SIDELOADED &&
            entry.state != StoreEntryState.UPDATE_AVAILABLE
        ) {
            return null
        }
        return entry.localEntry?.installedPackageName()
            ?: entry.registryPlugin?.artifact?.packageName?.takeIf { entry.installedVersionCode != null }
    }

    private fun openHistory() {
        startActivity(StoreVersionHistoryActivity.intent(this, pluginId))
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bigButton(
        label: String,
        filled: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
        setAllCaps(false)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        includeFontPadding = false
        isEnabled = enabled
        setTextColor(
            when {
                !enabled -> NexusUi.INK4
                filled -> NexusUi.ON_ACCENT
                else -> NexusUi.GREEN
            },
        )
        background = if (filled && enabled) {
            NexusUi.rounded(this@StorePluginDetailActivity, NexusUi.GREEN, 12)
        } else {
            NexusUi.bordered(
                this@StorePluginDetailActivity,
                Color.TRANSPARENT,
                if (enabled) 0xFF2C4A37.toInt() else NexusUi.LINE2,
                12,
            )
        }
        setOnClickListener { onClick() }
    }

    private fun pad(params: LinearLayout.LayoutParams): LinearLayout.LayoutParams = params.apply {
        marginStart = dp(22)
        marginEnd = dp(22)
    }

    private fun dp(value: Int): Int = NexusUi.dp(this, value)

    companion object {
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val COLLAPSED_NOTES_LINES = 7

        /** The listing markdown often opens with its own "About" heading; ours is already on screen. */
        private fun stripLeadingAboutHeading(markdown: String): String {
            val lines = markdown.lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0) return markdown
            if (!Regex("^#{1,6}\\s*about\\s*$", RegexOption.IGNORE_CASE).matches(lines[first].trim())) {
                return markdown
            }
            return lines.filterIndexed { index, _ -> index != first }.joinToString("\n")
        }
        private val REVIEW_STATES = setOf(
            PluginCatalogState.PENDING,
            PluginCatalogState.DENIED,
            PluginCatalogState.DISABLED,
            PluginCatalogState.MISSING_CAPABILITY,
            PluginCatalogState.INVALID,
        )

        fun intent(context: Context, pluginId: String): Intent =
            Intent(context, StorePluginDetailActivity::class.java).putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
