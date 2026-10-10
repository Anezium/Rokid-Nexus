package com.anezium.rokidbus.phone

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * A one-line choice among a few ambient options, styled like the launcher choice on the display
 * page. [onSelect] runs only for a choice the user makes, never for the initial one.
 */
internal fun <T> ambientChoiceRow(
    context: Context,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
): RadioGroup = RadioGroup(context).apply {
    orientation = RadioGroup.HORIZONTAL
    options.forEach { (value, label) ->
        addView(
            RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 12f
                setTextColor(NexusUi.INK)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                )
                isChecked = value == selected
                setOnCheckedChangeListener { _, checked -> if (checked) onSelect(value) }
            },
            RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
