package com.anezium.rokidbus.phone

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.GlassesFileEntry
import com.anezium.rokidbus.shared.GlassesFilesAccess
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError

/** Phone-side browser for the glasses' shared storage; the hub service owns every transfer. */
class GlassesFilesActivity : Activity() {
    private sealed interface ViewState {
        data object Loading : ViewState
        data object Listed : ViewState
        data class Failed(val refused: Boolean) : ViewState
        data object Protected : ViewState
        data object Disconnected : ViewState
        data object Unsupported : ViewState
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var stripHost: LinearLayout
    private lateinit var bannerHost: LinearLayout
    private lateinit var noticeHost: LinearLayout
    private lateinit var transfersHost: LinearLayout
    private lateinit var chipsHost: LinearLayout
    private lateinit var listHost: LinearLayout
    private lateinit var bottomHost: LinearLayout
    private lateinit var scroll: ScrollView

    private var path = ""
    private var entries: List<GlassesFileEntry> = emptyList()
    private var total = 0
    private var access: GlassesFilesAccess? = null
    private var state: ViewState = ViewState.Loading
    private var generation = 0
    /** Generation of the listing still awaiting its answer, or -1 once it has one. */
    private var pendingGeneration = -1
    private var snapshot = GlassesFilesHub.snapshot()
    private var granting = false
    private var grantFailed = false
    private var seenDone = emptySet<String>()
    private var backCallback: Any? = null

    private val listener: (GlassesFilesSnapshot) -> Unit = ::onSnapshot
    private val clearNotice = Runnable { if (::noticeHost.isInitialized) noticeHost.removeAllViews() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = savedInstanceState?.getString(STATE_PATH)
            ?: getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE).getString(PREF_LAST_PATH, "").orEmpty()
        path = GlassesFilesContract.normalizePath(path) ?: ""
        buildUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        GlassesFilesHub.addListener(listener)
        load(path, restoring = true)
    }

    override fun onStop() {
        GlassesFilesHub.removeListener(listener)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
    }

    @Deprecated("Uses the platform back callback on API 33+")
    override fun onBackPressed() {
        navigateBack()
    }

