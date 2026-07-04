package com.anezium.rokidbus.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object BusTheme {
    const val bg = 0xFF030C06.toInt()
    const val panel = 0xFF08120B.toInt()
    const val field = 0xFF050D08.toInt()
    const val stroke = 0xFF203426.toInt()
    const val text = 0xFFE0F5E2.toInt()
    const val muted = 0xFF8DA291.toInt()
    const val dim = 0xFF556A5A.toInt()
    const val phosphor = 0xFF71FF97.toInt()
    const val phosphorDim = 0xFF489C5D.toInt()
    const val danger = 0xFFFF7070.toInt()
    val glassesBg = Color.BLACK

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun root(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
        }

    fun header(context: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                monoText(context, title.uppercase(), 22f, phosphor, bold = true).apply {
                    letterSpacing = 0.15f
                },
            )
            addView(
                monoText(context, subtitle, 12f, muted).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                },
            )
        }

    fun sectionLabel(context: Context, label: String): TextView =
        monoText(context, label.uppercase(), 11f, muted).apply {
            letterSpacing = 0.2f
        }

    fun panel(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, panel, stroke, 12)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        }

    fun ghostButton(context: Context, label: String, dangerVariant: Boolean = false): Button =
        Button(context).apply {
            text = label.uppercase()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setTextColor(if (dangerVariant) danger else phosphor)
            setAllCaps(false)
            minHeight = dp(context, 44)
            minimumHeight = dp(context, 44)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            background = ghostBackground(context, if (dangerVariant) danger else stroke)
        }

    fun logWell(context: Context, logView: TextView): ScrollView =
        ScrollView(context).apply {
            background = rounded(context, field, stroke, 10)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            isFillViewport = false
            addView(
                logView.apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextColor(muted)
                    includeFontPadding = true
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    fun statusRow(context: Context, label: String): StatusRow {
        val dot = View(context).apply {
            background = oval(context, stroke)
        }
        val labelView = monoText(context, label.uppercase(), 13f, text)
        val valueView = monoText(context, "DOWN", 13f, dim).apply {
            gravity = Gravity.END
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            addView(
                dot,
                LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)).apply {
                    marginEnd = dp(context, 10)
                },
            )
            addView(
                labelView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                valueView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        return StatusRow(context, row, dot, valueView)
    }

    fun monoText(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) {
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            } else {
                Typeface.MONOSPACE
            }
        }

    fun gap(context: Context, value: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, value),
            )
        }

    private fun ghostBackground(context: Context, line: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(context, 0xFF0C2C16.toInt(), line, 10),
            )
            addState(intArrayOf(), rounded(context, 0x00000000, line, 10))
        }

    private fun rounded(context: Context, fill: Int, line: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(context, 1), line)
            cornerRadius = dp(context, radius).toFloat()
        }

    private fun oval(context: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }

    class StatusRow(
        private val context: Context,
        val view: LinearLayout,
        private val dot: View,
        private val valueView: TextView,
    ) {
        fun set(up: Boolean, value: String) {
            dot.background = oval(context, if (up) phosphor else stroke)
            valueView.text = value.uppercase()
            valueView.setTextColor(if (up) phosphor else dim)
        }
    }
}
