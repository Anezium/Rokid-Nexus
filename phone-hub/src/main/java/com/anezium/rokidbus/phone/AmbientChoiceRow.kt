package com.anezium.rokidbus.phone

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Space
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * A choice among a few ambient options, styled like the launcher choice on the display page.
 * Two options per line: four across one phone width wrapped "Automatic" mid-word, so each label
 * gets half the width and a line of its own. [onSelect] runs only for a choice the user makes,
 * never for the initial one or for tapping the option already chosen.
 */
internal fun <T> ambientChoiceRow(
    context: Context,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    var current = selected
    val buttons = mutableListOf<Pair<T, RadioButton>>()
    options.chunked(AMBIENT_CHOICE_COLUMNS).forEach { line ->
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                line.forEach { (value, label) ->
                    val button = RadioButton(context).apply {
                        id = View.generateViewId()
                        text = label
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(NexusUi.INK)
                        buttonTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                        )
                        isChecked = value == selected
                    }
                    buttons += value to button
                    addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                // A short last line keeps the column width of the lines above it.
                repeat(AMBIENT_CHOICE_COLUMNS - line.size) {
                    addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
    // The buttons span several lines, so no RadioGroup owns them: exclusivity is kept here. A
    // tap on a RadioButton only ever checks it, which is why the others are cleared by hand.
    buttons.forEach { (value, button) ->
        button.setOnClickListener {
            buttons.forEach { (_, other) -> if (other !== button) other.isChecked = false }
            button.isChecked = true
            if (value == current) return@setOnClickListener
            current = value
            onSelect(value)
        }
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal const val AMBIENT_CHOICE_COLUMNS = 2
