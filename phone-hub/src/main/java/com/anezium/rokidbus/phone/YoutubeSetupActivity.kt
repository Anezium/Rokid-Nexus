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

/** No Google account information enters this screen; sign-in stays inside MicroG on glasses. */
class YoutubeSetupActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var message: TextView
    private var unsubscribe: (() -> Unit)? = null
    private var rendered: YoutubeSetupState? = null
    private var refreshed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshed = savedInstanceState?.getBoolean("refreshed") ?: false
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
        unsubscribe = YoutubeSetupStateStore.observe(::render)
        if (!refreshed) {
            refreshed = true
            command(YoutubeSetupController.REFRESH)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("refreshed", refreshed)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    private fun render(state: YoutubeSetupState) {
        if (rendered?.copy(message = state.message) == state) {
            message.text = state.message
            rendered = state
            return
        }
        rendered = state
        content.removeAllViews()
        content.addView(NexusUi.cardTitle(this, "YouTube on glasses"), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(NexusUi.cardBody(this,
            "Use your Google account with Morphe YouTube, ad blocking and SponsorBlock. Both apps run on the glasses."), NexusUi.block())
        message = NexusUi.cardBody(this, state.message)
        content.addView(BusTheme.gap(this, 16))
        content.addView(message, NexusUi.block())
        button(content, "Refresh glasses apps", !state.busy) { command(YoutubeSetupController.REFRESH) }

        card("Phone keyboard",
            "Automatically open the phone keyboard when a YouTube field takes focus on the glasses. " +
                "If Android prevents opening it, tap the keyboard notification. " +
                "When off, use Keyboard & remote manually. Changes apply to the next field you open.") { box ->
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

        state.preparedLabel?.let { label ->
            card("Ready to install", label) { box ->
                button(box, "Install / update on glasses", state.canInstall && !state.busy) {
                    command(YoutubeSetupController.INSTALL)
                }
            }
        }
        val microG = state.inventory?.apps?.single { it.packageName == YoutubeSetupContract.MICROG }
        val youtubeApps = state.inventory?.apps?.filter { it.packageName != YoutubeSetupContract.MICROG && it.installed }
        card("1. MicroG-RE", when {
            microG == null -> "Refresh to check whether MicroG is installed."
            microG.installed -> "Installed on the glasses. Download the latest release to update it."
            else -> "Install MicroG once to connect your Google account."
        }) { box ->
            button(box, "Get latest MicroG-RE", !state.busy) { command(YoutubeSetupController.PREPARE_MICROG) }
            button(box, "Morphe MicroG-RE source", !state.busy) {
                browse("https://github.com/MorpheApp/MicroG-RE")
            }
        }
        card("2. Patched YouTube", buildString {
            if (!youtubeApps.isNullOrEmpty()) append("YouTube is installed on the glasses. ")
            append("Download stock YouTube ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION} from APKMirror: pick the APK variant, not a bundle. ")
            append("Add the Rokid patches to Morphe once, then patch it from the Rokid glasses source: ")
            append("Rokid controls, GmsCore support, Hide ads and SponsorBlock are selected by default, ")
            append("and Morphe's Expert mode lets you change that selection. ")
            append("Then choose the patched APK here. To update, use an APK signed with the same key.")
        }) { box ->
            button(box, "Get YouTube ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}", !state.busy) {
                browse(YoutubeApkPolicy.STOCK_YOUTUBE_URL)
            }
            button(box, "Add Rokid patches to Morphe", !state.busy) {
                val source = Intent(Intent.ACTION_VIEW, Uri.parse(YoutubeApkPolicy.ROKID_PATCHES_SOURCE_URL))
                    .setPackage(YoutubeApkPolicy.MORPHE_MANAGER_PACKAGE)
                runCatching { startActivity(source) }.onFailure { browse(YoutubeApkPolicy.MORPHE_MANAGER_URL) }
            }
            button(box, "Patch with Morphe", !state.busy) {
                val patch = Intent(YoutubeApkPolicy.MORPHE_ACTION_PATCH_APP)
                    .setClassName(YoutubeApkPolicy.MORPHE_MANAGER_PACKAGE, YoutubeApkPolicy.MORPHE_MANAGER_ACTIVITY)
                    .putExtra(YoutubeApkPolicy.MORPHE_EXTRA_PATCH_PACKAGE, YoutubeApkPolicy.STOCK_YOUTUBE_PACKAGE)
                runCatching { startActivity(patch) }.onFailure { browse(YoutubeApkPolicy.MORPHE_MANAGER_URL) }
            }
            button(box, "How the Rokid patches work", !state.busy) {
                browse(YoutubeApkPolicy.ROKID_PATCHES_README_URL)
            }
            button(box, "Choose patched YouTube APK", !state.busy) {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivityForResult(picker, PICK_APK) }
                    .onFailure { toast("No file picker is available on this phone.") }
            }
        }
        card("3. Connect your account",
            "Open MicroG on the glasses and choose Add account. Use the phone keyboard and remote to complete sign-in, including any Google verification. Your account stays on the glasses.") { box ->
            button(box, "Open MicroG on glasses", !state.busy && microG?.launchable == true) {
                command(YoutubeSetupController.OPEN_MICROG)
            }
            if (microG?.installed == true && !microG.launchable) {
                box.addView(NexusUi.cardBody(this,
                    "MicroG has no launcher icon. Install the release offered above to make it accessible here."), NexusUi.block())
            }
            button(box, "Keyboard & remote", !state.busy) {
                startActivity(Intent(this, RemoteInputActivity::class.java)
                    .putExtra(RemoteInputActivity.EXTRA_SECURE_SESSION, true))
            }
        }
        card("4. Watch YouTube",
            "Open YouTube and check that your account appears. In Settings > Morphe, check ad blocking and set SponsorBlock to skip the categories you want automatically.") { box ->
            button(box, "Open YouTube on glasses", !state.busy && youtubeApps?.any { it.launchable } == true) {
                command(YoutubeSetupController.OPEN_YOUTUBE)
            }
        }
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
        if (requestCode == PICK_APK && resultCode == RESULT_OK) {
            data?.data?.takeIf { it.scheme == "content" }?.let { command(YoutubeSetupController.IMPORT_YOUTUBE, it) }
        }
    }

    private fun browse(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("No browser is available on this phone.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object { private const val PICK_APK = 41 }
}
