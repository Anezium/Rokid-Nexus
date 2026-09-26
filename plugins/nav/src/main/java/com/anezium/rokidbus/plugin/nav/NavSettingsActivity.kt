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
import android.widget.TextView
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The one thing Navigation needs from the wearer: Notification Access, so it
 * can read the guidance Google Maps and Citymapper already post.
 */
class NavSettingsActivity : Activity() {
    private lateinit var accessStatus: TextView
    private lateinit var routeStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this).apply {
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
                    R.drawable.nexus_glyph_nav,
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
        val guidance = NavState.guidance
        routeStatus.text = when {
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
