package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PatcherContract as Contract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Patcher's settings screen: two equal app targets, signing-key maintenance and the
 * canonical uninstall card. Both apps' guided setups belong to the hub; an older hub that
 * cannot open one leaves that app patching here.
 */
class PatcherHomeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var jobs: PatchJobStore
    private lateinit var key: SigningKey
    private lateinit var appsHost: LinearLayout
    private lateinit var keyHost: LinearLayout
    private var keyMoreOpen = false
    private var keyNote: Pair<String, Int>? = null
    private var backupPassword: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        jobs = PatchJobStore.get(this)
        key = SigningKey(File(filesDir, "signing/patcher.p12"))
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.cardBody(this@PatcherHomeActivity,
                "Patch official apps on this phone so they work with your glasses. Patching runs locally, with no cloud upload."), NexusUi.block())
            addView(BusTheme.gap(this@PatcherHomeActivity, 18))
            addView(NexusUi.sectionRow(this@PatcherHomeActivity, "Apps"), NexusUi.block())
            addView(BusTheme.gap(this@PatcherHomeActivity, 8))
            appsHost = LinearLayout(this@PatcherHomeActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(appsHost, NexusUi.block())
            section(this, "Maintenance")
            keyHost = LinearLayout(this@PatcherHomeActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(keyHost, NexusUi.block())
            section(this, "Plugin")
            addView(NexusUi.uninstallCard(this@PatcherHomeActivity, "Patcher") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }, NexusUi.block())
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@PatcherHomeActivity, com.anezium.rokidbus.client.R.drawable.ic_plugin_bolt,
                "Patcher", "Phone-only · v1.0.0"), NexusUi.block())
            addView(NexusUi.screen(this@PatcherHomeActivity, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        scope.launch {
            // Progress ticks every second; the cards only change with the job's outline.
            jobs.state.map { listOf(it.targetId, it.status, it.stock, it.result) }.distinctUntilChanged()
                .combine(jobs.keyMaintenance) { _, _ -> }.collect { renderApps(); renderKey() }
        }
    }

    private fun section(parent: LinearLayout, label: String) {
        parent.addView(BusTheme.gap(this, 28))
        parent.addView(NexusUi.sectionRow(this, label), NexusUi.block())
        parent.addView(BusTheme.gap(this, 8))
    }

    private fun renderApps() {
        appsHost.removeAllViews()
        PatchTargets.all.forEachIndexed { index, target ->
            if (index > 0) appsHost.addView(BusTheme.gap(this, 12))
            appsHost.addView(appCard(target), NexusUi.block())
        }
    }

    private fun appCard(target: PatchTarget): LinearLayout {
        val state = jobs.state.value
        val mine = state.targetId == target.id
        val running = mine && state.active
        val lockedBy = PatchTargets.find(state.targetId)?.takeIf { state.active && !mine }
        val youtube = target.id == Contract.TARGET_YOUTUBE
        val ready = mine && state.status == PatchJobStatus.SUCCESS && jobs.result(state) != null
        val stopped = mine && state.status in setOf(PatchJobStatus.FAILURE, PatchJobStatus.INTERRUPTED)
        // The sentence is sans body copy; the mono line stays short enough for one line at 360 dp.
        val summary = when {
            running && state.status == PatchJobStatus.PREPARING -> "Checking your official file. Leaving this screen does not stop it."
            running -> "Adding the glasses controls. Leaving this screen does not stop it."
            ready -> "Install it on the glasses from the ${target.displayName} steps."
            youtube -> "Adds the glasses controls. Guided steps: MicroG, official file, patch and install, sign in."
            else -> "Adds a glasses HUD and reviewed replies. Guided steps: official file, patch and install, sign in; no MicroG needed."
        }
        val status = when {
            running && state.status == PatchJobStatus.PREPARING -> "Checking file"
            running -> "Patching"
            ready -> "Patched APK ready"
            stopped -> "Stopped · open to retry"
            mine && state.stock != null -> "File checked · ready"
            youtube -> "Glasses setup · 4 steps"
            else -> "Glasses setup · 3 steps"
        }
        val label = when {
            running -> "Open running job"
            else -> "Set up ${target.displayName}"
        }
        return NexusUi.card(this).apply {
            addView(LinearLayout(this@PatcherHomeActivity).apply {
                gravity = Gravity.TOP
                addView(AppTargets.mark(this@PatcherHomeActivity, target, 44))
                addView(LinearLayout(this@PatcherHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(this@PatcherHomeActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(NexusUi.cardTitle(this@PatcherHomeActivity, target.displayName))
                        if (target.preview) addView(NexusUi.metaLabel(this@PatcherHomeActivity, "Preview", NexusUi.AMBER),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = NexusUi.dp(this@PatcherHomeActivity, 10)
                            })
                    })
                    addView(BusTheme.gap(this@PatcherHomeActivity, 4))
                    addView(NexusUi.rowSub(this@PatcherHomeActivity, status).apply {
                        // Wraps rather than clipping when the user enlarges text.
                        maxLines = 2; ellipsize = null
                        when {
                            running || ready -> setTextColor(NexusUi.GREEN_DIM)
                            stopped -> setTextColor(NexusUi.AMBER)
                        }
                    })
                    addView(BusTheme.gap(this@PatcherHomeActivity, 6))
                    addView(NexusUi.cardBody(this@PatcherHomeActivity, summary))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = NexusUi.dp(this@PatcherHomeActivity, 14)
                })
            }, NexusUi.block())
            addView(BusTheme.gap(this@PatcherHomeActivity, 14))
            val enabled = lockedBy == null
            addView(NexusUi.outlinePillButton(this@PatcherHomeActivity, label).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else .45f
                setOnClickListener { open(target) }
            }, NexusUi.block())
            lockedBy?.let {
                addView(BusTheme.gap(this@PatcherHomeActivity, 8))
                addView(NexusUi.rowSub(this@PatcherHomeActivity, "Locked while ${it.displayName} is being patched").apply {
                    maxLines = 2; ellipsize = null
                    setTextColor(NexusUi.AMBER)
                }, NexusUi.block())
            }
        }
    }

    private fun open(target: PatchTarget) {
        val state = jobs.state.value
        // The disabled card covers the visible path; a late tap still cannot switch a running job.
        if (state.active && state.targetId != target.id) {
            toast("Locked while ${PatchTargets.find(state.targetId)?.displayName ?: "another app"} is being patched.")
            return
        }
        val hint = AppTargets.jobHint(state, target, jobs.result(state) != null)
        if (!(state.active && state.targetId == target.id) && AppTargets.hasHubSetup(this, target)) {
            if (AppTargets.openHubSetup(this, target, hint, REQUEST_HUB_SETUP)) return
        } else if (target.id in Contract.SETUP_TARGETS && !state.active) {
            toast("Update Nexus to set up ${target.displayName} on the glasses. You can still patch it here.")
        }
        startActivity(Intent(this, PatchActivity::class.java).putExtra(Contract.EXTRA_TARGET_ID, target.id))
    }

    // ---- Signing key: the only key both apps' outputs are signed with ----

    private fun renderKey() {
        keyHost.removeAllViews()
        val locked = jobs.keyMaintenance.value || jobs.state.value.active
        keyHost.addView(NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(this@PatcherHomeActivity, "Signing key"), NexusUi.block())
            addView(BusTheme.gap(this@PatcherHomeActivity, 6))
            addView(NexusUi.cardBody(this@PatcherHomeActivity,
                "Apps patched here only update from the key that signed them. Uninstalling this plugin deletes that key, so export a backup now."), NexusUi.block())
            keyNote?.let { (text, color) ->
                addView(BusTheme.gap(this@PatcherHomeActivity, 8))
                addView(NexusUi.statusLine(this@PatcherHomeActivity).apply {
                    this.text = text; textSize = 13f; setTextColor(color)
                }, NexusUi.block())
            }
            addView(BusTheme.gap(this@PatcherHomeActivity, 6))
            addView(LinearLayout(this@PatcherHomeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(quiet(if (keyMoreOpen) "Less" else "More") { keyMoreOpen = !keyMoreOpen; renderKey() })
                addView(View(this@PatcherHomeActivity), LinearLayout.LayoutParams(0, 0, 1f))
                addView(quiet("Import key", enabled = !locked) {
                    AlertDialog.Builder(this@PatcherHomeActivity).setTitle("Replace signing key?")
                        .setMessage("Future patches will use the imported key. The installed app must have the same signer to update.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ -> askPassword(true) }.show()
                })
                addView(quiet("Export key", enabled = !locked) { askPassword(false) })
            }, NexusUi.block())
            if (keyMoreOpen) {
                addView(BusTheme.gap(this@PatcherHomeActivity, 4))
                addView(NexusUi.cardBody(this@PatcherHomeActivity, "The backup is a password-protected file you can import into a fresh install of this plugin. If Nexus stops an update on a different signer, import the backup that signed the installed app; Nexus never uninstalls it for you.\n\nAn app patched with Morphe Manager uses Manager's key, which this backup format cannot import: keep updating that build with Manager, or uninstall that app on the glasses by hand before installing a build from this plugin.").apply { textSize = 12f; setTextColor(NexusUi.INK3) }, NexusUi.block())
            }
        }, NexusUi.block())
    }

    private fun quiet(label: String, enabled: Boolean = true, action: () -> Unit): Button =
        NexusUi.textButton(this, label).apply { isEnabled = enabled; alpha = if (enabled) 1f else .4f; setOnClickListener { action() } }

    private fun note(text: String, color: Int) { keyNote = text to color; renderKey() }

    private fun askPassword(importing: Boolean) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val field = EditText(this).apply { inputType = 129; hint = "Backup password (at least 8 characters)" }
        AlertDialog.Builder(this).setTitle(if (importing) "Import key" else "Export key").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ ->
                val password = field.text.toString().toCharArray(); field.text.clear()
                if (password.size < 8) { password.fill('\u0000'); note("Use at least eight characters.", NexusUi.DANGER) }
                else {
                    backupPassword?.fill('\u0000'); backupPassword = password
                    startActivityForResult(Intent(if (importing) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                        if (!importing) putExtra(Intent.EXTRA_TITLE, "patcher-signing-key.ypk")
                    }, if (importing) REQUEST_IMPORT else REQUEST_EXPORT)
                }
            }.setOnDismissListener { field.text.clear() }.show()
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) return
        val password = backupPassword
        backupPassword = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || password == null) { password?.fill('\u0000'); return }
        // The job signs with this key: the store refuses this while a job runs, and refuses jobs until it ends.
        val lease = try { jobs.beginKeyMaintenance() } catch (e: IllegalStateException) {
            password.fill('\u0000')
            note(PatchErrors.reason(e, "Wait for the running patch to finish, then try again."), NexusUi.AMBER)
            return
        }
        // Undispatched: the finally below owns the lease even if this screen is destroyed before dispatch.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    if (requestCode == REQUEST_EXPORT) contentResolver.openOutputStream(uri, "wt").use { key.export(requireNotNull(it), password) }
                    else contentResolver.openInputStream(uri).use { key.import(requireNotNull(it), password) }
                }
                note(if (requestCode == REQUEST_EXPORT) "Password-protected key exported." else "Signing key imported.", NexusUi.INK)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { note(PatchErrors.reason(e, "Could not ${if (requestCode == REQUEST_EXPORT) "export" else "import"} the key."), NexusUi.DANGER) }
            finally { password.fill('\u0000'); jobs.endKeyMaintenance(lease) }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        scope.cancel()
        backupPassword?.fill('\u0000'); backupPassword = null
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_EXPORT = 2
        const val REQUEST_IMPORT = 3
        const val REQUEST_HUB_SETUP = 6
    }
}
