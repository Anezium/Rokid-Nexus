package com.anezium.rokidbus.plugin.nav

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * Starts the wearer's own walking journeys, and holds the switches for following other apps'
 * guidance (all of it, or one app at a time) with the one thing that needs: Notification Access.
 * Each asks for its Android permission only at the moment it is used.
 */
class NavSettingsActivity : Activity() {
    private lateinit var accessValue: TextView
    private lateinit var accessSub: TextView
    private lateinit var accessAction: TextView
    private lateinit var accessChevron: TextView
    private lateinit var routeRow: View
    private lateinit var routeDot: View
    private lateinit var routeStatus: TextView
    private val sourceSwitches = mutableMapOf<NavSource, Switch>()
    private lateinit var journeyPanel: NavJourneyPanel
    private val main = Handler(Looper.getMainLooper())
    private val rebind = NavListenerRebind(rebindRequest)

    // Only while the screen is in front: the journey reports through the shared state.
    private val refresh = object : Runnable {
        override fun run() {
            journeyPanel.refresh()
            refreshAccess()
            refreshRoute()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@NavSettingsActivity, getString(R.string.nav_settings_walk)), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 10))
            addView(NavJourneyPanel(this@NavSettingsActivity).also { journeyPanel = it }, NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@NavSettingsActivity, getString(R.string.nav_settings_glasses)), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 10))
            addView(switchesCard(), NexusUi.block())
            addView(BusTheme.gap(this@NavSettingsActivity, 8))
            addView(accessCard(), NexusUi.block())
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
        // Access granted and guidance on but the listener not connected is Android's to fix:
        // one request per visit here, and the line below says what the listener really is.
        rebind.onEnter()
        rebind.consider(
            ComponentName(this, NavNotificationListener::class.java),
            accessGranted = accessGranted(),
            guidanceEnabled = NavSettings(this).switches().enabled,
            connected = NavState.listenerConnected,
        )
        refreshAccess()
        refreshRoute()
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    /** What the listener really is: allowed and connected, allowed but not connected yet, or not allowed. */
    private fun refreshAccess() {
        val granted = accessGranted()
        val connected = granted && NavState.listenerConnected
        accessValue.text = getString(
            when {
                !granted -> R.string.nav_settings_access_off
                connected -> R.string.nav_settings_access_on
                else -> R.string.nav_settings_access_connecting
            },
        )
        accessValue.setTextColor(if (connected) NexusUi.GREEN_DIM else NexusUi.AMBER)
        accessSub.text = getString(R.string.nav_settings_access_sub) +
            if (granted && !connected) "\n\n" + getString(R.string.nav_settings_access_connecting_sub) else ""
        accessAction.text = getString(
            if (granted) R.string.nav_settings_access_action_manage else R.string.nav_settings_access_action_allow,
        ).uppercase()
        val actionColor = if (granted) NexusUi.INK3 else NexusUi.GREEN
        accessAction.setTextColor(actionColor)
        accessChevron.setTextColor(if (granted) NexusUi.INK4 else NexusUi.GREEN)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        journeyPanel.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NavJourneyPanel.LOCATION_REQUEST) journeyPanel.permissionResult()
    }

    /**
     * The route line lives inside the access card and only appears when there is something to
     * say: a live route from another app, or the plugin still waiting for approval in the hub.
     * Switched off, or simply idle, it stays out of the way.
     */
    private fun refreshRoute() {
        val guidance = NavState.guidance?.takeUnless { it.source == NavSource.NEXUS }
        val registration = NavState.registration
        val wanted = NavControl.journeyActive || NavSettings(this).switches().enabled
        val live = wanted && guidance != null
        val notApproved = wanted && registration != null && registration != PluginRegistrationResult.APPROVED
        when {
            live -> {
                routeStatus.text = getString(
                    R.string.nav_settings_route_live,
                    guidance!!.source.label,
                    listOfNotNull(guidance.primary, guidance.secondary).joinToString(" · "),
                )
                routeStatus.setTextColor(NexusUi.INK2)
                NexusUi.setDotColor(routeDot, NexusUi.GREEN)
                routeRow.visibility = View.VISIBLE
            }
            notApproved -> {
                routeStatus.text = getString(R.string.nav_settings_not_approved)
                routeStatus.setTextColor(NexusUi.AMBER)
                NexusUi.setDotColor(routeDot, NexusUi.AMBER)
                routeRow.visibility = View.VISIBLE
            }
            else -> routeRow.visibility = View.GONE
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
            Triple(NavSource.ORGANIC_MAPS, current.organicMaps, R.string.nav_settings_organic_sub),
            Triple(NavSource.OSMAND, current.osmand, R.string.nav_settings_osmand_sub),
            Triple(NavSource.YANDEX_MAPS, current.yandexMaps, R.string.nav_settings_yandex_sub),
            Triple(NavSource.MAPS_ME, current.mapsMe, R.string.nav_settings_maps_me_sub),
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

    /**
     * One pressable card for Notification Access: title, the listener's real state, the full
     * privacy explanation (never clipped), the live route or approval line when there is one,
     * and an ALLOW / MANAGE action with a chevron so the tap is obviously a permission step.
     */
    private fun accessCard(): LinearLayout = NexusUi.pressableCard(this).apply {
        val ctx = this@NavSettingsActivity
        contentDescription = getString(R.string.nav_settings_access_title)
        setOnClickListener { openAccessSettings() }
        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(ctx, getString(R.string.nav_settings_access_title)))
                addView(BusTheme.gap(ctx, 4))
                addView(
                    NexusUi.rowSub(ctx, "").apply {
                        maxLines = 2
                        ellipsize = null
                    }.also { accessValue = it },
                    NexusUi.block(),
                )
                addView(BusTheme.gap(ctx, 8))
                // Body copy, not a one-line sub: the privacy explanation has to wrap in full.
                addView(NexusUi.cardBody(ctx, "").also { accessSub = it }, NexusUi.block())
                addView(routeLine(), NexusUi.block())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(NexusUi.rowSub(ctx, "").also { accessAction = it })
                addView(
                    NexusUi.chevron(ctx).also { accessChevron = it },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = NexusUi.dp(ctx, 4) },
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = NexusUi.dp(ctx, 12) },
        )
    }

    /** Dot plus status text; hidden until [refreshRoute] has a live route or an approval error to show. */
    private fun routeLine(): LinearLayout = LinearLayout(this).apply {
        val ctx = this@NavSettingsActivity
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, NexusUi.dp(ctx, 10), 0, 0)
        visibility = View.GONE
        addView(
            NexusUi.dot(ctx).also { routeDot = it },
            LinearLayout.LayoutParams(NexusUi.dp(ctx, 7), NexusUi.dp(ctx, 7)).apply {
                marginEnd = NexusUi.dp(ctx, 8)
            },
        )
        addView(
            NexusUi.rowSub(ctx, "").apply {
                maxLines = 3
                ellipsize = null
            }.also { routeStatus = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        routeRow = this
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

    internal companion object {
        const val REFRESH_MS = 1_000L

        /** The platform's rebind request; replaced by tests that record it instead. */
        var rebindRequest: (ComponentName) -> Unit = NotificationListenerService::requestRebind
    }
}
