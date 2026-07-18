package com.anezium.rokidbus.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
/**
 * Phone-app widget kit and palette.
 *
 * Phosphor times Mono: dark layers, one electric green accent, system sans for
 * names/body, and monospace for caps/meta UI.
 */
object NexusUi {
    const val BG = 0xFF070A08.toInt()
    const val PANEL = 0xFF0D150F.toInt()
    const val CARD = 0xFF0E150F.toInt()
    const val LINE = 0xFF182619.toInt()
    const val LINE2 = 0xFF16241A.toInt()
    const val INK = 0xFFDCF3E4.toInt()
    const val INK2 = 0xFF8BA896.toInt()
    const val INK3 = 0xFF5F7A68.toInt()
    const val INK4 = 0xFF42574A.toInt()
    const val GREEN = 0xFF4DFF8C.toInt()
    const val GREEN_DIM = 0xFF2F9D5C.toInt()
    const val AMBER = 0xFFFFB84D.toInt()
    const val ON_ACCENT = 0xFF04180D.toInt()
    const val DANGER = 0xFFFF8A8A.toInt()

    // Backward-compatible aliases used by existing phone-hub screens.
    const val TEXT = INK
    const val MUTED = INK2
    const val DIM = INK3
    const val ACCENT = GREEN
    const val ACCENT_PRESSED = GREEN_DIM
    const val WELL = PANEL
    const val HAIRLINE = LINE
    const val CARD_PRESSED = LINE2
    const val CONSOLE_INK = INK2

    private val sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val mono: Typeface = Typeface.MONOSPACE

    fun dp(context: Context, value: Int): Int = BusTheme.dp(context, value)

    fun alpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Scrolling screen shell around a padded content [column]. */
    fun screen(context: Context, content: LinearLayout): ScrollView =
        ScrollView(context).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /** Root for screens with fixed header/footer outside the scroll view. */
    fun fixedRoot(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setOnApplyWindowInsetsListener { view, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                view.setPadding(0, top, 0, bottom)
                insets
            }
        }

    /** Padded vertical column that tracks status/navigation bar insets. */
    fun column(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            val horizontal = dp(context, 20)
            setPadding(horizontal, dp(context, 16), horizontal, dp(context, 24))
            setOnApplyWindowInsetsListener { view, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                view.setPadding(
                    horizontal,
                    dp(context, 16) + top,
                    horizontal,
                    dp(context, 24) + bottom,
                )
                insets
            }
        }

