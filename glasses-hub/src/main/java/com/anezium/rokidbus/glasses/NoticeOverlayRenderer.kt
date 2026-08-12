package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.NoticeSurfaceContract
/**
 * The ROM sleeps the display five seconds after the last input (vendor-set
 * `screen_off_timeout`), which is shorter than a notice's own life -- a dictated
 * reply once died mid-flow under a dark screen. The window exists exactly as
 * long as the notice does, so holding the screen here cannot outlive what
 * warrants it. On this firmware the flag alone does not actually stop the
 * panel; the assistant's episode wake lock is what does. Both are kept: the
 * flag costs nothing and other firmware honours it.
 */
internal fun noticeWindowFlags(textInputActive: Boolean = false): Int =
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        (if (textInputActive) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

internal fun noticeBackdropAlpha(fadeAlpha: Float, backdrop: Boolean): Float =
    if (backdrop) fadeAlpha else 0f

internal enum class NoticeRenderMotion {
    ENTER,
    REENTER,
    UPDATE,
}

internal fun noticeRenderMotion(fadeAlpha: Float, exitRunning: Boolean): NoticeRenderMotion = when {
    exitRunning -> NoticeRenderMotion.REENTER
    fadeAlpha == 0f -> NoticeRenderMotion.ENTER
    else -> NoticeRenderMotion.UPDATE
}

internal fun noticeBandHeightCeiling(
    displayHeightPx: Int,
    heightFraction: Float,
    topInsetPx: Int,
): Int = ((displayHeightPx * heightFraction).toInt() - topInsetPx).coerceAtLeast(0)

internal enum class NoticeDismissMotion {
    SLIDE_AND_FADE,
    INK_FADE_IN_PLACE,
}

internal fun noticeDismissMotion(inkMorphActive: Boolean): NoticeDismissMotion =
    if (inkMorphActive) NoticeDismissMotion.INK_FADE_IN_PLACE else NoticeDismissMotion.SLIDE_AND_FADE

internal data class NoticeInkMorphToken(
    val surfaceId: String,
    val seq: Long,
    val ownerPluginId: String,
    val bandHeightPx: Int,
    val initialAlpha: Float,
) {
    fun matches(notice: NexusNoticeSurface): Boolean =
        surfaceId == notice.surfaceId && seq == notice.seq && ownerPluginId == notice.ownerPluginId
}

/**
 * The notice band: a transient panel across the top that arrives, says its
 * piece, and leaves.
 *
 * The window is full-screen and the band is a child inside it. That is not
 * decoration: `updateViewLayout` is an IPC round-trip to `system_server`, so
 * driving it per frame races against the view's own frame production, and a
 * window can only translate and resize a rectangle where a view can also fade,
 * clip and morph. The window stays put and only child bounds move. See plan 013.
 *
 * Like the pin and like Relay's own overlay, the window is normally neither
 * focusable nor touchable. A notice text field temporarily makes it focusable
 * so the trusted Nexus IME can bind to that field; it remains not touchable.
 * Ordinary notices never keep the screen on. The assistant marks only its
 * listening, thinking, and answer-review episode as engaged. The window keeps
 * its existing flag, but the fixed [AssistantDisplayEpisode] owner holds the
 * measured firmware wake lease independently of this drawable and its morph.
 * Wake requests remain separately owned by [NoticeController].
 */
object NoticeOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var scrim: View? = null
    private var band: NoticeBandView? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var bandHeightPx = 0
    private var backdrop = false
    private var textInputActive = false
    private var hudTopInsetDp = 0
    private var exitRunning = false
    private var renderedSeq: Long? = null
    private var inkMorph: NoticeInkMorphToken? = null

    private val slide = HudMotionValue(0f) { offset -> band?.translationY = offset }
    private val fade = HudMotionValue(0f) { alpha ->
        band?.alpha = alpha
        scrim?.alpha = noticeBackdropAlpha(alpha, backdrop)
    }

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = NoticeController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        teardown()
        this.service = null
        windowManager = null
    }

    /** Re-add above the pin when the surface window is recreated; notice goes last. */
    fun ensureOnTop() {
        val manager = windowManager ?: return
        val root = container ?: return
        runCatching {
            manager.removeView(root)
            manager.addView(root, params(textInputActive))
        }.onFailure { logError("Notice overlay z-order refresh failed", it) }
    }

    fun isShown(): Boolean = container != null

    internal fun beginInkMorph(notice: NexusNoticeSurface): NoticeInkMorphToken? {
        val view = band ?: return null
        if (
            container == null || exitRunning || renderedSeq != notice.seq ||
            notice.ownerPluginId.isBlank() || view.width <= 0 || view.height <= 0 ||
            fade.current <= 0f
        ) {
            return null
        }
        val token = NoticeInkMorphToken(
            surfaceId = notice.surfaceId,
            seq = notice.seq,
            ownerPluginId = notice.ownerPluginId,
            bandHeightPx = view.height,
            initialAlpha = fade.current,
        )
        inkMorph = token
        bandHeightPx = view.height
        exitRunning = false
        slide.snapTo(0f)
        fade.cancel()
        return token
    }

    internal fun startInkMorphFade(token: NoticeInkMorphToken): Boolean {
        if (inkMorph != token || container == null || band == null) return false
        return runCatching {
            slide.snapTo(0f)
            fade.animateTo(0f, HudMotion.MICRO_MS, HudMotion.enter)
            true
        }.onFailure {
            fade.snapTo(0f)
            logError("Notice Ink morph fade could not start", it)
        }.getOrDefault(false)
    }

    internal fun finishInkMorph(token: NoticeInkMorphToken): Boolean {
        if (inkMorph != token) return false
        fade.snapTo(0f)
        inkMorph = null
        teardown()
        return true
    }

    internal fun cancelInkMorph(token: NoticeInkMorphToken): Boolean {
        if (inkMorph != token) return false
        inkMorph = null
        fade.snapTo(token.initialAlpha)
        return true
    }

    private fun render(notice: NexusNoticeSurface?) {
        if (notice == null) {
            dismiss()
            return
        }
        val interruptedInkMorph = inkMorph?.matches(notice) == false
        if (interruptedInkMorph) {
            inkMorph = null
            fade.cancel()
            slide.snapTo(0f)
        }
        backdrop = notice.content.backdrop
        val nextTextInputActive = notice.liveTextInput != null
        scrim?.alpha = noticeBackdropAlpha(fade.current, backdrop)
        val activeService = service ?: return
        val view = ensureWindow(activeService, nextTextInputActive) ?: return
        updateWindowInputMode(nextTextInputActive)
        val motion = if (interruptedInkMorph) {
            NoticeRenderMotion.REENTER
        } else {
            noticeRenderMotion(fade.current, exitRunning)
        }
        val fadeWasRunning = fade.isRunning
        renderedSeq = notice.seq
        view.render(notice)
        if (nextTextInputActive) view.activateTextInput()
        log(
            "renderer seq=${notice.seq} event=render attached=${container != null} " +
                "fadeRunning=$fadeWasRunning",
        )
        when (motion) {
            NoticeRenderMotion.ENTER -> {
                // Measure once the content is in place: the band's height is what the
                // arrival slides through, and it depends on how much body there is.
                view.post {
                    if (band !== view || renderedSeq != notice.seq || exitRunning) return@post
                    bandHeightPx = view.height.takeIf { it > 0 } ?: bandHeightPx
                    slide.snapTo(-bandHeightPx.toFloat())
                    slide.animateTo(0f, HudMotion.STANDARD_MS, HudMotion.enter)
                    fade.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)
                }
            }
            NoticeRenderMotion.REENTER -> {
                // Retargeting immediately cancels the exit's teardown continuation.
                // Waiting for layout here leaves one main-loop turn in which the old
                // fade can still reach zero and remove the live notice's window.
                exitRunning = false
                slide.animateTo(0f, HudMotion.STANDARD_MS, HudMotion.enter)
                fade.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)
            }
            NoticeRenderMotion.UPDATE -> Unit
        }
    }

    private fun dismiss() {
        if (container == null) return
        if (noticeDismissMotion(inkMorph != null) == NoticeDismissMotion.INK_FADE_IN_PLACE) return
        exitRunning = true
        slide.animateTo(-bandHeightPx.toFloat(), HudMotion.EXIT_MS, HudMotion.exit)
        fade.animateTo(0f, HudMotion.EXIT_MS, HudMotion.exit) { teardown() }
    }

    private fun ensureWindow(
        service: AccessibilityService,
        textInputActive: Boolean,
    ): NoticeBandView? {
        band?.let { return it }
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return null
        val root = FrameLayout(service)
        // An opted-in notice can own the whole display while it is up. The scrim
        // is opaque black: the additive optics emit nothing for it, but it
        // occludes every window underneath. It rides the band's fade and, being
        // part of a NOT_TOUCHABLE window, blocks nothing but light.
        val shade = View(service).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
        }
        root.addView(
            shade,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val view = NoticeBandView(
            service,
            NoticeController::setPageCount,
            NoticeController::submitText,
        ).apply {
            setHudTopInsetDp(hudTopInsetDp)
        }
        val metrics = service.resources.displayMetrics
        root.addView(
            view,
            FrameLayout.LayoutParams(
                HudBandGeometry.widthPx(metrics.widthPixels),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = HudBandGeometry.topPx(service, hudTopInsetDp)
            },
        )
        if (runCatching { manager.addView(root, params(textInputActive)) }.isFailure) {
            logError("Notice overlay window could not be added")
            return null
        }
        this.textInputActive = textInputActive
        container = root
        scrim = shade
        band = view
        view.alpha = 0f
        fade.snapTo(0f)
        return view
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        band?.let { currentBand ->
            currentBand.setHudTopInsetDp(hudTopInsetDp)
            val layout = currentBand.layoutParams as? FrameLayout.LayoutParams ?: return@let
            layout.topMargin = HudBandGeometry.topPx(currentBand.context, hudTopInsetDp)
            currentBand.layoutParams = layout
        }
        container?.requestLayout()
    }

    private fun updateWindowInputMode(active: Boolean) {
        if (textInputActive == active || container == null) return
        runCatching {
            val manager = checkNotNull(windowManager)
            val root = checkNotNull(container)
            manager.updateViewLayout(root, params(active))
        }.onSuccess {
            textInputActive = active
        }.onFailure { logError("Notice overlay input mode could not be updated", it) }
    }

    private fun params(textInputActive: Boolean): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            noticeWindowFlags(textInputActive),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    private fun teardown() {
        val root = container
        if (root == null) {
            return
        }
        val seq = renderedSeq ?: -1L
        band?.releaseTextInput()
        runCatching { windowManager?.removeView(root) }
            .onFailure { logError("Notice overlay removal failed", it) }
        container = null
        scrim = null
        band = null
        inkMorph = null
        backdrop = false
        textInputActive = false
        exitRunning = false
        slide.snapTo(0f)
        fade.snapTo(0f)
        renderedSeq = null
        log(
            "renderer seq=$seq event=teardown attached=${container != null} " +
                "fadeRunning=${fade.isRunning}",
        )
    }

    /** Shared top-band geometry used unchanged by notices and activity flares. */
    internal class NoticeBandView(
        context: Context,
        private val pageCountChanged: ((String, Long, Int) -> Unit)? = null,
        private val textSubmitted: ((String, String, String) -> Unit)? = null,
    ) : LinearLayout(context) {
        private val title = row(bold = true, sizeSp = TITLE_SP, color = BusTheme.phosphor)
        private val image = NoticeImageView(context)
        private val body = NoticeBodyView(context) { count ->
            if (measuredPageCount != count && noticeIdentity != null) {
                pageCountReportPending = true
            }
            measuredPageCount = count
            updateFooter()
        }
        private val footer = row(bold = false, sizeSp = FOOTER_SP, color = BusTheme.muted)
        private val pageIndicator = row(
            bold = false,
            sizeSp = FOOTER_SP,
            color = BusTheme.muted,
        ).apply {
            gravity = Gravity.END
        }
        private val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                footer,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                pageIndicator,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        private val actions = HudActionRowView(context)
        private val textInput = EditText(context).apply {
            setSingleLine(true)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            privateImeOptions = NoticeTextInputImeTrust.privateImeOptions
            filters = arrayOf(InputFilter.LengthFilter(NoticeSurfaceContract.MAX_TEXT_INPUT_CHARS))
            setTextColor(BusTheme.phosphor)
            setHintTextColor(BusTheme.muted)
            textSize = BODY_SP
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            includeFontPadding = false
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setPadding(
                BusTheme.dp(context, 8),
                BusTheme.dp(context, 6),
                BusTheme.dp(context, 8),
                BusTheme.dp(context, 6),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF000000.toInt())
                setStroke(BusTheme.dp(context, 1), BusTheme.hairline)
                cornerRadius = BusTheme.dp(context, 5).toFloat()
            }
            visibility = View.GONE
        }
        private var noticeIdentity: Pair<String, Long>? = null
        private var textInputIdentity: Pair<String, String>? = null
        private var pluginFooter: String? = null
        private var renderedPageIndex = 0
        private var measuredPageCount = 1
        private var pageableNotice = false
        private var noticeHasImage = false
        private var noticeActionCount = 0
        private var pageCountReportPending = false
        private var hudTopInsetPx = 0

        init {
            orientation = VERTICAL
            val horizontal = BusTheme.dp(context, 10)
            val vertical = BusTheme.dp(context, 8)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Pure black. The additive optics emit nothing for black, so the
                // fill reads as transparent and only the border and text light up.
                // A "nicer" translucent grey is a visible grey rectangle on-glasses.
                setColor(0xFF000000.toInt())
                setStroke(BusTheme.dp(context, 1), BusTheme.hairline)
                cornerRadius = BusTheme.dp(context, 7).toFloat()
            }
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                image,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            addView(
                body,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            addView(
                footerRow,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 5)
                },
            )
            // Under the footer, so the reading order is what the band says, then
            // how to answer it, then the answers themselves.
            addView(
                actions,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 6)
                },
            )
            addView(
                textInput,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 6)
                },
            )
            textInput.setOnEditorActionListener { _, actionId, event ->
                val enterDown = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId != EditorInfo.IME_ACTION_DONE && !enterDown) {
                    return@setOnEditorActionListener false
                }
                val identity = textInputIdentity ?: return@setOnEditorActionListener true
                val submitted = textInput.text?.toString().orEmpty()
                textSubmitted?.invoke(identity.first, identity.second, submitted)
                true
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val heightFraction = if (pageableNotice) {
                GROWN_HEIGHT_FRACTION
            } else {
                MAX_HEIGHT_FRACTION
            }
            val ceiling = noticeBandHeightCeiling(
                displayHeightPx = resources.displayMetrics.heightPixels,
                heightFraction = heightFraction,
                topInsetPx = hudTopInsetPx,
            )
            val cappedHeightSpec = MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST)

            if (!pageableNotice) {
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
                publishPageCount()
                return
            }

            var remainingPasses = CAPACITY_MEASURE_PASSES
            do {
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
                val lineCount = body.measuredLineCount
                val lineHeight = body.measuredLineHeightPx
                if (lineCount <= 0 || lineHeight <= 0) break

                // The image has a stable five-line cost below. Remove its current
                // measured contribution here so page one and later pages derive
                // the same full-page capacity when the image appears/disappears.
                val nonImageChromeHeight = measuredHeight - body.measuredHeight -
                    visibleImageHeightWithMargin()
                val availableBodyHeight = (ceiling - nonImageChromeHeight).coerceAtLeast(0)
                val grownCapacity = noticeBodyLineCapacity(availableBodyHeight, lineHeight)
                val capacities = noticePageCapacities(
                    lineCount = lineCount,
                    grownCapacity = grownCapacity,
                    hasImage = noticeHasImage,
                    actionCount = noticeActionCount,
                )
                if (!body.setPageCapacities(capacities)) break
                remainingPasses -= 1
            } while (remainingPasses > 0)

            if (remainingPasses == 0) {
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
            }
            publishPageCount()
        }

        fun setHudTopInsetDp(value: Int) {
            val next = BusTheme.dp(context, HudTopInset.sanitize(value))
            if (hudTopInsetPx == next) return
            hudTopInsetPx = next
            requestLayout()
        }

        /**
         * The band draws the notice's *live* actions, so an answered one loses
         * its row and becomes an inert display without the content it was shown
         * with being rewritten.
         */
        fun render(notice: NexusNoticeSurface) {
            noticeIdentity = notice.surfaceId to notice.seq
            pluginFooter = notice.content.footer
            renderedPageIndex = notice.pageIndex
            measuredPageCount = notice.pageCount
            pageCountReportPending = true
            renderTitle(notice.content.title, null)
            val hasImage = notice.imageBitmap?.takeUnless { it.isRecycled } != null
            val paging = notice.content.actions.size <= 1
            pageableNotice = paging
            noticeHasImage = hasImage
            noticeActionCount = notice.content.actions.size
            val drawsImage = hasImage && notice.pageIndex == 0
            image.render(notice.imageBitmap?.takeIf { drawsImage })
            val capacities = noticePageCapacities(
                lineCount = 0,
                grownCapacity = MIN_BODY_LINES,
                hasImage = hasImage,
                actionCount = notice.content.actions.size,
            )
            body.render(
                text = noticeBodyText(notice.content),
                pageIndex = notice.pageIndex,
                capacities = capacities,
                // The same test as NoticeState.isPaged: a row of two or more
                // needs the directions to choose along; anything less leaves
                // them free to turn pages.
                paging = paging,
            )
            updateFooter()
            actions.render(
                notice.liveActions.map { HudActionChip(it.glyph, it.label) },
                notice.selectedActionIndex,
            )
            renderTextInput(notice)
        }

        /**
         * The text-only form the activity flare borrows. It carries no actions:
         * a flare is a moment of emphasis on something the wearer is already
         * following, not a question, and its own row lives on the panel.
         */
        fun render(
            titleText: String?,
            bodyText: String?,
            footerText: String?,
            leadingGlyph: Drawable?,
            actionChips: List<HudActionChip> = emptyList(),
            selectedActionIndex: Int = 0,
        ) {
            noticeIdentity = null
            pluginFooter = footerText
            renderedPageIndex = 0
            measuredPageCount = 1
            pageableNotice = false
            noticeHasImage = false
            noticeActionCount = actionChips.size
            pageCountReportPending = false
            renderTitle(titleText, leadingGlyph)
            image.render(null)
            body.render(
                text = bodyText,
                pageIndex = 0,
                capacities = NoticePageCapacities(MIN_BODY_LINES, MIN_BODY_LINES),
                paging = false,
            )
            updateFooter()
            actions.render(actionChips, selectedActionIndex)
            clearTextInput()
        }

        fun activateTextInput() {
            if (textInput.visibility != View.VISIBLE) return
            textInput.requestFocus()
            textInput.post {
                if (textInput.visibility != View.VISIBLE || !textInput.hasFocus()) return@post
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        fun releaseTextInput() {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(textInput.windowToken, 0)
            clearTextInput()
        }

        private fun renderTextInput(notice: NexusNoticeSurface) {
            val input = notice.liveTextInput ?: run {
                clearTextInput()
                return
            }
            val nextIdentity = notice.surfaceId to input.id
            if (textInputIdentity != nextIdentity) {
                textInput.setText("")
                textInputIdentity = nextIdentity
            }
            textInput.hint = input.hint
            textInput.contentDescription = input.hint
            textInput.visibility = View.VISIBLE
        }

        private fun clearTextInput() {
            textInput.clearFocus()
            textInput.setText("")
            textInput.visibility = View.GONE
            textInputIdentity = null
        }

        private fun renderTitle(titleText: String?, leadingGlyph: Drawable?) {
            title.text = titleText.orEmpty()
            title.visibility = visibleIf(!titleText.isNullOrEmpty())
            leadingGlyph?.setBounds(
                0,
                0,
                BusTheme.dp(context, GLYPH_SIZE_DP),
                BusTheme.dp(context, GLYPH_SIZE_DP),
            )
            title.compoundDrawablePadding = if (leadingGlyph == null) 0 else BusTheme.dp(context, 7)
            title.setCompoundDrawables(leadingGlyph, null, null, null)
        }

        private fun updateFooter() {
            footer.text = pluginFooter.orEmpty()
            footer.visibility = visibleIf(!pluginFooter.isNullOrEmpty())
            pageIndicator.text = if (measuredPageCount > 1) {
                "${renderedPageIndex.coerceIn(0, measuredPageCount - 1) + 1}/$measuredPageCount"
            } else {
                ""
            }
            pageIndicator.visibility = visibleIf(measuredPageCount > 1)
            footerRow.visibility = visibleIf(
                !pluginFooter.isNullOrEmpty() || measuredPageCount > 1,
            )
        }

        private fun visibleImageHeightWithMargin(): Int {
            if (image.visibility != View.VISIBLE) return 0
            val margins = image.layoutParams as LayoutParams
            return image.measuredHeight + margins.topMargin + margins.bottomMargin
        }

        private fun publishPageCount() {
            if (!pageCountReportPending) return
            pageCountReportPending = false
            noticeIdentity?.let { (surfaceId, seq) ->
                pageCountChanged?.invoke(surfaceId, seq, measuredPageCount)
            }
        }

        private fun row(bold: Boolean, sizeSp: Float, color: Int) =
            TextView(context).apply {
                setTextColor(color)
                textSize = sizeSp
                typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    private class NoticeImageView(context: Context) : ImageView(context) {
        init {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = MAX_IMAGE_HEIGHT_PX
            setBackgroundColor(0xFF000000.toInt())
        }

        fun render(bitmap: android.graphics.Bitmap?) {
            setImageBitmap(bitmap)
            visibility = if (bitmap == null) View.GONE else View.VISIBLE
        }
    }

    private class NoticeBodyView(
        context: Context,
        private val pageCountChanged: (Int) -> Unit,
    ) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BusTheme.muted
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                BODY_SP,
                resources.displayMetrics,
            )
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        private var text: String? = null
        private var pageIndex = 0
        private var capacities = NoticePageCapacities(MIN_BODY_LINES, MIN_BODY_LINES)
        private var paging = false
        private var layout: StaticLayout? = null
        private var window = NoticePageWindow(0, 0)
        private var reportedPageCount = 1

        val measuredLineCount: Int
            get() = if (visibility == View.VISIBLE) layout?.lineCount ?: 0 else 0

        val measuredLineHeightPx: Int
            get() {
                val measured = layout
                    ?.takeIf { visibility == View.VISIBLE && it.lineCount > 0 }
                    ?: return 0
                return measured.getLineTop(1) - measured.getLineTop(0)
            }

        fun render(
            text: String?,
            pageIndex: Int,
            capacities: NoticePageCapacities,
            paging: Boolean,
        ) {
            this.text = text
            this.pageIndex = pageIndex
            this.capacities = capacities
            this.paging = paging
            reportedPageCount = -1
            contentDescription = text.orEmpty()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val content = text
            if (content.isNullOrEmpty()) {
                layout = null
                window = NoticePageWindow(0, 0)
                publishPageCount(1)
                setMeasuredDimension(width, 0)
                return
            }

            val builder = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (!paging) {
                builder
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(width)
                    .setMaxLines(capacities.firstPageLines)
            }
            val measured = builder.build()
            layout = measured
            val count = if (paging) {
                noticePageCount(
                    measured.lineCount,
                    capacities.firstPageLines,
                    capacities.followingPageLines,
                )
            } else {
                1
            }
            publishPageCount(count)
            window = if (paging) {
                noticePageWindow(
                    pageIndex = pageIndex,
                    lineCount = measured.lineCount,
                    firstPageLines = capacities.firstPageLines,
                    followingPageLines = capacities.followingPageLines,
                )
            } else {
                NoticePageWindow(0, measured.lineCount)
            }
            val desiredHeight = measured.getLineTop(window.lastLineExclusive) -
                measured.getLineTop(window.firstLine)
            setMeasuredDimension(
                width,
                resolveSize(desiredHeight, heightMeasureSpec),
            )
        }

        fun setPageCapacities(next: NoticePageCapacities): Boolean {
            if (capacities == next) return false
            capacities = next
            reportedPageCount = -1
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val measured = layout ?: return
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(0f, -measured.getLineTop(window.firstLine).toFloat())
            measured.draw(canvas)
            canvas.restore()
        }

        private fun publishPageCount(count: Int) {
            if (reportedPageCount == count) return
            reportedPageCount = count
            pageCountChanged(count)
        }
    }

    // Eight lines preserve the compact band exactly. Pageable long notices can
    // grow to fourteen measured lines under the taller ceiling; an image still
    // spends five lines on page one and later pages recover the full capacity.
    private const val MAX_HEIGHT_FRACTION = 0.65f
    private const val GROWN_HEIGHT_FRACTION = 0.92f
    private const val CAPACITY_MEASURE_PASSES = 3
    private const val MAX_IMAGE_HEIGHT_PX = 150
    private const val TITLE_SP = 15f
    private const val BODY_SP = 12f
    private const val FOOTER_SP = 11f
    private const val GLYPH_SIZE_DP = 36
}
