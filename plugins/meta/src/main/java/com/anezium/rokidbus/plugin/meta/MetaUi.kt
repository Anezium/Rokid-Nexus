package com.anezium.rokidbus.plugin.meta

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The building blocks the three Meta Messages screens share, so the hub and every
 * service page read as one product: the same header, status line, action rows,
 * risk note and destructive rows. Pure view construction; no state lives here.
 */
internal object MetaUi {
    /** The bundled brand vector of a service, or null for one without a mark. */
    fun serviceMark(service: String): Int? = when (service) {
        "WhatsApp" -> R.drawable.ic_service_whatsapp
        "Instagram" -> R.drawable.ic_service_instagram
        "Messenger" -> R.drawable.ic_service_messenger
        else -> null
    }

    /** The service mark in the kit's accent tile, sized for a row or a header. */
    fun serviceTile(context: Context, service: String, sizeDp: Int = 40): View {
        val mark = serviceMark(service)
        // A service without a bundled mark keeps the kit's letter tile.
        return if (mark != null) NexusUi.iconTileImage(context, mark, sizeDp)
        else NexusUi.iconTile(context, service.take(1).uppercase(), sizeDp).apply {
            textSize = if (sizeDp >= 48) 20f else 17f
        }
    }

    /** The fixed screen chrome: service header with the hub's back chevron, then the scrolling body. */
    fun screen(
        context: Context,
        service: String,
        subtitle: String,
        content: LinearLayout,
        onBack: (() -> Unit)?,
    ): LinearLayout = NexusUi.fixedRoot(context).apply {
        addView(NexusUi.pluginHeader(context, serviceTile(context, service, 48), service, subtitle, onBack), NexusUi.block())
        addView(NexusUi.screen(context, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Section label with the kit's spacing above and below, so pages share one rhythm. */
    fun section(context: Context, column: LinearLayout, label: String, value: String? = null) {
        column.addView(BusTheme.gap(context, 24))
        column.addView(NexusUi.sectionRow(context, label, value), NexusUi.block())
        column.addView(BusTheme.gap(context, 10))
    }

    /** The one status line under the header: dot, headline, optional trailing action. */
    class StatusLine(context: Context, trailing: View? = null) {
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexusUi.dp(context, 2), 0, if (trailing == null) NexusUi.dp(context, 2) else 0, 0)
        }
        val dot: View = NexusUi.dot(context).apply {
            layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 8), NexusUi.dp(context, 8)).apply {
                marginEnd = NexusUi.dp(context, 10)
            }
        }
        val text: TextView = NexusUi.statusLine(context).apply { setTextColor(NexusUi.INK) }

