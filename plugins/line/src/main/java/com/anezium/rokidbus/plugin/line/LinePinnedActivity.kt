package com.anezium.rokidbus.plugin.line

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * Pinned contacts: the conversations the owner wants first on the glasses list, in their order.
 * The list orders pins by dragging their handle and removes them with ×; "Pin a contact" turns
 * the screen into a picker over the conversations LINE lists, so a pin is always one the glasses
 * can open. LINE connects only while this screen is in front, like the glasses page.
 */
class LinePinnedActivity : Activity() {
    private val owner = Any()
    private var picking = false
    private var connectAsked = false
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private var pinnedCard: LinearLayout? = null
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
        LineSession.acquire(this, owner, ::changed)
    }

    override fun onStop() {
        LineSession.release(owner)
        connectAsked = false
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

    private fun changed() {
        if (isFinishing || isDestroyed) return
        val snapshot = LineSession.snapshot
        if (snapshot.state in setOf("standby", "offline") && !connectAsked) {
            connectAsked = true
            LineSession.call(this, owner, { it.connect() }) { }
        }
        if (snapshot.state == "ready") LinePins.rename(this, snapshot.chats)
        if (picking) fillPicker() else fillPinned()
    }

    // ---- screens ------------------------------------------------------------------------

    private fun show() {
        pinnedCard = null
        pickerList = null
        val content = NexusUi.contentColumn(this, topDp = 4)
        val root = NexusUi.fixedRoot(this)
        if (picking) {
            root.addView(NexusUi.pluginHeader(this, LineUi.tile(this), "Pin a contact", "LINE · ${LinePins.get(this).size} pinned") { onBack() }, NexusUi.block())
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
            pickerStatus = LineUi.note(this, "").also {
                content.addView(it, NexusUi.block().apply { topMargin = NexusUi.dp(this@LinePinnedActivity, 12) })
            }
            LineUi.section(this, content, "Conversations", "newest first")
            pickerList = NexusUi.card(this).apply { setPadding(0, 0, 0, 0) }
            content.addView(pickerList, NexusUi.block())
            root.addView(NexusUi.screen(this, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(pickerBar(), NexusUi.block())
            fillPicker()
        } else {
            root.addView(NexusUi.pluginHeader(this, LineUi.tile(this), "Pinned contacts", "Shown first on the glasses") { onBack() }, NexusUi.block())
            pinnedCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(pinnedCard, NexusUi.block())
            content.addView(NexusUi.outlinePillButton(this, "+ Pin a contact").apply {
                setOnClickListener {
                    picking = true
                    query = ""
                    selected.clear()
                    show()
                }
            }, NexusUi.block().apply { topMargin = NexusUi.dp(this@LinePinnedActivity, 12) })
            content.addView(LineUi.note(this, "Pinned conversations stay at the top of the LINE page on the glasses, in this order. The most recent conversations follow."),
                NexusUi.block().apply { topMargin = NexusUi.dp(this@LinePinnedActivity, 10) })
            root.addView(NexusUi.screen(this, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            fillPinned()
        }
        setContentView(root)
    }

    private fun onBack() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    /** The pins in their order; a pin LINE no longer lists says so, the glasses skip it. */
    private fun fillPinned() {
        val column = pinnedCard ?: return
        column.removeAllViews()
        val pins = LinePins.get(this)
        LineUi.section(this, column, "Pinned · in this order", "${pins.size} pinned")
        if (pins.isEmpty()) {
            column.addView(NexusUi.card(this).apply {
                addView(NexusUi.cardBody(context, "No pinned contacts yet. The LINE page on the glasses lists your most recent conversations."))
            }, NexusUi.block())
            return
        }
        val snapshot = LineSession.snapshot
        val listed = snapshot.chats.mapTo(HashSet()) { it.id }
        val card = NexusUi.card(this).apply { setPadding(0, 0, 0, 0) }
        pins.forEachIndexed { index, pin ->
            if (index > 0) card.addView(divider())
            val sub = when {
                snapshot.state == "ready" && pin.id !in listed -> "Not in your recent LINE conversations"
                pin.group -> "Group"
                else -> "Contact"
            }
            card.addView(pinRow(card, pin, sub))
        }
        column.addView(card, NexusUi.block())
    }

    private fun pinRow(card: LinearLayout, pin: LinePin, sub: String): LinearLayout = LinearLayout(this).apply {
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
        addView(LineUi.rowText(context, pin.title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
                LinePins.set(this@LinePinnedActivity, LinePins.get(this@LinePinnedActivity).filter { it.id != pin.id })
                fillPinned()
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
                    val pins = LinePins.get(this).toMutableList()
                    val moved = pins.indexOfFirst { it.id == row.tag }
                    if (moved >= 0) {
                        pins.add(to.coerceAtMost(pins.size - 1), pins.removeAt(moved))
                        LinePins.set(this, pins)
                    }
                    fillPinned()
                }
            }
        }
        return true
    }

    // ---- picker -------------------------------------------------------------------------

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
        val snapshot = LineSession.snapshot
        pickerStatus?.apply {
            text = when (snapshot.state) {
                "ready" -> ""
                "signed_out" -> "Link your LINE account first, on the previous screen."
                "failed" -> "LINE unavailable · check sign-in and try again."
                else -> "Connecting to LINE…"
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val pinned = LinePins.ids(this).toSet()
        val needle = query.trim()
        val chats = snapshot.chats.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
        list.removeAllViews()
        list.visibility = if (chats.isEmpty()) View.GONE else View.VISIBLE
        chats.forEachIndexed { index, chat ->
            if (index > 0) list.addView(divider())
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

    private fun pickRow(chat: LineChat, pinned: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(NexusUi.dp(context, 15), NexusUi.dp(context, 12), NexusUi.dp(context, 15), NexusUi.dp(context, 12))
        val chosen = chat.id in selected
        addView(avatar(chat.title, chat.group))
        addView(LineUi.rowText(context, chat.title, if (pinned) "Pinned" else if (chat.group) "Group" else "Contact"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = NexusUi.dp(context, 12) })
        addView(check(chosen, pinned))
        if (!pinned) {
            background = NexusUi.pressed(context, android.graphics.Color.TRANSPARENT, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!selected.remove(chat.id)) selected += chat.id
                fillPicker()
            }
        }
    }

    private fun pinSelected() {
        val chats = LineSession.snapshot.chats.associateBy { it.id }
        val added = selected.mapNotNull(chats::get).map { LinePin(it.id, it.title, it.group) }
        if (added.isEmpty()) return
        LinePins.set(this, LinePins.get(this) + added)
        picking = false
        selected.clear()
        show()
    }

    // ---- parts --------------------------------------------------------------------------

    private fun divider(): View = View(this).apply {
        setBackgroundColor(NexusUi.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexusUi.dp(context, 1))
    }

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
            pinned -> NexusUi.bordered(context, android.graphics.Color.TRANSPARENT, NexusUi.GREEN_DIM, 11)
            else -> NexusUi.bordered(context, android.graphics.Color.TRANSPARENT, NexusUi.INK4, 11)
        }
        layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 22), NexusUi.dp(context, 22))
    }

    private fun initials(title: String): String =
        title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { String(Character.toChars(it.codePointAt(0))) }.uppercase().ifEmpty { "?" }
}
