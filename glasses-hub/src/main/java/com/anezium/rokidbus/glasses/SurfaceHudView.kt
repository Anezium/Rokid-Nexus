package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.EditableSurfaceField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class SurfaceHudView(context: Context) : LinearLayout(context) {
    private data class ActiveInkMorph(
        val surfaceId: String,
        var seq: Long,
        val notice: NoticeInkMorphToken,
        val state: InkCardMorphState = InkCardMorphState(),
    )

    private data class PendingInkFallback(
        val surfaceId: String,
        var seq: Long,
    )

    /**
     * The one row every Nexus surface shares, regardless of plugin: phone
     * charge on the left, the date in the middle, the glasses' own charge on
     * the right. It lives here rather than as a launcher overlay because it is
     * our own drawing, not a chip fighting the ROM's launcher for a place in
     * someone else's status row — so it is exactly as available as the surface
     * itself is, in every plugin, with nothing to lose sync with.
     */
    private val phoneBatteryStatusView = monoText(11f, BusTheme.muted).apply {
        isSingleLine = true
        gravity = Gravity.START
    }
    private val dateStatusView = monoText(11f, BusTheme.dim).apply {
        isSingleLine = true
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val glassesBatteryStatusView = monoText(11f, BusTheme.muted).apply {
        isSingleLine = true
        gravity = Gravity.END
        textAlignment = TEXT_ALIGNMENT_VIEW_END
    }
    private val statusRowView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(phoneBatteryStatusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(dateStatusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(glassesBatteryStatusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private var stopObservingPhoneBattery: (() -> Unit)? = null
    private val statusDateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val statusRowTicker = object : Runnable {
        override fun run() {
            updateStatusRow()
            postDelayed(this, STATUS_ROW_TICK_MS)
        }
    }

    private val titleView = monoText(17f, BusTheme.text, bold = true)
    private val subtitleView = monoText(11f, BusTheme.muted)
    private val previousView = monoText(15f, BusTheme.dim)
    private val currentView = monoText(25f, BusTheme.phosphor, bold = true).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 5
    }
    private val nextView = monoText(17f, BusTheme.muted).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 3
    }
    private val boardView = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val editView = EditText(context).apply {
        visibility = GONE
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        // A plugin opened this field to be typed into, so the phone may bring its
        // keyboard up for it, unlike any field the wearer merely lands on.
        privateImeOptions = RemoteInputMetadataPolicy.EDITABLE_SURFACE_IME_OPTION
        setTextColor(BusTheme.text)
        setHintTextColor(BusTheme.dim)
        setBackgroundColor(BusTheme.glassesBg)
        textSize = 17f
        typeface = android.graphics.Typeface.MONOSPACE
        // One line: this is a reply, not a composer, and it lets a physical
        // Enter key submit directly instead of inserting a newline the way a
        // multi-line field would.
        setSingleLine(true)
        // Without this, typing or pasting past the wire limit would let the
        // wearer see and edit text that committedPayload() then silently cuts
        // off on submit. Stopping input at the boundary makes the limit
        // visible instead of losing the tail after the fact.
        filters = arrayOf(InputFilter.LengthFilter(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH))
        setOnEditorActionListener { view, actionId, _ ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
            if (isSend) {
                SurfaceController.forwardSurfaceText(view.text?.toString().orEmpty(), cancelled = false)
            }
            isSend
        }
        // A hardware Enter key on a bonded keyboard never reaches
        // onEditorActionListener above — that path is IME-synthesized. This is
        // the raw key event a physical Enter actually dispatches through.
        setOnKeyListener { view, keyCode, event ->
            val isEnterDown = keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isEnterDown) {
                SurfaceController.forwardSurfaceText(
                    (view as EditText).text?.toString().orEmpty(),
                    cancelled = false,
                )
            }
            isEnterDown
        }
    }
    private val readerView = ReaderSurfaceView(context).apply { visibility = GONE }
    private val mediaView = MediaHudView(context).apply { visibility = GONE }
    private val imageView = ImageHudView(context).apply {
        visibility = GONE
        setPadding(px(4), px(4), px(4), px(4))
    }
    private val inkView = InkHudView(context).apply { visibility = GONE }
    private val inkCardHost = InkCardClipHost(context).apply {
        visibility = GONE
        addView(
            inkView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    private val footerView = monoText(10.5f, BusTheme.dim).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 1
    }
    private var surface: NexusSurface? = null
    private var lastEditableSurfaceId: String? = null
    private var listRenderGeneration = 0L
    private var pendingListLayoutListener: View.OnLayoutChangeListener? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var stopObservingReaderScroll: (() -> Unit)? = null
    private var hudTopInsetDp = 0
    private var inkPresentationSurfaceId: String? = null
    private var inkPresentationGeneration: Long? = null
    private var activeInkMorph: ActiveInkMorph? = null
    private var pendingInkFallback: PendingInkFallback? = null
    private var inkMorphTimeout: Runnable? = null
    private val inkClipMotion = HudMotionValue(0f) { height ->
        inkCardHost.revealTo(height.roundToInt())
    }
    private val inkAlphaMotion = HudMotionValue(1f) { alpha -> inkView.alpha = alpha }

    private val ticker = object : Runnable {
        override fun run() {
            val active = surface ?: return
            renderNow(active)
            if (shouldTick(active)) {
                postDelayed(this, tickDelay(active))
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(BusTheme.glassesBg)
        setPadding(px(18), px(16), px(18), px(12))
        isFocusable = true
        isFocusableInTouchMode = true

        applyMarquee(titleView)
        subtitleView.maxLines = 1
        subtitleView.ellipsize = TextUtils.TruncateAt.END
        previousView.gravity = Gravity.CENTER
        previousView.textAlignment = TEXT_ALIGNMENT_CENTER
        previousView.maxLines = 2
        previousView.ellipsize = TextUtils.TruncateAt.END

        addView(statusRowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = px(6)
        })
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(3)
        })
        addView(mediaView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(inkCardHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(previousView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(30)
        })
        addView(currentView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(boardView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(editView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(readerView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(nextView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
        })
        addView(footerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(16)
        })
    }

    fun render(next: NexusSurface?) {
        removeCallbacks(ticker)
        val pendingGeneration = next?.takeIf(NexusSurface::isInk)?.let {
            SurfaceController.inkPresentationGeneration(it.surfaceId, it.seq)
        }
        val keepsPresentation = shouldKeepInkPresentation(
            nextIsInk = next?.isInk == true,
            nextSurfaceId = next?.surfaceId.orEmpty(),
            presentationSurfaceId = inkPresentationSurfaceId,
            pendingGeneration = pendingGeneration,
            presentationGeneration = inkPresentationGeneration,
        )
        if (!keepsPresentation) {
            cancelInkPresentation()
            inkPresentationSurfaceId = null
            inkPresentationGeneration = null
        }
        surface = next
        if (next == null) {
            clear()
            return
        }
        renderNow(next)
        if (next.isInk) {
            activeInkMorph
                ?.takeIf {
                    it.surfaceId == next.surfaceId &&
                        it.state.phase == InkCardMorphPhase.WAITING_FOR_FIRST_FRAME
                }
                ?.seq = next.seq
            pendingInkFallback
                ?.takeIf { it.surfaceId == next.surfaceId }
                ?.seq = next.seq
            if (!keepsPresentation) prepareInkPresentation(next)
        }
        if (shouldTick(next)) {
            postDelayed(ticker, tickDelay(next))
        }
        // An editable card keeps the field's own focus: this container regaining
        // it on every render (a re-render can arrive mid-keystroke) is exactly
        // what silently ate the wearer's typing here before.
        if (next.kind == NexusSurface.KIND_CARD && next.editable != null) {
            editView.requestFocus()
        } else if (!next.isInk || !hasFocus()) {
            requestFocus()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val drawn = surface?.takeIf(NexusSurface::isInk) ?: return
        if (!inkView.isLayoutSettledForDraw()) return
        SurfaceController.onInkFrameDrawn(
            surfaceId = drawn.surfaceId,
            seq = drawn.seq,
            widthPx = inkView.width,
            heightPx = inkView.height,
        ) { onInkFirstFrame(drawn.surfaceId, drawn.seq) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(context, ::applyHudTopInset)
        stopObservingReaderScroll?.invoke()
        stopObservingReaderScroll = SurfaceController.observeReaderScroll { direction ->
            if (surface?.isReader == true) readerView.smoothScrollByViewport(direction)
        }
        stopObservingPhoneBattery?.invoke()
        stopObservingPhoneBattery = PhoneBatteryController.observe(::updateStatusRow)
        removeCallbacks(statusRowTicker)
        statusRowTicker.run()
    }

    override fun onDetachedFromWindow() {
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        removeCallbacks(ticker)
        removeCallbacks(statusRowTicker)
        invalidatePendingListLayout()
        cancelInkPresentation()
        inkPresentationSurfaceId = null
        inkPresentationGeneration = null
        stopObservingReaderScroll?.invoke()
        stopObservingReaderScroll = null
        stopObservingPhoneBattery?.invoke()
        stopObservingPhoneBattery = null
        SurfaceController.detachInkRenderer(inkView)
        super.onDetachedFromWindow()
    }

    /** Phone charge, the date, and the glasses' own charge — refreshed independently of the surface. */
    private fun updateStatusRow() {
        val phone = PhoneBatteryController.reading()
        phoneBatteryStatusView.text = phone?.let { "HP ${batteryLabel(it.level, it.charging)}" }.orEmpty()
        phoneBatteryStatusView.visibility = visibleIf(phone != null)
        dateStatusView.text = statusDateFormat.format(Date())
        val glasses = readGlassesBattery()
        glassesBatteryStatusView.text = glasses?.let { (level, charging) -> batteryLabel(level, charging) }
            .orEmpty()
        glassesBatteryStatusView.visibility = visibleIf(glasses != null)
    }

    private fun batteryLabel(level: Int, charging: Boolean): String =
        "$level%" + if (charging) "+" else ""

    /**
     * The sticky [Intent.ACTION_BATTERY_CHANGED] broadcast always has a last
     * value queued, so a null receiver reads it once without registering a
     * live one to unregister later — the same trick a status-bar clock uses.
     */
    private fun readGlassesBattery(): Pair<Int, Boolean>? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to charging
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        if (surface?.isInk == true) {
            applyInkCardHost()
        } else {
            applyFullBleedHost()
        }
        requestLayout()
    }

    private fun renderNow(surface: NexusSurface) {
        invalidatePendingListLayout()
        when (surfaceHudMode(surface.kind)) {
            SurfaceHudMode.INK_CARD -> applyInkCardHost()
            SurfaceHudMode.FULL_BLEED -> applyFullBleedHost()
        }
        titleView.text = surface.title
        titleView.visibility = visibleIf(surface.title.isNotBlank())
        subtitleView.text = surface.subtitle
        subtitleView.visibility = visibleIf(surface.subtitle.isNotBlank())
        footerView.text = surface.footer
        footerView.visibility = visibleIf(surface.footer.isNotBlank())

        if (!surface.isInk) hideInk()
        if (surface.kind != NexusSurface.KIND_CARD || surface.editable == null) {
            editView.visibility = GONE
        }
        when {
            surface.isInk -> renderInk(surface)
            surface.isImage -> renderImage(surface)
            surface.isMedia -> renderMedia(surface)
            surface.isReader -> renderReader(surface)
            surface.isTimed -> renderTimed(surface)
            surface.kind == NexusSurface.KIND_CARD && surface.editable != null ->
                renderEditableCard(surface, surface.editable)
            else -> renderCard(surface)
        }
    }

    /**
     * A card with one bounded editable field instead of a read-only body. The
     * glasses' own system IME ([RemoteInputController]) activates the normal
     * Android way once this field takes focus — nothing new to wire there.
     * Submitting/cancelling reports back once via
     * [SurfaceController.forwardSurfaceText]; see [EditableSurfaceContract].
     */
    private fun renderEditableCard(surface: NexusSurface, editable: EditableSurfaceField) {
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        nextView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        // A plugin that reuses one surfaceId for every editable prompt it ever
        // shows — Agents' single board surface doubles as its reply field —
        // means surfaceId alone cannot tell "still the same compose" from "a
        // new one that happens to reuse the id". Having stopped being the
        // visible editable field in between is what actually marks a fresh
        // prompt; same surfaceId while never leaving still means keep typing.
        val reopened = editView.visibility != VISIBLE || surface.surfaceId != lastEditableSurfaceId
        editView.visibility = VISIBLE
        editView.hint = editable.placeholder.orEmpty()
        if (reopened && editView.text?.toString() != editable.initialText.orEmpty()) {
            editView.setText(editable.initialText.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        lastEditableSurfaceId = surface.surfaceId
        editView.requestFocus()
    }

    private fun renderInk(surface: NexusSurface) {
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        titleView.visibility = GONE
        subtitleView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        footerView.visibility = GONE
        inkCardHost.visibility = VISIBLE
        inkView.visibility = VISIBLE
        SurfaceController.attachInkRenderer(inkView, surface.ink?.debugActions == true)
    }

    private fun hideInk() {
        SurfaceController.detachInkRenderer(inkView)
        inkView.visibility = GONE
        inkCardHost.visibility = GONE
        resetInkReveal()
    }

    private fun applyFullBleedHost() {
        applyHostChrome(SurfaceHudMode.FULL_BLEED)
    }

    private fun applyHostChrome(mode: SurfaceHudMode) {
        val chrome = surfaceHostChrome(mode, hudTopInsetDp)
        val fill = chrome.backgroundColor
        if (fill == null) background = null else setBackgroundColor(fill)
        setPadding(
            px(chrome.paddingLeftDp),
            px(chrome.paddingTopDp),
            px(chrome.paddingRightDp),
            px(chrome.paddingBottomDp),
        )
    }

    private fun applyInkCardHost() {
        applyHostChrome(SurfaceHudMode.INK_CARD)
        val metrics = resources.displayMetrics
        val topPx = HudBandGeometry.topPx(context, hudTopInsetDp)
        val layout = (inkCardHost.layoutParams as? LayoutParams)
            ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        layout.width = HudBandGeometry.widthPx(metrics.widthPixels)
        layout.height = LayoutParams.WRAP_CONTENT
        layout.weight = 0f
        layout.gravity = Gravity.CENTER_HORIZONTAL
        layout.topMargin = topPx
        inkCardHost.layoutParams = layout
        inkCardHost.heightCapPx = HudBandGeometry.availableHeightPx(
            displayHeightPx = metrics.heightPixels,
            topPx = topPx,
        )
    }

    private fun prepareInkPresentation(surface: NexusSurface) {
        inkPresentationSurfaceId = surface.surfaceId
        inkPresentationGeneration =
            SurfaceController.inkPresentationGeneration(surface.surfaceId, surface.seq)
        if (!SurfaceController.isInkPresentationPending(surface.surfaceId, surface.seq)) {
            resetInkReveal()
            return
        }
        val notice = runCatching { NoticeController.prepareInkMorph(surface.ownerPluginId) }
            .onFailure { logError("Ink card morph notice preparation failed", it) }
            .getOrNull()
        if (notice == null) {
            resetInkReveal()
            val fallback = PendingInkFallback(surface.surfaceId, surface.seq)
            pendingInkFallback = fallback
            scheduleInkReadyFallback(fallback)
            return
        }

        val morph = ActiveInkMorph(surface.surfaceId, surface.seq, notice)
        activeInkMorph = morph
        inkClipMotion.snapTo(notice.bandHeightPx.toFloat())
        inkAlphaMotion.snapTo(0f)
        val timeout = Runnable {
            if (activeInkMorph === morph) {
                commitInkMorphInstant(morph, reason = "first_frame_timeout", releaseGate = true)
            }
        }
        inkMorphTimeout = timeout
        postDelayed(timeout, 500L)
    }

    private fun onInkFirstFrame(surfaceId: String, seq: Long) {
        if (inkPresentationSurfaceId == surfaceId) clearInkMorphTimeout()
        pendingInkFallback?.takeIf { it.surfaceId == surfaceId }?.let {
            pendingInkFallback = null
            logMorphDecision(
                decision = "instant",
                reason = "no_matching_band",
                bandPx = 0,
                cardPx = inkCardHost.height,
            )
            return
        }
        val morph = activeInkMorph?.takeIf {
            it.surfaceId == surfaceId && it.state.phase == InkCardMorphPhase.WAITING_FOR_FIRST_FRAME
        } ?: return
        morph.seq = seq
        try {
            clearInkMorphTimeout()
            val cardHeightPx = inkCardHost.height
            if (cardHeightPx <= 0 || morph.notice.bandHeightPx <= 0) {
                commitInkMorphInstant(morph, reason = "invalid_bounds", releaseGate = false)
                return
            }
            if (!HudMotion.enabled) {
                commitInkMorphInstant(morph, reason = "motion_disabled", releaseGate = false)
                return
            }
            if (cardHeightPx == morph.notice.bandHeightPx) {
                commitInkMorphInstant(morph, reason = "no_clip_delta", releaseGate = false)
                return
            }
            if (!morph.state.startAnimation()) return

            inkClipMotion.snapTo(morph.notice.bandHeightPx.toFloat())
            inkAlphaMotion.snapTo(0f)
            inkAlphaMotion.animateTo(1f, HudMotion.MICRO_MS, HudMotion.enter)
            if (!NoticeController.startInkMorphFade(morph.notice)) {
                commitInkMorphInstant(morph, reason = "notice_unavailable", releaseGate = false)
                return
            }
            if (!NoticeController.closeForInkMorph(morph.notice)) {
                commitInkMorphInstant(morph, reason = "notice_replaced", releaseGate = false)
                return
            }
            inkClipMotion.animateTo(
                target = cardHeightPx.toFloat(),
                durationMs = HudMotion.STANDARD_MS,
                interpolator = HudMotion.enter,
            ) {
                completeInkMorph(morph, cardHeightPx)
            }
            logMorphDecision(
                decision = "animate",
                reason = "first_frame",
                bandPx = morph.notice.bandHeightPx,
                cardPx = cardHeightPx,
            )
        } catch (error: Throwable) {
            logError("Ink card morph animator unavailable", error)
            commitInkMorphInstant(morph, reason = "animator_unavailable", releaseGate = false)
        }
    }

    private fun completeInkMorph(morph: ActiveInkMorph, cardHeightPx: Int) {
        if (activeInkMorph !== morph || !morph.state.completeAnimation()) return
        inkClipMotion.snapTo(cardHeightPx.toFloat())
        inkAlphaMotion.snapTo(1f)
        inkCardHost.revealFully()
        activeInkMorph = null
        runCatching { NoticeController.finishInkMorph(morph.notice) }
            .onFailure { logError("Ink card morph notice teardown failed", it) }
    }

    private fun commitInkMorphInstant(
        morph: ActiveInkMorph,
        reason: String,
        releaseGate: Boolean,
    ) {
        if (activeInkMorph !== morph || !morph.state.commitInstant()) return
        clearInkMorphTimeout()
        inkClipMotion.cancel()
        inkAlphaMotion.cancel()
        inkCardHost.revealFully()
        inkView.alpha = 1f
        logMorphDecision(
            decision = "instant",
            reason = reason,
            bandPx = morph.notice.bandHeightPx,
            cardPx = inkCardHost.height,
        )
        activeInkMorph = null
        runCatching { NoticeController.closeForInkMorph(morph.notice) }
            .onFailure { logError("Ink card morph notice close failed", it) }
        runCatching { NoticeController.finishInkMorph(morph.notice) }
            .onFailure { logError("Ink card morph notice teardown failed", it) }
        if (releaseGate) {
            SurfaceController.onInkFirstFrameTimeout(morph.surfaceId, morph.seq)
        }
    }

    private fun cancelInkPresentation() {
        clearInkMorphTimeout()
        pendingInkFallback = null
        val morph = activeInkMorph
        activeInkMorph = null
        inkClipMotion.cancel()
        inkAlphaMotion.cancel()
        if (morph != null) {
            morph.state.cancel()
            runCatching { NoticeController.cancelInkMorph(morph.notice) }
                .onFailure { logError("Ink card morph notice restore failed", it) }
        }
        resetInkReveal()
    }

    private fun clearInkMorphTimeout() {
        inkMorphTimeout?.let(::removeCallbacks)
        inkMorphTimeout = null
    }

    private fun scheduleInkReadyFallback(fallback: PendingInkFallback) {
        val timeout = Runnable {
            if (pendingInkFallback !== fallback) return@Runnable
            val current = surface?.takeIf { it.isInk && it.surfaceId == fallback.surfaceId }
                ?: return@Runnable
            fallback.seq = current.seq
            pendingInkFallback = null
            logMorphDecision(
                decision = "instant",
                reason = "no_matching_band",
                bandPx = 0,
                cardPx = inkCardHost.height,
            )
            SurfaceController.onInkFirstFrameTimeout(current.surfaceId, current.seq)
        }
        inkMorphTimeout = timeout
        postDelayed(timeout, 500L)
    }

    private fun resetInkReveal() {
        inkClipMotion.cancel()
        inkAlphaMotion.cancel()
        inkCardHost.revealFully()
        inkView.alpha = 1f
    }

    private fun logMorphDecision(
        decision: String,
        reason: String,
        bandPx: Int,
        cardPx: Int,
    ) {
        log("morph decision=$decision reason=$reason bandPx=$bandPx cardPx=$cardPx")
    }

    private fun renderTimed(surface: NexusSurface) {
        // Timed lines (lyrics) show one big centered line; cards pack a board.
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        // Long lyric lines must never lose their tail: shrink to fit instead of clipping.
        currentView.maxLines = TIMED_BODY_MAX_LINES
        fitTimedBody()
        currentView.gravity = Gravity.CENTER
        currentView.textAlignment = TEXT_ALIGNMENT_CENTER
        val index = currentTimedIndex(surface)
        previousView.text = surface.timedLines.getOrNull(index - 1)?.text.orEmpty()
        previousView.visibility = visibleIf(previousView.text.isNotBlank())
        currentView.text = surface.timedLines.getOrNull(index)?.text
            ?.takeIf { it.isNotBlank() }
            ?: surface.timedLines.firstOrNull()?.text
            ?: ""
        nextView.text = surface.timedLines.getOrNull(index + 1)?.text.orEmpty()
        nextView.visibility = visibleIf(nextView.text.isNotBlank())
    }

    private fun fitCardBody() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentView.setAutoSizeTextTypeUniformWithConfiguration(
                CARD_BODY_MIN_SP,
                CARD_BODY_MAX_SP,
                CARD_BODY_STEP_SP,
                TypedValue.COMPLEX_UNIT_SP,
            )
        } else {
            currentView.textSize = CARD_BODY_SP
        }
    }

    private fun fitTimedBody() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentView.setAutoSizeTextTypeUniformWithConfiguration(
                TIMED_BODY_MIN_SP,
                TIMED_BODY_MAX_SP,
                TIMED_BODY_STEP_SP,
                TypedValue.COMPLEX_UNIT_SP,
            )
        } else {
            currentView.textSize = TIMED_BODY_SP
        }
    }

    private fun renderCard(surface: NexusSurface) {
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        nextView.visibility = GONE
        val rows = surface.rows.filter { it.text.isNotBlank() || it.isStructured }
        when {
            rows.any { it.isListRow } -> renderList(rows)
            rows.any { it.isStructured } -> renderBoard(rows)
            else -> renderPlainCard(rows)
        }
    }

    /**
     * Attention-ordered list: a selection rail, per-row weight, and an optional
     * secondary line. Unlike the departure board there is no chip column — the
     * hierarchy is carried by weight and position, which is what a list of live
     * things (agent sessions, conversations) actually needs.
     */
    private fun renderList(rows: List<SurfaceRow>) {
        currentView.visibility = GONE
        boardView.visibility = VISIBLE
        boardView.gravity = Gravity.TOP
        boardView.removeAllViews()
        val rowViews = rows.mapIndexed { index, row ->
            val view = if (row.tone == SurfaceRow.TONE_BODY) bodyRow(row) else listRow(row)
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) {
                    topMargin = px(
                        if (row.tone == SurfaceRow.TONE_BODY) LIST_BODY_GAP_DP else LIST_ROW_GAP_DP,
                    )
                }
            }
            view to params
        }
        // The board's height is weight-fixed, but the chrome above it is not: a
        // title appearing on this render moves the board's bounds only at the
        // next layout pass. Window against whatever size is current, then again
        // whenever the bounds actually change — the size key keeps the listener
        // from looping on the layout its own re-attachment triggers.
        var windowedSizeKey = 0L
        fun windowNow() {
            val width = boardView.width - boardView.paddingLeft - boardView.paddingRight
            val height = boardView.height - boardView.paddingTop - boardView.paddingBottom
            if (width <= 0 || height <= 0) return
            val sizeKey = (width.toLong() shl 32) or height.toLong()
            if (sizeKey == windowedSizeKey) return
            windowedSizeKey = sizeKey
            attachListWindow(rows, rowViews)
        }

        val generation = listRenderGeneration
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Never attach from inside the layout pass: children added mid-layout
            // sit in the tree unmeasured and the board draws empty (seen on
            // device, first render of a fresh surface window). The post lands
            // after the traversal, where addView schedules a clean one.
            boardView.post {
                if (generation == listRenderGeneration) windowNow()
            }
        }
        pendingListLayoutListener = listener
        boardView.addOnLayoutChangeListener(listener)
        windowNow()
        if (windowedSizeKey == 0L) boardView.requestLayout()
    }

    private fun attachListWindow(
        rows: List<SurfaceRow>,
        rowViews: List<Pair<View, LayoutParams>>,
    ) {
        val viewportWidth = (boardView.width - boardView.paddingLeft - boardView.paddingRight)
            .coerceAtLeast(1)
        val viewportHeight = (boardView.height - boardView.paddingTop - boardView.paddingBottom)
            .coerceAtLeast(0)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val rowOuterHeights = rowViews.map { (view, params) ->
            view.measure(widthSpec, heightSpec)
            view.measuredHeight + params.topMargin
        }
        val indicatorProbe = listOverflowIndicator(up = false, hiddenCount = rows.size)
        indicatorProbe.measure(widthSpec, heightSpec)
        val selectedIndex = rows.indexOfFirst { it.selected }.takeIf { it >= 0 }
        val window = surfaceListViewport(
            rowOuterHeightsPx = rowOuterHeights,
            viewportHeightPx = viewportHeight,
            selectedIndex = selectedIndex,
            indicatorHeightPx = indicatorProbe.measuredHeight,
        )

        boardView.removeAllViews()
        if (window.hiddenAbove > 0) {
            boardView.addView(
                listOverflowIndicator(up = true, hiddenCount = window.hiddenAbove),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        for (index in window.firstRow until window.lastRowExclusive) {
            val (view, params) = rowViews[index]
            boardView.addView(view, params)
        }
        if (window.hiddenBelow > 0) {
            boardView.addView(
                listOverflowIndicator(up = false, hiddenCount = window.hiddenBelow),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun listOverflowIndicator(up: Boolean, hiddenCount: Int): TextView =
        monoText(LIST_SUB_SP, BusTheme.muted).apply {
            text = "${if (up) "▴" else "▾"} $hiddenCount"
            maxLines = 1
        }

    private fun invalidatePendingListLayout() {
        listRenderGeneration += 1
        pendingListLayoutListener?.let(boardView::removeOnLayoutChangeListener)
        pendingListLayoutListener = null
    }

    private fun listRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                selectionRail(row.selected),
                LayoutParams(px(3), LayoutParams.MATCH_PARENT),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.BOTTOM
                            addView(
                                monoText(LIST_TITLE_SP, toneColor(row), bold = row.isEmphasised)
                                    .apply {
                                        text = row.text
                                        maxLines = 1
                                        // Never marquee a list title: a scrolling row is
                                        // unreadable at a glance, which is the whole point.
                                        ellipsize = TextUtils.TruncateAt.END
                                    },
                                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                            )
                            if (row.trail.isNotEmpty()) {
                                addView(
                                    listMetaView(row),
                                    LayoutParams(
                                        LayoutParams.WRAP_CONTENT,
                                        LayoutParams.WRAP_CONTENT,
                                    ).apply { marginStart = px(8) },
                                )
                            }
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    if (row.sub.isNotBlank()) {
                        addView(
                            monoText(LIST_SUB_SP, BusTheme.muted).apply {
                                text = row.sub
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                                .apply { topMargin = px(2) },
                        )
                    }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(9) },
            )
        }

    /** Prose row: a fixed dim label, then wrapped text — a conversation, not a table. */
    private fun bodyRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            if (row.badge.isNotBlank()) {
                addView(
                    monoText(LIST_LABEL_SP, BusTheme.muted, bold = true).apply {
                        text = row.badge
                        maxLines = 1
                    },
                    LayoutParams(px(LIST_LABEL_WIDTH_DP), LayoutParams.WRAP_CONTENT),
                )
            }
            addView(
                monoText(LIST_BODY_SP, if (row.selected) BusTheme.phosphor else BusTheme.text).apply {
                    text = row.text
                    maxLines = LIST_BODY_MAX_LINES
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    private fun selectionRail(selected: Boolean): View =
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (selected) BusTheme.phosphor else BusTheme.glassesBg)
                cornerRadius = px(2).toFloat()
            }
        }

    /** Status token bright, the rest (age, counters) muted and smaller. */
    private fun listMetaView(row: SurfaceRow): TextView =
        monoText(LIST_META_SP, toneColor(row), bold = row.isEmphasised).apply {
            maxLines = 1
            text = SpannableStringBuilder().apply {
                append(row.trail.first())
                row.trail.drop(1).forEach { token ->
                    val start = length
                    append("  ")
                    append(token)
                    setSpan(
                        ForegroundColorSpan(BusTheme.muted),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        RelativeSizeSpan(0.82f),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun toneColor(row: SurfaceRow): Int = when {
        row.tone == SurfaceRow.TONE_ALERT -> BusTheme.phosphor
        row.tone == SurfaceRow.TONE_DIM -> BusTheme.muted
        row.selected -> BusTheme.phosphor
        else -> BusTheme.text
    }

    private fun renderMedia(surface: NexusSurface) {
        hideReader()
        imageView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        mediaView.visibility = VISIBLE
        mediaView.render(surface)
    }

    private fun renderImage(surface: NexusSurface) {
        hideReader()
        mediaView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        imageView.visibility = VISIBLE
        imageView.render(surface)
    }

    private fun renderReader(surface: NexusSurface) {
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        readerView.visibility = VISIBLE
        readerView.render(surface.surfaceId, surface.readerSegments, surface.readerAnchor)
    }

    private fun hideReader() {
        if (readerView.visibility != GONE) readerView.clear()
        readerView.visibility = GONE
    }

    private fun renderPlainCard(rows: List<SurfaceRow>) {
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        // Long card bodies (assistant replies, article text) must never lose
        // their tail: shrink to fit instead of clipping, like timed lines do.
        fitCardBody()
        currentView.maxLines = CARD_BODY_MAX_LINES
        // Plain cards align as a left block; per-line centering scatters the columns.
        currentView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        currentView.textAlignment = TEXT_ALIGNMENT_VIEW_START
        currentView.text = rows.joinToString("\n") { it.text }
    }

    /** Departure-board rows: route badge, destination, wait times — one row each. */
    private fun renderBoard(rows: List<SurfaceRow>) {
        currentView.visibility = GONE
        boardView.visibility = VISIBLE
        // Both renderers share this container, so each states its own alignment
        // rather than inheriting whatever the last surface left behind.
        boardView.gravity = Gravity.CENTER_VERTICAL
        boardView.removeAllViews()
        rows.forEachIndexed { index, row ->
            boardView.addView(
                boardRow(row),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = px(BOARD_ROW_GAP_DP)
                },
            )
        }
    }

    private fun boardRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                badgeView(row.badge),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                monoText(BOARD_TEXT_SP, BusTheme.text).apply {
                    text = row.text
                    applyMarquee(this)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = px(10)
                },
            )
            if (row.trail.isNotEmpty()) {
                addView(
                    trailView(row.trail),
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = px(10)
                    },
                )
            }
        }

    /**
     * Slow horizontal scroll for names too long for their slot. isSelected
     * keeps the marquee running without focus, which overlays never hold.
     */
    private fun applyMarquee(view: TextView) {
        view.isSingleLine = true
        view.setHorizontallyScrolling(true)
        view.ellipsize = TextUtils.TruncateAt.MARQUEE
        view.marqueeRepeatLimit = -1
        view.isSelected = true
    }

    /** Solid phosphor chip with punched-out route text — the brightest mark on the row. */
    private fun badgeView(badge: String): TextView =
        monoText(BOARD_BADGE_SP, BusTheme.glassesBg, bold = true).apply {
            text = badge.ifBlank { "·" }
            maxLines = 1
            gravity = Gravity.CENTER
            minWidth = px(44)
            setPadding(px(7), px(2), px(7), px(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(BusTheme.phosphor)
                cornerRadius = px(6).toFloat()
            }
        }

    /** Next departure large and bright, the following ones smaller and muted. */
    private fun trailView(trail: List<String>): TextView =
        monoText(BOARD_TRAIL_SP, BusTheme.phosphor, bold = true).apply {
            maxLines = 1
            text = SpannableStringBuilder().apply {
                append(trail.first())
                trail.drop(1).forEach { token ->
                    val start = length
                    append("  ")
                    append(token)
                    setSpan(
                        ForegroundColorSpan(BusTheme.muted),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        RelativeSizeSpan(0.78f),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun currentTimedIndex(surface: NexusSurface): Int {
        val position = surface.anchor?.effectivePositionMs(SystemClock.elapsedRealtime()) ?: 0L
        var candidate = 0
        for (index in surface.timedLines.indices) {
            if (surface.timedLines[index].timeMs <= position) {
                candidate = index
            } else {
                break
            }
        }
        return candidate.coerceIn(0, (surface.timedLines.size - 1).coerceAtLeast(0))
    }

    private fun clear() {
        invalidatePendingListLayout()
        applyFullBleedHost()
        titleView.text = ""
        subtitleView.text = ""
        previousView.text = ""
        currentView.text = ""
        nextView.text = ""
        footerView.text = ""
        mediaView.clear()
        mediaView.visibility = GONE
        imageView.render(null)
        imageView.visibility = GONE
        readerView.clear()
        readerView.visibility = GONE
        hideInk()
        boardView.removeAllViews()
        boardView.visibility = GONE
        editView.setText("")
        editView.visibility = GONE
        lastEditableSurfaceId = null
        currentView.visibility = VISIBLE
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private fun shouldTick(surface: NexusSurface): Boolean =
        surface.anchor?.playing == true && (surface.isTimed || surface.isMedia)

    private fun tickDelay(surface: NexusSurface): Long =
        if (surface.isMedia) MEDIA_TICK_MS else TICK_MS

    private fun monoText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        monoHudText(context, sizeSp, color, bold)

    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val TICK_MS = 100L
        private const val MEDIA_TICK_MS = 500L
        private const val STATUS_ROW_TICK_MS = 30_000L

        // Plain card bodies (messages, chooser): smaller mono, more lines.
        // Auto-fit mirrors the lyrics pattern: short bodies keep the full
        // size, long ones shrink to fit instead of clipping their tail.
        private const val CARD_BODY_SP = 17f
        private const val CARD_BODY_MAX_LINES = 15
        private const val CARD_BODY_MAX_SP = 17
        private const val CARD_BODY_MIN_SP = 14
        private const val CARD_BODY_STEP_SP = 1
        private const val TIMED_BODY_SP = 25f
        private const val TIMED_BODY_MAX_LINES = 5

        // Lyrics auto-fit: keep the big size for short lines, shrink long ones to fit.
        private const val TIMED_BODY_MAX_SP = 25
        private const val TIMED_BODY_MIN_SP = 14
        private const val TIMED_BODY_STEP_SP = 1

        // Structured board rows: badge chip, destination, wait times.
        private const val BOARD_BADGE_SP = 15f
        private const val BOARD_TEXT_SP = 16f
        private const val BOARD_TRAIL_SP = 18f
        private const val BOARD_ROW_GAP_DP = 12

        // List rows: selection rail, title + meta, optional secondary line.
        private const val LIST_TITLE_SP = 16f
        private const val LIST_SUB_SP = 12.5f
        private const val LIST_META_SP = 14f
        private const val LIST_ROW_GAP_DP = 11
        // Conversation rows: fixed speaker label, wrapped prose.
        private const val LIST_BODY_SP = 14.5f
        private const val LIST_LABEL_SP = 11.5f
        private const val LIST_LABEL_WIDTH_DP = 38
        private const val LIST_BODY_MAX_LINES = 3
        private const val LIST_BODY_GAP_DP = 9
    }
}
