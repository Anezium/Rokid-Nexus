package com.anezium.rokidbus.phone

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PatcherContract
import com.anezium.rokidbus.shared.YoutubeSetupContract

/**
 * Patcher → YouTube. Opened only through [PatcherSetupEntryActivity]; it looks like a
 * Patcher screen, but the hub keeps MicroG, inventory, installs, keyboard and checklist.
 * No Google account information enters this screen; sign-in Done is only the user's checklist.
 */
class YoutubeSetupActivity : Activity() {
    // Titles stay one line in the 20 sp header at 360 dp; the step ordinal lives in the subtitle.
    internal enum class Route(val title: String, val step: Int = 0) {
        OVERVIEW("YouTube"),
        MICROG("MicroG", 1),
        SOURCE("Official YouTube", 2),
        PATCH("Patch and install", 3),
        SIGNIN("Sign in and open", 4),
        ADVANCED("Advanced"),
    }

    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var message: TextView
    private var unsubscribe: (() -> Unit)? = null
    private var rendered: YoutubeSetupState? = null
    private val expanded = mutableSetOf<String>()
    private var route = Route.OVERVIEW
    private var patchPending = false
    private var patchRequestCode = PATCH_APK - 1
    private var pickPending = false
    private var patcherIdentity: String? = null
    // Only a UI label; the patch button and the result boundary re-authenticate the patcher.
    private var patcherApproved = false
    // Patcher's informational job hint, from the entry or an authenticated result; never evidence.
    private var jobHint: String? = null
    private var started = false
    private var skipRefreshOnce = false
    // API 33+ platform Back callback. Android 16 with targetSdk 36 never calls onBackPressed.
    // Typed as Any so devices below API 33 never resolve android.window classes.
    private var backCallback: Any? = null
    private val checklist by lazy { YoutubeSetupChecklist(this) }
    private val installHistory by lazy { YoutubeSetupInstallHistory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        patchPending = savedInstanceState?.getBoolean("patchPending") ?: false
        patchRequestCode = savedInstanceState?.getInt("patchRequestCode",
            if (patchPending) PATCH_APK else PATCH_APK - 1) ?: PATCH_APK - 1
        pickPending = savedInstanceState?.getBoolean("pickPending") ?: false
        patcherIdentity = savedInstanceState?.getString("patcherIdentity")
        route = savedInstanceState?.getString("route")?.let { saved -> Route.values().firstOrNull { it.name == saved } }
            ?: Route.OVERVIEW
        jobHint = PatcherContract.jobState(if (savedInstanceState != null) savedInstanceState.getString("jobHint")
            else intent.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
        savedInstanceState?.getStringArrayList("expanded")?.let(expanded::addAll)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = NexusUi.contentColumn(this)
        footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(header, NexusUi.block())
            addView(NexusUi.screen(this@YoutubeSetupActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer, NexusUi.block())
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        patcherApproved = PatcherHandoff.authenticatedIdentity(this) != null
        rendered = null
        unsubscribe = YoutubeSetupStateStore.observe(::render)
        // Android delivers activity results after onStart; a refresh here would make the
        // controller busy and drop the imported or patched APK that is about to arrive.
        if (!skipRefreshOnce && !patchPending && !pickPending && !YoutubeSetupStateStore.state.busy) {
            command(YoutubeSetupController.REFRESH)
        }
        skipRefreshOnce = false
        started = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("patchPending", patchPending)
        outState.putInt("patchRequestCode", patchRequestCode)
        outState.putBoolean("pickPending", pickPending)
        outState.putString("patcherIdentity", patcherIdentity)
        outState.putString("route", route.name)
        outState.putString("jobHint", jobHint)
        outState.putStringArrayList("expanded", ArrayList(expanded))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!patchPending) return
        val identity = PatcherHandoff.authenticatedIdentity(this)
        patcherApproved = identity != null
        if (!PatcherHandoff.acceptsResult(patcherIdentity, identity)) {
            abandonPatchResult()
            toast("Patcher stopped — retry.")
        }
    }

    override fun onStop() {
        started = false
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDestroy()
    }

    /**
     * Legacy fallback: below API 33, and on API 33 to 35 when the app has not opted in to
     * OnBackInvokedCallback, the platform still dispatches Back here. Exactly one of the two
     * paths receives a given Back event, so both share [navigateBack] without double handling.
     */
    @Deprecated("Uses the platform back callback on API 33+")
    override fun onBackPressed() {
        navigateBack()
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        if (backCallback != null) return
        val callback = OnBackInvokedCallback { navigateBack() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
    }

    /**
     * The single Back rule for system Back and the header arrow: a step screen returns to the
     * YouTube overview; the overview finishes and returns to the Patcher screen that opened it.
     * finish() is explicit and nothing re-dispatches Back, so there is no recursion.
     */
    private fun navigateBack() {
        if (route != Route.OVERVIEW) show(Route.OVERVIEW) else finish()
    }

    private fun show(next: Route) {
        route = next
        rerender()
    }

    private fun rerender() {
        rendered = null
        render(YoutubeSetupStateStore.state)
    }

    private fun render(state: YoutubeSetupState) {
        // Download progress publishes several times per second; keep the screen and its
        // expanded sections intact when only the message changed.
        if (rendered?.copy(message = state.message) == state) {
            message.text = state.message
            rendered = state
            return
        }
        rendered = state
        renderHeader()
        content.removeAllViews()
        footer.removeAllViews()
        val setup = Snapshot(state)
        message = NexusUi.statusLine(this).apply { text = state.message }
        when (route) {
            Route.OVERVIEW -> renderOverview(setup)
            Route.MICROG -> renderMicroG(setup)
            Route.SOURCE -> renderSource(setup)
            Route.PATCH -> renderPatch(setup)
            Route.SIGNIN -> renderSignIn(setup)
            Route.ADVANCED -> renderAdvanced(setup)
        }
    }

    private fun renderHeader() {
        header.removeAllViews()
        header.addView(NexusUi.pluginHeader(this,
            NexusUi.iconTileImage(this, R.drawable.ic_app_youtube, 48), route.title,
            when {
                route == Route.OVERVIEW -> "Patcher · Glasses setup"
                route.step > 0 -> "Step ${route.step} of 4 · YouTube"
                else -> "Patcher · YouTube"
            },
        ) { navigateBack() }, NexusUi.block())
    }

    /**
     * The controller's latest report (refresh, download progress or failure), kept beside the
     * steps it describes instead of floating between paragraphs. It wraps, so errors stay whole.
     */
    private fun report(parent: LinearLayout, state: YoutubeSetupState) {
        parent.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.TOP
            addView(NexusUi.dot(this@YoutubeSetupActivity).apply { NexusUi.setDotColor(this, if (state.busy) NexusUi.GREEN else NexusUi.INK4) },
                LinearLayout.LayoutParams(NexusUi.dp(this@YoutubeSetupActivity, 6), NexusUi.dp(this@YoutubeSetupActivity, 6)).apply {
                    topMargin = NexusUi.dp(this@YoutubeSetupActivity, 6)
                    marginEnd = NexusUi.dp(this@YoutubeSetupActivity, 8)
                })
            addView(message.apply { textSize = 13f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, NexusUi.block())
    }

    /** Everything the four steps read, computed once per render. */
    private inner class Snapshot(val state: YoutubeSetupState) {
        val checked = state.inventory != null
        val enabled = !state.busy
        val microG = state.inventory?.apps?.firstOrNull { it.packageName == YoutubeSetupContract.MICROG }
        val youtube = state.inventory?.apps?.filter { it.packageName != YoutubeSetupContract.MICROG && it.installed }.orEmpty()
        val confirmedYoutube = installHistory.installed(YoutubeSetupContract.YOUTUBE)
        val installStatus = youtubeInstallStatus(state.inventory, confirmedYoutube)
        val youtubeDone = installStatus == "Done"
        val prepared = state.preparedLabel?.takeIf { state.canInstall }
        val jobRunning = patchPending || jobHint == PatcherContract.JOB_RUNNING
        val microGAction = MicroGSetupAction.choose(microG, state.latestMicroGVersionCode)
        val microGStatus = when {
            microG?.installed == true && microG.launchable && microGAction == MicroGSetupAction.UPDATE -> "Update available"
            else -> status(checked, microG?.installed == true, microG?.installed == true && !microG.launchable)
        }
        val sourceStatus = when {
            jobHint in setOf(PatcherContract.JOB_SOURCE_READY, PatcherContract.JOB_RUNNING, PatcherContract.JOB_READY) ->
                "Done — Patcher checked your file"
            state.youtubeApkReady -> "Done — a patched APK was accepted"
            youtube.isNotEmpty() -> "Done — YouTube is on the glasses"
            else -> "To do — download it, then choose it in Patcher"
        }
        val patchStatus = when {
            prepared != null -> patchStepLine(prepared, checked)
            patchPending -> "Waiting for Patcher — open it to check progress or use the result."
            jobHint == PatcherContract.JOB_RUNNING -> "Patching in Patcher — open the running job to follow it."
            jobHint == PatcherContract.JOB_READY -> "Patched APK ready in Patcher — install it on the glasses."
            youtubeDone -> "Done — YouTube ${confirmedYoutube?.versionName} is installed."
            else -> installStatus
        }
        val signInStatus = if (checklist.signInDone) "Done (marked by you)" else "To do"
    }

    private fun renderOverview(setup: Snapshot) {
        content.addView(NexusUi.cardBody(this,
            "Four steps put YouTube with the glasses controls on your glasses. Your account stays on the glasses."), NexusUi.block())
        content.addView(BusTheme.gap(this, 22))
        val done = listOf(setup.microGStatus, setup.sourceStatus, setup.patchStatus, setup.signInStatus).count { it.startsWith("Done") }
        content.addView(NexusUi.sectionRow(this, "Setup", "$done of 4 done"), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        report(content, setup.state)
        content.addView(BusTheme.gap(this, 10))
        stepRow(Route.MICROG, setup.microGStatus)
        stepRow(Route.SOURCE, setup.sourceStatus)
        stepRow(Route.PATCH, setup.patchStatus)
        stepRow(Route.SIGNIN, setup.signInStatus)
        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Phone keyboard"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(keyboardCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Advanced"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(NexusUi.navCard(this, "Morphe or patched APK",
            "Patch elsewhere, then let Nexus check and install the result.") { show(Route.ADVANCED) }.apply {
            contentDescription = "${Route.ADVANCED.title}: Morphe or patched APK"
        }, NexusUi.block())
        footerShortcut(setup)
    }

    private fun stepRow(step: Route, status: String) {
        if (step != Route.MICROG) content.addView(BusTheme.gap(this, 8))
        content.addView(NexusUi.pressableCard(this).apply {
            contentDescription = "${step.title}: $status"
            setOnClickListener { show(step) }
            addView(NexusUi.iconTile(this@YoutubeSetupActivity, if (status.startsWith("Done")) "✓" else step.step.toString()))
            addView(LinearLayout(this@YoutubeSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@YoutubeSetupActivity, step.title))
                addView(BusTheme.gap(this@YoutubeSetupActivity, 4))
                // The row carries the short state; its detail is on the step screen and in contentDescription.
                addView(NexusUi.rowSub(this@YoutubeSetupActivity, status.substringBefore(" — ")).apply {
                    maxLines = Int.MAX_VALUE; ellipsize = null
                    if (status.startsWith("Done")) setTextColor(NexusUi.GREEN_DIM)
                    else if (status.startsWith("Needs attention") || status == "Update available") setTextColor(NexusUi.AMBER)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = NexusUi.dp(this@YoutubeSetupActivity, 12)
                marginEnd = NexusUi.dp(this@YoutubeSetupActivity, 8)
            })
            addView(NexusUi.chevron(this@YoutubeSetupActivity))
        }, NexusUi.block())
    }

    /** The one shortcut an experienced user needs next; beginners follow the steps. */
    private fun footerShortcut(setup: Snapshot) {
        val (label, action) = when {
            setup.prepared != null -> "Install patched APK" to { command(YoutubeSetupController.INSTALL) }
            setup.jobRunning -> "Open running job" to { reopenPatcher() }
            !patcherApproved -> PatchStepAction.APPROVE.label to { patch() }
            jobHint == PatcherContract.JOB_READY -> "Install patched APK" to { patch() }
            setup.youtubeDone -> "Patch an update" to { patch() }
            else -> "Patch now" to { patch() }
        }
        footer.addView(NexusUi.divider(this).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        footer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = NexusUi.dp(this@YoutubeSetupActivity, 22)
            setPadding(horizontal, NexusUi.dp(this@YoutubeSetupActivity, 12), horizontal, NexusUi.dp(this@YoutubeSetupActivity, 12))
            addView(NexusUi.pillButton(this@YoutubeSetupActivity, label).apply {
                isEnabled = setup.enabled
                alpha = if (setup.enabled) 1f else .45f
                setOnClickListener { action() }
            }, NexusUi.block())
        }, NexusUi.block())
    }

    private fun keyboardCard(): LinearLayout {
        val settings = YoutubeKeyboardSettings(this)
        fun explain(on: Boolean) = if (on)
            "The phone keyboard comes forward when a YouTube field takes focus and leaves when you finish. If Android blocks it, tap the keyboard notification."
        else "Off: open Keyboard & remote yourself when YouTube needs text. MicroG sign-in always uses the secure Keyboard & remote."
        val sub = NexusUi.rowSub(this, explain(settings.autoOpen)).apply { maxLines = Int.MAX_VALUE; ellipsize = null }
        return NexusUi.card(this).apply {
            addView(NexusUi.switchRow(this@YoutubeSetupActivity, "Auto-open YouTube keyboard",
                control = NexusUi.switch(this@YoutubeSetupActivity).apply {
                    contentDescription = "Auto-open YouTube keyboard"
                    isChecked = settings.autoOpen
                    setOnCheckedChangeListener { _, checked ->
                        settings.autoOpen = checked
                        sub.text = explain(checked)
                    }
                }), NexusUi.block())
            addView(BusTheme.gap(this@YoutubeSetupActivity, 6))
            addView(sub, NexusUi.block())
        }
    }

    private fun renderAdvanced(setup: Snapshot) {
        content.addView(NexusUi.card(this).apply {
            report(this, setup.state)
            addView(BusTheme.gap(this@YoutubeSetupActivity, 10))
            addView(NexusUi.cardBody(this@YoutubeSetupActivity,
                "Patch with Morphe Manager instead, then import its final APK here, or import a YouTube APK that is already patched. " +
                    "This is not the official download from step 2. Nexus checks it the same way before installing."), NexusUi.block())
            note(this, "Morphe Manager signs with its own key: an app installed from Manager cannot be updated from Patcher, and the other way round.")
            advanced(this, setup)
        }, NexusUi.block())
    }

    private fun advanced(box: LinearLayout, setup: Snapshot) {
        button(box, "Add Rokid patches to Morphe", setup.enabled) {
            val source = Intent(Intent.ACTION_VIEW, Uri.parse(YoutubeApkPolicy.ROKID_PATCHES_SOURCE_URL))
                .setPackage(YoutubeApkPolicy.MORPHE_MANAGER_PACKAGE)
            runCatching { startActivity(source) }.onFailure { browse(YoutubeApkPolicy.MORPHE_MANAGER_URL) }
        }
        button(box, "Patch with Morphe", setup.enabled) {
            val patch = Intent(YoutubeApkPolicy.MORPHE_ACTION_PATCH_APP)
                .setClassName(YoutubeApkPolicy.MORPHE_MANAGER_PACKAGE, YoutubeApkPolicy.MORPHE_MANAGER_ACTIVITY)
                .putExtra(YoutubeApkPolicy.MORPHE_EXTRA_PATCH_PACKAGE, YoutubeApkPolicy.STOCK_YOUTUBE_PACKAGE)
            runCatching { startActivity(patch) }.onFailure { browse(YoutubeApkPolicy.MORPHE_MANAGER_URL) }
        }
        button(box, "How the Rokid patches work", setup.enabled) { browse(YoutubeApkPolicy.ROKID_PATCHES_README_URL) }
        button(box, "Choose patched YouTube APK", setup.enabled && !setup.jobRunning) { choosePatchedApk() }
        if (setup.jobRunning) box.addView(NexusUi.rowSub(this, "Locked while YouTube is being patched in Patcher.").apply {
            maxLines = Int.MAX_VALUE; ellipsize = null
            setTextColor(NexusUi.AMBER)
        }, NexusUi.block().apply { topMargin = NexusUi.dp(this@YoutubeSetupActivity, 6) })
        button(box, "Install prepared APK", setup.enabled && setup.state.canInstall) { command(YoutubeSetupController.INSTALL) }
        button(box, "Refresh glasses apps", setup.enabled) { command(YoutubeSetupController.REFRESH) }
    }

    private fun renderMicroG(setup: Snapshot) {
        stepCard(setup.microGStatus,
            "MicroG lets YouTube sign in to your Google account on the glasses. Nexus downloads the official MicroG-RE release with a launcher icon, checks it and installs it in one action.") { box ->
            button(box, setup.microGAction.label, setup.enabled, primary = true) {
                command(if (setup.microGAction == MicroGSetupAction.OPEN) YoutubeSetupController.OPEN_MICROG
                    else YoutubeSetupController.INSTALL_MICROG)
            }
            note(box, "This step is done only when the glasses report MicroG installed. A download or a transfer alone does not count.")
            more(box, "microg") { extras ->
                button(extras, "Check for MicroG updates", setup.enabled, secondary = true) { command(YoutubeSetupController.PREPARE_MICROG) }
                button(extras, "MicroG source", setup.enabled, secondary = true) { browse("https://github.com/MorpheApp/MicroG-RE") }
                button(extras, "Refresh glasses apps", setup.enabled, secondary = true) { command(YoutubeSetupController.REFRESH) }
            }
        }
    }

    private fun renderSource(setup: Snapshot) {
        stepCard(setup.sourceStatus,
            "Download official YouTube ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION} from APKMirror in your browser: the APK or the complete bundle, with arm64-v8a. " +
                "Then choose that file in Patcher, which checks the app, version, signature and completeness. Nexus never downloads YouTube for you.") { box ->
            button(box, "Download YouTube ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}", setup.enabled) {
                browse(YoutubeApkPolicy.STOCK_YOUTUBE_URL)
            }
            note(box, "Opening the download page does not complete this step.")
            if (setup.jobRunning) {
                button(box, "Open running job", setup.enabled, primary = true) { reopenPatcher() }
                note(box, "The file is locked while YouTube is being patched. Cancel the job in Patcher to change it.")
            } else {
                button(box, "Choose the file in Patcher", setup.enabled, primary = true) { patch() }
            }
            note(box, "Already have a patched YouTube APK? Use Advanced on the YouTube screen instead.")
        }
    }

    private fun renderPatch(setup: Snapshot) {
        val action = patchStepAction(setup.prepared != null, setup.youtubeDone, patcherApproved)
        val readyInPatcher = setup.prepared == null && jobHint == PatcherContract.JOB_READY && patcherApproved
        stepCard(setup.patchStatus, when {
            setup.prepared != null || patchPending -> "Nexus checks the patched APK before transfer, installs it on the glasses, then confirms the version and signer they report."
            else -> "Patcher adds the glasses controls in about 6–7 minutes and keeps going in a small window while you use other apps. Nexus then checks the result and installs it on the glasses."
        }) { box ->
            if (readyInPatcher) button(box, "Install on glasses", setup.enabled && !patchPending, primary = true) { patch() }
            else button(box, action.label, setup.enabled && (action == PatchStepAction.INSTALL_PREPARED || !patchPending),
                primary = action != PatchStepAction.REINSTALL, secondary = action == PatchStepAction.REINSTALL) {
                if (action == PatchStepAction.INSTALL_PREPARED) command(YoutubeSetupController.INSTALL) else patch()
            }
            if (patchPending) button(box, "Open Patcher", setup.enabled) { reopenPatcher() }
            box.addView(BusTheme.gap(this, 14))
            checkLine(box, "Patched APK on this phone", setup.prepared != null || jobHint == PatcherContract.JOB_READY || setup.youtubeDone)
            checkLine(box, "Transferred to the glasses", setup.youtubeDone)
            checkLine(box, "Glasses report the new version and signer · the only confirmation that counts", setup.youtubeDone)
            note(box, "Updates need the same signing key. If Nexus stops on a different signer, import your key backup in Patcher → Maintenance. Nexus never uninstalls YouTube to get around it.")
            more(box, "patch") { extras ->
                if (setup.prepared != null) button(extras, if (setup.youtubeDone) "Reinstall / update" else "Patch again", setup.enabled && !patchPending, secondary = true) { patch() }
                button(extras, "Patcher in Store", setup.enabled, secondary = true) { startActivity(PatcherHandoff.storeIntent(this)) }
                button(extras, "Refresh glasses apps", setup.enabled, secondary = true) { command(YoutubeSetupController.REFRESH) }
            }
        }
    }

    private fun renderSignIn(setup: Snapshot) {
        stepCard(setup.signInStatus,
            "1. Open MicroG on the glasses and choose Add account.\n" +
                "2. Type with Keyboard & remote: the phone screen stays secure and is never mirrored.\n" +
                "3. Finish Google's verification on the glasses.\n" +
                "4. Open YouTube and check your account, the controls and Morphe → SponsorBlock.") { box ->
            button(box, "Open MicroG on glasses", setup.enabled && setup.microG?.launchable == true, primary = true) {
                command(YoutubeSetupController.OPEN_MICROG)
            }
            button(box, "Keyboard & remote", setup.enabled) {
                startActivity(Intent(this, RemoteInputActivity::class.java)
                    .putExtra(RemoteInputActivity.EXTRA_SECURE_SESSION, true))
            }
            button(box, "Open YouTube", setup.enabled && setup.youtube.any { it.launchable }) {
                command(YoutubeSetupController.OPEN_YOUTUBE)
            }
            note(box, "Nexus and Patcher never see your account, password or tokens, nor whether sign-in worked. Done is your own mark, separate from the install confirmation.")
            button(box, if (checklist.signInDone) "Mark sign-in to do" else "Mark sign-in done", setup.enabled, secondary = true) {
                checklist.signInDone = !checklist.signInDone
                rerender()
            }
        }
    }

    private fun stepCard(status: String, body: String, actions: (LinearLayout) -> Unit) {
        content.addView(NexusUi.card(this).apply {
            addView(NexusUi.rowTitle(this@YoutubeSetupActivity, status).apply {
                maxLines = Int.MAX_VALUE; ellipsize = null
                if (status.startsWith("Done")) setTextColor(NexusUi.GREEN)
                else if (status.startsWith("Needs attention") || status == "Update available") setTextColor(NexusUi.AMBER)
            }, NexusUi.block())
            addView(BusTheme.gap(this@YoutubeSetupActivity, 8))
            report(this, rendered ?: YoutubeSetupStateStore.state)
            addView(BusTheme.gap(this@YoutubeSetupActivity, 10))
            addView(NexusUi.cardBody(this@YoutubeSetupActivity, body), NexusUi.block())
            actions(this)
        }, NexusUi.block())
    }

    private fun checkLine(parent: LinearLayout, label: String, done: Boolean) {
        parent.addView(LinearLayout(this).apply {
            setPadding(0, NexusUi.dp(this@YoutubeSetupActivity, 4), 0, NexusUi.dp(this@YoutubeSetupActivity, 4))
            addView(NexusUi.metaLabel(this@YoutubeSetupActivity, if (done) "✓" else "·", if (done) NexusUi.GREEN else NexusUi.INK4).apply {
                minWidth = NexusUi.dp(this@YoutubeSetupActivity, 18)
            })
            addView(NexusUi.rowSub(this@YoutubeSetupActivity, label).apply {
                maxLines = 3
                setTextColor(if (done) NexusUi.INK2 else NexusUi.INK3)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, NexusUi.block())
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(BusTheme.gap(this, 8))
        parent.addView(NexusUi.cardBody(this, text).apply { textSize = 12f; setTextColor(NexusUi.INK3) }, NexusUi.block())
    }

    private fun status(checked: Boolean, done: Boolean, attention: Boolean = false): String = when {
        attention -> "Needs attention"
        done -> "Done"
        !checked -> "Needs attention — refresh glasses apps"
        else -> "To do"
    }

    /** Secondary actions fold behind an end-aligned More, like the signing key card in Patcher. */
    private fun more(parent: LinearLayout, key: String, actions: (LinearLayout) -> Unit) {
        val extras = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (key in expanded) View.VISIBLE else View.GONE
        }
        parent.addView(BusTheme.gap(this, 6))
        parent.addView(endAligned(NexusUi.textButton(this, if (key in expanded) "Less" else "More").apply {
            setOnClickListener {
                val open = expanded.add(key)
                if (!open) expanded.remove(key)
                text = if (open) "Less" else "More"
                extras.visibility = if (open) View.VISIBLE else View.GONE
            }
        }), NexusUi.block())
        actions(extras)
        parent.addView(extras, NexusUi.block())
    }

    private fun endAligned(button: android.widget.Button): LinearLayout = LinearLayout(this).apply {
        gravity = android.view.Gravity.END
        // The kit's text button is 42 dp; these stand alone, so keep a 48 dp touch target.
        button.minHeight = NexusUi.dp(this@YoutubeSetupActivity, 48)
        button.minimumHeight = NexusUi.dp(this@YoutubeSetupActivity, 48)
        addView(button)
    }

    private fun button(parent: LinearLayout, label: String, enabled: Boolean, primary: Boolean = false,
                       secondary: Boolean = false, action: () -> Unit) {
        parent.addView(BusTheme.gap(this, if (secondary) 2 else 10))
        val view = when {
            secondary -> NexusUi.textButton(this, label)
            primary -> NexusUi.pillButton(this, label)
            else -> NexusUi.outlinePillButton(this, label)
        }.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else .45f
            setOnClickListener { action() }
        }
        parent.addView(if (secondary) endAligned(view) else view, NexusUi.block())
    }

    private fun command(action: String, uri: Uri? = null) {
        YoutubeSetupCommands.submit(Intent(action).setData(uri))
    }

    private fun patch() {
        val identity = PatcherHandoff.authenticatedIdentity(this)
        patcherApproved = identity != null
        if (identity == null) {
            startActivity(PatcherHandoff.reviewIntent(this))
            return
        }
        patcherIdentity = identity
        patchRequestCode++
        patchPending = true
        rerender()
        runCatching { startActivityForResult(PatcherHandoff.patchIntent(this), patchRequestCode) }
            .onFailure {
                patchPending = false
                patcherIdentity = null
                rerender()
                toast("Patcher could not open. Update it from the Store.")
            }
    }

    private fun reopenPatcher() {
        if (patchPending) abandonPatchResult()
        patch()
    }

    private fun choosePatchedApk() {
        if (patchPending || jobHint == PatcherContract.JOB_RUNNING) {
            toast("Locked while YouTube is being patched. Finish or cancel the job in Patcher first.")
            return
        }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.android.package-archive", "application/octet-stream",
                "application/zip", "application/x-zip-compressed",
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickPending = true
        runCatching { startActivityForResult(picker, PICK_APK) }
            .onFailure {
                pickPending = false
                toast("No file picker is available on this phone.")
            }
    }

    @Deprecated("Uses the Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode >= PATCH_APK && requestCode == patchRequestCode) {
            if (!patchPending) return
            patchPending = false
            val launchedIdentity = patcherIdentity
            patcherIdentity = null
            val authentic = PatcherHandoff.acceptsResult(launchedIdentity, PatcherHandoff.authenticatedIdentity(this))
            if (resultCode == RESULT_OK && !authentic) {
                toast("Patcher changed or is no longer approved. Review it and patch again.")
            } else if (resultCode == RESULT_OK) {
                jobHint = null
                val uri = PatcherHandoff.resultUri(data)
                if (uri == null) toast("Patcher did not return a readable APK. Try again.")
                else submitResult(YoutubeSetupController.PATCH_AND_INSTALL, uri, "patch it again")
            } else {
                jobHint = if (authentic) PatcherContract.jobState(data?.getStringExtra(PatcherContract.EXTRA_JOB_STATE)) else null
            }
            rerender()
        } else if (requestCode == PICK_APK) {
            pickPending = false
            if (resultCode != RESULT_OK) return
            // A picker opened before a patch started must not swap the source under the job.
            if (patchPending || jobHint == PatcherContract.JOB_RUNNING) {
                toast("Locked while YouTube is being patched. The chosen file was not imported.")
                return
            }
            data?.data?.takeIf { it.scheme == "content" }?.let {
                submitResult(YoutubeSetupController.IMPORT_YOUTUBE, it, "choose the APK again")
            }
        }
    }

    private fun abandonPatchResult() {
        patchPending = false
        patcherIdentity = null
        // Finish the old result relationship before retrying. Each launch uses a new
        // request code so a late result cannot be accepted by the replacement session.
        finishActivity(patchRequestCode)
        rerender()
    }

    private fun submitResult(action: String, uri: Uri, retry: String) {
        // The controller ignores commands while busy; failing it here would also end the
        // operation in flight, so only tell the user.
        if (YoutubeSetupStateStore.state.busy) {
            toast("Another glasses app operation is running. Wait for it to finish, then $retry.")
            return
        }
        // Results can also arrive before onStart; its refresh must not replace this command.
        if (!started) skipRefreshOnce = true
        command(action, uri)
    }

    private fun browse(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("No browser is available on this phone.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val PICK_APK = 41
        private const val PATCH_APK = 42

        internal fun intent(context: Context, jobHint: String?): Intent =
            Intent(context, YoutubeSetupActivity::class.java).putExtra(PatcherContract.EXTRA_JOB_STATE, jobHint)
    }
}
