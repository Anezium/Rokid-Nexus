package com.anezium.rokidbus.plugin.nav

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * Navigation's switches (all of it, or one app at a time) and the one thing it
 * needs from the wearer: Notification Access, so it can read the guidance
 * Google Maps and Citymapper already post.
 */
class NavSettingsActivity : Activity() {
    private lateinit var accessStatus: TextView
    private lateinit var routeStatus: TextView
    private val sourceSwitches = mutableMapOf<NavSource, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@NavSettingsActivity, getString(R.string.nav_settings_glasses)), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 10))
            addView(switchesCard(), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@NavSettingsActivity, getString(R.string.nav_settings_access)), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 10))
            addView(accessCard(), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 8))
            addView(NexusUi.statusLine(this@NavSettingsActivity).also { accessStatus = it }, NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@NavSettingsActivity, getString(R.string.nav_settings_route)), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 10))
            addView(
                NexusUi.cardBody(this@NavSettingsActivity, getString(R.string.nav_settings_how)),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@NavSettingsActivity, 8))
            addView(NexusUi.statusLine(this@NavSettingsActivity).also { routeStatus = it }, NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@NavSettingsActivity, getString(R.string.nav_settings_plugin)), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 10))
            addView(
                NexusUi.uninstallCard(this@NavSettingsActivity, getString(R.string.app_name)) {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@NavSettingsActivity,
                    R.drawable.nexus_glyph_route,
                    getString(R.string.app_name),
                    getString(R.string.nav_settings_subtitle),
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@NavSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        accessStatus.text = getString(
            if (accessGranted()) R.string.nav_settings_access_on else R.string.nav_settings_access_off,
        )
        refreshRoute()
    }

    private fun refreshRoute() {
        val guidance = NavState.guidance
        routeStatus.text = when {
            !NavSettings(this).switches().enabled -> getString(R.string.nav_settings_route_off)
            guidance != null -> getString(
                R.string.nav_settings_route_live,
                guidance.source.label,
                listOfNotNull(guidance.primary, guidance.secondary).joinToString(" · "),
            )
            NavState.registration != null && NavState.registration != PluginRegistrationResult.APPROVED ->
                getString(R.string.nav_settings_not_approved)
            else -> getString(R.string.nav_settings_route_idle)
        }
    }

    /**
     * Off, a switch ends that app's live route on the glasses at once; nothing
     * has to be uninstalled to keep an app's guidance off the HUD.
     */
    private fun switchesCard(): LinearLayout = NexusUi.card(this).apply {
        orientation = LinearLayout.VERTICAL
        val settings = NavSettings(this@NavSettingsActivity)
        val current = settings.switches()
        val master = NexusUi.switch(this@NavSettingsActivity).apply { isChecked = current.enabled }
        addView(
            NexusUi.switchRow(
                this@NavSettingsActivity,
                getString(R.string.nav_settings_enabled),
                getString(R.string.nav_settings_enabled_sub),
                master,
            ),
        )
        listOf(
            Triple(NavSource.GOOGLE_MAPS, current.googleMaps, R.string.nav_settings_maps_sub),
            Triple(NavSource.CITYMAPPER, current.citymapper, R.string.nav_settings_citymapper_sub),
        ).forEach { (source, checked, sub) ->
            val control = NexusUi.switch(this@NavSettingsActivity).apply {
                isChecked = checked
                isEnabled = current.enabled
                setOnCheckedChangeListener { _, value ->
                    settings.setSource(source, value)
                    NavControl.settingsChanged()
                    refreshRoute()
                }
            }
            sourceSwitches[source] = control
            addView(BusTheme.gap(this@NavSettingsActivity, 12))
            addView(NexusUi.divider(this@NavSettingsActivity))
            addView(BusTheme.gap(this@NavSettingsActivity, 12))
            addView(NexusUi.switchRow(this@NavSettingsActivity, source.label, getString(sub), control))
        }
        master.setOnCheckedChangeListener { _, value ->
            settings.setEnabled(value)
            sourceSwitches.values.forEach { it.isEnabled = value }
            NavControl.settingsChanged()
            refreshRoute()
        }
    }

    private fun accessCard(): LinearLayout = NexusUi.pressableCard(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(NexusUi.rowTitle(this@NavSettingsActivity, getString(R.string.nav_settings_access_title)))
        addView(NexusUi.rowSub(this@NavSettingsActivity, getString(R.string.nav_settings_access_sub)))
        setOnClickListener { openAccessSettings() }
    }

    private fun accessGranted(): Boolean = runCatching {
        getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(this, NavNotificationListener::class.java)) == true
    }.getOrDefault(false)

    private fun openAccessSettings() {
        val component = ComponentName(this, NavNotificationListener::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        runCatching { startActivity(detail) }
            .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
}
