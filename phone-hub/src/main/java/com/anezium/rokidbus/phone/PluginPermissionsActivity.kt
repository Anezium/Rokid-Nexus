package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.plugin.PluginCapability

class PluginPermissionsActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var grantStore: PluginGrantStore
    private var developerDetails = false
    private var focusedTarget: PluginGrantTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grantStore = PluginGrantStore(applicationContext)
        focusedTarget = intent?.let(::targetFromIntent)
        developerDetails = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_DEVELOPER_DETAILS, false)
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

        val allCandidates = PhonePluginDiscovery(packageManager).discover()
        val valid = allCandidates.mapNotNull { (it as? PhonePluginCandidate.Valid)?.principal }
        grantStore.reconcile(valid)
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
        card.addView(titleRow(candidate.displayName, "Invalid", NexusUi.DANGER), NexusUi.block())
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
        card.addView(titleRow(principal.descriptor.displayName, stateLabel, stateColor), NexusUi.block())
        card.addView(BusTheme.gap(this, 4))
        card.addView(NexusUi.metaLabel(this, "Unverified installed plugin", NexusUi.INK3))
        card.addView(BusTheme.gap(this, 12))

        val selected = ((state as? PluginGrantState.Approved)?.capabilities ?: emptySet()).toMutableSet()
        principal.descriptor.requestedCapabilities.sortedBy(PluginCapability::wireValue).forEach { capability ->
            card.addView(capabilityToggle(capability, selected), NexusUi.block())
            card.addView(BusTheme.gap(this, 6))
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

        card.addView(BusTheme.gap(this, 12))
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(actionButton("Approve") {
                    grantStore.approve(principal, selected)
                    BusHubService.onPluginAuthorizationChanged(applicationContext, principal.grantKey())
                    render()
                })
                addView(actionButton("Deny", danger = true) {
                    grantStore.deny(principal)
                    BusHubService.onPluginAuthorizationChanged(applicationContext, principal.grantKey())
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = NexusUi.dp(this@PluginPermissionsActivity, 8)
                })
                if (state !is PluginGrantState.Pending) {
                    addView(actionButton("Revoke", danger = true) {
                        grantStore.revoke(principal)
                        BusHubService.onPluginAuthorizationChanged(applicationContext, principal.grantKey())
                        render()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = NexusUi.dp(this@PluginPermissionsActivity, 8)
                    })
                }
            },
            NexusUi.block(),
        )
    }

    private fun capabilityToggle(
        capability: PluginCapability,
        selected: MutableSet<PluginCapability>,
    ): CheckBox {
        val unavailable = capability == PluginCapability.MICROPHONE
        if (unavailable) selected -= capability
        return CheckBox(this).apply {
            text = when (capability) {
                PluginCapability.SURFACES -> "Show surfaces on your glasses"
                PluginCapability.MICROPHONE -> "Use the glasses microphone\nRequires Nexus microphone indicator support"
                PluginCapability.HTTP_PROXY -> "Use the Nexus phone HTTP proxy"
            }
            textSize = 13f
            setTextColor(if (unavailable) NexusUi.INK3 else NexusUi.INK2)
            buttonTintList = android.content.res.ColorStateList.valueOf(NexusUi.GREEN)
            isChecked = capability in selected && !unavailable
            isEnabled = !unavailable
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected += capability else selected -= capability
            }
        }
    }

    private fun titleRow(title: String, state: String, stateColor: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(NexusUi.cardTitle(this@PluginPermissionsActivity, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexusUi.metaLabel(this@PluginPermissionsActivity, state, stateColor))
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

    private fun actionButton(label: String, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(if (danger) NexusUi.DANGER else NexusUi.GREEN)
            background = NexusUi.bordered(this@PluginPermissionsActivity, Color.TRANSPARENT, if (danger) NexusUi.DANGER else NexusUi.GREEN, 18)
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding(NexusUi.dp(this@PluginPermissionsActivity, 12), 0, NexusUi.dp(this@PluginPermissionsActivity, 12), 0)
            setOnClickListener { onClick() }
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