        init {
            view.addView(dot)
            view.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) view.addView(trailing)
        }

        fun set(headline: String, tone: AccountStatus.Tone) {
            text.text = headline
            NexusUi.setDotColor(dot, toneColor(tone))
        }
    }

    fun toneColor(tone: AccountStatus.Tone?): Int = when (tone) {
        AccountStatus.Tone.GOOD -> NexusUi.GREEN
        AccountStatus.Tone.BUSY -> NexusUi.AMBER
        AccountStatus.Tone.BAD -> NexusUi.DANGER
        AccountStatus.Tone.IDLE, null -> NexusUi.INK3
    }

    /** The amber notice under the status line; hidden while empty. */
    fun notice(context: Context): TextView = NexusUi.cardBody(context, "").apply {
        setTextColor(NexusUi.AMBER)
        visibility = View.GONE
    }

    /** One calm line under a service's sign-in: what this client is, and what the service may do about it. */
    fun unofficialNote(context: Context, service: String): TextView =
        NexusUi.rowSub(context, "Unofficial client, not made by Meta. $service may sign this device out or restrict the account.").apply {
            maxLines = 3
            setPadding(NexusUi.dp(context, 4), 0, NexusUi.dp(context, 4), 0)
        }

    fun setNotice(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A quiet explanatory note in the kit's dim ink, for the one disclosure a screen carries. */
    fun note(context: Context, body: String): TextView =
        NexusUi.cardBody(context, body).apply { setTextColor(NexusUi.INK3) }

    /**
     * A numbered walkthrough: one row per step, the current step in full ink and the
     * others dimmed, so the owner always sees where they are without reading everything.
     */
    class Steps(private val context: Context, labels: List<String>) {
        val view: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val numbers = ArrayList<TextView>()
        private val texts = ArrayList<TextView>()

        init {
            labels.forEachIndexed { index, label ->
                if (index > 0) view.addView(BusTheme.gap(context, 12))
                val number = TextView(context).apply {
                    text = (index + 1).toString()
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 24), NexusUi.dp(context, 24)).apply {
                        marginEnd = NexusUi.dp(context, 12)
                    }
                }
                val text = NexusUi.cardBody(context, label)
                numbers += number
                texts += text
                view.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    addView(number)
                    addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
            highlight(0)
        }

        /** Marks [index] as the step in progress; steps before it read as done, steps after as pending. */
        fun highlight(index: Int) {
            numbers.forEachIndexed { i, number ->
                val done = i < index
                val active = i == index
                number.text = if (done) "✓" else (i + 1).toString()
                number.setTextColor(if (active) NexusUi.ON_ACCENT else if (done) NexusUi.GREEN_DIM else NexusUi.INK3)
                number.background = if (active) NexusUi.rounded(context, NexusUi.GREEN, 12)
                else NexusUi.bordered(context, Color.TRANSPARENT, if (done) NexusUi.alpha(NexusUi.GREEN, 0x50) else NexusUi.LINE2, 12)
                texts[i].setTextColor(if (active) NexusUi.INK else if (done) NexusUi.INK2 else NexusUi.INK3)
            }
        }
    }

    /** A text input that never reaches autofill or the saved view state: credentials, codes, numbers. */
    fun secretField(context: Context, hint: String, type: Int): EditText = NexusUi.field(context, hint).apply {
        inputType = type
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }

    /**
     * A masked field with a SHOW/HIDE switch beside it, for passwords the owner may
     * need to check while typing.
     */
    fun revealable(context: Context, field: EditText): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hidden = field.inputType
        val shown = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        addView(NexusUi.metaLabel(context, "Show", NexusUi.GREEN).apply {
            setPadding(NexusUi.dp(context, 14), NexusUi.dp(context, 12), NexusUi.dp(context, 4), NexusUi.dp(context, 12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val reveal = field.inputType == hidden
                val cursor = field.selectionEnd
                field.inputType = if (reveal) shown else hidden
                field.setSelection(cursor.coerceIn(0, field.text.length))
                text = if (reveal) "Hide" else "Show"
            }
        })
    }

    /** A two-line text column for rows: title over a mono sub line. */
    fun rowText(context: Context, title: String, sub: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(NexusUi.rowTitle(context, title))
        addView(BusTheme.gap(context, 4))
        addView(NexusUi.rowSub(context, sub))
    }

    /** A tappable row inside a flush card: title, sub, chevron. Disabled rows fade instead of hiding. */
    fun actionRow(context: Context, title: String, sub: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexusUi.dp(context, 15), NexusUi.dp(context, 14), NexusUi.dp(context, 15), NexusUi.dp(context, 14))
            background = NexusUi.pressed(context, Color.TRANSPARENT, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(rowText(context, title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexusUi.chevron(context))
        }

    /** A hairline between two rows of a flush card. */
    fun rowDivider(context: Context): View = NexusUi.divider(context).apply {
        (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; bottomMargin = 0 }
    }

    /** Destructive row shaped like the kit's uninstall card so the bottom of every page reads as one list. */
    fun dangerRow(context: Context, title: String, sub: String, action: String, onClick: () -> Unit): LinearLayout =
        NexusUi.pressableCard(context).apply {
            contentDescription = title
            setOnClickListener { onClick() }
            addView(rowText(context, title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexusUi.rowSub(context, "$action ›").apply { setTextColor(NexusUi.DANGER) })
        }

    fun setRowEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.45f
    }

    /**
     * The pairing code as a row of character cells: large, mono, phosphor, with the
     * groups WhatsApp separates by a dash kept apart. Nothing here is saved with the
     * view state; [set] with an empty code clears every cell.
     */
    class PairingCode(private val context: Context) {
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isSaveEnabled = false
        }

        fun set(code: String) {
            view.removeAllViews()
            val groups = code.split('-').filter { it.isNotEmpty() }
            if (groups.isEmpty()) return
            // Ten cells is the most a phone width carries legibly; longer codes fall back to one line.
            if (code.count { it != '-' } > 10) {
                view.addView(NexusUi.hero(context, 30f).apply {
                    text = code
                    typeface = android.graphics.Typeface.MONOSPACE
                    letterSpacing = 0.12f
                    isSaveEnabled = false
                    setTextColor(NexusUi.GREEN)
                })
                return
            }
            groups.forEachIndexed { index, group ->
                if (index > 0) view.addView(View(context), LinearLayout.LayoutParams(NexusUi.dp(context, 14), 1))
                for (char in group) view.addView(cell(char), LinearLayout.LayoutParams(0, NexusUi.dp(context, 52), 1f).apply {
                    marginStart = NexusUi.dp(context, 2)
                    marginEnd = NexusUi.dp(context, 2)
                })
            }
        }

        private fun cell(char: Char): TextView = TextView(context).apply {
            text = char.toString()
            textSize = 24f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSaveEnabled = false
            setTextColor(NexusUi.GREEN)
            background = NexusUi.bordered(context, NexusUi.BG, NexusUi.alpha(NexusUi.GREEN, 0x40), 10)
        }
    }
}

/**
 * What each service page last learned about its saved account, kept in memory only so
 * the hub can show it without opening an engine. It never outlives the process and
 * holds no secret: a handle is public, a pairing code or password never reaches it.
 * A settled change (a login or pairing that completed, a session forgotten) is also the
 * one signal the glasses pages have that a service gained or lost its session while
 * they are open; observers hear it on the thread that set it.
 */
internal object MetaPresence {
    sealed class State {
        object Unknown : State()
        object Absent : State()
        data class Present(val label: String) : State()
    }

    private val states = HashMap<String, State>()
    private val observers = java.util.concurrent.CopyOnWriteArrayList<(String, State) -> Unit>()

    @Synchronized fun get(serviceId: String): State = states[serviceId] ?: State.Unknown

    fun set(serviceId: String, state: State) {
        val changed = synchronized(this) {
            val before = states[serviceId]
            states[serviceId] = state
            // Present labels change with the handle; presence itself changes only across kinds.
            before == null || before::class != state::class
        }
        if (changed) observers.forEach { it(serviceId, state) }
    }

    /** Hears every change of presence kind; returns the call that stops it. */
    fun observe(observer: (serviceId: String, state: State) -> Unit): () -> Unit {
        observers += observer
        return { observers -= observer }
    }
}
