package com.anezium.rokidbus.plugin.line

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The building blocks of the LINE settings screen, shaped like the other messaging
 * plugins' pages so the phone reads as one product: header with the service mark,
 * one status line, a walkthrough, the kit's rows. Pure view construction; no state.
 */
internal object LineUi {
    enum class Tone { IDLE, BUSY, GOOD, BAD }

    /** The LINE mark in the kit's accent tile, sized for a header. */
    fun tile(context: Context, sizeDp: Int = 48): View = NexusUi.iconTileImage(context, R.drawable.ic_service_line, sizeDp)

    /** The fixed screen chrome: header with the hub's back chevron, then the scrolling body. */
    fun screen(context: Context, subtitle: String, content: LinearLayout, onBack: () -> Unit): LinearLayout =
        NexusUi.fixedRoot(context).apply {
            addView(NexusUi.pluginHeader(context, tile(context), "LINE Messages", subtitle, onBack), NexusUi.block())
            addView(NexusUi.screen(context, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

    /** Section label with the kit's spacing above and below, so pages share one rhythm. */
    fun section(context: Context, column: LinearLayout, label: String, value: String? = null) {
        column.addView(BusTheme.gap(context, 24))
        column.addView(NexusUi.sectionRow(context, label, value), NexusUi.block())
        column.addView(BusTheme.gap(context, 10))
    }

    /** The one status line under the header: dot and headline. */
    class StatusLine(context: Context) {
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexusUi.dp(context, 2), 0, NexusUi.dp(context, 2), 0)
        }
        private val dot: View = NexusUi.dot(context).apply {
            layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 8), NexusUi.dp(context, 8)).apply {
                marginEnd = NexusUi.dp(context, 10)
            }
        }
        private val text: TextView = NexusUi.statusLine(context).apply { setTextColor(NexusUi.INK) }

        init {
            view.addView(dot)
            view.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        fun set(headline: String, tone: Tone) {
            text.text = headline
            NexusUi.setDotColor(
                dot,
                when (tone) {
                    Tone.GOOD -> NexusUi.GREEN
                    Tone.BUSY -> NexusUi.AMBER
                    Tone.BAD -> NexusUi.DANGER
                    Tone.IDLE -> NexusUi.INK3
                },
            )
        }
    }

    /** A quiet explanatory line in the kit's dim ink. */
    fun note(context: Context, body: String): TextView =
        NexusUi.rowSub(context, body).apply {
            maxLines = 4
            setPadding(NexusUi.dp(context, 4), 0, NexusUi.dp(context, 4), 0)
        }

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
                    typeface = Typeface.MONOSPACE
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

    /** A text input that never reaches autofill or the saved view state: the email and the password. */
    fun secretField(context: Context, hint: String, type: Int): EditText = NexusUi.field(context, hint).apply {
        inputType = type
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    /** A masked field with a Show/Hide switch beside it, for the password the owner may want to check. */
    fun revealable(context: Context, field: EditText): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hidden = field.inputType
        val shown = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
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

    /** A two-line text column for rows: title over a sub line. */
    fun rowText(context: Context, title: String, sub: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(NexusUi.rowTitle(context, title))
        addView(BusTheme.gap(context, 4))
        addView(NexusUi.rowSub(context, sub))
    }

    /** A tappable row inside a card: title, sub, chevron. */
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

    /** Destructive row shaped like the kit's uninstall card so the bottom of the page reads as one list. */
    fun dangerRow(context: Context, title: String, sub: String, action: String, onClick: () -> Unit): LinearLayout =
        NexusUi.pressableCard(context).apply {
            contentDescription = title
            setOnClickListener { onClick() }
            addView(rowText(context, title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexusUi.rowSub(context, "$action ›").apply { setTextColor(NexusUi.DANGER) })
        }

    /**
     * The approval PIN as a row of digit cells: large, mono, phosphor, readable from
     * across the room while LINE asks for it. Nothing here is saved with the view
     * state; [set] with an empty PIN clears every cell.
     */
    class PinCode(private val context: Context) {
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isSaveEnabled = false
        }

        fun set(pin: String) {
            view.removeAllViews()
            val digits = pin.filter { !it.isWhitespace() && it != '-' }
            if (digits.isEmpty()) return
            // Eight cells is the most a phone width carries legibly; longer codes fall back to one line.
            if (digits.length > 8) {
                view.addView(NexusUi.hero(context, 30f).apply {
                    text = digits
                    typeface = Typeface.MONOSPACE
                    letterSpacing = 0.12f
                    isSaveEnabled = false
                    setTextColor(NexusUi.GREEN)
                })
                return
            }
            for (char in digits) view.addView(cell(char), LinearLayout.LayoutParams(0, NexusUi.dp(context, 56), 1f).apply {
                marginStart = NexusUi.dp(context, 3)
                marginEnd = NexusUi.dp(context, 3)
            })
        }

        private fun cell(char: Char): TextView = TextView(context).apply {
            text = char.toString()
            textSize = 26f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSaveEnabled = false
            setTextColor(NexusUi.GREEN)
            background = NexusUi.bordered(context, NexusUi.BG, NexusUi.alpha(NexusUi.GREEN, 0x40), 10)
        }
    }
}
