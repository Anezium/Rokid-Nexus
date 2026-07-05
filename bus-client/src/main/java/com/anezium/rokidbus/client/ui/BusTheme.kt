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

/**
 * RokidBus design system — quiet premium terminal.
 * Near-black green canvas, one phosphor accent, soft unstroked tiles,
 * large light hero type. Shared by hubs, probes and every client app.
 */
object BusTheme {
    const val bg = 0xFF030C06.toInt()
    const val card = 0xFF09150D.toInt()
    const val cardPressed = 0xFF102316.toInt()
    const val well = 0xFF050D08.toInt()
    const val hairline = 0xFF13221A.toInt()
    const val text = 0xFFECF4EC.toInt()
    const val muted = 0xFF7E9585.toInt()
    const val dim = 0xFF47584D.toInt()
    const val phosphor = 0xFF71FF97.toInt()
    const val danger = 0xFFFF7070.toInt()
    val glassesBg = Color.BLACK

    private val sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun root(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 16))
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                view.setPadding(
                    dp(context, 22),
                    dp(context, 18) + topInset,
                    dp(context, 22),
                    dp(context, 16),
                )
                insets
            }
        }

    /** Small phosphor brand tag, the only loud typographic voice. */
    fun wordmark(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label.uppercase()
            textSize = 12f
            typeface = monoBold
            letterSpacing = 0.35f
            setTextColor(phosphor)
        }

    /** Large light headline — the state of the system is the title of the screen. */
    fun hero(context: Context): TextView =
        TextView(context).apply {
            textSize = 40f
            typeface = sansLight
            letterSpacing = -0.01f
            setTextColor(BusTheme.text)
        }

    fun heroSub(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            letterSpacing = 0.02f
            setTextColor(muted)
        }

    fun tinyLabel(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label.uppercase()
            textSize = 10f
            typeface = sansMedium
            letterSpacing = 0.25f
            setTextColor(dim)
        }

    /** Soft unstroked status tile: caption on top, live value under it. */
    class Tile(context: Context, label: String) {
        private val value = TextView(context).apply {
            textSize = 15f
            typeface = sansMedium
            letterSpacing = 0.05f
            setTextColor(dim)
            text = "—"
        }
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, card, 18)
            setPadding(dp(context, 14), dp(context, 13), dp(context, 14), dp(context, 13))
            addView(tinyLabel(context, label))
            addView(gap(context, 7))
            addView(value)
        }

        fun set(up: Boolean, valueText: String) {
            value.text = valueText.uppercase()
            value.setTextColor(if (up) phosphor else dim)
        }
    }

    fun tileRow(context: Context, tiles: List<Tile>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.forEachIndexed { index, tile ->
                addView(
                    tile.view,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(context, 10)
                    },
                )
            }
        }

    /** Full-radius quiet action pill. */
    fun pill(context: Context, label: String, dangerVariant: Boolean = false): Button =
        Button(context).apply {
            text = label.uppercase()
            textSize = 13f
            typeface = sansMedium
            letterSpacing = 0.12f
            setTextColor(if (dangerVariant) danger else phosphor)
            setAllCaps(false)
            stateListAnimator = null
            minHeight = dp(context, 52)
            minimumHeight = dp(context, 52)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    rounded(context, cardPressed, 26),
                )
                addState(intArrayOf(), rounded(context, card, 26))
            }
        }

    /** Recessed console well for the activity feed. */
    fun console(context: Context, logView: TextView): ScrollView =
        ScrollView(context).apply {
            background = rounded(context, well, 16)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            isVerticalScrollBarEnabled = false
            addView(
                logView.apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 10f
                    setTextColor(dim)
                    setLineSpacing(dp(context, 3).toFloat(), 1f)
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    fun gap(context: Context, value: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, value),
            )
        }

    /** Minimal phosphor-on-black text screen for the glasses HUD. */
    fun glassesScreen(context: Context, content: String): TextView =
        TextView(context).apply {
            text = content
            typeface = Typeface.MONOSPACE
            setTextColor(phosphor)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(glassesBg)
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
        }

    private fun rounded(context: Context, fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
        }
}
