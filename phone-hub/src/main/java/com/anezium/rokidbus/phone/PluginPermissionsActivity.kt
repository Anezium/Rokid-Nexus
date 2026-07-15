package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.plugin.PluginCapability

class PluginPermissionsActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var grantStore: PluginGrantStore
    private lateinit var grantReconciler: PluginGrantReconciler
    private var developerDetails = false
    private var focusedTarget: PluginGrantTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grantStore = PluginGrantStore(applicationContext)
        grantReconciler = PluginGrantReconciler(
            discoverCandidates = PhonePluginDiscovery(packageManager)::discover,
            reconcileGrants = grantStore::reconcile,
        )
        focusedTarget = intent?.let(::targetFromIntent)
        focusedTarget?.let { PluginInstallRecoveryStore(applicationContext).clearSuccess(it) }
        developerDetails = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_DEVELOPER_DETAILS, DeveloperModeStore(applicationContext).isEnabled())
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(header(), NexusUi.block())
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun render() {
        content.removeAllViews()
        content.addView(
            NexusUi.cardBody(
                this,
                "Installed plugins stay off until you approve the access they request.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 14))
        content.addView(developerToggle(), NexusUi.block())
        content.addView(BusTheme.gap(this, 22))

        val allCandidates = grantReconciler.reconcile().candidates
        val candidates = focusedTarget?.let { target ->
            allCandidates.filter { candidate ->
                when (candidate) {
                    is PhonePluginCandidate.Valid -> target.matches(candidate.principal)
                    is PhonePluginCandidate.Invalid -> candidate.packageName == target.packageName
                }
            }
        } ?: allCandidates
        if (candidates.isEmpty()) {
            content.addView(
                NexusUi.navCard(this, "No plugins installed", "Install a Nexus phone plugin to review its access."),
                NexusUi.block(),
            )
            return
        }
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) content.addView(BusTheme.gap(this, 10))
            content.addView(candidateCard(candidate), NexusUi.block())
        }
    }

    private fun candidateCard(candidate: PhonePluginCandidate): LinearLayout =
        NexusUi.card(this).apply {
            when (candidate) {
                is PhonePluginCandidate.Invalid -> renderInvalid(this, candidate)
                is PhonePluginCandidate.Valid -> renderValid(this, candidate.principal)
            }
        }

    private fun renderInvalid(card: LinearLayout, candidate: PhonePluginCandidate.Invalid) {
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.cardTitle(this@PluginPermissionsActivity, candidate.displayName),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(statePill("Invalid", NexusUi.DANGER))
            },
            NexusUi.block(),
        )
        card.addView(BusTheme.gap(this, 8))
        card.addView(NexusUi.cardBody(this, "Nexus cannot use this plugin descriptor."), NexusUi.block())
        if (developerDetails) {
            card.addView(BusTheme.gap(this, 10))
            card.addView(developerText("Package: ${candidate.packageName}\nService: ${candidate.serviceComponent?.flattenToShortString().orEmpty()}\nReason: ${candidate.reason}"))
        }
    }

    private fun renderValid(card: LinearLayout, principal: PhonePluginPrincipal) {
        val state = grantStore.stateFor(principal)
        val stateLabel = when (state) {
            PluginGrantState.Pending -> "Pending"
            PluginGrantState.Denied -> "Denied"
            PluginGrantState.Disabled -> "Disabled"
            is PluginGrantState.Approved -> "Approved"
        }
        val stateColor = when (state) {
            is PluginGrantState.Approved -> NexusUi.GREEN
            PluginGrantState.Denied -> NexusUi.DANGER
            PluginGrantState.Disabled -> NexusUi.INK3
            PluginGrantState.Pending -> NexusUi.AMBER
        }
        val catalogEntry = BusHubService.pluginCatalog(this).entries.singleOrNull { entry ->
            entry.principal?.packageName == principal.packageName && entry.id == principal.descriptor.id
        }
        val provenanceLabel = if (
            catalogEntry?.provenance == PluginProvenance.REGISTRY && catalogEntry.registryAuthor != null
        ) {
            "Verified · ${catalogEntry.registryAuthor}"
        } else {
            "Unverified installed plugin"
        }
        card.addView(
            pluginHeaderRow(principal.descriptor.id, principal.descriptor.displayName, provenanceLabel, stateLabel, stateColor),
            NexusUi.block(),
        )
        card.addView(BusTheme.gap(this, 14))

        val live = state is PluginGrantState.Approved
        val selected = ((state as? PluginGrantState.Approved)?.capabilities
            ?: principal.descriptor.requestedCapabilities).toMutableSet()
        selected -= PluginCapability.MICROPHONE
        principal.descriptor.requestedCapabilities.sortedBy(PluginCapability::wireValue).forEach { capability ->
            card.addView(capabilityRow(capability, selected, live, principal), NexusUi.block())
            card.addView(BusTheme.gap(this, 8))
        }

        if (developerDetails) {
            card.addView(BusTheme.gap(this, 6))
            card.addView(
                developerText(
                    "Package: ${principal.packageName}\n" +
                        "Service: ${principal.serviceComponent.flattenToShortString()}\n" +
                        "Plugin ID: ${principal.descriptor.id}\n" +
                        "API: ${principal.descriptor.apiVersion}\n" +
                        "Signer SHA-256: ${principal.signingDigestSha256}\n" +
                        "Receive: ${principal.descriptor.receivePrefixes.joinToString()}",
                ),
            )
        }

        card.addView(BusTheme.gap(this, 6))
        if (live) {
            // Approved: capability switches apply live; the only remaining action is quiet.
            card.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.textButton(this@PluginPermissionsActivity, "Revoke access", danger = true).apply {
                            setOnClickListener { applyDecision(principal) { grantStore.revoke(principal) } }
                        },
                    )
                },
                NexusUi.block(),
            )
        } else {
            card.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.pillButton(this@PluginPermissionsActivity, "Allow on glasses").apply {
                            setOnClickListener { applyDecision(principal) { grantStore.approve(principal, selected) } }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@PluginPermissionsActivity, "Deny", danger = true).apply {
                            setOnClickListener { applyDecision(principal) { grantStore.deny(principal) } }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = NexusUi.dp(this@PluginPermissionsActivity, 10) },
                    )
                },
                NexusUi.block(),
            )
        }
    }

    private fun applyDecision(principal: PhonePluginPrincipal, decision: () -> Unit) {
        decision()
        BusHubService.onPluginAuthorizationChanged(applicationContext, principal.grantKey())
        render()
    }

    private fun capabilityRow(
        capability: PluginCapability,
        selected: MutableSet<PluginCapability>,
        live: Boolean,
        principal: PhonePluginPrincipal,
    ): LinearLayout {
        val unavailable = capability == PluginCapability.MICROPHONE
        val title = when (capability) {
            PluginCapability.SURFACES -> "Show on your glasses"
            PluginCapability.MICROPHONE -> "Glasses microphone"
            PluginCapability.HTTP_PROXY -> "Nexus HTTP proxy"
            PluginCapability.CAMERA -> "Glasses camera"
        }
        val note = when (capability) {
            PluginCapability.SURFACES -> "Render cards and images on the HUD"
            PluginCapability.MICROPHONE -> "Not available yet"
            PluginCapability.HTTP_PROXY -> "Fetch through the phone connection"
            PluginCapability.CAMERA -> "Only while the camera view is open"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                LinearLayout(this@PluginPermissionsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@PluginPermissionsActivity).apply {
                            text = title
                            textSize = 13f
                            setTextColor(if (unavailable) NexusUi.INK3 else NexusUi.INK)
                        },
                    )
                    addView(
                        TextView(this@PluginPermissionsActivity).apply {
                            text = note
                            textSize = 10f
                            setTextColor(NexusUi.INK3)
                        },
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                Switch(this@PluginPermissionsActivity).apply {
                    isChecked = capability in selected && !unavailable
                    isEnabled = !unavailable
                    thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                    )
                    trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(NexusUi.GREEN_DIM, NexusUi.LINE),
                    )
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected += capability else selected -= capability
                        if (live) applyDecision(principal) { grantStore.approve(principal, selected.toSet()) }
                    }
                },
            )
        }
    }

    private fun pluginHeaderRow(
        pluginId: String,
        title: String,
        provenance: String,
        state: String,
        stateColor: Int,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                NexusUi.iconTileImage(this@PluginPermissionsActivity, iconFor(pluginId), sizeDp = 30),
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@PluginPermissionsActivity, 30),
                    NexusUi.dp(this@PluginPermissionsActivity, 30),
                ),
            )
            addView(
                LinearLayout(this@PluginPermissionsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.cardTitle(this@PluginPermissionsActivity, title))
                    addView(BusTheme.gap(this@PluginPermissionsActivity, 2))
                    addView(NexusUi.metaLabel(this@PluginPermissionsActivity, provenance, NexusUi.INK3))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = NexusUi.dp(this@PluginPermissionsActivity, 11)
                },
            )
            addView(statePill(state, stateColor))
        }

    private fun statePill(label: String, color: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(color)
            background = NexusUi.bordered(
                this@PluginPermissionsActivity,
                NexusUi.alpha(color, 22),
                NexusUi.alpha(color, 80),
                9,
            )
            setPadding(
                NexusUi.dp(this@PluginPermissionsActivity, 8),
                NexusUi.dp(this@PluginPermissionsActivity, 3),
                NexusUi.dp(this@PluginPermissionsActivity, 8),
                NexusUi.dp(this@PluginPermissionsActivity, 3),
            )
        }

    private fun iconFor(id: String): Int = when (id) {
        "lyrics" -> BusClientR.drawable.ic_plugin_music
        "media" -> BusClientR.drawable.ic_plugin_disc
        "transit" -> BusClientR.drawable.ic_plugin_bus
        "lens" -> BusClientR.drawable.ic_plugin_lens
        else -> BusClientR.drawable.ic_plugin_send
    }

    private fun developerToggle(): CheckBox = CheckBox(this).apply {
        text = "Developer details"
        textSize = 13f
        setTextColor(NexusUi.INK2)
        buttonTintList = android.content.res.ColorStateList.valueOf(NexusUi.GREEN)
        isChecked = developerDetails
        setOnCheckedChangeListener { _, enabled ->
            developerDetails = enabled
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(KEY_DEVELOPER_DETAILS, enabled)
                .apply()
            render()
        }
    }

    private fun developerText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(NexusUi.INK3)
        setTextIsSelectable(true)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(NexusUi.dp(this@PluginPermissionsActivity, 14), NexusUi.dp(this@PluginPermissionsActivity, 12), NexusUi.dp(this@PluginPermissionsActivity, 22), NexusUi.dp(this@PluginPermissionsActivity, 12))
        addView(NexusUi.metaLabel(this@PluginPermissionsActivity, "‹", NexusUi.INK).apply {
            textSize = 26f
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            setPadding(0, 0, NexusUi.dp(this@PluginPermissionsActivity, 18), 0)
        })
        addView(NexusUi.metaLabel(this@PluginPermissionsActivity, "Plugin access", NexusUi.INK).apply {
            textSize = 12f
            letterSpacing = 0.16f
        })
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "plugin_package_name"
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val PREFERENCES = "plugin_access_ui"
        private const val KEY_DEVELOPER_DETAILS = "developer_details"

        fun intent(context: Context, target: PluginGrantTarget): Intent =
            Intent(context, PluginPermissionsActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, target.packageName)
                .putExtra(EXTRA_PLUGIN_ID, target.pluginId)

        private fun targetFromIntent(intent: Intent): PluginGrantTarget? {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
            val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
            return if (packageName.isBlank() || pluginId.isBlank()) null else PluginGrantTarget(packageName, pluginId)
        }
    }
}
