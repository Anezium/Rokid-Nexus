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
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.BusConstants

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var launcherView: View
    private lateinit var onboardingView: View
    private lateinit var onboardingStepView: TextView
    private lateinit var onboardingTitleView: TextView
    private lateinit var onboardingBodyView: TextView
    private lateinit var onboardingActionView: TextView
    private var launcherEntries: List<GlassesHub.LauncherEntry> = emptyList()
    private var selectedIndex = 0
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
            launcherEntries = entries
            selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            renderLauncher()
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
        statusView = text(13f, BusTheme.muted).apply {
            text = "SPP ${BusConstants.SERVICE_NAME}\n${BusConstants.SPP_UUID_STRING}\nAccessibility keeps the hub alive."
            gravity = Gravity.CENTER_HORIZONTAL
        }
        emptyView = text(17f, BusTheme.dim).apply {
            text = "No phone plugins synced"
            gravity = Gravity.CENTER
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
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
            addView(gap(10))
            addView(statusView, matchWrap())
            addView(gap(26))
            addView(text(10.5f, BusTheme.dim).apply {
                text = "PLUGINS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(10))
            addView(listContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        if (complete) {
            statusView.text = if (snapshot.bootstrapComplete) {
                "Self-arm ready\nEncrypted Wireless Debugging paired\nLegacy ADB 5555 disabled"
            } else {
                "Accessibility armed\nWRITE_SECURE_SETTINGS granted\nADB fallback remains available"
            }
            return
        }

        when (onboardingState.stage) {
            SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY -> {
                onboardingStepView.text = "FIRST-RUN SETUP  •  1/2"
                onboardingTitleView.text = "Enable Nexus"
                onboardingBodyView.text =
                    "Open Accessibility and enable “Rokid Nexus Hub” once.\n\n" +
                    "This is the only accessibility service Nexus needs. It handles normal " +
                    "glasses controls and the secure setup flow."
                onboardingActionView.text = "OPEN ACCESSIBILITY"
            }
            SelfArmOnboardingState.Stage.READY_FOR_WIRELESS -> {
                onboardingStepView.text = "FIRST-RUN SETUP  •  2/2"
                onboardingTitleView.text = "Arm securely"
                onboardingBodyView.text =
                    "Nexus will open Developer options and Wireless Debugging. Keep “Pair device " +
                    "with pairing code” open while Nexus reads the 6-digit code locally.\n\n" +
                    "The grant uses encrypted, authenticated ADB. Legacy port 5555 is disabled."
                onboardingActionView.text = "START WIRELESS SETUP"
            }
            SelfArmOnboardingState.Stage.RUNNING -> {
                onboardingStepView.text = "SECURE LOCAL PAIRING"
                onboardingTitleView.text = "Keep Settings open"
                onboardingBodyView.text =
                    "Nexus is navigating Wireless Debugging and waiting for the pairing code.\n\n" +
                    humanSetupState(onboardingState.detail)
                onboardingActionView.text = "SETUP RUNNING"
            }
            SelfArmOnboardingState.Stage.FAILED -> {
                onboardingStepView.text = "SETUP NEEDS ATTENTION"
                onboardingTitleView.text = "Retry pairing"
                onboardingBodyView.text =
                    humanSetupState(onboardingState.detail) + "\n\nOpen Wireless Debugging, choose “Pair " +
                    "device with pairing code,” keep the 6-digit code visible, then retry."
                onboardingActionView.text = "RETRY WIRELESS SETUP"
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
        if (launcherEntries.isEmpty()) {
            listContainer.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        launcherEntries.forEachIndexed { index, entry ->
            listContainer.addView(pluginRow(entry, selected = index == selectedIndex), matchWrap().apply {
                topMargin = if (index == 0) 0 else dp(8)
            })
        }
    }

    private fun pluginRow(
        entry: GlassesHub.LauncherEntry,
        selected: Boolean,
    ): TextView =
        text(18f, if (selected) BusTheme.phosphor else BusTheme.text, bold = selected).apply {
            text = if (selected) "> ${entry.displayName}" else "  ${entry.displayName}"
            minHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = outline(selected)
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
                SelfArmOnboardingStore.requestSetup(applicationContext)
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
        "waiting_for_nexus_accessibility" -> "Waiting for Rokid Nexus Hub to be enabled."
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
}
