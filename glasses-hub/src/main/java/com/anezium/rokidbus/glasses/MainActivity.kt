package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.BusConstants

class MainActivity : Activity() {
    private lateinit var emptyView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var launcherViewport: FrameLayout
    private lateinit var launcherView: View
    private lateinit var onboardingView: View
    private lateinit var onboardingStepView: TextView
    private lateinit var onboardingTitleView: TextView
    private lateinit var onboardingBodyView: TextView
    private lateinit var onboardingActionView: TextView
    private var launcherEntries: List<GlassesHub.LauncherEntry> = emptyList()
    private var selectedIndex = 0
    private var scrollOffset = 0
    private var onboardingState = SelfArmOnboardingState(
        stage = SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY,
        action = SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY,
        detail = "",
    )
    private var unsubscribeLauncher: (() -> Unit)? = null
    private var onboardingReceiverRegistered = false
    private val swipeDedupe = DpadPairDedupe()
    private val onboardingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SelfArmOnboardingStore.ACTION_CHANGED) renderScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BusTheme.glassesBg
        window.navigationBarColor = BusTheme.glassesBg
        buildUi()
        requestBluetoothConnectIfNeeded()
        GlassesHub.start(applicationContext)
        unsubscribeLauncher = GlassesHub.observeLauncher { entries ->
            // The hub notifies listeners from the CXR receive thread. Touching views off the main
            // thread throws (swallowed by the hub's runCatching), so a launcher list that arrives
            // while this activity is already up would silently never render. Marshal to the UI.
            runOnUiThread {
                launcherEntries = entries
                selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                renderLauncher()
            }
        }
        log("Launcher activity opened")
    }

    override fun onStart() {
        super.onStart()
        if (!onboardingReceiverRegistered) {
            val filter = IntentFilter(SelfArmOnboardingStore.ACTION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(onboardingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(onboardingReceiver, filter)
            }
            onboardingReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        SelfArmOnboardingStore.refreshNetworkPosture(applicationContext)
        renderScreen()
    }

    override fun onStop() {
        if (onboardingReceiverRegistered) {
            onboardingReceiverRegistered = false
            unregisterReceiver(onboardingReceiver)
        }
        super.onStop()
    }

    override fun onDestroy() {
        unsubscribeLauncher?.invoke()
        unsubscribeLauncher = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        val direction = swipeDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)
        if (onboardingState.stage != SelfArmOnboardingState.Stage.COMPLETE) {
            if (direction != null) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                -> true
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                -> {
                    performOnboardingAction()
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }
        when (direction) {
            DpadPairDedupe.Direction.FORWARD -> {
                moveSelection(1)
                return true
            }
            DpadPairDedupe.Direction.BACKWARD -> {
                moveSelection(-1)
                return true
            }
            null -> Unit
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> true
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                openSelected()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun buildUi() {
        emptyView = text(17f, BusTheme.dim).apply {
            text = "No phone plugins synced"
            gravity = Gravity.CENTER
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        launcherViewport = FrameLayout(this).apply {
            setBackgroundColor(BusTheme.glassesBg)
            // The list can be taller than this viewport; it's scrolled via
            // translationY and clipped here. No ScrollView (its layers dither
            // grey grain on the AR waveguide).
            addView(
                listContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
        }
        val launcherListViewport = launcherViewport
        launcherView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(BusTheme.glassesBg)
            setPadding(dp(22), dp(20), dp(22), dp(16))
            addView(text(12f, BusTheme.phosphor, bold = true).apply {
                text = "ROKID NEXUS"
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(gap(20))
            addView(text(24f, BusTheme.text, bold = true).apply {
                text = "Launcher"
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(gap(22))
            addView(text(10.5f, BusTheme.dim).apply {
                text = "PLUGINS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(10))
            addView(launcherListViewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        onboardingStepView = text(11f, BusTheme.phosphor, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        onboardingTitleView = text(23f, BusTheme.text, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        onboardingBodyView = text(15f, BusTheme.muted).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.18f)
        }
        onboardingActionView = text(17f, BusTheme.phosphor, bold = true).apply {
            minHeight = dp(58)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = outline(true)
        }
        onboardingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(BusTheme.glassesBg)
            setPadding(dp(24), dp(28), dp(24), dp(22))
            addView(onboardingStepView, matchWrap())
            addView(gap(22))
            addView(onboardingTitleView, matchWrap())
            addView(gap(24))
            addView(onboardingBodyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(onboardingActionView, matchWrap())
            addView(gap(10))
            addView(text(11f, BusTheme.dim).apply {
                text = "Swipe: focus  •  Tap: select  •  Back: exit"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BusTheme.glassesBg)
            addView(
                launcherView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                onboardingView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        })
        renderScreen()
        renderLauncher()
    }

    private fun renderScreen() {
        if (!::launcherView.isInitialized) return
        val snapshot = SelfArmOnboardingStore.snapshot(applicationContext)
        onboardingState = SelfArmOnboardingStateMachine.evaluate(snapshot)
        val complete = onboardingState.stage == SelfArmOnboardingState.Stage.COMPLETE
        launcherView.visibility = if (complete) View.VISIBLE else View.GONE
        onboardingView.visibility = if (complete) View.GONE else View.VISIBLE
        if (complete) return

        when (onboardingState.stage) {
            SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY -> {
                onboardingStepView.text = "FIRST-RUN SETUP  •  1/2"
                onboardingTitleView.text = "Turn on Rokid Nexus Glasses"
                onboardingBodyView.text =
                    "Tap Open settings, then switch on “Rokid Nexus Glasses” in the list.\n\n" +
                    "Nexus brings you right back here the moment you do — no need to press Back."
                onboardingActionView.text = "OPEN SETTINGS"
            }
            SelfArmOnboardingState.Stage.READY_FOR_WIRELESS -> {
                onboardingStepView.text = "FIRST-RUN SETUP  •  2/2"
                onboardingTitleView.text = "Finish setup"
                onboardingBodyView.text =
                    "Nexus takes it from here — it secures the connection and locks the setup " +
                    "down on its own. Just keep the glasses on for a few seconds.\n\n" +
                    "Everything stays on-device over an encrypted link."
                onboardingActionView.text = "FINISH SETUP"
            }
            SelfArmOnboardingState.Stage.RUNNING -> {
                onboardingStepView.text = "ARMING SECURELY"
                onboardingTitleView.text = "Almost there"
                onboardingBodyView.text =
                    "Nexus is finishing the secure setup. Keep the glasses on — this only " +
                    "takes a few seconds.\n\n" +
                    humanSetupState(onboardingState.detail)
                onboardingActionView.text = "WORKING…"
            }
            SelfArmOnboardingState.Stage.FAILED -> {
                onboardingStepView.text = "SETUP HIT A SNAG"
                onboardingTitleView.text = "Let’s try again"
                onboardingBodyView.text =
                    humanSetupState(onboardingState.detail) + "\n\n" +
                    "Tap retry — Nexus will reopen the secure setup and finish arming."
                onboardingActionView.text = "RETRY"
            }
            SelfArmOnboardingState.Stage.UNSUPPORTED -> {
                onboardingStepView.text = "SETUP UNAVAILABLE"
                onboardingTitleView.text = "Android 11 required"
                onboardingBodyView.text =
                    "This firmware does not provide the Wireless Debugging pairing API. Use the " +
                    "documented ADB pm grant fallback."
                onboardingActionView.text = "NO WIRELESS SETUP"
            }
            SelfArmOnboardingState.Stage.COMPLETE -> Unit
        }
        onboardingActionView.alpha =
            if (onboardingState.action == SelfArmOnboardingState.Action.NONE) 0.45f else 1f
    }

    private fun renderLauncher() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val entries = launcherEntries
        if (entries.isEmpty()) {
            listContainer.translationY = 0f
            listContainer.addView(emptyView, matchWrap())
            return
        }
        entries.forEachIndexed { index, entry ->
            listContainer.addView(
                pluginRow(entry, selected = index == selectedIndex),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PLUGIN_ROW_HEIGHT_DP)).apply {
                    topMargin = if (index == 0) 0 else dp(PLUGIN_ROW_MARGIN_DP)
                },
            )
        }
        val n = entries.size
        // Force the exact content height so it isn't clamped to the viewport
        // (a WRAP_CONTENT child gets measured AT_MOST the parent height).
        (listContainer.layoutParams as FrameLayout.LayoutParams).height =
            n * dp(PLUGIN_ROW_HEIGHT_DP) + (n - 1) * dp(PLUGIN_ROW_MARGIN_DP)
        listContainer.requestLayout()
        launcherViewport.post { scrollToSelected() }
    }

    private fun scrollToSelected() {
        if (!::launcherViewport.isInitialized || !::listContainer.isInitialized) return
        val viewport = launcherViewport.height
        if (viewport <= 0) {
            launcherViewport.post { scrollToSelected() }
            return
        }
        val n = launcherEntries.size
        val content =
            if (n == 0) 0 else n * dp(PLUGIN_ROW_HEIGHT_DP) + (n - 1) * dp(PLUGIN_ROW_MARGIN_DP)
        val maxOffset = (content - viewport).coerceAtLeast(0)
        val stride = dp(PLUGIN_ROW_HEIGHT_DP) + dp(PLUGIN_ROW_MARGIN_DP)
        val selTop = selectedIndex * stride
        val selBottom = selTop + dp(PLUGIN_ROW_HEIGHT_DP)
        // Scroll ONLY when the selected row is off-screen, and only by the
        // minimum needed — the list stays put while the selection is visible,
        // then jumps once when you reach a row past the fold (e.g. the last one).
        var offset = scrollOffset
        if (selTop < offset) offset = selTop
        else if (selBottom > offset + viewport) offset = selBottom - viewport
        offset = offset.coerceIn(0, maxOffset)
        scrollOffset = offset
        listContainer.translationY = -offset.toFloat()
    }

    private fun pluginRow(
        entry: GlassesHub.LauncherEntry,
        selected: Boolean,
    ): View {
        val icon = ImageView(this).apply {
            setImageResource(
                com.anezium.rokidbus.client.ui.NexusPluginIcons.drawableFor(entry.iconKey, entry.id),
            )
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) }
        }
        val label = text(18f, if (selected) BusTheme.phosphor else BusTheme.text, bold = selected).apply {
            text = entry.displayName
            gravity = Gravity.CENTER_VERTICAL
            paint.isDither = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = outline(selected)
            addView(icon)
            addView(label)
        }
    }

    private fun moveSelection(delta: Int) {
        if (launcherEntries.isEmpty()) return
        selectedIndex = (selectedIndex + delta + launcherEntries.size) % launcherEntries.size
        renderLauncher()
    }

    private fun openSelected() {
        val entry = launcherEntries.getOrNull(selectedIndex) ?: return
        val result = GlassesHub.openLauncherEntry(entry.id)
        log("Launcher open result: $result")
    }

    private fun performOnboardingAction() {
        when (onboardingState.action) {
            SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY -> {
                SelfArmOnboardingStore.markAwaitingAccessibility(applicationContext)
                val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .setPackage("com.android.settings")
                val opened = runCatching { startActivity(settings) }.isSuccess ||
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess
                if (!opened) {
                    SelfArmOnboardingStore.finish(
                        applicationContext,
                        "accessibility_settings_unavailable",
                        false,
                    )
                }
            }
            SelfArmOnboardingState.Action.START_WIRELESS,
            SelfArmOnboardingState.Action.RETRY_WIRELESS,
            -> {
                SelfArmOnboardingStore.requestSetup(applicationContext)
                if (!RokidBusAccessibilityService.requestWirelessBootstrap(applicationContext)) {
                    SelfArmOnboardingStore.reportProgress(
                        applicationContext,
                        "waiting_for_nexus_accessibility",
                    )
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .setPackage("com.android.settings"),
                        )
                    }
                }
            }
            SelfArmOnboardingState.Action.NONE -> Unit
        }
        renderScreen()
    }

    private fun humanSetupState(value: String): String = when (value) {
        "" -> "Waiting to begin."
        "waiting_for_nexus_accessibility" -> "Waiting for Rokid Nexus Glasses to be enabled."
        "starting_wireless_debugging_setup" -> "Opening Wireless Debugging…"
        "enabling_wifi" -> "Turning Wi-Fi on…"
        "opening_developer_options" -> "Opening Developer options…"
        "opening_wireless_debugging" -> "Opening Wireless Debugging…"
        "turning_wireless_debugging_on" -> "Enable Wireless Debugging when Settings asks."
        "confirming_wireless_debugging" -> "Confirm Wireless Debugging."
        "opening_pairing_code" -> "Opening the pairing-code screen…"
        "waiting_for_pairing_code",
        "searching_pairing_code",
        -> "Waiting for the 6-digit pairing code and ports…"
        "wireless_bootstrap_complete" -> "Secure self-arm completed."
        "pairing_code_expired" -> "The pairing code expired. Generate a new code."
        "wireless_setup_timeout" -> "Wireless Debugging setup timed out."
        "wireless_debugging_manual_step_needed" -> "Wireless Debugging needs a manual tap."
        "developer_options_manual_step_needed" -> "Developer options need a manual tap."
        "accessibility_settings_unavailable" -> "Accessibility Settings could not be opened."
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase() } + "."
    }

    private fun requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
        }
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun gap(value: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun outline(selected: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(if (selected) 2 else 1), if (selected) BusTheme.phosphor else BusTheme.hairline)
            cornerRadius = dp(4).toFloat()
        }

    private fun dp(value: Int): Int =
        BusTheme.dp(this, value)

    private companion object {
        const val PLUGIN_ROW_HEIGHT_DP = 52
        const val PLUGIN_ROW_MARGIN_DP = 8
    }
}
