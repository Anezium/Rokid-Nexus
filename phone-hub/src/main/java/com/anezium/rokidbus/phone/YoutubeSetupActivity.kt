package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.YoutubeSetupContract

/** No Google account information enters this screen; Done is only the user's checklist. */
class YoutubeSetupActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var message: TextView
    private var unsubscribe: (() -> Unit)? = null
    private var rendered: YoutubeSetupState? = null
    private val expanded = mutableSetOf<String>()
    private var patchPending = false
    private var pickPending = false
    private var patcherIdentity: String? = null
    // Only a UI label; the patch button and the result boundary re-authenticate the patcher.
    private var patcherApproved = false
    private var started = false
    private var skipRefreshOnce = false
    private val checklist by lazy { YoutubeSetupChecklist(this) }
    private val installHistory by lazy { YoutubeSetupInstallHistory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        patchPending = savedInstanceState?.getBoolean("patchPending") ?: false
        pickPending = savedInstanceState?.getBoolean("pickPending") ?: false
        patcherIdentity = savedInstanceState?.getString("patcherIdentity")
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.textButton(this@YoutubeSetupActivity, "‹  Glasses apps").apply {
                setOnClickListener { finish() }
            }, NexusUi.block())
            addView(ScrollView(this@YoutubeSetupActivity).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(content)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        patcherApproved = YoutubePatcherHandoff.authenticatedIdentity(this) != null
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
        outState.putBoolean("pickPending", pickPending)
        outState.putString("patcherIdentity", patcherIdentity)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        started = false
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
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
        content.removeAllViews()
        content.addView(NexusUi.cardTitle(this, "YouTube on glasses"), NexusUi.block())
        content.addView(NexusUi.cardBody(this,
            "Patching adds the glasses controls to YouTube. Your account stays on the glasses."), NexusUi.block())
        message = NexusUi.cardBody(this, state.message)
        content.addView(message, NexusUi.block())
        val microG = state.inventory?.apps?.firstOrNull { it.packageName == YoutubeSetupContract.MICROG }
        val youtube = state.inventory?.apps?.filter { it.packageName != YoutubeSetupContract.MICROG && it.installed }.orEmpty()
        val enabled = !state.busy && !patchPending
        val checked = state.inventory != null

        card("1. MicroG on the glasses", status(checked, microG?.installed == true,
            microG?.installed == true && !microG.launchable) +
            " — Connect your Google account with MicroG.") { box ->
            button(box, "Install MicroG", enabled) { command(YoutubeSetupController.INSTALL_MICROG) }
            more(box, "microg") { extras ->
                button(extras, "MicroG source", enabled) { browse("https://github.com/MorpheApp/MicroG-RE") }
            }
        }
        card("2. YouTube APK", status(checked || state.youtubeApkReady, youtube.isNotEmpty() || state.youtubeApkReady) +
            " — Download YouTube ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}. APK or APKMirror bundle are both fine. The patcher checks your file; Nexus cannot inspect your browser downloads.") { box ->
            button(box, "Download YouTube ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}", enabled) {
                browse(YoutubeApkPolicy.STOCK_YOUTUBE_URL)
            }
        }
        card("3. Patch and install", youtubeInstallStatus(state.inventory,
            installHistory.installed(YoutubeSetupContract.YOUTUBE)) +
            " — Approve YouTube Patcher first, then choose your file and review the patches. Nexus checks the result and installs it automatically. APK checks alone cannot authenticate the patcher on a first glasses install.") { box ->
            button(box, if (patcherApproved) "Patch and install" else "Get or approve YouTube Patcher", enabled) {
                val identity = YoutubePatcherHandoff.authenticatedIdentity(this)
                patcherApproved = identity != null
                if (identity == null) {
                    startActivity(YoutubePatcherHandoff.reviewIntent(this))
                } else {
                    patcherIdentity = identity
                    patchPending = true
                    rerender()
                    runCatching { startActivityForResult(YoutubePatcherHandoff.patchIntent(), PATCH_APK) }
                        .onFailure {
                            patchPending = false
                            patcherIdentity = null
                            rerender()
                            toast("YouTube Patcher could not open. Update it from the Store.")
                        }
                }
            }
            more(box, "patch") { extras ->
                button(extras, "YouTube Patcher in Store", enabled) {
                    startActivity(YoutubePatcherHandoff.storeIntent(this))
                }
                state.preparedLabel?.let {
                    extras.addView(NexusUi.cardBody(this, it), NexusUi.block())
                    button(extras, "Retry prepared install", enabled && state.canInstall) {
                        command(YoutubeSetupController.INSTALL)
                    }
                }
                button(extras, "Refresh glasses apps", enabled) { command(YoutubeSetupController.REFRESH) }
            }
        }
        card("4. Sign in and open", (if (checklist.signInDone) "Done (marked by you)" else "To do") +
            " — Open MicroG, choose Add account, then use Keyboard & remote. Open YouTube to check your account. Nexus never reads accounts or tokens.") { box ->
            button(box, "Open MicroG on glasses", enabled && microG?.launchable == true) {
                command(YoutubeSetupController.OPEN_MICROG)
            }
            more(box, "sign-in") { extras ->
                button(extras, "Keyboard & remote", enabled) {
                    startActivity(Intent(this, RemoteInputActivity::class.java)
                        .putExtra(RemoteInputActivity.EXTRA_SECURE_SESSION, true))
                }
                button(extras, "Open YouTube", enabled && youtube.any { it.launchable }) {
                    command(YoutubeSetupController.OPEN_YOUTUBE)
                }
                button(extras, if (checklist.signInDone) "Mark sign-in to do" else "Done", enabled) {
                    checklist.signInDone = !checklist.signInDone
                    rerender()
                }
            }
        }
        card("Phone keyboard", "Auto-open is off by default. When off, use Keyboard & remote manually.") { box ->
            val settings = YoutubeKeyboardSettings(this)
            box.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(NexusUi.rowTitle(this@YoutubeSetupActivity, "Auto-open keyboard"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(NexusUi.switch(this@YoutubeSetupActivity).apply {
                    contentDescription = "Auto-open YouTube keyboard"
                    isChecked = settings.autoOpen
                    setOnCheckedChangeListener { _, checked -> settings.autoOpen = checked }
                })
            }, NexusUi.block())
        }
        more(content, "advanced", "Advanced → Patch with Morphe Manager instead") { box ->
            button(box, "Add Rokid patches to Morphe", enabled) {
                val source = Intent(Intent.ACTION_VIEW, Uri.parse(YoutubeApkPolicy.ROKID_PATCHES_SOURCE_URL))
                    .setPackage(YoutubeApkPolicy.MORPHE_MANAGER_PACKAGE)
                runCatching { startActivity(source) }.onFailure { browse(YoutubeApkPolicy.MORPHE_MANAGER_URL) }
            }
            button(box, "Patch with Morphe", enabled) {
                val patch = Intent(YoutubeApkPolicy.MORPHE_ACTION_PATCH_APP)
                    .setClassName(YoutubeApkPolicy.MORPHE_MANAGER_PACKAGE, YoutubeApkPolicy.MORPHE_MANAGER_ACTIVITY)
                    .putExtra(YoutubeApkPolicy.MORPHE_EXTRA_PATCH_PACKAGE, YoutubeApkPolicy.STOCK_YOUTUBE_PACKAGE)
                runCatching { startActivity(patch) }.onFailure { browse(YoutubeApkPolicy.MORPHE_MANAGER_URL) }
            }
            button(box, "How the Rokid patches work", enabled) { browse(YoutubeApkPolicy.ROKID_PATCHES_README_URL) }
            button(box, "Choose patched YouTube APK", enabled) {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                pickPending = true
                runCatching { startActivityForResult(picker, PICK_APK) }
                    .onFailure {
                        pickPending = false
                        toast("No file picker is available on this phone.")
                    }
            }
            button(box, "Install prepared APK", enabled && state.canInstall) { command(YoutubeSetupController.INSTALL) }
        }
    }

    private fun status(checked: Boolean, done: Boolean, attention: Boolean = false): String = when {
        attention -> "Needs attention"
        done -> "Done"
        !checked -> "Needs attention — refresh glasses apps"
        else -> "To do"
    }

    private fun more(parent: LinearLayout, key: String, label: String = "More", actions: (LinearLayout) -> Unit) {
        val extras = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (key in expanded) android.view.View.VISIBLE else android.view.View.GONE
        }
        parent.addView(NexusUi.textButton(this, label).apply {
            setOnClickListener {
                val open = expanded.add(key)
                if (!open) expanded.remove(key)
                extras.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            }
        }, NexusUi.block())
        actions(extras)
        parent.addView(extras, NexusUi.block())
    }

    private fun card(title: String, body: String, actions: (LinearLayout) -> Unit) {
        content.addView(BusTheme.gap(this, 16))
        content.addView(NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(this@YoutubeSetupActivity, title), NexusUi.block())
            addView(BusTheme.gap(this@YoutubeSetupActivity, 7))
            addView(NexusUi.cardBody(this@YoutubeSetupActivity, body), NexusUi.block())
            actions(this)
        }, NexusUi.block())
    }

    private fun button(parent: LinearLayout, label: String, enabled: Boolean, action: () -> Unit) {
        parent.addView(BusTheme.gap(this, 10))
        parent.addView(NexusUi.outlinePillButton(this, label).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else .45f
            setOnClickListener { action() }
        }, NexusUi.block())
    }

    private fun command(action: String, uri: Uri? = null) {
        YoutubeSetupCommands.submit(Intent(action).setData(uri))
    }

    @Deprecated("Uses the Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PATCH_APK) {
            if (!patchPending) return
            patchPending = false
            val launchedIdentity = patcherIdentity
            patcherIdentity = null
            if (resultCode == RESULT_OK && !YoutubePatcherHandoff.acceptsResult(
                    launchedIdentity, YoutubePatcherHandoff.authenticatedIdentity(this))) {
                toast("YouTube Patcher changed or is no longer approved. Review it and patch again.")
            } else if (resultCode == RESULT_OK) {
                val uri = YoutubePatcherHandoff.resultUri(data)
                if (uri == null) toast("The patcher did not return a readable APK. Try again.")
                else submitResult(YoutubeSetupController.PATCH_AND_INSTALL, uri, "patch it again")
            }
            rerender()
        } else if (requestCode == PICK_APK) {
            pickPending = false
            if (resultCode == RESULT_OK) data?.data?.takeIf { it.scheme == "content" }?.let {
                submitResult(YoutubeSetupController.IMPORT_YOUTUBE, it, "choose the APK again")
            }
        }
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
    }
}
