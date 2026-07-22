package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.io.Closeable

/**
 * The guided, phone-driven manual pairing wizard. It is the fallback for glasses whose on-device
 * self-pairing keeps failing (the adbd on the unit closes the local secure channel). The wearer
 * reads the three values off the glasses' own Wireless Debugging dialog and types them here; the
 * phone pairs across the network and finishes the secure arm.
 *
 * The wizard drives the [GlassesManualPairingEngine] living in [BusHubService]; it never handles
 * pairing/transport itself and never stores the typed six-digit code (the engine wipes it after the
 * single pairing call). Every visible string is written for someone who has never seen a developer
 * setting in their life.
 */
class GlassesManualSetupActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engineSubscription: Closeable? = null
    private var engine: GlassesManualPairingEngine? = null

    private lateinit var root: LinearLayout
    private lateinit var stepper: LinearLayout
    private lateinit var body: LinearLayout

    // Retained so a state change that arrives mid-typing doesn't wipe half-entered values: we only
    // rebuild the form when we *enter* WAITING_FOR_CODE, not on every re-emit of it.
    private var renderedStateKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        title = "Manual glasses setup"

        root = NexusUi.fixedRoot(this)
        val content = NexusUi.contentColumn(this)
        content.addView(NexusUi.wordmark(this, "MANUAL SETUP"))
        content.addView(BusTheme.gap(this, 16))
        stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(stepper, NexusUi.block())
        content.addView(BusTheme.gap(this, 18))

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body, NexusUi.block())

        val scroll = ScrollView(this).apply {
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
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        // The wizard is only reachable from the setup screen, so the hub is normally already up; make
        // sure regardless, then attach once the service instance exists.
        BusHubService.start(this)
        attachEngineWhenReady(attempts = 0)
    }

    private fun attachEngineWhenReady(attempts: Int) {
        val live = BusHubService.manualPairingEngine()
        if (live != null) {
            engine = live
            engineSubscription = live.observe { state ->
                mainHandler.post { render(state) }
            }
            return
        }
        if (attempts >= MAX_ENGINE_ATTACH_ATTEMPTS) {
            renderServiceUnavailable()
            return
        }
        mainHandler.postDelayed({ attachEngineWhenReady(attempts + 1) }, ENGINE_ATTACH_RETRY_MS)
    }

    override fun onDestroy() {
        engineSubscription?.let { runCatching { it.close() } }
        engineSubscription = null
        // Leaving the wizard tears down any in-flight attempt so the glasses drop back to their HUD.
        engine?.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        engine?.cancel()
        super.onBackPressed()
    }

    // ---- Rendering -----------------------------------------------------------------------------

    private fun render(state: GlassesManualPairingState) {
        val key = stateKey(state)
        // WAITING_FOR_CODE can re-emit; don't rebuild the form under the user's fingers.
        if (key == renderedStateKey && state is GlassesManualPairingState.WAITING_FOR_CODE) return
        renderedStateKey = key

        renderStepper(activeStepFor(state))
        body.removeAllViews()
        when (state) {
            GlassesManualPairingState.IDLE -> renderIntro()
            GlassesManualPairingState.WAITING_FOR_CODE -> renderCodeForm()
            GlassesManualPairingState.PAIRING ->
                renderWorking("Pairing…", "Connecting to your glasses. Keep them on.")
            GlassesManualPairingState.CONNECTING ->
                renderWorking("Almost there…", "Securing the connection to your glasses.")
            GlassesManualPairingState.ARMING ->
                renderWorking("Finishing setup…", "Arming Nexus on your glasses. Don't take them off yet.")
            GlassesManualPairingState.DONE -> renderDone()
            is GlassesManualPairingState.ERROR -> renderError(state)
        }
    }

    private fun renderIntro() {
        body.addView(NexusUi.hero(this, 30f).apply { text = "Set up by hand" }, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(
                this,
                "Your glasses' automatic setup didn't take, so we'll do it together — it takes " +
                    "about two minutes. Put the glasses on, keep this phone in front of you, and " +
                    "tap Start. Nexus will give you direct Settings buttons instead of trying to " +
                    "drive the glasses menus automatically.",
            ),
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 14))
        body.addView(checklistCard(), NexusUi.block())
        body.addView(BusTheme.gap(this, 24))
        body.addView(primary("Start") { engine?.start() }, NexusUi.block())
    }

    private fun checklistCard(): View = NexusUi.card(this).apply {
        addView(NexusUi.metaLabel(this@GlassesManualSetupActivity, "BEFORE YOU START", NexusUi.INK3))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 10))
        addView(bullet("The glasses are on your face and turned on"))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
        addView(bullet("The glasses are connected to Wi-Fi (any network)"))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
        addView(bullet("This phone is on the same Wi-Fi as the glasses"))
    }

    private fun renderCodeForm() {
        body.addView(NexusUi.hero(this, 26f).apply { text = "Open the pairing screen" }, NexusUi.block())
        body.addView(BusTheme.gap(this, 10))
        body.addView(
            NexusUi.cardBody(
                this,
                "The first button performs only the six fast Build number taps. The other buttons " +
                    "open their Android Settings pages directly.",
            ),
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 14))
        body.addView(
            NexusUi.outlinePillButton(this, "1. Enable Developer options (6 taps)").apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    engine?.enableDeveloperOptions()
                    android.widget.Toast.makeText(
                        this@GlassesManualSetupActivity,
                        "Enabling Developer options on the glasses. Wait for it to finish.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 6))
        body.addView(
            NexusUi.cardBody(
                this,
                "Skip this step if Developer options are already enabled.",
            ).apply { textSize = 12f },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.outlinePillButton(this, "2. Open Developer options").apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    engine?.openDeveloperOptions()
                    android.widget.Toast.makeText(
                        this@GlassesManualSetupActivity,
                        "Opening Developer options on the glasses...",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 6))
        body.addView(
            NexusUi.cardBody(
                this,
                "If Android says they are disabled, run step 1 once, then tap this button again.",
            ).apply { textSize = 12f },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.outlinePillButton(this, "3. Show Wireless debugging").apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    engine?.showWirelessDebugging()
                    android.widget.Toast.makeText(
                        this@GlassesManualSetupActivity,
                        "Opening Wireless debugging on the glasses...",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 6))
        body.addView(
            NexusUi.cardBody(
                this,
                "On the glasses, tap Wireless debugging, turn it on, then tap “Pair device with " +
                    "pairing code”.",
            ).apply { textSize = 12f },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 18))
        body.addView(NexusUi.hero(this, 24f).apply { text = "Type what you see" }, NexusUi.block())
        body.addView(BusTheme.gap(this, 10))
        body.addView(pairingDialogMock(), NexusUi.block())
        body.addView(BusTheme.gap(this, 20))

        val ipField = labelledField("Wi-Fi IP address", "192.168.1.84", numeric = false)
        val portField = labelledField("Pairing port (after the “:”)", "37103", numeric = true)
        val codeField = labelledField("6-digit code", "123456", numeric = true)
        body.addView(ipField.container, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(portField.container, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(codeField.container, NexusUi.block())

        val error = NexusUi.statusLine(this).apply {
            setTextColor(NexusUi.DANGER)
            visibility = View.GONE
        }
        body.addView(BusTheme.gap(this, 10))
        body.addView(error, NexusUi.block())

        body.addView(BusTheme.gap(this, 16))
        body.addView(
            primary("Pair") {
                val host = ipField.edit.text.toString().trim()
                val port = portField.edit.text.toString().trim().toIntOrNull() ?: 0
                val code = codeField.edit.text.toString().trim()
                val problem = validate(host, port, code)
                if (problem != null) {
                    error.text = problem
                    error.visibility = View.VISIBLE
                    return@primary
                }
                error.visibility = View.GONE
                hideKeyboard()
                engine?.submit(host, port, code)
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 8))
        body.addView(
            NexusUi.textButton(this, "Cancel").apply {
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
            NexusUi.block(),
        )
    }

    /** A faithful, in-app copy of the glasses' Android "Pair with device" dialog, with pointers. */
    private fun pairingDialogMock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@GlassesManualSetupActivity, NexusUi.PANEL, NexusUi.LINE, 15)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(mockLabel("Pair with device", NexusUi.INK3, 12f))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 12))
        addView(mockLabel("Wi-Fi pairing code", NexusUi.INK2, 12f))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 2))
        addView(
            TextView(this@GlassesManualSetupActivity).apply {
                text = "123 456"
                typeface = Typeface.MONOSPACE
                textSize = 30f
                setTextColor(NexusUi.GREEN)
                letterSpacing = 0.08f
            },
        )
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 4))
        addView(mockLabel("→ the 6-digit code box", NexusUi.INK3, 11f))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 14))
        addView(mockLabel("IP address & Port", NexusUi.INK2, 12f))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 2))
        addView(
            TextView(this@GlassesManualSetupActivity).apply {
                text = "192.168.1.84:37103"
                typeface = Typeface.MONOSPACE
                textSize = 18f
                setTextColor(NexusUi.INK)
            },
        )
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 4))
        addView(
            mockLabel(
                "→ before the “:” is the IP address · after it is the pairing port",
                NexusUi.INK3,
                11f,
            ),
        )
    }

    private fun renderWorking(headline: String, detail: String) {
        body.addView(BusTheme.gap(this, 24))
        body.addView(
            TextView(this).apply {
                text = headline
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                textSize = 26f
                setTextColor(NexusUi.INK)
                gravity = Gravity.CENTER
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(this, detail).apply { gravity = Gravity.CENTER },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 24))
        body.addView(
            NexusUi.textButton(this, "Cancel").apply {
                gravity = Gravity.CENTER
                setOnClickListener { engine?.cancel(); finish() }
            },
            NexusUi.block(),
        )
    }

    private fun renderDone() {
        body.addView(BusTheme.gap(this, 30))
        body.addView(
            TextView(this).apply {
                text = "✓"
                textSize = 48f
                setTextColor(NexusUi.GREEN)
                gravity = Gravity.CENTER
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 8))
        body.addView(
            NexusUi.hero(this, 28f).apply {
                text = "You're all set"
                gravity = Gravity.CENTER
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(
                this,
                "Your glasses are armed and the plugin launcher is ready. You won't have to do " +
                    "this again — it sticks across reboots.",
            ).apply { gravity = Gravity.CENTER },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 26))
        body.addView(primary("Done") { finish() }, NexusUi.block())
    }

    private fun renderError(state: GlassesManualPairingState.ERROR) {
        body.addView(NexusUi.hero(this, 26f).apply { text = "That didn't work" }, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(NexusUi.cardBody(this, state.userMessage), NexusUi.block())
        body.addView(BusTheme.gap(this, 14))
        body.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.metaLabel(this@GlassesManualSetupActivity, "WHAT TO CHECK", NexusUi.INK3))
                addView(BusTheme.gap(this@GlassesManualSetupActivity, 10))
                addView(bullet("The glasses and this phone are on the same Wi-Fi"))
                addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
                addView(bullet("You typed the code before it expired (it rotates every few minutes)"))
                addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
                addView(bullet("Restarting the glasses once, then trying again"))
            },
            NexusUi.block(),
        )
        if (state.supportDetail.isNotBlank()) {
            body.addView(BusTheme.gap(this, 12))
            body.addView(
                NexusUi.metaLabel(this, "Support code: ${state.supportDetail}", NexusUi.INK3).apply {
                    textSize = 11f
                },
                NexusUi.block(),
            )
        }
        body.addView(BusTheme.gap(this, 22))
        body.addView(primary("Try again") { engine?.start() }, NexusUi.block())
        body.addView(BusTheme.gap(this, 8))
        body.addView(
            NexusUi.textButton(this, "Close").apply {
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
            NexusUi.block(),
        )
    }

    private fun renderServiceUnavailable() {
        renderStepper(0)
        body.removeAllViews()
        body.addView(NexusUi.hero(this, 26f).apply { text = "Not connected" }, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(
                this,
                "Nexus isn't linked to your glasses right now. Go back, make sure the glasses are " +
                    "connected, and open manual setup again.",
            ),
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 22))
        body.addView(primary("Go back") { finish() }, NexusUi.block())
    }

    // ---- Stepper -------------------------------------------------------------------------------

    private fun renderStepper(activeStep: Int) {
        stepper.removeAllViews()
        for (index in 0 until STEP_COUNT) {
            if (index > 0) {
                stepper.addView(
                    View(this).apply {
                        setBackgroundColor(NexusUi.LINE)
                    },
                    LinearLayout.LayoutParams(dp(22), dp(1)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    },
                )
            }
            val on = index <= activeStep && activeStep >= 0
            stepper.addView(
                View(this).apply {
                    background = NexusUi.bordered(
                        this@GlassesManualSetupActivity,
                        if (on) NexusUi.GREEN else NexusUi.PANEL,
                        if (on) NexusUi.GREEN else NexusUi.LINE,
                        6,
                    )
                },
                LinearLayout.LayoutParams(dp(9), dp(9)),
            )
        }
    }

    private fun activeStepFor(state: GlassesManualPairingState): Int = when (state) {
        GlassesManualPairingState.IDLE -> 0
        GlassesManualPairingState.WAITING_FOR_CODE -> 1
        GlassesManualPairingState.PAIRING,
        GlassesManualPairingState.CONNECTING,
        GlassesManualPairingState.ARMING,
        -> 2
        GlassesManualPairingState.DONE -> 3
        is GlassesManualPairingState.ERROR -> -1
    }

    // ---- Small builders ------------------------------------------------------------------------

    private class Field(val container: LinearLayout, val edit: EditText)

    private fun labelledField(label: String, hint: String, numeric: Boolean): Field {
        val edit = NexusUi.field(this, hint).apply {
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(NexusUi.metaLabel(this@GlassesManualSetupActivity, label.uppercase(), NexusUi.INK3).apply {
                textSize = 10.5f
                letterSpacing = 0.1f
            })
            addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
            addView(edit, NexusUi.block())
        }
        return Field(container, edit)
    }

    private fun bullet(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            TextView(this@GlassesManualSetupActivity).apply {
                this.text = "·"
                setTextColor(NexusUi.GREEN)
                textSize = 15f
            },
        )
        addView(
            NexusUi.cardBody(this@GlassesManualSetupActivity, text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            },
        )
    }

    private fun mockLabel(text: String, color: Int, size: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
        }

    private fun primary(label: String, onClick: () -> Unit): View =
        NexusUi.pillButton(this, label).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onClick() }
        }

    private fun validate(host: String, port: Int, code: String): String? = when {
        host.isBlank() -> "Enter the Wi-Fi IP address shown on the glasses."
        !IPV4.matches(host) -> "That IP address doesn't look right — check it on the glasses."
        port !in 1..65535 -> "Enter the pairing port (the number after the “:”)."
        !CODE.matches(code) -> "The code is exactly 6 digits."
        else -> null
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun stateKey(state: GlassesManualPairingState): String = when (state) {
        is GlassesManualPairingState.ERROR -> "ERROR:${state.supportDetail}:${state.userMessage}"
        else -> state::class.java.simpleName
    }

    private fun dp(value: Int): Int = NexusUi.dp(this, value)

    private companion object {
        const val STEP_COUNT = 4
        const val MAX_ENGINE_ATTACH_ATTEMPTS = 20
        const val ENGINE_ATTACH_RETRY_MS = 150L
        val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val CODE = Regex("""^\d{6}$""")
    }
}
