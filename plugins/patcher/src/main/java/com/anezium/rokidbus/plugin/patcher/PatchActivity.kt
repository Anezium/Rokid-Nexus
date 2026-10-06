package com.anezium.rokidbus.plugin.patcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PatcherContract as Contract
import kotlinx.coroutines.*
import java.io.File

class PatchActivity : Activity() {
    /** Where a status message belongs on screen: each step card shows only its own. */
    private enum class Slot { STOCK, BUNDLE, PATCH, KEY }
    private enum class Tone { INFO, OK, WARN, ERROR }
    private class Note(val text: String, val tone: Tone)
    private class NoteView(val row: View, val dot: View, val text: TextView)
    private class StageRowView(val index: TextView, val label: TextView, val mark: TextView, val dot: View)
    private class LiveViews(val hero: TextView, val bar: PhosphorBar, val phase: TextView, val patch: TextView, val rows: List<StageRowView>)
    private class PrepareViews(val bar: PhosphorBar, val phase: TextView)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private lateinit var stockHost: LinearLayout
    private lateinit var patchesHost: LinearLayout
    private lateinit var actionHost: LinearLayout
    private lateinit var keyHost: LinearLayout
    private lateinit var detailsHost: LinearLayout
    private val notes = mutableMapOf<Slot, Note>()
    private val noteViews = mutableMapOf<Slot, NoteView>()
    private var slot = Slot.PATCH
    private var live: LiveViews? = null
    private var prepare: PrepareViews? = null
    private var pulse: ValueAnimator? = null
    private var pulsing: View? = null
    private var patchAfterPermission = false
    private var allPatchesOpen = false
    private var keyMoreOpen = false
    private var detailsOpen = false
    private lateinit var target: PatchTarget
    private lateinit var bundleStore: BundleStore
    private lateinit var selections: SelectionStore
    private lateinit var key: SigningKey
    private var bundle: BundleStore.Loaded? = null
    private var choices = mutableMapOf<String, Boolean>()
    private var stock: File? = null
    private var stockName: String? = null
    private var busy = false
    private var patching = false
    private var result: File? = null
    private lateinit var jobs: PatchJobStore
    private var resumed = false
    private var returningResult = false
    private var backupPassword: CharArray? = null
    private var screenLock: java.nio.channels.FileLock? = null
    private var lockFile: java.io.RandomAccessFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockFile = java.io.RandomAccessFile(File(filesDir, "screen.lock"), "rw")
        screenLock = try { lockFile!!.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        if (screenLock == null) {
            // The notification opens a new task; the live screen may sit in the hub's task.
            // Bring that one forward instead of a second copy that cannot share the job.
            val liveTask = liveTaskId
            if (liveTask != null && liveTask != taskId &&
                runCatching { getSystemService(ActivityManager::class.java).moveTaskToFront(liveTask, 0) }.isSuccess) {
                finish()
                return
            }
            AlertDialog.Builder(this).setMessage("Patcher is already open in another window. Close it first.")
                .setPositiveButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        liveTaskId = taskId
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        jobs = PatchJobStore.get(this)
        val requested = intent.getStringExtra(Contract.EXTRA_TARGET_ID)
            ?: if (intent.action == Contract.ACTION_PATCH) "" else jobs.state.value.targetId
        val selectedTarget = PatchTargets.find(requested)
        if (selectedTarget == null || jobs.state.value.active && jobs.state.value.targetId != requested) {
            setResult(RESULT_CANCELED)
            AlertDialog.Builder(this).setMessage("Patcher does not know this app, or another app is already being patched.")
                .setPositiveButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        target = selectedTarget
        jobs.selectTarget(target.id)
        jobs.reconcileResult()
        bundleStore = BundleStore(this, target)
        selections = SelectionStore(File(filesDir, "selections/${target.id}.json"))
        key = SigningKey(File(filesDir, "signing/patcher.p12"))
        val state = jobs.state.value
        stock = jobs.stock(); result = jobs.result()
        busy = state.active; patching = state.active
        noteFor(state)
        if (!state.active) {
            File(filesDir, "results").listFiles()?.filter { it.name.endsWith(".partial") }?.forEach { it.delete() }
            purgeResults(result)
        }
        build()
        scope.launch {
            var previousStatus: PatchJobStatus? = null
            jobs.state.collect { current ->
                busy = current.active; patching = current.active
                stock = jobs.stock(current); result = jobs.result(current)
                if (current.status == PatchJobStatus.PREPARING) slot = Slot.STOCK
                noteFor(current)
                updateScreenAwake()
                if (previousStatus != current.status) renderAll() else updateLive(current)
                previousStatus = current.status
                deliverResult()
                if (!current.active && bundle == null) loadBundle()
            }
        }
    }
    /** A job's words sit next to the step they belong to: file problems under step 1, patch outcomes under step 3. */
    private fun noteFor(state: PatchJobState) {
        val where = if (PatchPresentation.isPreparePhase(state.progress.phase)) Slot.STOCK else Slot.PATCH
        if (state.active) {
            notes.remove(where); applyNote(where)
        } else {
            val tone = when (state.status) {
                PatchJobStatus.SUCCESS, PatchJobStatus.READY -> Tone.OK
                PatchJobStatus.FAILURE, PatchJobStatus.INTERRUPTED -> Tone.ERROR
                PatchJobStatus.CANCELLED -> Tone.WARN
                else -> Tone.INFO
            }
            report(state.message, tone, where)
        }
        if (where == Slot.PATCH && notes[Slot.STOCK] == null && jobs.stock(state) != null)
            report("Validated stock ${target.displayName} ${target.versionLabel}", Tone.OK, Slot.STOCK)
    }
    private fun loadBundle() {
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
        choices = selections.load(loaded.version, loaded.patches.associate { it.name!! to (target.defaultSelection[it.name] ?: it.default) }).toMutableMap()
        selections.save(loaded.version, choices)
        renderPatches(); renderAction(); renderDetails()
    }

    // ---- Screen skeleton: built once, sections re-render into their own host ----

    private fun build() {
        noteViews.clear()
        content = NexusUi.contentColumn(this)
        content.addView(NexusUi.cardBody(this, "${target.description} Pick stock ${target.displayName} ${target.versionLabel}, review the patches, then patch. Your APK never leaves this phone."), NexusUi.block())
        content.addView(BusTheme.gap(this, 18))
        stockHost = host(); content.addView(BusTheme.gap(this, 12))
        patchesHost = host(); content.addView(BusTheme.gap(this, 12))
        actionHost = host()
        section("Signing key"); keyHost = host()
        section("Details"); detailsHost = host()
        section("Plugin")
        content.addView(NexusUi.uninstallCard(this, "Patcher") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())
        val root = NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@PatchActivity, target.icon, "Patcher", "Phone-only · v1.0.0"), NexusUi.block())
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
        val state = jobs.state.value
        val validating = state.status == PatchJobStatus.PREPARING
        prepare = null
        stockHost.addView(NexusUi.card(this).apply {
            addView(stepHeader(1, if (validating) "Checking your file" else "Stock ${target.displayName}", done = stock != null, active = stock == null), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            when {
                validating -> {
                    val bar = PhosphorBar(this@PatchActivity)
                    val phase = NexusUi.statusLine(this@PatchActivity).apply { setTextColor(NexusUi.INK) }
                    prepare = PrepareViews(bar, phase)
                    addView(BusTheme.gap(this@PatchActivity, 6))
                    addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
                    addView(BusTheme.gap(this@PatchActivity, 10))
                    addView(phase, NexusUi.block())
                    stockName?.let { addView(BusTheme.gap(this@PatchActivity, 4)); addView(NexusUi.rowSub(this@PatchActivity, it), NexusUi.block()) }
                    addView(BusTheme.gap(this@PatchActivity, 2))
                    addView(quiet("Cancel") { cancelJob() }, endAligned())
                    updateLive(state)
                }
                stock != null -> {
                    addView(noteView(Slot.STOCK), NexusUi.block())
                    stockName?.let { addView(BusTheme.gap(this@PatchActivity, 4)); addView(NexusUi.rowSub(this@PatchActivity, it), NexusUi.block()) }
                    addView(BusTheme.gap(this@PatchActivity, 4))
                    addView(quiet("Change file", enabled = !busy) { picker(REQUEST_STOCK, Intent.ACTION_OPEN_DOCUMENT, "*/*") }, endAligned())
                }
                else -> {
                    addView(NexusUi.cardBody(this@PatchActivity, "Stock ${target.displayName} ${target.versionLabel}: the APK, or its complete split bundle."), NexusUi.block())
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
                addView(PhosphorBar(this@PatchActivity).apply { show(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
                addView(BusTheme.gap(this@PatchActivity, 10))
                addView(NexusUi.cardBody(this@PatchActivity, "Loading the patch bundle"), NexusUi.block())
                return@apply
            }
            val (featured, others) = loaded.patches.partition { target.priority(it.name!!) < target.featuredPatches.size }
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
        live = null
        stopPulse()
        val state = jobs.state.value
        // A terminal state reached while checking the file belongs to step 1; step 3 stays idle.
        val status = if (PatchPresentation.isPreparePhase(state.progress.phase)) PatchJobStatus.IDLE else state.status
        val ready = stock != null && bundle != null
        val done = result != null && status == PatchJobStatus.SUCCESS
        val running = status == PatchJobStatus.RUNNING
        val stopped = status in setOf(PatchJobStatus.FAILURE, PatchJobStatus.CANCELLED, PatchJobStatus.INTERRUPTED)
        actionHost.addView(NexusUi.card(this).apply {
            if (!ready && !done && !running && !stopped) alpha = .55f
            val ring = when (status) {
                PatchJobStatus.FAILURE, PatchJobStatus.INTERRUPTED -> NexusUi.DANGER
                PatchJobStatus.CANCELLED -> NexusUi.AMBER
                else -> NexusUi.GREEN
            }
            addView(stepHeader(3, PatchPresentation.headline(status, target.displayName), done = done,
                active = running || stopped || (ready && !done), ring = ring,
                trailing = if (done && state.elapsedMs > 0) NexusUi.metaLabel(this@PatchActivity, PatchPresentation.elapsed(state.elapsedMs), NexusUi.GREEN_DIM) else null), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            when {
                running -> renderRunning(this, state)
                done -> renderDone(this)
                stopped -> renderStopped(this, status)
                else -> renderIdle(this, ready)
            }
        }, NexusUi.block())
        applyNote(Slot.PATCH)
    }

    /** The live block: a clock that ticks, a bar that moves, the stage you are in. Nothing here may look frozen. */
    private fun renderRunning(card: LinearLayout, state: PatchJobState) {
        val hero = NexusUi.hero(this, 34f).apply { fontFeatureSettings = "tnum"; includeFontPadding = false }
        val bar = PhosphorBar(this)
        val phase = NexusUi.statusLine(this).apply { setTextColor(NexusUi.INK) }
        val patch = NexusUi.rowSub(this, "").apply { setTextColor(NexusUi.INK3) }
        val rows = PatchPresentation.patchStages.mapIndexed { index, stage -> stageRow(index + 1, stage.label) }
        live = LiveViews(hero, bar, phase, patch, rows)
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(hero, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexusUi.metaLabel(this@PatchActivity, "Elapsed", NexusUi.INK3).apply { setPadding(0, 0, 0, dp(6)) })
        }, NexusUi.block())
        card.addView(BusTheme.gap(this, 10))
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        card.addView(BusTheme.gap(this, 10))
        card.addView(phase, NexusUi.block())
        card.addView(patch, NexusUi.block().apply { topMargin = dp(4) })
        card.addView(BusTheme.gap(this, 14))
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(hairline())
            card.addView(row.index.parent as View, NexusUi.block())
        }
        card.addView(BusTheme.gap(this, 14))
        card.addView(NexusUi.cardBody(this, if (notificationsDenied())
            "Usually a few minutes. You can leave this screen or turn the display off: patching continues. Notifications are off for Patcher, so come back here to check on it."
        else "Usually a few minutes. You can leave this screen or turn the display off: patching continues, and the notification brings you back when it is ready."), NexusUi.block())
        card.addView(BusTheme.gap(this, 14))
        card.addView(NexusUi.pillButton(this, "Cancel patching", danger = true).apply { setOnClickListener { cancelJob() } }, NexusUi.block())
        updateLive(state)
    }

    private fun renderDone(card: LinearLayout) {
        val hubWaiting = intent.action == Contract.ACTION_PATCH && callingActivity != null
        card.addView(noteView(Slot.PATCH), NexusUi.block())
        card.addView(BusTheme.gap(this, 8))
        card.addView(NexusUi.cardBody(this, if (hubWaiting) "Handing it to Nexus, which installs it on your glasses."
        else "Nexus installs it on the glasses: open ${target.displayName} on glasses in Nexus and choose Patch and install. It picks this result up without patching again."), NexusUi.block())
        if (hubWaiting) return
        card.addView(BusTheme.gap(this, 14))
        val nexus = hubLaunchIntent()
        if (nexus != null) card.addView(primary("Open Nexus", enabled = !busy) { startActivity(nexus) }, NexusUi.block())
        else card.addView(primary("Share patched APK", enabled = !busy) { result?.let(::shareResult) }, NexusUi.block())
        card.addView(BusTheme.gap(this, 4))
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            if (nexus != null) addView(quiet("Share", enabled = !busy) { result?.let(::shareResult) })
            addView(quiet("Save APK", enabled = !busy) { picker(REQUEST_SAVE, Intent.ACTION_CREATE_DOCUMENT, "application/vnd.android.package-archive", "${target.id}-patched.apk") })
            addView(quiet("Patch again", enabled = !busy) { confirmPatch() })
            addView(quiet("Close") { cancelAndClose() })
        }, NexusUi.block())
    }

    private fun renderStopped(card: LinearLayout, status: PatchJobStatus) {
        card.addView(noteView(Slot.PATCH), NexusUi.block())
        card.addView(BusTheme.gap(this, 8))
        card.addView(NexusUi.cardBody(this, when {
            status == PatchJobStatus.INTERRUPTED -> "Android or a restart stopped it before it finished. Nothing was installed."
            stock != null -> "Nothing was installed. Retrying starts again from your checked file."
            else -> "Nothing was installed."
        }), NexusUi.block())
        card.addView(BusTheme.gap(this, 14))
        val label = when {
            stock == null -> "Choose the file again"
            status == PatchJobStatus.CANCELLED -> "Patch ${target.displayName}"
            else -> "Retry patch"
        }
        card.addView(primary(label, enabled = !busy && (stock == null || bundle != null)) {
            if (stock == null) picker(REQUEST_STOCK, Intent.ACTION_OPEN_DOCUMENT, "*/*") else confirmPatch()
        }, NexusUi.block())
        card.addView(BusTheme.gap(this, 4))
        card.addView(quiet("Close") { cancelAndClose() }, endAligned())
    }

    private fun renderIdle(card: LinearLayout, ready: Boolean) {
        card.addView(NexusUi.cardBody(this, "Patching takes a few minutes and keeps running if you leave the app. The result is signed with this plugin's key."), NexusUi.block())
        card.addView(noteView(Slot.PATCH), NexusUi.block())
        if (shouldAskNotifications()) {
            card.addView(BusTheme.gap(this, 8))
            card.addView(NexusUi.cardBody(this, "Patcher will ask to show notifications, so you can leave this screen and be brought back when it is ready.")
                .apply { textSize = 12f; setTextColor(NexusUi.INK3) }, NexusUi.block())
        }
        card.addView(BusTheme.gap(this, 14))
        card.addView(primary("Patch ${target.displayName}", enabled = !busy && ready) { confirmPatch() }, NexusUi.block())
        card.addView(BusTheme.gap(this, 4))
        card.addView(quiet("Close") { cancelAndClose() }, endAligned())
    }

    /** Refresh the live views in place; the card itself is rebuilt only when the status changes. */
    private fun updateLive(state: PatchJobState) {
        prepare?.let { views ->
            views.bar.show(state.progress.fraction)
            views.phase.text = PatchPresentation.phaseLine(state.progress)
        }
        val views = live ?: return
        views.hero.text = PatchPresentation.elapsed(state.elapsedMs)
        views.bar.show(state.progress.fraction)
        views.phase.text = PatchPresentation.phaseLine(state.progress)
        val last = state.progress.patchName
        views.patch.visibility = if (last == null) View.GONE else View.VISIBLE
        views.patch.text = if (last == null) "" else "\u2713  $last"
        PatchPresentation.stages(state.progress.phase).forEachIndexed { index, row -> style(views.rows[index], row.state) }
    }

    private fun stageRow(number: Int, label: String): StageRowView {
        val index = NexusUi.metaLabel(this, "%02d".format(number), NexusUi.INK4).apply { minWidth = dp(26) }
        val title = NexusUi.rowLabel(this, label)
        val mark = NexusUi.metaLabel(this, "\u2713", NexusUi.GREEN_DIM).apply { textSize = 12f; visibility = View.GONE }
        val dot = NexusUi.dot(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
            visibility = View.GONE
        }
        NexusUi.setDotColor(dot, NexusUi.GREEN)
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(34)
            setPadding(dp(2), 0, dp(4), 0)
            addView(index)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mark)
            addView(dot)
        }
        return StageRowView(index, title, mark, dot)
    }

    private fun style(row: StageRowView, state: PatchPresentation.StageState) {
        when (state) {
            PatchPresentation.StageState.DONE -> {
                row.index.setTextColor(NexusUi.GREEN_DIM); row.label.setTextColor(NexusUi.INK2)
                row.mark.visibility = View.VISIBLE; row.dot.visibility = View.GONE
            }
            PatchPresentation.StageState.CURRENT -> {
                row.index.setTextColor(NexusUi.GREEN); row.label.setTextColor(NexusUi.INK)
                row.mark.visibility = View.GONE; row.dot.visibility = View.VISIBLE
                if (pulsing !== row.dot) pulse(row.dot)
            }
            PatchPresentation.StageState.UPCOMING -> {
                row.index.setTextColor(NexusUi.INK4); row.label.setTextColor(NexusUi.INK4)
                row.mark.visibility = View.GONE; row.dot.visibility = View.GONE
            }
        }
    }

    private fun pulse(view: View) {
        stopPulse()
        pulsing = view
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pulse = ValueAnimator.ofFloat(1f, .3f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
    }
    private fun stopPulse() {
        pulse?.cancel(); pulse = null
        pulsing?.alpha = 1f; pulsing = null
    }

    private fun hubLaunchIntent(): Intent? = runCatching {
        packageManager.getLaunchIntentForPackage(com.anezium.rokidbus.client.HubTarget.PHONE.packageName)
    }.getOrNull()

    private fun renderKey() {
        keyHost.removeAllViews()
        keyHost.addView(NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(this@PatchActivity, "Keep ${target.displayName} updatable"), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            addView(NexusUi.cardBody(this@PatchActivity, "${target.displayName} on the glasses only updates from the key that signed it. Uninstalling this plugin deletes that key, so export a backup now."), NexusUi.block())
            addView(noteView(Slot.KEY), NexusUi.block())
            addView(BusTheme.gap(this@PatchActivity, 6))
            addView(LinearLayout(this@PatchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(quiet(if (keyMoreOpen) "Less" else "More") { keyMoreOpen = !keyMoreOpen; renderKey() })
                addView(View(this@PatchActivity), LinearLayout.LayoutParams(0, 0, 1f))
                addView(quiet("Import key", enabled = !busy) {
                    AlertDialog.Builder(this@PatchActivity).setTitle("Replace signing key?")
                        .setMessage("Future patches will use the imported key. The installed app must have the same signer to update.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ -> askPassword(true) }.show()
                })
                addView(quiet("Export key", enabled = !busy) { askPassword(false) })
            }, NexusUi.block())
            if (keyMoreOpen) {
                addView(BusTheme.gap(this@PatchActivity, 4))
                addView(NexusUi.cardBody(this@PatchActivity, "The backup is a password-protected file you can import into a fresh install of this plugin.\n\nAn app patched with Morphe Manager uses Manager's key, which this backup format cannot import: keep updating that build with Manager, or uninstall that app on the glasses by hand before installing a build from this plugin.").apply { textSize = 12f; setTextColor(NexusUi.INK3) }, NexusUi.block())
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
            addView(NexusUi.cardBody(this@PatchActivity, "Uses Morphe Patcher (GPLv3) and the selected target bundle. No signature is published for this bundle.\n${target.bundle.projectUrl}").apply {
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
            addView(NexusUi.cardBody(this@PatchActivity, "Patcher modifies the executable packaging of the selected target bundle by adding Android DEX. It is not an official upstream distribution.\n\n$notices\n\n$gpl"), NexusUi.block())
        }
        AlertDialog.Builder(this).setTitle("Licenses and notices").setView(NexusUi.screen(this, body)).setPositiveButton("Close", null).show()
    }

    // ---- Small composites built from the NexusUi vocabulary ----

    private fun stepHeader(number: Int, title: String, done: Boolean, active: Boolean, ring: Int = NexusUi.GREEN, trailing: View? = null): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(number, done, active, ring))
            addView(NexusUi.cardTitle(this@PatchActivity, title),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            trailing?.let { addView(it) }
        }
    private fun badge(number: Int, done: Boolean, active: Boolean, ring: Int = NexusUi.GREEN): TextView =
        TextView(this).apply {
            text = if (done) "✓" else number.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(if (done) NexusUi.ON_ACCENT else if (active) ring else NexusUi.INK3)
            background = if (done) NexusUi.rounded(this@PatchActivity, NexusUi.GREEN, 999)
            else NexusUi.bordered(this@PatchActivity, if (active) NexusUi.alpha(ring, 30) else NexusUi.PANEL, if (active) ring else NexusUi.LINE, 999)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        }
    private fun hairline(): View = View(this).apply {
        setBackgroundColor(NexusUi.LINE2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(2); bottomMargin = dp(2) }
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
            catch (e: OutOfMemoryError) { report("Not enough memory to patch this APK on this phone. No result was saved.", Tone.ERROR, target) }
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
        val warnings = target.warnings.filter { choices[it.patchName] != true }.map { it.message }
        if (warnings.isEmpty()) startPatch() else AlertDialog.Builder(this).setTitle("Continue without recommended patches?")
            .setMessage(warnings.joinToString("\n")).setNegativeButton("Review", null).setPositiveButton("Continue") { _, _ -> startPatch() }.show()
    }
    private fun startPatch() {
        if (busy || stock == null || bundle == null) return
        if (shouldAskNotifications()) {
            patchAfterPermission = true
            getSharedPreferences("patch-notifications", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        launchPatch()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS || !patchAfterPermission) return
        patchAfterPermission = false
        launchPatch()
    }
    private fun launchPatch() {
        val loaded = bundle ?: return
        if (busy || stock == null) return
        try {
            val state = jobs.patch(loaded.hash, loaded.patches.filter { choices[it.name] == true }.map { it.name!! })
            startJob(state)
        } catch (e: Exception) { report(e.message ?: "Cannot start patching.", Tone.ERROR, Slot.PATCH) }
    }
    private fun startJob(state: PatchJobState, source: Uri? = null) {
        try {
            startForegroundService(Intent(this, PatchJobService::class.java)
                .putExtra(PatchJobService.JOB_ID, state.id).setData(source)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            jobs.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = "Cannot start background patching. Return to this screen and retry.") }
        }
    }
    private fun deliverResult() {
        val file = result ?: return
        if (!resumed || returningResult || intent.action != Contract.ACTION_PATCH || callingActivity == null) return
        returningResult = true
        scope.launch {
            try {
                val contentUri = uri(file)
                PatchTimings().measure("hand_off") {
                    val data = Intent().setDataAndType(contentUri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Contract.EXTRA_TARGET_ID, target.id)
                    data.clipData = ClipData.newRawUri("Patched ${target.displayName}", contentUri)
                    if (resumed) { setResult(RESULT_OK, data); finish() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("Cannot return the result: ${e.message}", Tone.ERROR, Slot.PATCH) }
            finally { returningResult = false }
        }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(this, "$packageName.results", file)
    private fun shareResult(file: File) {
        val uri = uri(file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Patched ${target.displayName}", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share patched ${target.displayName}"))
    }
    private fun picker(request: Int, action: String, type: String, name: String? = null) {
        startActivityForResult(Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE); this.type = type
            if (request == REQUEST_STOCK) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.android.package-archive", "application/octet-stream",
                "application/zip", "application/x-zip-compressed",
            ))
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
                        "application/octet-stream", if (importing) null else "patcher-signing-key.ypk")
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
            REQUEST_STOCK -> {
                if (busy) return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    stockName = displayName(uri)
                    notes.remove(Slot.STOCK)
                    startJob(jobs.prepare(target.id), uri)
                } catch (e: Exception) { report("Cannot retain access to this file. Choose it with the document picker again.", Tone.ERROR, Slot.STOCK) }
            }
            REQUEST_EXPORT, REQUEST_IMPORT -> {
                val password = backupPassword ?: return
                backupPassword = null
                if (busy) { password.fill('\u0000'); return }
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
    private fun notificationsDenied(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun shouldAskNotifications(): Boolean =
        notificationsDenied() && !getSharedPreferences("patch-notifications", MODE_PRIVATE).getBoolean("asked", false)
    private fun cancelJob() {
        if (jobs.state.value.active) startService(Intent(this, PatchJobService::class.java)
            .setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, jobs.state.value.id))
    }
    private fun cancelAndClose() { setResult(RESULT_CANCELED); finish() }
    private fun updateScreenAwake() {
        if (resumed && ::jobs.isInitialized && jobs.state.value.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onResume() { super.onResume(); resumed = true; updateScreenAwake(); if (::jobs.isInitialized) deliverResult() }
    override fun onPause() { resumed = false; updateScreenAwake(); super.onPause() }
    @Deprecated("Platform callback") override fun onBackPressed() { cancelAndClose() }
    override fun onDestroy() {
        stopPulse()
        scope.cancel()
        if (screenLock != null && liveTaskId == taskId) liveTaskId = null
        backupPassword?.fill('\u0000'); backupPassword = null
        screenLock?.release(); lockFile?.close()
        super.onDestroy()
    }
    companion object {
        /** Task of the screen holding the lock; the service and both screens share the :patcher process. */
        @Volatile private var liveTaskId: Int? = null
        private const val REQUEST_NOTIFICATIONS = 5
        private const val REQUEST_STOCK = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_IMPORT = 3
        private const val REQUEST_SAVE = 4
    }
}
