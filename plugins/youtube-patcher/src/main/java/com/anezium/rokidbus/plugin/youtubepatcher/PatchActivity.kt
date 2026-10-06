package com.anezium.rokidbus.plugin.youtubepatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.YoutubePatcherContract as Contract
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class PatchActivity : Activity() {
    /** Where a status message belongs on screen: each step card shows only its own. */
    private enum class Slot { STOCK, BUNDLE, PATCH, KEY }
    private enum class Tone { INFO, OK, WARN, ERROR }
    private class Note(val text: String, val tone: Tone)
    private class NoteView(val row: View, val dot: View, val text: TextView)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var stockHost: LinearLayout
    private lateinit var patchesHost: LinearLayout
    private lateinit var actionHost: LinearLayout
    private lateinit var keyHost: LinearLayout
    private lateinit var detailsHost: LinearLayout
    private val notes = mutableMapOf<Slot, Note>()
    private val noteViews = mutableMapOf<Slot, NoteView>()
    private var slot = Slot.PATCH
    private var elapsed: TextView? = null
    private var patchStartedAt = 0L
    private var allPatchesOpen = false
    private var keyMoreOpen = false
    private var detailsOpen = false
    private lateinit var bundleStore: BundleStore
    private lateinit var selections: SelectionStore
    private lateinit var key: SigningKey
    private var bundle: BundleStore.Loaded? = null
    private var choices = mutableMapOf<String, Boolean>()
    private var stock: File? = null
    private var stockName: String? = null
    private lateinit var work: File
    private var busy = false
    private var patching = false
    private var result: File? = null
    private var backupPassword: CharArray? = null
    private var screenLock: java.nio.channels.FileLock? = null
    private var lockFile: java.io.RandomAccessFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockFile = java.io.RandomAccessFile(File(filesDir, "screen.lock"), "rw")
        screenLock = try { lockFile!!.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        if (screenLock == null) {
            AlertDialog.Builder(this).setMessage("YouTube Patcher is already open. Close the other screen first.")
                .setPositiveButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        bundleStore = BundleStore(this)
        selections = SelectionStore(File(filesDir, "selection.json"))
        key = SigningKey(File(filesDir, "signing/youtube.p12"))
        // Nothing in these directories is an accepted result. Clear crash leftovers.
        File(cacheDir, "jobs").deleteRecursively()
        File(filesDir, "results").listFiles()?.filter { it.name.endsWith(".partial") }?.forEach { it.delete() }
        purgeResults(null)
        work = File(cacheDir, "jobs/${UUID.randomUUID()}").apply { mkdirs() }
        build()
        perform(Slot.BUNDLE) {
            val loaded = withContext(Dispatchers.IO) { bundleStore.current() }
            adopt(loaded)
            var notice = loaded.notice
            try {
                val update = withContext(Dispatchers.IO) { bundleStore.checkForUpdate(loaded.version) }
                if (update.switched) withContext(Dispatchers.IO) { bundleStore.current() }.also { adopt(it); notice = it.notice }
                report(listOfNotNull(notice, update.message).joinToString("\n"), if (notice == null) Tone.INFO else Tone.WARN)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(listOfNotNull(notice, "Using saved bundle. Update check: ${e.message}").joinToString("\n"), Tone.WARN) }
        }
    }
    private fun purgeResults(keep: File?) {
        val results = File(filesDir, "results").listFiles()?.filter { PatchPolicy.isResult(it.name) } ?: return
        PatchPolicy.expiredResults(results.associateWith { it.lastModified() }, System.currentTimeMillis(), keep).forEach { it.delete() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest keeps this activity alive across configuration changes so a running
        // patch survives; rebuild only the views so sizes follow the new configuration.
        if (::content.isInitialized) build()
    }
    private fun adopt(loaded: BundleStore.Loaded) {
        bundle = loaded
        choices = selections.load(loaded.version, loaded.patches.associate { it.name!! to it.default }).toMutableMap()
        selections.save(loaded.version, choices)
        renderPatches(); renderAction(); renderDetails()
    }

    // ---- Screen skeleton: built once, sections re-render into their own host ----

    private fun build() {
        noteViews.clear()
        content = NexusUi.contentColumn(this)
        content.addView(NexusUi.cardBody(this, "Adds the glasses controls to YouTube. Pick Google's stock ${PatchPolicy.VERSION} release, review the patches, then patch. Your APK never leaves this phone."), NexusUi.block())
        content.addView(BusTheme.gap(this, 18))
        stockHost = host(); content.addView(BusTheme.gap(this, 12))
        patchesHost = host(); content.addView(BusTheme.gap(this, 12))
        actionHost = host()
        section("Signing key"); keyHost = host()
        section("Details"); detailsHost = host()
        section("Plugin")
        content.addView(NexusUi.uninstallCard(this, "YouTube Patcher") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())
        val root = NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@PatchActivity, com.anezium.rokidbus.client.R.drawable.ic_plugin_bolt, "YouTube Patcher", "Phone-only · v1.0.0"), NexusUi.block())
            addView(NexusUi.screen(this@PatchActivity, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        renderAll()
    }
    private fun host(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { content.addView(it, NexusUi.block()) }
    private fun section(label: String) {
        content.addView(BusTheme.gap(this, 28))
        content.addView(NexusUi.sectionRow(this, label), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
    }
    private fun renderAll() { renderStock(); renderPatches(); renderAction(); renderKey(); renderDetails() }
    private fun dp(value: Int) = NexusUi.dp(this, value)

    private fun renderStock() {
        stockHost.removeAllViews()
        val validating = busy && slot == Slot.STOCK
        stockHost.addView(NexusUi.card(this).apply {
            addView(stepHeader(1, "Stock YouTube", done = stock != null, active = stock == null), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            when {
                validating -> {
                    addView(LinearLayout(this@PatchActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(spinner(18))
                        addView(NexusUi.cardBody(this@PatchActivity, "Reading and validating ${stockName ?: "the file"}"),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                    }, NexusUi.block())
                }
                stock != null -> {
                    addView(noteView(Slot.STOCK), NexusUi.block())
                    stockName?.let { addView(BusTheme.gap(this@PatchActivity, 4)); addView(NexusUi.rowSub(this@PatchActivity, it), NexusUi.block()) }
                    addView(BusTheme.gap(this@PatchActivity, 4))
                    addView(quiet("Change file") { picker(REQUEST_STOCK, Intent.ACTION_OPEN_DOCUMENT, "*/*") }, endAligned())
                }
                else -> {
                    addView(NexusUi.cardBody(this@PatchActivity, "Google's stock YouTube ${PatchPolicy.VERSION}: the APK, or the split bundle from APKMirror."), NexusUi.block())
                    addView(noteView(Slot.STOCK), NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 14))
                    addView(primary("Choose APK or bundle", enabled = !busy) { picker(REQUEST_STOCK, Intent.ACTION_OPEN_DOCUMENT, "*/*") }, NexusUi.block())
                }
            }
        }, NexusUi.block())
        applyNote(Slot.STOCK)
    }

    private fun renderPatches() {
        patchesHost.removeAllViews()
        val loaded = bundle
        patchesHost.addView(NexusUi.card(this).apply {
            addView(stepHeader(2, "Patches", done = false, active = stock != null,
                trailing = loaded?.let { NexusUi.metaLabel(this@PatchActivity, it.version, NexusUi.GREEN_DIM) }), NexusUi.block())
            addView(noteView(Slot.BUNDLE), NexusUi.block())
            if (loaded == null) {
                addView(BusTheme.gap(this@PatchActivity, 6))
                addView(LinearLayout(this@PatchActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(spinner(18))
                    addView(NexusUi.cardBody(this@PatchActivity, "Loading the patch bundle"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                }, NexusUi.block())
                return@apply
            }
            val (featured, others) = loaded.patches.partition { BundleStore.priority(it.name!!) < 4 }
            addView(BusTheme.gap(this@PatchActivity, 6))
            featured.forEachIndexed { index, patch ->
                if (index > 0) addView(hairline())
                addView(toggleRow(loaded.version, patch.name!!, patch.description), NexusUi.block())
            }
            if (others.isEmpty()) return@apply
            addView(hairline())
            val enabledCount = others.count { choices[it.name] == true }
            addView(LinearLayout(this@PatchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                background = NexusUi.pressed(this@PatchActivity, Color.TRANSPARENT, 10)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                contentDescription = if (allPatchesOpen) "Hide all patches" else "Show all patches"
                addView(LinearLayout(this@PatchActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@PatchActivity, "All patches"))
                    addView(BusTheme.gap(this@PatchActivity, 4))
                    addView(NexusUi.rowSub(this@PatchActivity, "${others.size} more · $enabledCount on"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(NexusUi.chevron(this@PatchActivity).apply { rotation = if (allPatchesOpen) 90f else 0f })
                setOnClickListener { allPatchesOpen = !allPatchesOpen; renderPatches() }
            }, NexusUi.block())
            if (!allPatchesOpen) return@apply
            others.forEach { patch ->
                addView(hairline())
                addView(toggleRow(loaded.version, patch.name!!, null), NexusUi.block())
            }
        }, NexusUi.block())
        applyNote(Slot.BUNDLE)
    }
    private fun toggleRow(version: String, name: String, description: String?): LinearLayout {
        val control = NexusUi.switch(this).apply {
            isChecked = choices[name] == true
            isEnabled = !busy
            setOnCheckedChangeListener { _, checked ->
                choices[name] = checked
                try { selections.save(version, choices) } catch (e: Exception) { report(e.message ?: "Cannot save choices.", Tone.ERROR, Slot.BUNDLE) }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            alpha = if (busy) .55f else 1f
            addView(NexusUi.switchRow(this@PatchActivity, name, control = control), NexusUi.block())
            if (!description.isNullOrBlank()) {
                addView(BusTheme.gap(this@PatchActivity, 4))
                addView(NexusUi.cardBody(this@PatchActivity, description).apply { textSize = 12f; setTextColor(NexusUi.INK3) },
                    NexusUi.block().apply { marginEnd = dp(56) })
            }
        }
    }

    private fun renderAction() {
        actionHost.removeAllViews()
        handler.removeCallbacks(ticker); elapsed = null
        val ready = stock != null && bundle != null
        val done = result != null
        actionHost.addView(NexusUi.card(this).apply {
            if (!ready && !done) alpha = .55f
            addView(stepHeader(3, "Patch", done = done, active = ready && !done), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            when {
                patching -> {
                    addView(ProgressBar(this@PatchActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(NexusUi.GREEN)
                        progressBackgroundTintList = ColorStateList.valueOf(NexusUi.LINE)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))
                    addView(BusTheme.gap(this@PatchActivity, 6))
                    addView(noteView(Slot.PATCH), NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 6))
                    elapsed = NexusUi.metaLabel(this@PatchActivity, "", NexusUi.INK3).also { addView(it, NexusUi.block()) }
                    tick()
                    addView(BusTheme.gap(this@PatchActivity, 12))
                    addView(NexusUi.cardBody(this@PatchActivity, "Keep this screen open. Closing it cancels the job."), NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 14))
                    addView(NexusUi.pillButton(this@PatchActivity, "Cancel patching", danger = true).apply { setOnClickListener { cancelAndClose() } }, NexusUi.block())
                }
                done -> {
                    addView(noteView(Slot.PATCH), NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 14))
                    addView(primary("Share patched APK", enabled = !busy) { result?.let(::shareResult) }, NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 8))
                    addView(NexusUi.outlinePillButton(this@PatchActivity, "Save patched APK").apply {
                        isEnabled = !busy; alpha = if (busy) .45f else 1f
                        setOnClickListener { picker(REQUEST_SAVE, Intent.ACTION_CREATE_DOCUMENT, "application/vnd.android.package-archive", "youtube-patched.apk") }
                    }, NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 4))
                    addView(LinearLayout(this@PatchActivity).apply {
                        gravity = Gravity.END
                        addView(quiet("Patch again", enabled = !busy) { confirmPatch() })
                        addView(quiet("Close") { cancelAndClose() })
                    }, NexusUi.block())
                }
                else -> {
                    addView(NexusUi.cardBody(this@PatchActivity, "Takes about three minutes on the phone. The result is signed with this plugin's key."), NexusUi.block())
                    addView(noteView(Slot.PATCH), NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 14))
                    addView(primary("Patch YouTube", enabled = ready && !busy) { confirmPatch() }, NexusUi.block())
                    addView(BusTheme.gap(this@PatchActivity, 4))
                    addView(quiet("Close") { cancelAndClose() }, endAligned())
                }
            }
        }, NexusUi.block())
        applyNote(Slot.PATCH)
    }
    private val ticker = object : Runnable { override fun run() { tick() } }
    private fun tick() {
        val view = elapsed ?: return
        val seconds = ((System.currentTimeMillis() - patchStartedAt) / 1000).coerceAtLeast(0)
        view.text = "Elapsed %d:%02d · usually about three minutes".format(seconds / 60, seconds % 60).uppercase()
        handler.postDelayed(ticker, 1000)
    }

    private fun renderKey() {
        keyHost.removeAllViews()
        keyHost.addView(NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(this@PatchActivity, "Keep YouTube updatable"), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            addView(NexusUi.cardBody(this@PatchActivity, "YouTube on the glasses only updates from the key that signed it. Uninstalling this plugin deletes that key, so export a backup now."), NexusUi.block())
            addView(noteView(Slot.KEY), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            addView(LinearLayout(this@PatchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(quiet(if (keyMoreOpen) "Less" else "More") { keyMoreOpen = !keyMoreOpen; renderKey() })
                addView(View(this@PatchActivity), LinearLayout.LayoutParams(0, 0, 1f))
                addView(quiet("Import key", enabled = !busy) {
                    AlertDialog.Builder(this@PatchActivity).setTitle("Replace signing key?")
                        .setMessage("Future patches will use the imported key. Existing YouTube must have the same signer to update.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ -> askPassword(true) }.show()
                })
                addView(quiet("Export key", enabled = !busy) { askPassword(false) })
            }, NexusUi.block())
            if (keyMoreOpen) {
                addView(BusTheme.gap(this@PatchActivity, 4))
                addView(NexusUi.cardBody(this@PatchActivity, "The backup is a password-protected file you can import into a fresh install of this plugin.\n\nA YouTube patched with Morphe Manager uses Manager's key, which this backup format cannot import: keep updating that build with Manager, or uninstall YouTube on the glasses by hand before installing a build from this plugin.").apply { textSize = 12f; setTextColor(NexusUi.INK3) }, NexusUi.block())
            }
        }, NexusUi.block())
        applyNote(Slot.KEY)
    }

    private fun renderDetails() {
        detailsHost.removeAllViews()
        val loaded = bundle
        detailsHost.addView(NexusUi.card(this).apply {
            addView(LinearLayout(this@PatchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                background = NexusUi.pressed(this@PatchActivity, Color.TRANSPARENT, 10)
                contentDescription = if (detailsOpen) "Hide details" else "Show details"
                addView(LinearLayout(this@PatchActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@PatchActivity, "Bundle and patcher"))
                    addView(BusTheme.gap(this@PatchActivity, 4))
                    addView(NexusUi.rowSub(this@PatchActivity, "Bundle ${loaded?.version ?: "loading"} · Patcher 1.7.0"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(NexusUi.chevron(this@PatchActivity).apply { rotation = if (detailsOpen) 90f else 0f })
                setOnClickListener { detailsOpen = !detailsOpen; renderDetails() }
            }, NexusUi.block())
            if (!detailsOpen) return@apply
            addView(BusTheme.gap(this@PatchActivity, 12))
            addView(NexusUi.rowSub(this@PatchActivity, listOfNotNull(
                "bundle    ${loaded?.version ?: "—"}",
                "sha-256   ${loaded?.hash ?: "—"}",
                "release   ${loaded?.sourceHash ?: "same as bundle"}",
                "patcher   1.7.0 · aapt-free resources",
            ).joinToString("\n")).apply { maxLines = Int.MAX_VALUE; ellipsize = null; setLineSpacing(dp(3).toFloat(), 1f) }, NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 12))
            addView(NexusUi.cardBody(this@PatchActivity, "Bundle updates must ship publisher-prepared DEX for Patcher 1.7.0. JVM-only fork releases cannot update this plugin on the phone and are ignored; the saved bundle stays in use. Install a plugin built with a supported prepared bundle instead.").apply { textSize = 12f; setTextColor(NexusUi.INK3) }, NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 10))
            addView(NexusUi.cardBody(this@PatchActivity, "Uses Morphe Patcher and the Rokid Morphe Patches fork (GPLv3). No signature is published for the bundle.\nhttps://github.com/Anezium/morphe-patches").apply {
                textSize = 12f; setTextColor(NexusUi.INK3); setLinkTextColor(NexusUi.GREEN_DIM)
                Linkify.addLinks(this, Linkify.WEB_URLS)
            }, NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 4))
            addView(quiet("Licenses and notices") { showLicenses() }, endAligned())
        }, NexusUi.block())
    }
    private fun showLicenses() {
        val notices = assets.open("licenses/MORPHE_LICENSE_NOTICE.TXT").bufferedReader().use { it.readText() }
        val gpl = assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
        val body = NexusUi.contentColumn(this).apply {
            addView(NexusUi.cardBody(this@PatchActivity, "YouTube Patcher modifies the executable packaging of the Rokid fork bundle by adding Android DEX. It is not an official upstream distribution.\n\n$notices\n\n$gpl"), NexusUi.block())
        }
        AlertDialog.Builder(this).setTitle("Licenses and notices").setView(NexusUi.screen(this, body)).setPositiveButton("Close", null).show()
    }

    // ---- Small composites built from the NexusUi vocabulary ----

    private fun stepHeader(number: Int, title: String, done: Boolean, active: Boolean, trailing: View? = null): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(number, done, active))
            addView(NexusUi.cardTitle(this@PatchActivity, title),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            trailing?.let { addView(it) }
        }
    private fun badge(number: Int, done: Boolean, active: Boolean): TextView =
        TextView(this).apply {
            text = if (done) "✓" else number.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(if (done) NexusUi.ON_ACCENT else if (active) NexusUi.GREEN else NexusUi.INK3)
            background = if (done) NexusUi.rounded(this@PatchActivity, NexusUi.GREEN, 999)
            else NexusUi.bordered(this@PatchActivity, if (active) NexusUi.alpha(NexusUi.GREEN, 30) else NexusUi.PANEL, if (active) NexusUi.GREEN else NexusUi.LINE, 999)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        }
    private fun hairline(): View = View(this).apply {
        setBackgroundColor(NexusUi.LINE2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(2); bottomMargin = dp(2) }
    }
    private fun spinner(sizeDp: Int): ProgressBar =
        ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(NexusUi.GREEN)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    private fun primary(label: String, enabled: Boolean, action: () -> Unit): Button =
        NexusUi.pillButton(this, label).apply { isEnabled = enabled; alpha = if (enabled) 1f else .4f; setOnClickListener { action() } }
    private fun quiet(label: String, enabled: Boolean = true, action: () -> Unit): Button =
        NexusUi.textButton(this, label).apply { isEnabled = enabled; alpha = if (enabled) 1f else .4f; setOnClickListener { action() } }
    private fun endAligned() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END }

    /** A dot plus one line of status; hidden until its slot has something to say. */
    private fun noteView(target: Slot): View {
        val dot = NexusUi.dot(this).apply { layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9); topMargin = dp(1) } }
        val text = NexusUi.statusLine(this).apply { textSize = 13f; setLineSpacing(dp(3).toFloat(), 1f) }
        val row = LinearLayout(this).apply {
            setPadding(0, dp(8), 0, 0)
            addView(LinearLayout(this@PatchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(20)
                addView(dot)
            })
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        noteViews[target] = NoteView(row, dot, text)
        return row
    }
    private fun applyNote(target: Slot) {
        val view = noteViews[target] ?: return
        val note = notes[target]
        view.row.visibility = if (note == null) View.GONE else View.VISIBLE
        if (note == null) return
        view.text.text = note.text
        val (ink, dot) = when (note.tone) {
            Tone.INFO -> NexusUi.INK2 to NexusUi.INK3
            Tone.OK -> NexusUi.INK to NexusUi.GREEN
            Tone.WARN -> NexusUi.AMBER to NexusUi.AMBER
            Tone.ERROR -> NexusUi.DANGER to NexusUi.DANGER
        }
        view.text.setTextColor(ink)
        NexusUi.setDotColor(view.dot, dot)
    }
    private fun report(message: String, tone: Tone = Tone.INFO, target: Slot = slot) {
        if (message.isBlank()) notes.remove(target) else notes[target] = Note(message, tone)
        applyNote(target)
    }

    // ---- Work ----

    private fun perform(target: Slot, block: suspend () -> Unit) {
        if (busy) return
        busy = true; slot = target; renderAll()
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { report("Not enough memory to patch YouTube on this phone. No result was saved.", Tone.ERROR, target) }
            catch (e: Exception) { report(e.message ?: e.javaClass.simpleName, Tone.ERROR, target) }
            finally {
                busy = false; patching = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isFinishing && !isDestroyed) renderAll()
            }
        }
    }
    private fun confirmPatch() {
        if (stock == null || bundle == null) { report("Choose a stock APK and wait for the bundle first.", Tone.WARN, Slot.PATCH); return }
        val warnings = mutableListOf<String>()
        if (choices["GmsCore support"] != true) warnings += "GmsCore support is off: sign-in will not work."
        if (choices.filterKeys { it.contains("Rokid", true) }.values.none { it }) warnings += "Rokid controls is off: the glasses cannot drive stock YouTube."
        if (warnings.isEmpty()) startPatch() else AlertDialog.Builder(this).setTitle("Continue without recommended patches?")
            .setMessage(warnings.joinToString("\n")).setNegativeButton("Review", null).setPositiveButton("Continue") { _, _ -> startPatch() }.show()
    }
    private fun startPatch() {
        val input = stock ?: return
        val loaded = bundle ?: return
        if (busy) return
        val selected = loaded.patches.filter { choices[it.name] == true }.toSet()
        result = null
        notes.remove(Slot.PATCH)
        patching = true
        patchStartedAt = System.currentTimeMillis()
        perform(Slot.PATCH) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val signed = withContext(Dispatchers.IO) {
                PatchRuntime().patch(input, selected, work, key) { message -> runOnUiThread { if (!isDestroyed) report(message, Tone.INFO, Slot.PATCH) } }
            }
            currentCoroutineContext().ensureActive()
            val directory = File(filesDir, "results").apply { mkdirs() }
            val target = File(directory, "youtube-${UUID.randomUUID()}.apk")
            withContext(Dispatchers.IO) {
                val pending = File(directory, ".${target.name}.partial")
                try {
                    signed.inputStream().use { input -> pending.outputStream().use { output ->
                        PatchPolicy.copyBounded(input, output); output.fd.sync()
                    } }
                    currentCoroutineContext().ensureActive()
                    require(pending.renameTo(target)) { "Cannot save verified result." }
                    signed.delete()
                } finally { pending.delete() }
                purgeResults(target)
            }
            currentCoroutineContext().ensureActive()
            result = target
            if (intent.action == Contract.ACTION_PATCH && callingActivity != null) {
                val uri = uri(target)
                val outputPackage = withContext(Dispatchers.IO) {
                    com.reandroid.apk.ApkModule.loadApkFile(target).use { it.packageName }
                }
                val data = Intent().setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Contract.EXTRA_PACKAGE_NAME, outputPackage)
                    .putExtra(Contract.EXTRA_VERSION_NAME, PatchPolicy.VERSION)
                    .putExtra(Contract.EXTRA_SHA256, withContext(Dispatchers.IO) { PatchPolicy.sha256(target) })
                data.clipData = ClipData.newRawUri("Patched YouTube", uri)
                patching = false
                setResult(RESULT_OK, data); finish()
            } else report("Patched and signed. The APK signature is verified.", Tone.OK, Slot.PATCH)
        }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(this, "$packageName.results", file)
    private fun shareResult(file: File) {
        val uri = uri(file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Patched YouTube", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share patched YouTube"))
    }
    private fun picker(request: Int, action: String, type: String, name: String? = null) {
        startActivityForResult(Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE); this.type = type
            name?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }, request)
    }
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
    private fun askPassword(importing: Boolean) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val field = EditText(this).apply { inputType = 129; hint = "Backup password (at least 8 characters)" }
        AlertDialog.Builder(this).setTitle(if (importing) "Import key" else "Export key").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ ->
                val password = field.text.toString().toCharArray(); field.text.clear()
                if (password.size < 8) { password.fill('\u0000'); report("Use at least eight characters.", Tone.ERROR, Slot.KEY) }
                else {
                    backupPassword?.fill('\u0000'); backupPassword = password
                    picker(if (importing) REQUEST_IMPORT else REQUEST_EXPORT,
                        if (importing) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT,
                        "application/octet-stream", if (importing) null else "youtube-signing-key.ypk")
                }
            }.setOnDismissListener { field.text.clear() }.show()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            backupPassword?.fill('\u0000'); backupPassword = null; return
        }
        val uri = data.data!!
        when (requestCode) {
            REQUEST_STOCK -> perform(Slot.STOCK) {
                stock = null
                stockName = displayName(uri)
                notes.remove(Slot.STOCK); renderStock()
                stock = withContext(Dispatchers.IO) {
                    val input = File(work, "input.zip")
                    contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "Cannot read selected file." }
                        input.outputStream().use { PatchPolicy.copyBounded(source, it) }
                    }
                    val prepare = File(work, "prepare").apply { deleteRecursively(); mkdirs() }
                    ApkPreparer().prepare(input, prepare)
                }
                report("Validated: stock YouTube ${PatchPolicy.VERSION}", Tone.OK, Slot.STOCK)
            }
            REQUEST_EXPORT, REQUEST_IMPORT -> {
                val password = backupPassword ?: return
                backupPassword = null
                perform(Slot.KEY) {
                    try {
                        withContext(Dispatchers.IO) {
                            if (requestCode == REQUEST_EXPORT) contentResolver.openOutputStream(uri, "wt").use { key.export(requireNotNull(it), password) }
                            else contentResolver.openInputStream(uri).use { key.import(requireNotNull(it), password) }
                        }
                        report(if (requestCode == REQUEST_EXPORT) "Password-protected key exported." else "Signing key imported.", Tone.OK, Slot.KEY)
                    } finally { password.fill('\u0000') }
                }
            }
            REQUEST_SAVE -> result?.let { file -> perform(Slot.PATCH) {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt").use { target ->
                        file.inputStream().use { PatchPolicy.copyBounded(it, requireNotNull(target)) }
                    }
                }
                report("Patched APK saved.", Tone.OK, Slot.PATCH)
            } }
        }
    }
    private fun cancelAndClose() {
        setResult(RESULT_CANCELED)
        scope.cancel()
        finish()
        // Some upstream patch code is synchronous and not cooperatively cancellable.
        // Only this screen lives in :patcher; process death stops it immediately.
        if (busy) android.os.Process.killProcess(android.os.Process.myPid())
    }
    @Deprecated("Platform callback") override fun onBackPressed() { cancelAndClose() }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        scope.cancel()
        backupPassword?.fill('\u0000'); backupPassword = null
        super.onDestroy()
        if (busy) android.os.Process.killProcess(android.os.Process.myPid())
        else if (::work.isInitialized) work.deleteRecursively()
        screenLock?.release(); lockFile?.close()
    }
    companion object {
        private const val REQUEST_STOCK = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_IMPORT = 3
        private const val REQUEST_SAVE = 4
    }
}