    @Deprecated("Activity result API is not used in this plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            if (isEmpty()) data.data?.let(::add)
        }
        if (uris.isEmpty()) return
        // The transfer outlives this screen, so the read grant must too; the hub releases it after.
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uris.size > MAX_PICK) showNotice("SENDING THE FIRST $MAX_PICK FILES")
        if (!GlassesFilesHub.send(applicationContext, path, uris)) {
            uris.forEach { uri ->
                runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            showNotice("NEXUS IS STILL STARTING. TRY AGAIN IN A MOMENT.")
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        val callback = OnBackInvokedCallback { navigateBack() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
    }

    /** Back walks up the tree like any file manager, and leaves only from the root. */
    private fun navigateBack() {
        if (path.isEmpty() || state == ViewState.Unsupported) finish() else load(GlassesFilesContract.parent(path))
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        stripHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noticeHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transfersHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottomHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val column = NexusUi.contentColumn(this, horizontalDp = 22, topDp = 0).apply {
            addView(bannerHost, NexusUi.block())
            addView(noticeHost, NexusUi.block())
            addView(transfersHost, NexusUi.block())
            addView(chipsHost, NexusUi.block())
            addView(listHost, NexusUi.block())
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader(), NexusUi.block())
                addView(stripHost, NexusUi.block())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bottomHost, NexusUi.block())
            },
        )
        renderAll()
    }

    private fun load(target: String, restoring: Boolean = false, offset: Int = 0) {
        val requested = generation + 1
        generation = requested
        pendingGeneration = requested
        val navigating = target != path
        if (navigating) {
            path = target
            entries = emptyList()
            total = 0
            state = ViewState.Loading
            renderAll()
            scroll.scrollTo(0, 0)
        } else if (offset == 0) {
            // Keep the rows on screen while a refresh is in flight; only a slow answer shows loading.
            main.postDelayed({
                if (pendingGeneration == requested && state != ViewState.Loading) {
                    state = ViewState.Loading
                    renderAll()
                }
            }, LOADING_DELAY_MS)
        }
        GlassesFilesHub.list(target, offset) { outcome ->
            if (generation != requested || isDestroyed) return@list
            pendingGeneration = -1
            onListing(target, offset, outcome, restoring)
        }
    }

    private fun onListing(target: String, offset: Int, outcome: GlassesListingOutcome, restoring: Boolean) {
        when (outcome) {
            is GlassesListingOutcome.Listed -> {
                access = outcome.access
                entries = if (offset == 0) outcome.entries else entries + outcome.entries
                total = outcome.total
                state = ViewState.Listed
                getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_PATH, target).apply()
            }
            is GlassesListingOutcome.Failed -> {
                outcome.access?.let { access = it }
                if (restoring && target.isNotEmpty() &&
                    (outcome.error == GlassesFilesError.NOT_FOUND || outcome.error == GlassesFilesError.NOT_A_FOLDER)
                ) {
                    load("")
                    return
                }
                state = when (outcome.error) {
                    GlassesFilesError.PROTECTED -> ViewState.Protected
                    GlassesFilesError.NOT_FOUND,
                    GlassesFilesError.NOT_PERMITTED,
                    GlassesFilesError.NOT_A_FOLDER,
                    -> ViewState.Failed(refused = true)
                    else -> ViewState.Failed(refused = false)
                }
                entries = emptyList()
            }
            GlassesListingOutcome.TimedOut -> state = ViewState.Failed(refused = false)
            GlassesListingOutcome.Disconnected -> state = ViewState.Disconnected
            GlassesListingOutcome.Unsupported -> state = ViewState.Unsupported
        }
        renderAll()
    }

    private fun onSnapshot(next: GlassesFilesSnapshot) {
        val previous = snapshot
        snapshot = next
        if (!::listHost.isInitialized) return
        val doneHere = next.transfers.filter {
            it.state == GlassesTransferState.DONE && it.direction == GlassesTransferDirection.SEND && it.folderPath == path
        }.map { it.id }.toSet()
        val freshlyDone = doneHere - seenDone
        seenDone = seenDone + doneHere
        when {
            next.supported == false -> {
                state = ViewState.Unsupported
                renderAll()
            }
            !next.connected && previous.connected -> {
                state = ViewState.Disconnected
                renderAll()
            }
            next.connected && (!previous.connected || state == ViewState.Disconnected) -> load(path)
            freshlyDone.isNotEmpty() && state == ViewState.Listed -> {
                renderTransfers()
                load(path)
            }
            else -> {
                renderTransfers()
                renderBottom()
            }
        }
    }

    private fun renderAll() {
        renderStrip()
        renderBanner()
        renderTransfers()
        renderChips()
        renderList()
        renderBottom()
    }

    private fun renderStrip() {
        stripHost.removeAllViews()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(12), dp(10))
        }
        val segments = if (state == ViewState.Unsupported) listOf(ROOT_LABEL) else listOf(ROOT_LABEL) + pathSegments()
        row.addView(breadcrumb(segments), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (state != ViewState.Unsupported) {
            row.addView(
                NexusUi.textButton(this, "New folder").apply {
                    val enabled = canCreateHere()
                    isEnabled = enabled
                    setTextColor(if (enabled) NexusUi.GREEN else NexusUi.INK4)
                    setOnClickListener { showNewFolderDialog() }
                },
            )
        }
        stripHost.addView(row, NexusUi.block())
    }

    private fun renderBanner() {
        bannerHost.removeAllViews()
        if (access != GlassesFilesAccess.LIMITED || state == ViewState.Unsupported || state == ViewState.Disconnected) return
        bannerHost.addView(
            NexusUi.updateBanner(
                context = this,
                versionLabel = if (grantFailed) "Try again, or check the helper in Settings" else "Nexus can only use Download and Documents",
                title = if (grantFailed) "Couldn’t allow full access" else "Some folders are hidden",
                actionLabel = if (granting) "Allowing…" else "Allow full access",
                actionEnabled = !granting,
            ) { requestFullAccess() },
            NexusUi.block(),
        )
        bannerHost.addView(BusTheme.gap(this, 10))
    }

    private fun requestFullAccess() {
        granting = true
        grantFailed = false
        renderBanner()
        GlassesFilesHub.grantAccess { result ->
            if (isDestroyed) return@grantAccess
            granting = false
            when {
                result?.access == GlassesFilesAccess.FULL -> {
                    access = GlassesFilesAccess.FULL
                    renderBanner()
                    load(path)
                }
                result?.needsWearer == true -> {
                    renderBanner()
                    showNotice("FINISH ON THE GLASSES: TURN ON THE SWITCH FOR NEXUS, THEN COME BACK", AFTER_GRANT_NOTICE_MS)
                }
                else -> {
                    grantFailed = true
                    renderBanner()
                }
            }
        }
    }

    private fun renderChips() {
        chipsHost.removeAllViews()
        if (path.isNotEmpty() || state != ViewState.Listed) return
        val available = QUICK_FOLDERS.filter { name -> entries.any { it.directory && it.name == name } }
        if (available.isEmpty()) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        available.forEachIndexed { index, name ->
            row.addView(
                NexusUi.outlinePillButton(this, name).apply {
                    text = name
                    textSize = 11f
                    minHeight = dp(32)
                    minimumHeight = dp(32)
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener { load(name) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
        chipsHost.addView(row, NexusUi.block())
        chipsHost.addView(BusTheme.gap(this, 10))
    }

    private fun renderList() {
        listHost.removeAllViews()
        listHost.alpha = 1f
        when (val current = state) {
            ViewState.Loading -> {
                listHost.addView(NexusUi.metaLabel(this, "READING THE GLASSES…", NexusUi.INK4), NexusUi.block())
                listHost.addView(BusTheme.gap(this, 10))
                repeat(3) { index ->
                    if (index > 0) listHost.addView(BusTheme.gap(this, 8))
                    listHost.addView(
                        View(this).apply {
                            background = NexusUi.bordered(this@GlassesFilesActivity, NexusUi.PANEL, NexusUi.LINE2, 15)
                            alpha = 0.5f
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)),
                    )
                }
            }
            ViewState.Listed -> renderRows()
            is ViewState.Failed -> stateCard(
                "Couldn’t read this folder",
                if (current.refused) "The glasses wouldn’t open this folder." else "The glasses didn’t answer in time.",
                "Try again" to { load(path) },
            )
            ViewState.Protected -> stateCard(
                "Protected by Android",
                "Apps keep their private files here. Nothing on the phone can see or change them, not even with full access.",
            )
            ViewState.Disconnected -> {
                stateCard(
                    "Glasses disconnected",
                    "Reconnect the glasses to browse their files. Any transfer that was running has stopped; retry it once the glasses are back.",
                )
                if (entries.isNotEmpty()) {
                    listHost.addView(BusTheme.gap(this, 10))
                    val stale = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        alpha = 0.55f
                    }
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) stale.addView(BusTheme.gap(this, 8))
                        stale.addView(entryRow(entry, clickable = false), NexusUi.block())
                    }
                    listHost.addView(stale, NexusUi.block())
                }
            }
            ViewState.Unsupported -> stateCard(
                "Update the glasses app",
                "Browsing files needs a newer Nexus on the glasses. Update it from this phone, then come back here.",
                "Update glasses app" to {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                },
            )
        }
        listHost.addView(BusTheme.gap(this, 16))
    }

    private fun renderRows() {
        if (entries.isEmpty()) {
            val deletable = path.isNotEmpty() && GlassesFilesFormat.canDeleteFolder(path)
            stateCard(
                "Nothing here yet",
                "Send files from your phone and they will appear in this folder.",
                if (deletable) "Delete this folder" to { confirmDeleteFolder() } else null,
                dangerAction = true,
            )
            return
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) listHost.addView(BusTheme.gap(this, 8))
            listHost.addView(entryRow(entry, clickable = true), NexusUi.block())
        }
        val remaining = total - entries.size
        if (remaining > 0) {
            listHost.addView(BusTheme.gap(this, 8))
            listHost.addView(
                NexusUi.textButton(
                    this,
                    if (remaining >= GlassesFilesContract.MAX_PAGE_ENTRIES) {
                        "Show ${GlassesFilesContract.MAX_PAGE_ENTRIES} more"
                    } else {
                        "Show the last $remaining"
                    },
                ).apply { setOnClickListener { load(path, offset = entries.size) } },
                NexusUi.block(),
            )
        }
    }

    private fun entryRow(entry: GlassesFileEntry, clickable: Boolean): View {
        val entryPath = GlassesFilesContract.child(path, entry.name)
        val protected = entry.directory && GlassesFilesContract.isProtected(entryPath)
        val row = if (clickable) NexusUi.pressableCard(this) else NexusUi.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tile = if (entry.directory) {
            NexusUi.iconTileImage(this, R.drawable.ic_files_folder).apply {
                if (protected) imageTintList = android.content.res.ColorStateList.valueOf(NexusUi.INK3)
            }
        } else {
            NexusUi.iconTile(this, GlassesFilesFormat.extensionTag(entry.name)).apply {
                textSize = 9f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.06f
                setTextColor(NexusUi.INK2)
            }
        }
        row.addView(tile)
        val now = System.currentTimeMillis()
        val modified = GlassesFilesFormat.modified(entry.modifiedMillis, now, is24Hour = DateFormat.is24HourFormat(this))
        val sub = when {
            protected -> "Protected"
            entry.directory -> modified
            else -> "${GlassesFilesFormat.size(entry.sizeBytes)} · $modified"
        }
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    NexusUi.rowTitle(this@GlassesFilesActivity, entry.name).apply {
                        maxLines = 1
                        ellipsize = if (entry.directory) TextUtils.TruncateAt.END else TextUtils.TruncateAt.MIDDLE
                    },
                )
                addView(BusTheme.gap(this@GlassesFilesActivity, 4))
                addView(NexusUi.rowSub(this@GlassesFilesActivity, sub))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) },
        )
        if (entry.directory) {
            row.addView(NexusUi.chevron(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        if (clickable) {
            row.setOnClickListener {
                if (entry.directory) load(entryPath) else showFileDialog(entry, entryPath)
            }
        }
        return row
    }

    private fun renderTransfers() {
        transfersHost.removeAllViews()
        val items = snapshot.transfers
        if (items.isEmpty() || state == ViewState.Unsupported) return
        val card = NexusUi.card(this).apply { setPadding(dp(15), dp(12), dp(15), dp(6)) }
        val heading = when {
            items.all { it.direction == GlassesTransferDirection.SEND } -> "SENDING TO GLASSES"
            items.all { it.direction == GlassesTransferDirection.COPY } -> "COPYING TO PHONE"
            else -> "TRANSFERS"
        }
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(NexusUi.sectionLabel(this@GlassesFilesActivity, heading), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (items.any { it.state == GlassesTransferState.QUEUED || it.state == GlassesTransferState.RUNNING }) {
                    addView(
                        NexusUi.textButton(this@GlassesFilesActivity, "Cancel all").apply {
                            setTextColor(NexusUi.INK2)
                            minHeight = dp(28)
                            minimumHeight = dp(28)
                            setOnClickListener { GlassesFilesHub.cancelAll() }
                        },
                    )
                }
            },
            NexusUi.block(),
        )
        val stoppedCount = items.count { it.state == GlassesTransferState.STOPPED }
        items.forEachIndexed { index, item ->
            if (index > 0) {
                card.addView(
                    View(this).apply { setBackgroundColor(NexusUi.LINE2) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                )
            }
            card.addView(transferRow(item, stoppedCount > 1), NexusUi.block())
        }
        transfersHost.addView(card, NexusUi.block())
        transfersHost.addView(BusTheme.gap(this, 10))
    }

    private fun transferRow(item: GlassesTransferItem, offerDismiss: Boolean): View {
        val color = when (item.state) {
            GlassesTransferState.DONE -> NexusUi.GREEN_DIM
            GlassesTransferState.STOPPED -> NexusUi.AMBER
            else -> NexusUi.GREEN
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(
            NexusUi.iconTile(this, if (item.direction == GlassesTransferDirection.SEND) "↑" else "↓").apply {
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(color)
            },
        )
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            NexusUi.rowTitle(this, item.name).apply {
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                if (item.state == GlassesTransferState.DONE) setTextColor(NexusUi.INK2)
            },
        )
        column.addView(BusTheme.gap(this, 5))
        if (item.state == GlassesTransferState.RUNNING) {
            column.addView(gauge(item.percent ?: 0), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            column.addView(BusTheme.gap(this, 5))
        }
        column.addView(NexusUi.metaLabel(this, transferLine(item), if (item.state == GlassesTransferState.RUNNING || item.state == GlassesTransferState.QUEUED) NexusUi.INK3 else color).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        when (item.state) {
            GlassesTransferState.QUEUED,
            GlassesTransferState.RUNNING,
            -> row.addView(closeGlyph { GlassesFilesHub.cancel(item.id) })
            GlassesTransferState.STOPPED -> {
                if (offerDismiss) {
                    row.addView(
                        NexusUi.textButton(this, "Dismiss").apply {
                            setTextColor(NexusUi.INK3)
                            setOnClickListener { GlassesFilesHub.dismiss(item.id) }
                        },
                    )
                }
                row.addView(NexusUi.textButton(this, "Retry").apply { setOnClickListener { GlassesFilesHub.retry(item.id) } })
            }
            GlassesTransferState.DONE -> Unit
        }
        return row
    }

    private fun transferLine(item: GlassesTransferItem): String = when (item.state) {
        GlassesTransferState.QUEUED -> "WAITING" + (item.totalBytes?.let { " · ${GlassesFilesFormat.size(it)}" } ?: "")
        GlassesTransferState.RUNNING -> {
            val done = GlassesFilesFormat.size(item.doneBytes)
            val bytes = item.totalBytes?.let { "$done OF ${GlassesFilesFormat.size(it)}" } ?: done
            "$bytes · ${GlassesFilesFormat.remaining(item.remainingSeconds)}".uppercase()
        }
        GlassesTransferState.DONE -> when {
            item.alreadyPresent -> "ALREADY ON THE GLASSES"
            item.direction == GlassesTransferDirection.COPY -> "SAVED TO DOWNLOAD › NEXUS"
            item.savedAs != null -> "DONE · SAVED AS ${item.savedAs.uppercase()}"
            else -> "DONE" + (item.totalBytes?.let { " · ${GlassesFilesFormat.size(it)}" } ?: "")
        }
        GlassesTransferState.STOPPED -> {
            val reason = item.stop ?: GlassesTransferStop.REFUSED
            val at = if (reason == GlassesTransferStop.LINK_LOST && item.stoppedAtPercent != null) " AT ${item.stoppedAtPercent}%" else ""
            "STOPPED · ${reason.label}$at"
        }
    }

    private fun gauge(percent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = NexusUi.rounded(this@GlassesFilesActivity, NexusUi.LINE, 2)
        addView(
            View(this@GlassesFilesActivity).apply { background = NexusUi.rounded(this@GlassesFilesActivity, NexusUi.GREEN, 2) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, percent.coerceIn(0, 100).toFloat()),
        )
        addView(View(this@GlassesFilesActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - percent.coerceIn(0, 100)).toFloat()))
    }

    private fun closeGlyph(onClick: () -> Unit): TextView = TextView(this).apply {
        text = "×"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(NexusUi.INK3)
        background = NexusUi.pressed(this@GlassesFilesActivity, Color.TRANSPARENT, 17)
        isClickable = true
        isFocusable = true
        contentDescription = "Cancel"
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) }
    }

    private fun renderBottom() {
        bottomHost.removeAllViews()
        if (state == ViewState.Unsupported) return
        bottomHost.addView(View(this).apply { setBackgroundColor(NexusUi.LINE2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(16))
        }
        val queueFull = snapshot.transfers.size >= MAX_QUEUE
        val limitedBlocked = access == GlassesFilesAccess.LIMITED && !GlassesFilesFormat.isWritableWithLimitedAccess(path)
        val enabled = state == ViewState.Listed && snapshot.connected && !limitedBlocked && !queueFull
        panel.addView(
            NexusUi.pillButton(this, "Send files here").apply {
                isEnabled = enabled
                if (!enabled) {
                    background = NexusUi.rounded(this@GlassesFilesActivity, NexusUi.LINE2, 24)
                    setTextColor(NexusUi.INK4)
                }
                setOnClickListener { pickFiles() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val hint = when {
            state != ViewState.Listed || !snapshot.connected -> null
            queueFull -> "FINISH THE CURRENT TRANSFERS FIRST"
            limitedBlocked -> "SENDING WORKS IN DOWNLOAD AND DOCUMENTS UNTIL FULL ACCESS IS ALLOWED"
            else -> null
        }
        if (hint != null) {
            panel.addView(BusTheme.gap(this, 8))
            panel.addView(
                NexusUi.metaLabel(this, hint, NexusUi.INK4).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        bottomHost.addView(panel, NexusUi.block())
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQUEST_PICK) }
    }

    private fun showFileDialog(entry: GlassesFileEntry, entryPath: String) {
        val now = System.currentTimeMillis()
        val meta = listOf(
            GlassesFilesFormat.extensionTag(entry.name),
            GlassesFilesFormat.size(entry.sizeBytes),
            GlassesFilesFormat.modified(entry.modifiedMillis, now, is24Hour = DateFormat.is24HourFormat(this)),
        ).joinToString(" · ").uppercase()
        lateinit var dialog: AlertDialog
        val card = dialogCard().apply {
            addView(NexusUi.cardTitle(this@GlassesFilesActivity, entry.name).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 4))
            addView(NexusUi.rowSub(this@GlassesFilesActivity, meta), NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 12))
            addView(
                NexusUi.cardBody(
                    this@GlassesFilesActivity,
                    "Copying takes ${GlassesFilesFormat.estimate(entry.sizeBytes)}. The file goes to Download › Nexus on this phone.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@GlassesFilesActivity, 14))
            addView(
                NexusUi.pillButton(this@GlassesFilesActivity, "Copy to phone").apply {
                    isEnabled = snapshot.connected
                    setOnClickListener {
                        GlassesFilesHub.copyToPhone(entryPath, entry.name, entry.sizeBytes)
                        dialog.dismiss()
                    }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@GlassesFilesActivity, 4))
            addView(
                dialogActions(
                    NexusUi.textButton(this@GlassesFilesActivity, "Delete…", danger = true).apply {
                        setOnClickListener {
                            dialog.dismiss()
                            confirmDelete(entry.name, entryPath, "It will be removed from the glasses. There is no undo.", folder = false)
                        }
                    },
                    NexusUi.textButton(this@GlassesFilesActivity, "Cancel").apply {
                        setTextColor(NexusUi.INK2)
                        setOnClickListener { dialog.dismiss() }
                    },
                ),
                NexusUi.block(),
            )
        }
        dialog = showCardDialog(card)
    }

    private fun confirmDeleteFolder() {
        confirmDelete(
            GlassesFilesContract.name(path),
            path,
            "The empty folder will be removed from the glasses.",
            folder = true,
        )
    }

    private fun confirmDelete(name: String, target: String, body: String, folder: Boolean) {
        lateinit var dialog: AlertDialog
        val card = dialogCard().apply {
            addView(NexusUi.cardTitle(this@GlassesFilesActivity, "Delete “$name”?").apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 8))
            addView(NexusUi.cardBody(this@GlassesFilesActivity, body), NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 14))
            addView(
                NexusUi.pillButton(this@GlassesFilesActivity, "Delete from glasses", danger = true).apply {
                    setOnClickListener {
                        dialog.dismiss()
                        GlassesFilesHub.delete(target, folder) { error ->
                            if (isDestroyed) return@delete
                            if (error == null) {
                                if (folder && target == path) load(GlassesFilesContract.parent(path)) else load(path)
                            } else {
                                showNotice("COULDN’T DELETE ${name.uppercase()}. TRY AGAIN.")
                            }
                        }
                    }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@GlassesFilesActivity, 4))
            addView(
                dialogActions(
                    NexusUi.textButton(this@GlassesFilesActivity, "Cancel").apply {
                        setTextColor(NexusUi.INK2)
                        setOnClickListener { dialog.dismiss() }
                    },
                ),
                NexusUi.block(),
            )
        }
        dialog = showCardDialog(card)
    }

    private fun showNewFolderDialog() {
        val existing = entries.associate { it.name to it.directory }
        lateinit var dialog: AlertDialog
        val error = NexusUi.metaLabel(this, "", NexusUi.AMBER).apply { visibility = View.GONE }
        val field = NexusUi.field(this, "Folder name").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        val create = NexusUi.textButton(this, "Create").apply {
            isEnabled = false
            alpha = 0.45f
        }
        fun validate(): String? {
            val problem = GlassesFilesFormat.folderNameError(field.text.toString(), existing)
            create.isEnabled = problem == null
            create.alpha = if (problem == null) 1f else 0.45f
            error.text = problem.orEmpty()
            error.visibility = if (problem.isNullOrEmpty()) View.GONE else View.VISIBLE
            return problem
        }
        fun submit() {
            if (validate() != null) return
            val name = field.text.toString().trim()
            dialog.dismiss()
            GlassesFilesHub.makeFolder(path, name) { failure ->
                if (isDestroyed) return@makeFolder
                if (failure == null) {
                    load(GlassesFilesContract.child(path, name))
                } else {
                    showNotice("COULDN’T CREATE ${name.uppercase()}. TRY AGAIN.")
                    load(path)
                }
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                validate()
            }
        })
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        create.setOnClickListener { submit() }
        val folderLabel = if (path.isEmpty()) ROOT_LABEL else GlassesFilesContract.name(path)
        val card = dialogCard().apply {
            addView(NexusUi.cardTitle(this@GlassesFilesActivity, "New folder"), NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 4))
            addView(NexusUi.rowSub(this@GlassesFilesActivity, "IN ${folderLabel.uppercase()}"), NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 14))
            addView(field, NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 6))
            addView(error, NexusUi.block())
            addView(BusTheme.gap(this@GlassesFilesActivity, 6))
            addView(
                dialogActions(
                    NexusUi.textButton(this@GlassesFilesActivity, "Cancel").apply {
                        setTextColor(NexusUi.INK2)
                        setOnClickListener { dialog.dismiss() }
                    },
                    create,
                ),
                NexusUi.block(),
            )
        }
        dialog = showCardDialog(card)
        field.requestFocus()
        field.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, 0)
        }, 120)
    }

    private fun dialogCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@GlassesFilesActivity, NexusUi.PANEL, NexusUi.LINE, 18)
        setPadding(dp(20), dp(18), dp(20), dp(12))
    }

    private fun dialogActions(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        buttons.forEach { addView(it) }
    }

    private fun showCardDialog(card: View): AlertDialog =
        AlertDialog.Builder(this).setView(card).create().apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            show()
        }

    private fun stateCard(title: String, body: String, action: Pair<String, () -> Unit>? = null, dangerAction: Boolean = false) {
        listHost.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.cardTitle(this@GlassesFilesActivity, title), NexusUi.block())
                addView(BusTheme.gap(this@GlassesFilesActivity, 7))
                addView(NexusUi.cardBody(this@GlassesFilesActivity, body), NexusUi.block())
                action?.let { (label, callback) ->
                    addView(BusTheme.gap(this@GlassesFilesActivity, 12))
                    addView(
                        NexusUi.outlinePillButton(this@GlassesFilesActivity, label).apply {
                            if (dangerAction) {
                                setTextColor(NexusUi.DANGER)
                                background = NexusUi.pressedBordered(this@GlassesFilesActivity, NexusUi.PANEL, 14)
                            }
                            setOnClickListener { callback() }
                        },
                        NexusUi.block(),
                    )
                }
            },
            NexusUi.block(),
        )
    }

    private fun showNotice(text: String, durationMs: Long = NOTICE_MS) {
        main.removeCallbacks(clearNotice)
        noticeHost.removeAllViews()
        noticeHost.addView(NexusUi.rowSub(this, text).apply {
            setTextColor(NexusUi.AMBER)
            maxLines = 3
        }, NexusUi.block())
        noticeHost.addView(BusTheme.gap(this, 10))
        main.postDelayed(clearNotice, durationMs)
    }

    /**
     * Location trail: ancestors dim and tappable, the current folder bright. A horizontally
     * scrolling strip pinned to its end keeps the current folder visible however deep the path.
     */
    private fun breadcrumb(segments: List<String>): View {
        val trail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        segments.forEachIndexed { index, label ->
            if (index > 0) {
                trail.addView(crumbText("›", NexusUi.INK4).apply { setPadding(dp(6), 0, dp(6), 0) })
            }
            val current = index == segments.lastIndex
            trail.addView(
                crumbText(label, if (current) NexusUi.INK else NexusUi.INK3).apply {
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    if (!current) {
                        background = NexusUi.pressed(this@GlassesFilesActivity, Color.TRANSPARENT, 8)
                        isClickable = true
                        setOnClickListener {
                            load(pathSegments().take(index).joinToString("/"))
                        }
                    }
                },
            )
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(trail)
            post { fullScroll(View.FOCUS_RIGHT) }
        }
    }

    private fun crumbText(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.04f
        setTextColor(color)
        maxLines = 1
    }

    private fun pathSegments(): List<String> = if (path.isEmpty()) emptyList() else path.split('/')

    private fun canCreateHere(): Boolean {
        if (state != ViewState.Listed || !snapshot.connected) return false
        if (GlassesFilesContract.isProtected(path)) return false
        return access != GlassesFilesAccess.LIMITED || GlassesFilesFormat.isWritableWithLimitedAccess(path)
    }

    private fun titleHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(this@GlassesFilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(12), dp(22), dp(12))
                addView(
                    TextView(this@GlassesFilesActivity).apply {
                        text = "‹"
                        textSize = 26f
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                        setTextColor(NexusUi.INK)
                        background = NexusUi.pressed(this@GlassesFilesActivity, Color.TRANSPARENT, 22)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Back"
                        setOnClickListener { navigateBack() }
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    },
                )
                addView(
                    NexusUi.metaLabel(this@GlassesFilesActivity, "GLASSES FILES", NexusUi.INK).apply {
                        textSize = 12f
                        letterSpacing = 0.2f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(NexusUi.wordmark(this@GlassesFilesActivity, "NEXUS"))
            },
            NexusUi.block(),
        )
        addView(NexusUi.divider(this@GlassesFilesActivity))
    }

    private fun dp(value: Int): Int = NexusUi.dp(this, value)

    private companion object {
        const val STATE_PATH = "path"
        const val PREF_LAST_PATH = "glasses_files_last_path"
        const val ROOT_LABEL = "Glasses storage"
        const val REQUEST_PICK = 41
        const val MAX_PICK = 20
        const val MAX_QUEUE = 40
        const val LOADING_DELAY_MS = 300L
        const val NOTICE_MS = 4_000L
        const val AFTER_GRANT_NOTICE_MS = 12_000L
        val QUICK_FOLDERS = listOf("Download", "Documents", "Books")
    }
}