    /** Padded content column for scroll areas that already live inside fixedRoot. */
    fun contentColumn(context: Context, horizontalDp: Int = 22, topDp: Int = 18, bottomDp: Int = 24): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(
                dp(context, horizontalDp),
                dp(context, topDp),
                dp(context, horizontalDp),
                dp(context, bottomDp),
            )
        }

    fun block(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    fun wordmark(context: Context, label: String): TextView =
        TextView(context).apply {
            val upper = label.uppercase()
            text = SpannableString(upper).apply {
                setSpan(ForegroundColorSpan(INK), 0, upper.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val nexusStart = upper.indexOf("NEXUS")
                if (nexusStart >= 0) {
                    setSpan(
                        ForegroundColorSpan(GREEN),
                        nexusStart,
                        nexusStart + "NEXUS".length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        nexusStart,
                        nexusStart + "NEXUS".length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            textSize = 12f
            typeface = mono
            letterSpacing = 0.26f
            includeFontPadding = false
        }

    fun sectionLabel(context: Context, label: String): TextView =
        monoText(context, label.uppercase(), 10f, INK4, 0.22f)

    fun metaLabel(context: Context, label: String, color: Int = INK3): TextView =
        monoText(context, label.uppercase(), 10f, color, 0.12f)

    fun sectionRow(context: Context, label: String, value: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), 0)
            addView(
                sectionLabel(context, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (value != null) {
                addView(metaLabel(context, value, GREEN_DIM))
            }
        }

    fun hero(context: Context, sizeSp: Float = 38f): TextView =
        TextView(context).apply {
            textSize = sizeSp
            typeface = sansLight
            letterSpacing = 0f
            setTextColor(INK)
        }

    fun statusLine(context: Context): TextView =
        TextView(context).apply {
            textSize = 14f
            typeface = sans
            letterSpacing = 0f
            setTextColor(INK2)
        }

    fun dot(context: Context): View =
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(INK3)
            }
        }

    fun setDotColor(dotView: View, color: Int) {
        (dotView.background as? GradientDrawable)?.setColor(color)
    }

    fun card(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bordered(context, PANEL, LINE2, 15)
            setPadding(dp(context, 15), dp(context, 14), dp(context, 15), dp(context, 14))
        }

    fun pressableCard(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressedBordered(context, PANEL, 15)
            setPadding(dp(context, 15), dp(context, 14), dp(context, 15), dp(context, 14))
            isClickable = true
            isFocusable = true
        }

    fun cardTitle(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 16f
            typeface = sansMedium
            letterSpacing = 0f
            setTextColor(INK)
        }

    fun cardBody(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            typeface = sans
            letterSpacing = 0f
            setTextColor(INK2)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
        }

    fun rowLabel(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = sans
            setTextColor(INK2)
        }

    fun rowValue(context: Context): TextView =
        metaLabel(context, "", INK3).apply {
            textSize = 12f
            gravity = Gravity.END
        }

    fun rowTitle(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = sans
            setTextColor(INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    fun rowSub(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            typeface = mono
            letterSpacing = 0.04f
            setTextColor(INK3)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun textButton(context: Context, label: String, danger: Boolean = false): Button =
        Button(context).apply {
            text = label
            textSize = 13f
            typeface = sansMedium
            letterSpacing = 0f
            setTextColor(if (danger) DANGER else GREEN)
            setAllCaps(false)
            stateListAnimator = null
            minHeight = dp(context, 42)
            minimumHeight = dp(context, 42)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            background = pressed(context, Color.TRANSPARENT, 21)
        }

    fun pillButton(context: Context, label: String, danger: Boolean = false): Button =
        Button(context).apply {
            text = label
            textSize = 12f
            typeface = mono
            letterSpacing = 0.12f
            setAllCaps(false)
            stateListAnimator = null
            minHeight = dp(context, 46)
            minimumHeight = dp(context, 46)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            if (danger) stylePillAsDanger(context, this) else stylePillAsPrimary(context, this)
        }

    fun outlinePillButton(context: Context, label: String): Button =
        Button(context).apply {
            text = label.uppercase()
            textSize = 12f
            typeface = mono
            letterSpacing = 0.1f
            setTextColor(GREEN)
            setAllCaps(false)
            stateListAnimator = null
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    bordered(context, alpha(GREEN, 0x18), alpha(GREEN, 0x60), 14),
                )
                addState(
                    intArrayOf(),
                    bordered(context, alpha(GREEN, 0x0A), alpha(GREEN, 0x38), 14),
                )
            }
        }

    fun stylePillAsPrimary(context: Context, button: Button) {
        button.setTextColor(ON_ACCENT)
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, GREEN_DIM, 24))
            addState(intArrayOf(), rounded(context, GREEN, 24))
        }
    }

    fun stylePillAsDanger(context: Context, button: Button) {
        button.setTextColor(DANGER)
        button.background = pressedBordered(context, PANEL, 24)
    }

    fun chevron(context: Context): TextView =
        TextView(context).apply {
            text = "\u203A"
            textSize = 20f
            typeface = sans
            includeFontPadding = false
            setTextColor(INK4)
        }

    /** Icon tile backed by a vector drawable — the standard plugin mark. */
    fun iconTileImage(context: Context, resId: Int, sizeDp: Int = 34): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            val pad = dp(context, if (sizeDp > 40) 12 else 8)
            setPadding(pad, pad, pad, pad)
            background = rounded(context, alpha(GREEN, 0x14), if (sizeDp > 34) 11 else 9)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Icon tile backed by an already-resolved drawable. */
    fun iconTileDrawable(context: Context, drawable: Drawable, sizeDp: Int = 34): ImageView =
        ImageView(context).apply {
            setImageDrawable(drawable)
            val pad = dp(context, if (sizeDp > 40) 12 else 8)
            setPadding(pad, pad, pad, pad)
            background = rounded(context, alpha(GREEN, 0x14), if (sizeDp > 34) 11 else 9)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    fun iconTile(context: Context, glyph: String, sizeDp: Int = 34): TextView =
        TextView(context).apply {
            text = glyph
            textSize = if (sizeDp > 34) 18f else 15f
            typeface = sansMedium
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(GREEN)
            background = rounded(context, alpha(GREEN, 0x14), if (sizeDp > 34) 11 else 9)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Fixed header for a plugin settings screen: icon tile, name, one-liner, hairline. */
    fun pluginHeader(context: Context, iconRes: Int, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 18))
                    addView(iconTileImage(context, iconRes, 48))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(cardTitle(context, title).apply { textSize = 20f })
                            addView(BusTheme.gap(context, 5))
                            addView(rowSub(context, subtitle))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(context, 14)
                        },
                    )
                },
                block(),
            )
            addView(
                View(context).apply {
                    setBackgroundColor(LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 1),
                    )
                },
            )
        }

    /** Canonical end-of-settings uninstall card: every plugin settings screen ends with one. */
    fun uninstallCard(context: Context, pluginName: String, onUninstall: () -> Unit): LinearLayout =
        pressableCard(context).apply {
            contentDescription = "Uninstall $pluginName"
            setOnClickListener { onUninstall() }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(rowTitle(context, "Uninstall $pluginName"))
                    addView(BusTheme.gap(context, 4))
                    addView(rowSub(context, "Remove the plugin from this phone"))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(rowSub(context, "REMOVE ›").apply { setTextColor(DANGER) })
        }

    fun field(context: Context, hintText: String): EditText =
        EditText(context).apply {
            hint = hintText
            setHintTextColor(INK4)
            setTextColor(INK)
            typeface = sans
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            includeFontPadding = false
            minHeight = dp(context, 52)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            background = bordered(context, PANEL, LINE2, 14)
        }

    fun divider(context: Context): View =
        View(context).apply {
            setBackgroundColor(LINE2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1),
            ).apply {
                topMargin = dp(context, 12)
                bottomMargin = dp(context, 12)
            }
        }

    fun console(context: Context, logView: TextView): ScrollView {
        logView.apply {
            textSize = 11f
            typeface = mono
            setTextColor(CONSOLE_INK)
            setLineSpacing(dp(context, 2).toFloat(), 1f)
        }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            background = bordered(context, PANEL, LINE2, 14)
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    fun updateBanner(
        context: Context,
        versionLabel: String,
        title: String = "Update available",
        actionLabel: String = "Install",
        actionEnabled: Boolean = true,
        onInstall: () -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bordered(context, alpha(AMBER, 0x10), 0xFF4A3A1C.toInt(), 13)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 13f
                        typeface = sans
                        setTextColor(AMBER)
                    },
                )
                addView(BusTheme.gap(context, 4))
                addView(
                    monoText(context, versionLabel, 10f, 0xFFB79A6A.toInt(), 0.06f),
                )
            }
            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                Button(context).apply {
                    text = actionLabel
                    textSize = 10f
                    typeface = mono
                    letterSpacing = 0.12f
                    setAllCaps(false)
                    setTextColor(0xFF241701.toInt())
                    stateListAnimator = null
                    minHeight = dp(context, 36)
                    minimumHeight = dp(context, 36)
                    minWidth = 0
                    minimumWidth = 0
                    includeFontPadding = false
                    setPadding(dp(context, 13), 0, dp(context, 13), 0)
                    background = rounded(context, AMBER, 18)
                    isEnabled = actionEnabled
                    alpha = if (actionEnabled) 1f else 0.65f
                    setOnClickListener { onInstall() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(context, 10) },
            )
        }

    /** Plugin/navigation card: title + one-line description, optional chevron. */
    fun navCard(
        context: Context,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle(context, title))
            addView(BusTheme.gap(context, 4))
            addView(cardBody(context, subtitle))
        }
        val row = if (onClick != null) pressableCard(context) else staticRowCard(context)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (onClick != null) {
            row.addView(
                chevron(context),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(context, 12) },
            )
            row.setOnClickListener { onClick() }
        }
        return row
    }

    private fun staticRowCard(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bordered(context, PANEL, LINE2, 15)
            setPadding(dp(context, 15), dp(context, 14), dp(context, 15), dp(context, 14))
        }

    fun rounded(context: Context, fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
        }

    fun bordered(
        context: Context,
        fill: Int,
        stroke: Int,
        radius: Int,
        strokeWidthDp: Int = 1,
        dashWidthDp: Int = 0,
        dashGapDp: Int = 0,
    ): GradientDrawable =
        rounded(context, fill, radius).apply {
            if (dashWidthDp > 0 && dashGapDp > 0) {
                setStroke(
                    dp(context, strokeWidthDp),
                    stroke,
                    dp(context, dashWidthDp).toFloat(),
                    dp(context, dashGapDp).toFloat(),
                )
            } else {
                setStroke(dp(context, strokeWidthDp), stroke)
            }
        }

    fun pressed(context: Context, restingFill: Int, radius: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(context, if (restingFill == Color.TRANSPARENT) LINE2 else CARD_PRESSED, radius),
            )
            addState(intArrayOf(), rounded(context, restingFill, radius))
        }

    fun pressedBordered(context: Context, restingFill: Int, radius: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                bordered(context, CARD_PRESSED, LINE, radius),
            )
            addState(intArrayOf(), bordered(context, restingFill, LINE2, radius))
        }

    private fun monoText(
        context: Context,
        label: String,
        sizeSp: Float,
        color: Int,
        tracking: Float,
    ): TextView =
        TextView(context).apply {
            text = label
            textSize = sizeSp
            typeface = mono
            letterSpacing = tracking
            setTextColor(color)
            includeFontPadding = false
        }
}
