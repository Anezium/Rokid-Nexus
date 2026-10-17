package com.anezium.rokidbus.phone

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PatcherContract
import com.anezium.rokidbus.shared.RedditSetupContract

/**
 * Patcher → Reddit. Opened only through [PatcherSetupEntryActivity]; like the YouTube setup it
 * reads as a Patcher screen while the hub keeps inventory, installs and the keyboard. Account
 * credentials remain in official Reddit and the transient remote keyboard.
 */
class RedditSetupActivity : Activity() {
    private lateinit var content: LinearLayout
    private var unsubscribe: (() -> Unit)? = null
    private var patchPending = false
    private var patchRequestCode = 100
    private var patcherIdentity: String? = null
    private var skipRefreshOnce = false
    // API 33+ platform Back callback. Android 16 with targetSdk 36 never calls onBackPressed.
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        patchPending = savedInstanceState?.getBoolean("patchPending") ?: false
        patchRequestCode = savedInstanceState?.getInt("patchRequestCode", 100) ?: 100
        patcherIdentity = savedInstanceState?.getString("patcherIdentity")
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@RedditSetupActivity,
                // Multi-tone mark: untinted, as on Patcher's Reddit card.
                NexusUi.iconTileImage(this@RedditSetupActivity, R.drawable.ic_app_reddit, 48).apply { imageTintList = null },
                "Reddit on glasses", "Patcher · Glasses setup",
            ) { navigateBack() }, NexusUi.block())
            addView(NexusUi.screen(this@RedditSetupActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        unsubscribe = RedditSetupStateStore.observe(::render)
        if (!skipRefreshOnce && !patchPending && !RedditSetupStateStore.state.busy) command(YoutubeSetupController.REFRESH)
        skipRefreshOnce = false
    }

    override fun onResume() {
        super.onResume()
        if (patchPending && !PatcherHandoff.acceptsResult(patcherIdentity, PatcherHandoff.authenticatedIdentity(this))) {
            patchPending = false
            patcherIdentity = null
            finishActivity(patchRequestCode)
            toast("Patcher changed or is no longer approved. Review it and patch again.")
        }
        if (::content.isInitialized) render(RedditSetupStateStore.state)
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDestroy()
    }

    /** Legacy fallback for the platforms that still dispatch Back here; see [YoutubeSetupActivity]. */
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

    /** One screen: system Back and the header arrow both return to the Patcher screen that opened it. */
    private fun navigateBack() = finish()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("patchPending", patchPending)
        outState.putInt("patchRequestCode", patchRequestCode)
        outState.putString("patcherIdentity", patcherIdentity)
        super.onSaveInstanceState(outState)
    }

    private fun render(state: YoutubeSetupState) {
        content.removeAllViews()
        content.addView(NexusUi.cardBody(this, "Official Reddit with the glasses HUD. Your account stays on the glasses."), NexusUi.block())
        content.addView(NexusUi.cardBody(this, state.message), NexusUi.block())
        val enabled = !state.busy
        val installed = state.inventory?.apps?.singleOrNull { it.packageName == RedditSetupContract.REDDIT }
        card("1. Official Reddit APK", "Download Reddit ${RedditSetupContract.VERSION_NAME} as a complete APKM bundle. Patcher verifies the stock APK and all required splits.") {
            button(this, "Download Reddit ${RedditSetupContract.VERSION_NAME}", enabled) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RedditApkPolicy.SOURCE_URL))) }
                    .onFailure { toast("No browser is available on this phone.") }
            }
        }
        card("2. Patch and install", when {
            state.canInstall -> "${state.preparedLabel} is checked and ready to install."
            patchPending -> "Waiting for Patcher. It keeps working while you use other apps."
            installed?.installed == true -> "Reddit is installed. Updates must use the same signing key."
            else -> "Choose the official bundle in Patcher. Keep Rokid Reddit controls, Spoof signature and Hide ads selected."
        }) {
            button(this, if (state.canInstall) "Install on glasses" else "Patch and install", enabled && !patchPending) {
                if (state.canInstall) command(YoutubeSetupController.INSTALL) else patch()
            }
            if (patchPending) button(this, "Open Patcher", enabled) {
                finishActivity(patchRequestCode)
                patchPending = false
                patcherIdentity = null
                patch()
            }
            button(this, "Refresh glasses apps", enabled && !patchPending) { command(YoutubeSetupController.REFRESH) }
        }
        card("3. Sign in and reply", "Open Reddit, sign in using Keyboard & remote, then review the exact post or comment and your reply before selecting Send.") {
            button(this, "Open Reddit on glasses", enabled && installed?.launchable == true) { command(YoutubeSetupController.OPEN_YOUTUBE) }
            button(this, "Keyboard & remote", enabled) {
                startActivity(Intent(this@RedditSetupActivity, RemoteInputActivity::class.java)
                    .putExtra(RemoteInputActivity.EXTRA_SECURE_SESSION, true))
            }
            val settings = YoutubeKeyboardSettings(this@RedditSetupActivity)
            addView(LinearLayout(this@RedditSetupActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(NexusUi.rowTitle(this@RedditSetupActivity, "Auto-open phone keyboard"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(NexusUi.switch(this@RedditSetupActivity).apply {
                    contentDescription = "Auto-open Reddit keyboard"
                    isChecked = settings.redditAutoOpen
                    setOnCheckedChangeListener { _, checked -> settings.redditAutoOpen = checked }
                })
            }, NexusUi.block())
        }
    }

    private fun patch() {
        val identity = PatcherHandoff.authenticatedIdentity(this)
        if (identity == null) {
            startActivity(PatcherHandoff.reviewIntent(this))
            return
        }
        patcherIdentity = identity
        patchPending = true
        patchRequestCode++
        render(RedditSetupStateStore.state)
        runCatching { startActivityForResult(PatcherHandoff.patchIntent(this, PatcherContract.TARGET_REDDIT), patchRequestCode) }
            .onFailure {
                patchPending = false
                patcherIdentity = null
                render(RedditSetupStateStore.state)
                toast("Patcher could not open. Update it from the Store.")
            }
    }

    @Deprecated("Uses the Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!patchPending || requestCode != patchRequestCode) return
        patchPending = false
        val identity = patcherIdentity
        patcherIdentity = null
        if (resultCode == RESULT_OK) {
            when {
                !PatcherHandoff.acceptsResult(identity, PatcherHandoff.authenticatedIdentity(this)) ->
                    toast("Patcher changed or is no longer approved. Review it and patch again.")
                RedditSetupStateStore.state.busy -> toast("Another Reddit operation is running. Patch again after it finishes.")
                else -> {
                    val uri = PatcherHandoff.resultUri(data, PatcherContract.TARGET_REDDIT)
                    if (uri == null) toast("Patcher did not return a readable Reddit APK.")
                    else {
                        skipRefreshOnce = true
                        RedditSetupCommands.submit(Intent(YoutubeSetupController.PATCH_AND_INSTALL).setData(uri))
                    }
                }
            }
        }
        render(RedditSetupStateStore.state)
    }

    private fun card(title: String, body: String, actions: LinearLayout.() -> Unit) {
        content.addView(BusTheme.gap(this, 16))
        content.addView(NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(this@RedditSetupActivity, title), NexusUi.block())
            addView(NexusUi.cardBody(this@RedditSetupActivity, body), NexusUi.block())
            actions()
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
    private fun command(action: String) = RedditSetupCommands.submit(Intent(action))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        internal fun intent(context: Context): Intent = Intent(context, RedditSetupActivity::class.java)
    }
}
