package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Rect
import android.graphics.Typeface
import android.content.Context
import android.content.Intent
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
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesRepairContract
import com.anezium.rokidbus.shared.SetupStage
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var scrollHost: ScrollView

    // Retained so a state change that arrives mid-typing doesn't wipe half-entered values: we only
    // rebuild the form when we *enter* WAITING_FOR_CODE, not on every re-emit of it.
    private var renderedStateKey: String = ""

    // Survives rotation and process recreation. Retyping an endpoint because the screen turned is
    // exactly the kind of insult this fallback is supposed to spare people.
    private var advancedExpanded = false
    private var advancedEndpoint = ""
    private var advancedCode = ""
    private var inlineStatus: String? = null
    private var inlineIsError = false
    private var commandPending = false

    // The engine renders through a posted handler, so between an accepted submit and the PAIRING
    // redraw the form is still on screen; a second tap in that window would be refused by the
    // engine and misreported as a failure while the pairing is actually running.
    private var pairSubmitInFlight = false
    private var lastState: GlassesManualPairingState = GlassesManualPairingState.IDLE

    // Re-pair: the same screen, opened after setup by an owner whose helper keeps dying. Core
    // readiness no longer ends it; the glasses' own pairing does.
    private var repair = false
    private var repairClient: BusClient? = null
    private var repairRequestInFlight = false

    // The preflight is drawn from what the lens has reported, so it has to redraw when that
    // changes — otherwise waking the glasses app leaves the screen still insisting it is down.
    // Marshalled, because these updates arrive on a Binder thread.
    private val stateRenderDispatcher by lazy {
        PhoneHomeRenderDispatcher(
            isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            postToMain = { action -> runOnUiThread { action() } },
            render = {
                if (!isDestroyed && !isFinishing &&
                    lastState == GlassesManualPairingState.IDLE
                ) {
                    rerenderCurrent()
                }
            },
        )
    }
    private val phoneStateListener: () -> Unit = stateRenderDispatcher::requestRender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BusHubService.noteGlassesSetupUserIntent()
        repair = intent.getBooleanExtra(EXTRA_REPAIR, false) ||
            savedInstanceState?.getBoolean(STATE_REPAIR, false) == true
        savedInstanceState?.let { saved ->
            advancedExpanded = saved.getBoolean(STATE_ADVANCED_EXPANDED, false)
            advancedEndpoint = saved.getString(STATE_ADVANCED_ENDPOINT).orEmpty()
            inlineStatus = saved.getString(STATE_INLINE_STATUS)
            inlineIsError = saved.getBoolean(STATE_INLINE_IS_ERROR, false)
        }
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        title = getString(titleRes())

        root = NexusUi.fixedRoot(this)
        val content = NexusUi.contentColumn(this)
        content.addView(
            NexusUi.wordmark(
                this,
                getString(if (repair) R.string.repair_pair_wordmark else R.string.guided_wordmark),
            ),
        )
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
            scrollHost = this
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

    override fun onStart() {
        super.onStart()
        NexusPhoneState.addUpdateListener(phoneStateListener)
    }

    override fun onStop() {
        NexusPhoneState.removeUpdateListener(phoneStateListener)
        super.onStop()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED_EXPANDED, advancedExpanded)
        outState.putString(STATE_ADVANCED_ENDPOINT, advancedEndpoint)
        // The pairing code is deliberately absent: it is short-lived, it rotates, and it has no
        // business surviving in a saved bundle.
        outState.putString(STATE_INLINE_STATUS, inlineStatus)
        outState.putBoolean(STATE_INLINE_IS_ERROR, inlineIsError)
        outState.putBoolean(STATE_REPAIR, repair)
    }

    override fun onDestroy() {
        engineSubscription?.let { runCatching { it.close() } }
        engineSubscription = null
        repairClient?.close()
        repairClient = null
        // Leaving the wizard tears down any in-flight attempt so the glasses drop back to their HUD.
        engine?.cancel()
        super.onDestroy()
    }

    // No onBackPressed override: it was redundant with onDestroy, which already cancels, and it
    // stopped being called at all once a device navigates by back gesture.

    // ---- Rendering -----------------------------------------------------------------------------

    /** Re-draws the state we are already in, after a local UI change rather than an engine event. */
    private fun rerenderCurrent() {
        renderedStateKey = ""
        render(lastState)
    }

    private fun render(state: GlassesManualPairingState) {
        lastState = state
        pairSubmitInFlight = false
        val key = stateKey(state)
        // WAITING_FOR_CODE can re-emit; don't rebuild the form under the user's fingers.
        if (key == renderedStateKey && state is GlassesManualPairingState.WAITING_FOR_CODE) return
        renderedStateKey = key
        // Any engine transition answers whatever we last asked for, so the screen stops claiming
        // to be waiting on it.
        if (state !is GlassesManualPairingState.WAITING_FOR_CODE) commandPending = false

        renderStepper(activeStepFor(state))
        body.removeAllViews()
        when (state) {
            GlassesManualPairingState.IDLE -> renderPreflight()
            GlassesManualPairingState.WAITING_FOR_CODE -> renderCodeForm()
            GlassesManualPairingState.PAIRING ->
                renderWorking(R.string.guided_working_pairing_title, R.string.guided_working_pairing_body)
            GlassesManualPairingState.CONNECTING ->
                renderWorking(R.string.guided_working_connecting_title, R.string.guided_working_connecting_body)
            GlassesManualPairingState.ARMING ->
                renderWorking(R.string.guided_working_arming_title, R.string.guided_working_arming_body)
            GlassesManualPairingState.DONE -> renderDone()
            is GlassesManualPairingState.ERROR -> renderError(state)
        }
    }

    /**
     * What guided setup shows before asking for anything: what it inspected, and the single thing
     * that is actually missing. The old screen opened with a static checklist the owner had to
     * audit themselves, then re-ran the whole automaton from the top regardless of how far it had
     * already got.
     */
    private fun renderPreflight() {
        val preflight = currentPreflight()
        body.addView(
            NexusUi.hero(this, 30f).apply { text = getString(titleRes()) },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(
                this,
                getString(if (repair) R.string.repair_pair_intro else R.string.guided_intro),
            ),
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 14))
        body.addView(preflightCard(preflight), NexusUi.block())
        // Not one of the checks above, because it cannot be checked: the switch is a vendor
        // property Hi Rokid owns. Every check in that card can pass with it off, so it is said
        // out loud here instead of being inferred from a run that fails without naming it.
        if (!preflight.alreadyComplete) {
            body.addView(BusTheme.gap(this, 14))
            body.addView(
                NexusUi.cardBody(this, getString(R.string.guided_adb_reminder))
                    .apply { textSize = 12f },
                NexusUi.block(),
            )
        }

        if (preflight.alreadyComplete) {
            body.addView(BusTheme.gap(this, 20))
            body.addView(
                NexusUi.statusLine(this).apply {
                    text = getString(
                        if (repair) R.string.repair_pair_status_paired else R.string.guided_status_success,
                    )
                },
                NexusUi.block(),
            )
            addInlineStatus()
            body.addView(BusTheme.gap(this, 12))
            body.addView(primary(getString(R.string.guided_action_cancel)) { finish() }, NexusUi.block())
            // "Set up" is the glasses' verdict on accessibility and the grant, not on whether
            // their helper survives a restart. This was the dead end of an owner whose helper
            // kept dying: a success line and nothing to press.
            body.addView(BusTheme.gap(this, 8))
            body.addView(
                NexusUi.textButton(
                    this,
                    getString(if (repair) R.string.repair_pair_again else R.string.repair_pair_offer),
                ).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (repair) {
                            requestSelfPairing()
                        } else {
                            repair = true
                            inlineStatus = null
                            rerenderCurrent()
                        }
                    }
                },
                NexusUi.block(),
            )
            // Shown even when there is nothing left to do: an owner comes back here to send what
            // happened, and a successful run is still worth reading when the last one failed.
            body.addView(BusTheme.gap(this, 20))
            body.addView(setupJournalBlock(), NexusUi.block())
            return
        }

        body.addView(BusTheme.gap(this, 20))
        val blocking = preflight.blocking
        if (blocking != null) {
            // Do not offer to prepare a pairing that cannot possibly work yet: say what is in the
            // way, and lead with the button that actually clears it. Offering only "Check again"
            // left the owner staring at a blocker they had no way to act on from here.
            body.addView(
                NexusUi.cardBody(this, getString(blockingMessage(blocking))),
                NexusUi.block(),
            )
            body.addView(BusTheme.gap(this, 14))
            when (blocking) {
                // Nothing this screen sends can land until Nexus is actually running on the lens,
                // so waking it is the offer rather than a step buried in the instructions.
                GuidedCheckId.GLASSES_APP -> {
                    body.addView(
                        primary(getString(R.string.manual_open_glasses_app)) {
                            BusHubService.openGlassesApp(this)
                            inlineStatus = getString(R.string.guided_status_opening_app)
                            inlineIsError = false
                            rerenderCurrent()
                        },
                        NexusUi.block(),
                    )
                    body.addView(BusTheme.gap(this, 8))
                }
                GuidedCheckId.ACCESSIBILITY -> {
                    body.addView(
                        primary(getString(R.string.manual_open_accessibility)) {
                            dispatch(R.string.guided_status_waiting) { it.openAccessibilitySettings() }
                        },
                        NexusUi.block(),
                    )
                    body.addView(BusTheme.gap(this, 8))
                }
                else -> Unit
            }
            body.addView(
                NexusUi.textButton(this, getString(R.string.guided_action_recheck)).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { rerenderCurrent() }
                },
                NexusUi.block(),
            )
        } else if (preflight.needsPairing) {
            // The glasses drive their own Settings to the pairing screen; nobody can do that by
            // hand on the lens. Pairing from this phone (below) revives the helper too, but only
            // until the glasses restart, so it is the fallback rather than the offer.
            body.addView(
                NexusUi.cardBody(this, getString(R.string.repair_pair_needed)),
                NexusUi.block(),
            )
            body.addView(BusTheme.gap(this, 14))
            body.addView(
                primary(getString(R.string.repair_pair_action)) { requestSelfPairing() },
                NexusUi.block(),
            )
        } else {
            body.addView(
                primary(getString(R.string.guided_action_prepare)) {
                    dispatch(R.string.guided_status_waiting) { it.showWirelessDebugging() }
                },
                NexusUi.block(),
            )
        }
        addInlineStatus()
        // With the lens app down every one of these would hang on "Waiting for the glasses…", so
        // the screen offers nothing it cannot deliver until Nexus is up over there.
        if (blocking != GuidedCheckId.GLASSES_APP) {
            body.addView(BusTheme.gap(this, 20))
            // Whatever the screen already leads with needs no second button underneath.
            body.addView(
                manualStepButtons(
                    skip = R.string.manual_open_accessibility
                        .takeIf { blocking == GuidedCheckId.ACCESSIBILITY },
                ),
                NexusUi.block(),
            )
        }
        body.addView(BusTheme.gap(this, 8))
        body.addView(advancedDisclosure(), NexusUi.block())
        body.addView(BusTheme.gap(this, 20))
        body.addView(setupJournalBlock(), NexusUi.block())
    }

    /**
     * The trail, on the screen that produces it.
     *
     * An owner whose setup keeps stopping has nothing to tell us today beyond "it does not work".
     * The lines that would answer it -- a Settings list that would not scroll, a screen that never
     * came up -- exist only in the lens's logcat, which no phone can reach. Here they are readable,
     * and one tap sends them.
     */
    private fun setupJournalBlock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val host = this@GlassesManualSetupActivity
        val entries = SetupJournal.entries(host)
        addView(NexusUi.metaLabel(host, getString(R.string.manual_log_title), NexusUi.INK3))
        addView(BusTheme.gap(host, 6))
        addView(NexusUi.cardBody(host, getString(R.string.manual_log_hint)).apply { textSize = 12f })
        addView(BusTheme.gap(host, 10))
        if (entries.isEmpty()) {
            addView(NexusUi.statusLine(host).apply { text = getString(R.string.manual_log_empty) })
            return@apply
        }
        // The tail is what matters: the last thing that happened is why the owner is on this
        // screen at all. The whole trail still goes out when they share it.
        entries.takeLast(JOURNAL_LINES_SHOWN).forEach { entry ->
            addView(
                NexusUi.statusLine(host).apply {
                    text = SetupJournalFormatter.line(entry, host::formatJournalTime)
                    textSize = 11f
                    if (entry.isFailure) setTextColor(NexusUi.DANGER)
                },
            )
        }
        addView(BusTheme.gap(host, 10))
        addView(
            NexusUi.textButton(host, getString(R.string.manual_log_share)).apply {
                setOnClickListener { shareSetupJournal() }
            },
        )
    }

    private fun formatJournalTime(atMillis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMillis))

    private fun shareSetupJournal() {
        val text = SetupJournalFormatter.shareText(
            entries = SetupJournal.entries(this),
            phoneVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            }.getOrDefault(""),
            glassesVersion = NexusPhoneState.installedGlassesVersionName.orEmpty(),
            clock = ::formatJournalTime,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.manual_log_share_subject))
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching {
            startActivity(Intent.createChooser(send, getString(R.string.manual_log_share)))
        }
    }

    /** Derived only from what the glasses actually report, so it never claims to know more. */
    private fun currentPreflight(): GuidedPreflight =
        GuidedSetupPreflightPolicy.fromReportedStage(
            linkReady = engine != null,
            reportedStage = NexusPhoneState.glassesSetupStage,
            coreReady = NexusPhoneState.glassesCoreReady,
            repair = repair,
            maintenanceReady = NexusPhoneState.glassesMaintenanceReady,
        )

    private fun titleRes(): Int = if (repair) R.string.repair_pair_title else R.string.guided_title

    /**
     * Asks the glasses to pair themselves again over the trusted repair route. Their own
     * automation opens Wireless debugging and pairs locally; if that fails it may hand the last
     * step to this phone, which only accepts it after an owner request like this one.
     */
    private fun requestSelfPairing() {
        if (repairRequestInFlight) return
        val client = repairClient ?: BusClient(
            context = applicationContext,
            clientId = "repair-pairing-ui",
            // The reply is consumed by the request/reply correlation, but delivery still requires
            // the prefix to be registered.
            pathPrefixes = listOf(BusPaths.GLASSES_REPAIR_REPLY),
        ) { }.also {
            it.connect()
            repairClient = it
        }
        BusHubService.noteGlassesSetupUserIntent()
        SetupJournal.record(this, fromGlasses = false, code = "repair_pairing_requested")
        repairRequestInFlight = true
        inlineStatus = getString(R.string.repair_pair_asking)
        inlineIsError = false
        rerenderCurrent()
        client.request(
            BusPaths.GLASSES_REPAIR_REQUEST,
            GlassesRepairContract.requestToJson(GlassesRepairContract.MODE_PAIR),
            timeoutMs = GlassesRepairStatusText.REQUEST_TIMEOUT_MS,
        ) { result ->
            if (isDestroyed || isFinishing) return@request
            repairRequestInFlight = false
            val reply = result.getOrNull()
            inlineStatus = if (result.isSuccess) {
                GlassesRepairStatusText.pairMessage(reply)
            } else {
                GlassesRepairStatusText.NO_ANSWER
            }
            inlineIsError = !GlassesRepairStatusText.pairIsGood(reply)
            rerenderCurrent()
        }
    }

    private fun preflightCard(preflight: GuidedPreflight): View = NexusUi.card(this).apply {
        val host = this@GlassesManualSetupActivity
        addView(NexusUi.metaLabel(host, getString(R.string.guided_preflight_title), NexusUi.INK3))
        addView(BusTheme.gap(host, 10))
        preflight.checks.forEachIndexed { index, check ->
            if (index > 0) addView(BusTheme.gap(host, 6))
            val label = getString(checkLabel(check.id))
            addView(
                bullet(if (check.satisfied) "✓  $label" else "•  $label").apply {
                    contentDescription = getString(
                        if (check.satisfied) R.string.a11y_check_pass else R.string.a11y_check_fail,
                        label,
                    )
                    alpha = if (check.satisfied) 1f else 0.7f
                },
            )
        }
    }

    private fun checkLabel(id: GuidedCheckId): Int = when (id) {
        GuidedCheckId.LINK -> R.string.guided_check_link
        GuidedCheckId.GLASSES_APP -> R.string.guided_check_glasses_app
        GuidedCheckId.ACCESSIBILITY -> R.string.guided_check_accessibility
        GuidedCheckId.WIFI -> R.string.guided_check_wifi
        GuidedCheckId.DEVELOPER -> R.string.guided_check_developer
        GuidedCheckId.SELF_PAIRING -> R.string.guided_check_self_pairing
    }

    /**
     * Every screen the lens can be sent to, as plain buttons the owner can reach at any point.
     *
     * The automatic run drives these itself, but it is exactly here that it gives up: the
     * developer-options list does not always scroll under the accessibility gesture, so wireless
     * debugging stays out of reach. Opening that page is a direct intent on the glasses and needs
     * no automation at all, so the manual screen offers it outright rather than leaving the owner
     * with a wizard that cannot get there either.
     */
    private fun manualStepButtons(skip: Int? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val host = this@GlassesManualSetupActivity
        addView(NexusUi.metaLabel(host, getString(R.string.manual_open_group), NexusUi.INK3))
        addView(BusTheme.gap(host, 6))
        addView(NexusUi.cardBody(host, getString(R.string.manual_open_hint)).apply { textSize = 12f })
        addView(BusTheme.gap(host, 10))
        listOf(
            R.string.manual_open_accessibility to
                { e: GlassesManualPairingEngine -> e.openAccessibilitySettings() },
            R.string.manual_open_developer to
                { e: GlassesManualPairingEngine -> e.openDeveloperOptions() },
            R.string.manual_open_wireless to
                { e: GlassesManualPairingEngine -> e.showWirelessDebugging() },
        ).filterNot { (label, _) -> label == skip }.forEachIndexed { index, (label, request) ->
            if (index > 0) addView(BusTheme.gap(host, 6))
            addView(
                NexusUi.textButton(host, getString(label)).apply {
                    gravity = Gravity.START
                    setOnClickListener { dispatch(R.string.guided_status_waiting, request) }
                },
            )
        }
    }

    private fun blockingMessage(id: GuidedCheckId): Int = when (id) {
        GuidedCheckId.LINK -> R.string.guided_check_link_fail
        GuidedCheckId.GLASSES_APP -> R.string.guided_check_glasses_app_fail
        GuidedCheckId.ACCESSIBILITY -> R.string.guided_check_accessibility_fail
        GuidedCheckId.WIFI -> R.string.guided_check_wifi_fail
        GuidedCheckId.DEVELOPER -> R.string.guided_check_developer_fail
        GuidedCheckId.SELF_PAIRING -> R.string.repair_pair_needed
    }

    /**
     * One instruction at a time. Nexus tries to open the screen itself; only when that does not
     * land does it ask the wearer to do it, and it never stacks four buttons the owner has to
     * sequence themselves.
     */
    private fun renderCodeForm() {
        body.addView(
            NexusUi.hero(this, 26f).apply { text = getString(R.string.guided_title) },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 10))
        // Described by what the screen does, never by an English Settings label: the phone and the
        // lens can be running in different languages, and the glasses do not report back the
        // wording they resolved.
        body.addView(
            NexusUi.cardBody(this, getString(R.string.guided_step_wireless_generic)),
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 14))
        body.addView(
            primary(getString(R.string.guided_action_continue)) {
                dispatch(R.string.guided_status_waiting) { it.showWirelessDebugging() }
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 8))
        body.addView(
            NexusUi.cardBody(this, getString(R.string.guided_step_confirm)).apply { textSize = 12f },
            NexusUi.block(),
        )
        addInlineStatus()
        body.addView(BusTheme.gap(this, 16))
        body.addView(advancedDisclosure(), NexusUi.block())
        body.addView(BusTheme.gap(this, 8))
        body.addView(
            NexusUi.textButton(this, getString(R.string.guided_action_cancel)).apply {
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
            NexusUi.block(),
        )
    }

    /**
     * Sends a settings command and reports what really happened. The old screen popped an
     * "Opening…" toast unconditionally, including when the request had already returned false and
     * nothing was ever sent.
     */
    private fun dispatch(waitingMessage: Int, request: (GlassesManualPairingEngine) -> Boolean) {
        val live = engine
        if (live == null || !request(live)) {
            commandPending = false
            SetupJournal.record(
                context = this,
                fromGlasses = false,
                code = "manual_command_refused",
                detail = if (live == null) "hub not attached" else "engine declined",
            )
            inlineStatus = getString(R.string.guided_error_link_down)
            inlineIsError = true
        } else {
            SetupJournal.record(this, fromGlasses = false, code = "manual_command_sent")
            commandPending = true
            inlineStatus = getString(waitingMessage)
            inlineIsError = false
        }
        rerenderCurrent()
    }

    private fun addInlineStatus() {
        val message = inlineStatus ?: return
        body.addView(BusTheme.gap(this, 10))
        body.addView(
            NexusUi.statusLine(this).apply {
                text = message
                if (inlineIsError) setTextColor(NexusUi.DANGER)
            },
            NexusUi.block(),
        )
    }

    /** The typed form is a last resort, so it starts folded away instead of leading the screen. */
    private fun advancedDisclosure(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val host = this@GlassesManualSetupActivity
        addView(
            NexusUi.textButton(host, getString(R.string.guided_advanced_title)).apply {
                setOnClickListener {
                    advancedExpanded = !advancedExpanded
                    rerenderCurrent()
                }
            },
        )
        if (!advancedExpanded) return@apply
        addView(BusTheme.gap(host, 10))
        addView(NexusUi.cardBody(host, getString(R.string.guided_advanced_body)))
        addView(BusTheme.gap(host, 14))
        // Anyone who gets this far is copying numbers off a lens; show them which is which.
        addView(pairingDialogMock())
        addView(BusTheme.gap(host, 18))

        val endpointField = labelledField(
            getString(R.string.guided_advanced_endpoint_label),
            getString(R.string.guided_advanced_endpoint_hint),
            numeric = false,
        )
        val codeField = labelledField(
            getString(R.string.guided_advanced_code_label),
            getString(R.string.guided_advanced_code_hint),
            numeric = true,
        )
        endpointField.edit.setText(advancedEndpoint)
        codeField.edit.setText(advancedCode)
        addView(endpointField.container)
        addView(BusTheme.gap(host, 12))
        addView(codeField.container)

        val endpointError = fieldError()
        val codeError = fieldError()
        addView(endpointError)
        addView(codeError)

        addView(BusTheme.gap(host, 16))
        addView(
            primary(getString(R.string.guided_advanced_submit)) {
                advancedEndpoint = endpointField.edit.text.toString()
                advancedCode = codeField.edit.text.toString()
                endpointError.visibility = View.GONE
                codeError.visibility = View.GONE
                val endpoint = ManualEndpointInput.parseEndpoint(advancedEndpoint)
                val code = ManualEndpointInput.parseCode(advancedCode)
                // Report every bad field at once: fixing one at a time to discover the next is
                // exactly the loop this screen exists to end.
                if (endpoint is ManualEndpointInput.Endpoint.Invalid) {
                    endpointError.text = getString(endpointErrorMessage(endpoint.error))
                    endpointError.visibility = View.VISIBLE
                }
                if (code is ManualEndpointInput.Code.Invalid) {
                    codeError.text = getString(codeErrorMessage(code.error))
                    codeError.visibility = View.VISIBLE
                }
                if (endpoint !is ManualEndpointInput.Endpoint.Valid ||
                    code !is ManualEndpointInput.Code.Valid
                ) {
                    return@primary
                }
                hideKeyboard()
                if (pairSubmitInFlight) return@primary
                val live = engine
                if (live == null ||
                    !live.submit(endpoint.host, endpoint.port, code.code, allowStartFromIdle = true)
                ) {
                    SetupJournal.record(
                        context = this@GlassesManualSetupActivity,
                        fromGlasses = false,
                        code = "manual_pair_refused",
                        detail = if (live == null) "hub not attached" else "engine declined",
                    )
                    inlineStatus = getString(R.string.guided_error_pair_refused)
                    inlineIsError = true
                    rerenderCurrent()
                } else {
                    pairSubmitInFlight = true
                    SetupJournal.record(
                        this@GlassesManualSetupActivity,
                        fromGlasses = false,
                        code = "manual_pair_submitted",
                    )
                }
            },
        )
    }

    private fun fieldError(): TextView = NexusUi.statusLine(this).apply {
        setTextColor(NexusUi.DANGER)
        visibility = View.GONE
    }

    private fun endpointErrorMessage(error: ManualEndpointInput.EndpointError): Int = when (error) {
        ManualEndpointInput.EndpointError.EMPTY -> R.string.guided_error_endpoint_empty
        ManualEndpointInput.EndpointError.FORMAT -> R.string.guided_error_endpoint_format
        ManualEndpointInput.EndpointError.IP -> R.string.guided_error_endpoint_ip
        ManualEndpointInput.EndpointError.PORT -> R.string.guided_error_endpoint_port
    }

    private fun codeErrorMessage(error: ManualEndpointInput.CodeError): Int = when (error) {
        ManualEndpointInput.CodeError.EMPTY -> R.string.guided_error_code_empty
        ManualEndpointInput.CodeError.FORMAT -> R.string.guided_error_code_format
    }

    /** A faithful, in-app copy of the glasses' Android "Pair with device" dialog, with pointers. */
    private fun pairingDialogMock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@GlassesManualSetupActivity, NexusUi.PANEL, NexusUi.LINE, 15)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(mockLabel(getString(R.string.guided_mock_dialog_title), NexusUi.INK3, 12f))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 12))
        addView(mockLabel(getString(R.string.guided_mock_code_label), NexusUi.INK2, 12f))
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
        addView(mockLabel(getString(R.string.guided_mock_code_pointer), NexusUi.INK3, 11f))
        addView(BusTheme.gap(this@GlassesManualSetupActivity, 14))
        addView(mockLabel(getString(R.string.guided_mock_endpoint_label), NexusUi.INK2, 12f))
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
                getString(R.string.guided_mock_endpoint_pointer),
                NexusUi.INK3,
                11f,
            ),
        )
    }

    private fun renderWorking(headlineRes: Int, detailRes: Int) {
        val headline = getString(headlineRes)
        val detail = getString(detailRes)
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
                text = getString(R.string.guided_done_title)
                gravity = Gravity.CENTER
            },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(
                this,
                getString(R.string.guided_done_body),
            ).apply { gravity = Gravity.CENTER },
            NexusUi.block(),
        )
        body.addView(BusTheme.gap(this, 26))
        body.addView(primary("Done") { finish() }, NexusUi.block())
    }

    private fun renderError(state: GlassesManualPairingState.ERROR) {
        body.addView(NexusUi.hero(this, 26f).apply { text = getString(R.string.guided_failed_title) }, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(NexusUi.cardBody(this, state.userMessage), NexusUi.block())
        body.addView(BusTheme.gap(this, 14))
        body.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.metaLabel(this@GlassesManualSetupActivity, getString(R.string.guided_failed_check_label), NexusUi.INK3))
                addView(BusTheme.gap(this@GlassesManualSetupActivity, 10))
                addView(bullet(getString(R.string.guided_failed_check_wifi)))
                addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
                addView(bullet(getString(R.string.guided_failed_check_code)))
                addView(BusTheme.gap(this@GlassesManualSetupActivity, 6))
                addView(bullet(getString(R.string.guided_failed_check_retry)))
            },
            NexusUi.block(),
        )
        if (state.supportDetail.isNotBlank()) {
            body.addView(BusTheme.gap(this, 12))
            body.addView(
                NexusUi.metaLabel(this, getString(R.string.guided_support_code, state.supportDetail), NexusUi.INK3).apply {
                    textSize = 11f
                },
                NexusUi.block(),
            )
        }
        body.addView(BusTheme.gap(this, 22))
        body.addView(primary(getString(R.string.guided_failed_action)) { engine?.start() }, NexusUi.block())
        // The screen an owner reaches when it went wrong is the screen the log has to be on.
        body.addView(BusTheme.gap(this, 20))
        body.addView(setupJournalBlock(), NexusUi.block())
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
        body.addView(NexusUi.hero(this, 26f).apply { text = getString(R.string.guided_unavailable_title) }, NexusUi.block())
        body.addView(BusTheme.gap(this, 12))
        body.addView(
            NexusUi.cardBody(
                this,
                getString(R.string.guided_unavailable_body),
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

    /**
     * Scrolls [view] clear of the soft keyboard. The rectangle is deliberately taller than the
     * field so the label above and the next field below stay readable while typing.
     */
    private fun revealAboveKeyboard(view: View) {
        if (!::scrollHost.isInitialized) return
        val reveal = Runnable {
            val margin = NexusUi.dp(this, 96)
            view.requestRectangleOnScreen(
                Rect(0, -NexusUi.dp(this, 28), view.width, view.height + margin),
                false,
            )
        }
        scrollHost.post(reveal)
        scrollHost.postDelayed(reveal, KEYBOARD_SETTLE_MS)
    }

    private class Field(val container: LinearLayout, val edit: EditText)

    private fun labelledField(label: String, hint: String, numeric: Boolean): Field {
        val edit = NexusUi.field(this, hint).apply {
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            // The keyboard covers exactly the three fields being copied off the glasses, and you
            // cannot check what you typed against a code that expires. Lift the focused field
            // above it — twice, because the window is still being resized on the first pass.
            setOnFocusChangeListener { view, hasFocus -> if (hasFocus) revealAboveKeyboard(view) }
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
            // While a command is in flight, tapping again would stack requests the glasses answer
            // out of order. Say we are busy instead of pretending to be idle.
            if (commandPending) {
                isEnabled = false
                alpha = 0.5f
                contentDescription = getString(R.string.a11y_busy)
            }
            setOnClickListener { onClick() }
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

    companion object {
        /** The window is still resizing when focus lands; the second pass lands after it settles. */
        private const val KEYBOARD_SETTLE_MS = 250L
        private const val STEP_COUNT = 4
        private const val MAX_ENGINE_ATTACH_ATTEMPTS = 20
        /** Enough to see how the run ended without turning the screen into a log viewer. */
        private const val JOURNAL_LINES_SHOWN = 8
        private const val ENGINE_ATTACH_RETRY_MS = 150L
        private const val STATE_ADVANCED_EXPANDED = "advanced_expanded"
        private const val STATE_ADVANCED_ENDPOINT = "advanced_endpoint"
        private const val STATE_INLINE_STATUS = "inline_status"
        private const val STATE_INLINE_IS_ERROR = "inline_is_error"
        private const val STATE_REPAIR = "repair"
        private const val EXTRA_REPAIR = "com.anezium.rokidbus.phone.extra.REPAIR_PAIRING"

        /** The Re-pair glasses entry: reachable after setup, whatever the app's Developer mode. */
        internal fun repairIntent(context: Context): Intent =
            Intent(context, GlassesManualSetupActivity::class.java).putExtra(EXTRA_REPAIR, true)
    }
}
