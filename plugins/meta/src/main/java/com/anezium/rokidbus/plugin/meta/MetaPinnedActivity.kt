package com.anezium.rokidbus.plugin.meta

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import org.json.JSONObject
import java.io.File

/**
 * Pinned contacts, one tab per service: the conversations the owner wants first on that
 * service's glasses page, in their order. The list orders pins by dragging their handle and
 * removes them with ×; "Pin a contact" turns the screen into a picker over the conversations
 * the service lists, so a pin is always one the glasses can open. A service connects only
 * while its picker is in front, and closes with this screen.
 */
class MetaPinnedActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var service = MessagingService.WHATSAPP
    private var picking = false
    private var started = false
    private var query = ""
    private val selected = LinkedHashSet<String>()
    /** Each service's conversations once loaded; null while loading, absent until asked. */
    private val loaded = HashMap<MessagingService, Result<List<MetaChat>>?>()
    private var whatsAppOwner: Any? = null
    private var pinnedColumn: LinearLayout? = null
    private var pickerList: LinearLayout? = null
    private var pickerStatus: TextView? = null
    private var counter: TextView? = null
    private var pinButton: Button? = null
    private var dragStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        show()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (picking) load(service)
    }

    override fun onStop() {
        started = false
        loaded.clear()
        whatsAppOwner?.let(WhatsMeowSession::release)
        whatsAppOwner = null
        for (account in MetaAccount.entries) {
            val host = account.host
            if (!host.picker) continue
            host.picker = false
            host.worker.execute { if (!host.visible && !host.glasses && !host.picker) host.engine?.disconnect() }
            host.releaseIfHidden()
        }
        super.onStop()
    }

    @Deprecated("The picker is a mode of this screen, so Back leaves it first.")
    override fun onBackPressed() {
        if (picking) {
            picking = false
            show()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun onBack() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    // ---- screens ------------------------------------------------------------------------

    private fun show() {
        pinnedColumn = null
        pickerList = null
        val content = NexusUi.contentColumn(this, topDp = 4)
        val root = NexusUi.fixedRoot(this)
        val tile = NexusUi.iconTileImage(this, NexusPluginIcons.drawableFor("chat", "meta-messages"), 48)
        if (picking) {
            root.addView(NexusUi.pluginHeader(this, MetaUi.serviceTile(this, service.title, 48), "Pin a contact",
                "${service.title} · ${MetaPins.get(this, service).size} pinned") { onBack() }, NexusUi.block())
            content.addView(NexusUi.field(this, "Search conversations").apply {
                setText(query)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        fillPicker()
                    }
                })
            }, NexusUi.block())
            pickerStatus = MetaUi.note(this, "").also {
                content.addView(it, NexusUi.block().apply { topMargin = NexusUi.dp(this@MetaPinnedActivity, 12) })
            }
            MetaUi.section(this, content, "Conversations", "newest first")
            pickerList = NexusUi.card(this).apply { setPadding(0, 0, 0, 0) }
            content.addView(pickerList, NexusUi.block())
            root.addView(NexusUi.screen(this, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(pickerBar(), NexusUi.block())
            if (started) load(service)
            fillPicker()
        } else {
            root.addView(NexusUi.pluginHeader(this, tile, "Pinned contacts", "Shown first on the glasses") { onBack() }, NexusUi.block())
            content.addView(tabs(), NexusUi.block())
            pinnedColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(pinnedColumn, NexusUi.block())
            content.addView(NexusUi.outlinePillButton(this, "+ Pin a contact").apply {
                setOnClickListener {
                    picking = true
                    query = ""
                    selected.clear()
                    show()
                }
            }, NexusUi.block().apply { topMargin = NexusUi.dp(this@MetaPinnedActivity, 12) })
            content.addView(MetaUi.note(this, "Pinned conversations stay at the top of the ${service.title} page on the glasses, in this order. The most recent conversations follow."),
                NexusUi.block().apply { topMargin = NexusUi.dp(this@MetaPinnedActivity, 10) })
            root.addView(NexusUi.screen(this, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            fillPinned()
        }
        setContentView(root)
    }

    /** One segment per service; a service without a session reads faded but still opens. */
    private fun tabs(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = NexusUi.rounded(context, NexusUi.PANEL, 12)
        val pad = NexusUi.dp(context, 3)
        setPadding(pad, pad, pad, pad)
        MessagingService.entries.forEachIndexed { index, each ->
            val count = MetaPins.get(context, each).size
            addView(TextView(context).apply {
                text = if (count > 0) "${each.title} $count" else each.title
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, NexusUi.dp(context, 9), 0, NexusUi.dp(context, 9))
                val on = each == service
                setTextColor(if (on) NexusUi.INK else NexusUi.INK2)
                background = if (on) NexusUi.bordered(context, NexusUi.CARD, NexusUi.LINE, 9) else null
                alpha = if (hasSession(each)) 1f else 0.45f
                setOnClickListener {
                    service = each
                    show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = pad
            })
        }
    }

    /** The service's pins in their order; a pin the service no longer lists says so, the glasses skip it. */
    private fun fillPinned() {
        val column = pinnedColumn ?: return
        column.removeAllViews()
        val pins = MetaPins.get(this, service)
        MetaUi.section(this, column, "Pinned · in this order", "${pins.size} pinned")
        if (pins.isEmpty()) {
            column.addView(NexusUi.card(this).apply {
                addView(NexusUi.cardBody(context,
                    if (hasSession(service)) "No pinned contacts yet. The ${service.title} page on the glasses lists your most recent conversations."
                    else "Sign in to ${service.title} first, then pin the conversations you want first on the glasses."))
            }, NexusUi.block())
            return
        }
        val listed = loaded[service]?.getOrNull()?.mapTo(HashSet()) { it.id }
        val card = NexusUi.card(this).apply { setPadding(0, 0, 0, 0) }
        pins.forEachIndexed { index, pin ->
            if (index > 0) card.addView(MetaUi.rowDivider(this))
            val sub = when {
                listed != null && pin.id !in listed -> "Not in your recent ${service.title} conversations"
                pin.group -> "Group"
                else -> "Contact"
            }
            card.addView(pinRow(card, pin, sub))
        }
        column.addView(card, NexusUi.block())
    }

    private fun pinRow(card: LinearLayout, pin: MetaPin, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = pin.id
        setBackgroundColor(NexusUi.PANEL)
        setPadding(NexusUi.dp(context, 6), NexusUi.dp(context, 12), NexusUi.dp(context, 12), NexusUi.dp(context, 12))
        val row = this
        addView(TextView(context).apply {
            text = "≡"
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "Drag to reorder ${pin.title}"
            setTextColor(NexusUi.INK3)
            setOnTouchListener { _, event -> drag(card, row, event) }
        }, LinearLayout.LayoutParams(NexusUi.dp(context, 40), NexusUi.dp(context, 44)))
        addView(avatar(pin.title, pin.group))
        addView(MetaUi.rowText(context, pin.title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = NexusUi.dp(context, 12)
        })
        addView(TextView(context).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = "Unpin ${pin.title}"
            setTextColor(NexusUi.INK2)
            background = NexusUi.bordered(context, NexusUi.PANEL, NexusUi.LINE, 16)
            setOnClickListener {
                MetaPins.set(this@MetaPinnedActivity, service, MetaPins.get(this@MetaPinnedActivity, service).filter { it.id != pin.id })
                show()
            }
        }, LinearLayout.LayoutParams(NexusUi.dp(context, 32), NexusUi.dp(context, 32)))
    }

    /**
     * Drags a pin by its handle: the row follows the finger, the rows it passes step aside, and
     * the new order is saved when the finger lifts.
     */
    private fun drag(card: LinearLayout, row: View, event: MotionEvent): Boolean {
        val rows = (0 until card.childCount).map(card::getChildAt).filter { it.tag is String }
        val from = rows.indexOf(row)
        if (from < 0) return false
        val step = if (rows.size > 1) (rows[1].top - rows[0].top).toFloat() else row.height.toFloat()
        val dy = event.rawY - dragStartY
        val to = if (step <= 0f) from else (from + Math.round(dy / step)).coerceIn(0, rows.size - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.rawY
                card.parent?.requestDisallowInterceptTouchEvent(true)
                row.elevation = NexusUi.dp(this, 6).toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                row.translationY = dy
                rows.forEachIndexed { index, other ->
                    if (other !== row) other.translationY = when {
                        index in (from + 1)..to -> -step
                        index in to until from -> step
                        else -> 0f
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rows.forEach { it.translationY = 0f }
                row.elevation = 0f
                if (event.actionMasked == MotionEvent.ACTION_UP && to != from) {
                    val pins = MetaPins.get(this, service).toMutableList()
                    val moved = pins.indexOfFirst { it.id == row.tag }
                    if (moved >= 0) {
                        pins.add(to.coerceAtMost(pins.size - 1), pins.removeAt(moved))
                        MetaPins.set(this, service, pins)
                    }
                    fillPinned()
                }
            }
        }
        return true
    }

    // ---- picker -------------------------------------------------------------------------

    private fun hasSession(service: MessagingService): Boolean {
        val account = MetaAccount.byId(service.id) ?: return File(noBackupFilesDir, "whatsmeow/session.sqlite").isFile
        return account.open != null && File(File(noBackupFilesDir, account.directory), account.sessionFile).isFile
    }

    /** Lists the service's conversations once per visit, the way its glasses page does. */
    private fun load(service: MessagingService) {
        if (service in loaded || !hasSession(service)) return
        loaded[service] = null
        val deliver: (Result<List<MetaChat>>) -> Unit = { result ->
            if (started && loaded.containsKey(service)) {
                loaded[service] = result
                result.onSuccess { MetaPins.rename(this, service, it) }
                if (picking && service == this.service) fillPicker()
            }
        }
        val account = MetaAccount.byId(service.id)
        if (account == null) {
            val owner = whatsAppOwner ?: Any().also { whatsAppOwner = it; WhatsMeowSession.acquire(it) }
            WhatsMeowSession.call(this, owner, { engine ->
                val status = JSONObject(engine.awaitLogin(8_000))
                check(status.getBoolean("connected") && status.getBoolean("loggedIn") && status.optString("blocker").isEmpty())
                MetaChats.whatsApp(engine, 1)
            }, deliver)
            return
        }
        val host = account.host
        host.picker = true
        val directory = File(noBackupFilesDir, account.directory).absolutePath
        val open = checkNotNull(account.open)
        host.worker.execute {
            val result = runCatching {
                val engine = host.engine ?: open(directory).also { host.engine = it }
                if (!JSONObject(engine.status()).getBoolean("connected")) engine.connect(30_000)
                MetaChats.account(engine)
            }
            main.post { deliver(result) }
        }
    }

    private fun pickerBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(NexusUi.BG)
        setPadding(NexusUi.dp(context, 22), NexusUi.dp(context, 10), NexusUi.dp(context, 22), NexusUi.dp(context, 14))
        counter = NexusUi.rowSub(context, "").apply { setTextColor(NexusUi.INK2) }
        addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pinButton = NexusUi.pillButton(context, "Pin").apply { setOnClickListener { pinSelected() } }
        addView(pinButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun fillPicker() {
        val list = pickerList ?: return
        val result = loaded[service]
        pickerStatus?.apply {
            text = when {
                !hasSession(service) -> "Sign in to ${service.title} first, from the Meta Messages screen."
                result == null -> "Connecting to ${service.title}…"
                result.isFailure -> "Can't reach ${service.title} · go back and try again."
                else -> ""
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val pinned = MetaPins.ids(this, service).toSet()
        val needle = query.trim()
        val chats = result?.getOrNull().orEmpty().filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
        list.removeAllViews()
        list.visibility = if (chats.isEmpty()) View.GONE else View.VISIBLE
        chats.forEachIndexed { index, chat ->
            if (index > 0) list.addView(MetaUi.rowDivider(this))
            list.addView(pickRow(chat, chat.id in pinned))
        }
        val count = selected.size
        counter?.text = if (count == 0) "Tap a conversation to pin it" else "Selected $count"
        pinButton?.apply {
            text = if (count == 0) "Pin" else "Pin $count"
            isEnabled = count > 0
            alpha = if (count > 0) 1f else 0.4f
        }
    }

    /** An encrypted Messenger chat is listed but cannot be pinned: the glasses cannot open it. */
    private fun pickRow(chat: MetaChat, pinned: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(NexusUi.dp(context, 15), NexusUi.dp(context, 12), NexusUi.dp(context, 15), NexusUi.dp(context, 12))
        val chosen = chat.id in selected
        val sub = when {
            pinned -> "Pinned"
            !chat.supported -> MetaPages.SUB_ENCRYPTED
            chat.group -> "Group"
            else -> "Contact"
        }
        addView(avatar(chat.title, chat.group))
        addView(MetaUi.rowText(context, chat.title, sub),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = NexusUi.dp(context, 12) })
        addView(check(chosen, pinned))
        if (!chat.supported && !pinned) alpha = 0.45f
        if (!pinned && chat.supported) {
            background = NexusUi.pressed(context, Color.TRANSPARENT, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!selected.remove(chat.id)) selected += chat.id
                fillPicker()
            }
        }
    }

    private fun pinSelected() {
        val chats = loaded[service]?.getOrNull().orEmpty().associateBy { it.id }
        val added = selected.mapNotNull(chats::get).map { MetaPin(it.id, it.title, it.group) }
        if (added.isEmpty()) return
        MetaPins.set(this, service, MetaPins.get(this, service) + added)
        picking = false
        selected.clear()
        show()
    }

    // ---- parts --------------------------------------------------------------------------

    /** Initials, never a profile picture: nothing is fetched to draw this list. */
    private fun avatar(title: String, group: Boolean): TextView = TextView(this).apply {
        text = initials(title)
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(NexusUi.INK2)
        background = NexusUi.bordered(context, NexusUi.PANEL, NexusUi.LINE, if (group) 11 else 18)
        layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 36), NexusUi.dp(context, 36))
    }

    private fun check(chosen: Boolean, pinned: Boolean): TextView = TextView(this).apply {
        text = if (chosen || pinned) "✓" else ""
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (chosen) NexusUi.ON_ACCENT else NexusUi.GREEN_DIM)
        background = when {
            chosen -> NexusUi.rounded(context, NexusUi.GREEN, 11)
            pinned -> NexusUi.bordered(context, Color.TRANSPARENT, NexusUi.GREEN_DIM, 11)
            else -> NexusUi.bordered(context, Color.TRANSPARENT, NexusUi.INK4, 11)
        }
        layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 22), NexusUi.dp(context, 22))
    }

    private fun initials(title: String): String =
        title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { String(Character.toChars(it.codePointAt(0))) }.uppercase().ifEmpty { "?" }
}
