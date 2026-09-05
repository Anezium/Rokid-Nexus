package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.InkSurfaceContract
import com.anezium.rokidbus.shared.MediaArtworkContract
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object SurfaceController {
    private const val PREFS = "surface_renderer"
    private const val PREF_DISPLAY_PATH = "display_path"
    private const val BACK_FAILSAFE_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private val inkRendererLayer = InkRendererLayer(
        main,
        ::onInkResyncNeeded,
        ::onInkAction,
        ::onInkRendererError,
    )
    private val orderingCoordinator = SurfaceOrderingCoordinator<JSONObject>()
    private val inkPresentationGate = InkPresentationGate()
    private val listeners = CopyOnWriteArrayList<(NexusSurface?) -> Unit>()
    private val readerScrollListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val inputDedupe = DpadPairDedupe()
    private val suppressedDpadUps = mutableSetOf<Int>()
    private val ringInputPolicy = RingSurfaceInputPolicy()
    private val ringTapExpiry = Runnable(::resolveRingTaps)
    private val imageDecodeCoordinator = ImageDecodeCoordinator<Bitmap>()
    private val imageDecodeExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "RokidNexusImageDecode").apply { isDaemon = true }
    }
    private var backFailsafeSurfaceId: String? = null
    private var backFailsafe: Runnable? = null
    private var inkFrameMeterRunning = false
    private var displayStateReceiverRegistered = false
    private var inkDisplayTransitioning = false
    private var pendingInk: NexusSurface? = null
    @Volatile private var inkResyncListener: ((InkResyncRequest) -> Unit)? = null
    @Volatile private var active: NexusSurface? = null

    private val displayStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnMain {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> inkDisplayTransitioning = true
                    Intent.ACTION_SCREEN_ON -> {
                        inkDisplayTransitioning = false
                        inkRendererLayer.invalidateLayoutMetrics()
                    }
                }
            }
        }
    }

    fun activeSurface(): NexusSurface? = active

    fun hasFocusedEditableSurface(): Boolean =
        active?.let { it.kind == NexusSurface.KIND_CARD && it.editable != null } == true

    // Overlay is the default: TYPE_ACCESSIBILITY_OVERLAY stays visible even when
    // another app (e.g. Rokid Relay's glasses activity) keeps relaunching itself
    // to the foreground, which starves activity-based surfaces on this firmware.
    fun displayPath(context: Context): SurfaceDisplayPath =
        when (
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_DISPLAY_PATH, SurfaceDisplayPath.OVERLAY.prefValue)
        ) {
            SurfaceDisplayPath.ACTIVITY.prefValue -> SurfaceDisplayPath.ACTIVITY
            else -> SurfaceDisplayPath.OVERLAY
        }

    fun setDisplayPath(context: Context, path: SurfaceDisplayPath) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DISPLAY_PATH, path.prefValue)
            .apply()
    }

    fun observe(listener: (NexusSurface?) -> Unit): () -> Unit {
        listeners += listener
        listener(active)
        return { listeners.remove(listener) }
    }

    internal fun observeReaderScroll(listener: (Int) -> Unit): () -> Unit {
        readerScrollListeners += listener
        return { readerScrollListeners.remove(listener) }
    }

    fun handleSurfaceEnvelope(context: Context, envelope: BusEnvelope): Boolean {
        return when (envelope.path) {
            BusPaths.SURFACE_SHOW,
            BusPaths.SURFACE_UPDATE,
            -> {
                runOnMain { processShowOrUpdate(context.applicationContext, envelope) }
                true
            }
            BusPaths.SURFACE_HIDE -> {
                val surfaceId = envelope.payload.optString("surfaceId")
                val seq = envelope.payload.optLong("seq", 0L)
                runOnMain { hideRemote(surfaceId, seq) }
                true
            }
            else -> false
        }
    }

    private fun processShowOrUpdate(context: Context, envelope: BusEnvelope) {
        val payload = envelope.payload
        if (envelope.path == BusPaths.SURFACE_UPDATE && isAnchorOnlyUpdate(payload)) {
            processAnchorUpdate(context, payload)
            return
        }

        val previous = active
        var surface = runCatching { NexusSurface.fromPayload(payload, previous) }
            .onFailure { logError("Surface parse failed", it) }
            .getOrNull()
            ?: return
        val carriesImage = surface.isImage ||
            (surface.isMedia &&
                (MediaArtworkContract.hasBinaryArtwork(payload) || envelope.binary != null))
        if (carriesImage) {
            val validation = if (surface.isImage) {
                ImageSurfaceContract.validate(payload, envelope.binary)
            } else {
                MediaArtworkContract.validate(payload, envelope.binary)
            }
            if (validation !is ImageSurfaceValidationResult.Valid) {
                val code = (validation as? ImageSurfaceValidationResult.Invalid)?.code
                    ?: ImageSurfaceContract.ERROR_INVALID_IMAGE
                log("Image surface rejected id=${surface.surfaceId} code=$code")
                return
            }
        }

        val baseOrder = surface.toSurfaceOrder()
        when (val decision = orderingCoordinator.onBase(baseOrder)) {
            is SurfaceOrderDecision.Drop -> {
                logOrderDrop(surface.surfaceId, surface.seq, decision)
                return
            }
            is SurfaceOrderDecision.ApplyBase -> {
                if (decision.pendingAnchor != null) {
                    val pending = decision.pendingAnchor
                    surface = runCatching { NexusSurface.fromPayload(pending.value, surface) }
                        .onFailure { logError("Pending surface anchor parse failed", it) }
                        .getOrDefault(surface)
                } else if (decision.appliedAnchorSeqToPreserve != null) {
                    surface = surface.copy(
                        seq = decision.appliedAnchorSeqToPreserve,
                        anchor = previous?.anchor,
                    )
                }
            }
            else -> return
        }

        val launcherShow = envelope.path == BusPaths.SURFACE_SHOW
        if (surface.isInk) {
            showOrUpdateInk(
                context = context,
                surface = surface,
                launcherShow = launcherShow,
            )
        } else if (carriesImage && !(surface.isMedia && surface.imageBitmap != null)) {
            showOrUpdateImage(
                context = context,
                surface = surface,
                bytes = envelope.binary!!,
                baseOrder = baseOrder,
                launcherShow = launcherShow,
            )
        } else {
            showOrUpdate(context, surface, launcherShow = launcherShow)
        }
    }

    private fun processAnchorUpdate(context: Context, payload: JSONObject) {
        val order = payload.toSurfaceOrder()
        when (val decision = orderingCoordinator.onAnchor(order, payload)) {
            SurfaceOrderDecision.ApplyAnchor -> {
                val surface = runCatching { NexusSurface.fromPayload(payload, active) }
                    .onFailure { logError("Surface anchor parse failed", it) }
                    .getOrNull()
                    ?: return
                showOrUpdate(context, surface)
            }
            SurfaceOrderDecision.StashAnchor -> {
                log("Surface anchor stashed until matching base arrives id=${order.surfaceId} kind=${order.kind}")
            }
            is SurfaceOrderDecision.Drop -> logOrderDrop(order.surfaceId, order.seq, decision)
            else -> Unit
        }
    }

    fun showDemoCard(context: Context, path: SurfaceDisplayPath): String {
        setDisplayPath(context, path)
        val surface = NexusSurface(
            surfaceId = "demo-${path.prefValue}",
            seq = System.currentTimeMillis(),
            kind = NexusSurface.KIND_CARD,
            contentKey = "demo-${path.prefValue}",
            title = "Rokid Nexus",
            subtitle = "surface renderer demo",
            footer = path.prefValue,
            // Width/height ruler: count the last digit that fits before the wrap
            // and the last row number that renders to calibrate card formatters.
            rows = listOf(
                "123456789012345678901234567890",
                "row 02",
                "row 03",
                "row 04",
                "row 05",
                "row 06",
                "row 07",
                "row 08",
                "row 09",
                "row 10",
                "row 11",
                "row 12",
            ).map { SurfaceRow(text = it) },
            timedLines = emptyList(),
            anchor = null,
            handlesBack = false,
        )
        showOrUpdate(context.applicationContext, surface, forcedPath = path)
        return "surfaceDemo=${path.prefValue} surfaceId=${surface.surfaceId}"
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val surface = active ?: return false
        if (surface.isReader) return handleReaderKeyEvent(surface, event)
        if (surface.kind == NexusSurface.KIND_CARD && surface.editable != null) {
            // FORWARDED_KEYS below exists for read-only cards' remote-control
            // navigation (space/enter as media or select). An editable field
            // needs those same keys typed, not intercepted, so only BACK is
            // still claimed here; everything else reaches the focused EditText
            // the normal Android way.
            if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
            ) {
                handleBackDown(surface)
                return true
            }
            return false
        }
        if (shouldSuppressDpadEvent(event)) {
            return true
        }
        if (surface.isInk && inkRendererLayer.handleKeyEvent(event)) {
            return true
        }
        if ((event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            event.keyCode in FORWARDED_KEYS
        ) {
            forwardSurfaceInput(event.keyCode, event.action)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            handleBackDown(surface)
            return true
        }
        return event.keyCode in FORWARDED_KEYS
    }

    fun handleRingKey(keyCode: Int, eventTimeMs: Long): Boolean {
        val surface = active ?: return false
        if (surface.isReader) {
            readerScrollDirection(keyCode)?.let { direction ->
                val dedupeKey = if (direction > 0) {
                    KeyEvent.KEYCODE_DPAD_RIGHT
                } else {
                    KeyEvent.KEYCODE_DPAD_LEFT
                }
                if (inputDedupe.onKey(dedupeKey, KeyEvent.ACTION_DOWN, 0, eventTimeMs) != null) {
                    requestReaderScroll(direction)
                }
                return true
            }
        }
        applyRingResolution(ringInputPolicy.onKeyDown(keyCode, eventTimeMs))
        if (keyCode == RingSurfaceInputPolicy.RING_KEYCODE_TAP) {
            main.removeCallbacks(ringTapExpiry)
            main.postDelayed(ringTapExpiry, RingTapPolicy.DEFAULT_WINDOW_MS + 1L)
        }
        return true
    }

    private fun handleReaderKeyEvent(surface: NexusSurface, event: KeyEvent): Boolean {
        if (event.keyCode in READER_SCROLL_KEYS) {
            if (event.keyCode in DPAD_DIRECTION_KEYS && shouldSuppressDpadEvent(event)) return true
            if (event.action == KeyEvent.ACTION_DOWN) {
                readerScrollDirection(event.keyCode)?.let(::requestReaderScroll)
            }
            return true
        }
        if ((event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            event.keyCode in READER_FORWARDED_KEYS
        ) {
            forwardSurfaceInput(event.keyCode, event.action)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            handleBackDown(surface)
            return true
        }
        return event.keyCode in READER_FORWARDED_KEYS
    }

    fun cancelRingInput() {
        runOnMain(::resetRingInputOnMain)
    }

    fun onPhoneLinkLost() {
        runOnMain {
            pendingInk = null
            val surface = active?.takeIf(NexusSurface::isInk)
            if (surface != null) {
                sendInkClosed(surface.surfaceId, InkSurfaceContract.CLOSE_LINK_LOST)
                hideLocalOnMain(DisplayHoldReleaseReason.LINK_LOSS)
            } else {
                clearInkRenderer()
            }
        }
    }

    fun forwardSurfaceInput(keyCode: Int, action: Int): Boolean {
        val surface = active ?: return false
        GlassesHub.sendSurfaceInput(
            JSONObject()
                .put("surfaceId", surface.surfaceId)
                .put("keyCode", keyCode)
                .put("action", action),
        )
        return true
    }

    /**
     * The wearer submitted or cancelled the active card's editable field.
     * [text] is ignored (and not sent) when [cancelled] — the payload never
     * carries a text field for a cancelled field, matching how
     * [EditableSurfaceContract.committedPayload] encodes it.
     */
    fun forwardSurfaceText(text: String, cancelled: Boolean): Boolean {
        val surface = active?.takeIf { it.kind == NexusSurface.KIND_CARD && it.editable != null }
            ?: return false
        GlassesHub.sendSurfaceTextCommitted(
            EditableSurfaceContract.committedPayload(surface.surfaceId, text, cancelled)
                .put("ownerPluginId", surface.ownerPluginId),
        )
        return true
    }

    internal fun attachInkRenderer(view: InkHudView, debugActions: Boolean) {
        inkRendererLayer.attach(view, debugActions)
    }

    internal fun detachInkRenderer(view: InkHudView) {
        inkRendererLayer.detach(view)
    }

    internal fun onInkFrameDrawn(
        surfaceId: String,
        seq: Long,
        widthPx: Int,
        heightPx: Int,
        onReleased: () -> Unit,
    ) {
        val current = active
        if (
            current?.isInk != true ||
            current.surfaceId != surfaceId ||
            current.seq != seq
        ) {
            return
        }
        if (
            inkPresentationGate.releaseAfterDraw(
                surfaceId,
                seq,
                widthPx,
                heightPx,
                displayTransitioning = inkDisplayTransitioning,
            )
        ) {
            runCatching(onReleased)
                .onFailure { logError("Ink first-frame commit failed", it) }
            onInkAnswerShown(current)
            sendInkEvent(surfaceId, InkSurfaceContract.EVENT_READY)
        }
    }

    // A renewal never needs to build a lease, so no context is required here:
    // an answer can only ever extend an episode that is already holding.
    private fun onInkAnswerShown(surface: NexusSurface) {
        AssistantDisplayEpisode.accept(
            null,
            assistantEpisodeAnswerShownSignal(surface.ownerPluginId, surface.seq),
        )
    }

    internal fun isInkPresentationPending(surfaceId: String, seq: Long): Boolean =
        inkPresentationGate.isPending(surfaceId, seq)

    internal fun inkPresentationGeneration(surfaceId: String, seq: Long): Long? =
        inkPresentationGate.pendingGeneration(surfaceId, seq)

    internal fun onInkFirstFrameTimeout(surfaceId: String, seq: Long): Boolean {
        val current = active
        if (
            current?.isInk != true || current.surfaceId != surfaceId || current.seq != seq ||
            !inkPresentationGate.forceRelease(surfaceId, seq)
        ) {
            return false
        }
        onInkAnswerShown(current)
        sendInkEvent(surfaceId, InkSurfaceContract.EVENT_READY)
        return true
    }

    internal fun setInkResyncListener(listener: ((InkResyncRequest) -> Unit)?) {
        inkResyncListener = listener
    }

    private fun showOrUpdate(
        context: Context,
        surface: NexusSurface,
        forcedPath: SurfaceDisplayPath? = null,
        launcherShow: Boolean = false,
    ) {
        val completesRingHandoff =
            launcherShow && launcherReturnCoordinator.onSurfaceShown(surface.surfaceId)
        runOnMain {
            if (surface.isInk) {
                ensureDisplayStateMonitoring(context)
            } else {
                pendingInk = null
            }
            notifyReplacedInk(surface)
            if (!surface.isInk) clearInkRenderer()
            val keepMediaDecode = surface.isMedia && surface.mediaArtworkMetadata != null &&
                imageDecodeCoordinator.isCurrent(surface.surfaceId, surface.contentKey)
            val coordinated = if (keepMediaDecode) null else imageDecodeCoordinator.invalidate()
            coordinated?.recycleSafely()
            recycleActiveImageUnless(surface.imageBitmap ?: coordinated)
            cancelBackFailsafeOnMain(surface.surfaceId)
            DisplayWakePolicy.requestWake(context, DisplayWakeKind.SURFACE, requested = true)
            deactivateReplacedSurface(surface.surfaceId)
            prepareRingInputForSurface(surface.surfaceId)
            AssistantDisplayEpisode.accept(
                context,
                assistantEpisodeSurfacePresentedSignal(surface.ownerPluginId),
            )
            active = surface
            syncInkFrameMeter(surface)
            RingFocusBroadcastCoordinator.setSurfaceActive(
                context,
                active = true,
                completesHandoff = completesRingHandoff,
            )
            notifyListeners(surface)
            displaySurface(context, surface, forcedPath)
        }
    }

    private fun showOrUpdateInk(
        context: Context,
        surface: NexusSurface,
        launcherShow: Boolean,
    ) {
        pendingInk = surface
        inkRendererLayer.submit(
            surface = surface,
            onCommitted = {
                if (
                    pendingInk?.surfaceId == surface.surfaceId &&
                    pendingInk?.seq == surface.seq
                ) {
                    pendingInk = null
                }
                if (launcherShow) {
                    inkPresentationGate.arm(surface.surfaceId, surface.seq)
                }
                showOrUpdate(context, surface, launcherShow = launcherShow)
            },
        )
    }

    private fun showOrUpdateImage(
        context: Context,
        surface: NexusSurface,
        bytes: ByteArray,
        baseOrder: SurfaceOrder,
        launcherShow: Boolean = false,
    ) {
        val completesRingHandoff =
            launcherShow && launcherReturnCoordinator.onSurfaceShown(surface.surfaceId)
        val metadata = surface.imageMetadata ?: surface.mediaArtworkMetadata ?: return
        val key = ImageDecodeKey(surface.surfaceId, baseOrder.seq, metadata.contentKey)
        runOnMain {
            if (!orderingCoordinator.isCurrentBase(baseOrder)) return@runOnMain
            // Keep the previously published HUD/bitmap until this body decodes.
            // begin() invalidates older work; active still owns the visible bitmap.
            imageDecodeCoordinator.begin(key)
            if (surface.isMedia) {
                notifyReplacedInk(surface)
                clearInkRenderer()
                recycleActiveImageUnless(surface.imageBitmap)
                cancelBackFailsafeOnMain(surface.surfaceId)
                DisplayWakePolicy.requestWake(context, DisplayWakeKind.SURFACE, requested = true)
                deactivateReplacedSurface(surface.surfaceId)
                prepareRingInputForSurface(surface.surfaceId)
                AssistantDisplayEpisode.accept(
                    context,
                    assistantEpisodeSurfacePresentedSignal(surface.ownerPluginId),
                )
                active = surface
                RingFocusBroadcastCoordinator.setSurfaceActive(
                    context,
                    active = true,
                    completesHandoff = completesRingHandoff,
                )
                notifyListeners(surface)
                displaySurface(context, surface, null)
            }
            imageDecodeExecutor.execute {
                val decoded = ImageHudView.decodeRgb565(bytes, metadata)
                if (decoded == null) {
                    log("Image decode failed id=${surface.surfaceId} seq=${surface.seq}")
                    main.post { imageDecodeCoordinator.cancel(key) }
                    return@execute
                }
                main.post {
                    when (val completion = imageDecodeCoordinator.complete(key, decoded)) {
                        is ImageDecodeCompletion.Rejected -> completion.stale.recycleSafely()
                        is ImageDecodeCompletion.Accepted -> {
                            completion.replaced?.recycleSafely()
                            val current = active
                            val target = if (surface.isMedia) {
                                current?.takeIf {
                                    it.surfaceId == key.surfaceId &&
                                        it.contentKey == key.contentKey &&
                                        it.mediaArtworkMetadata?.sha256 == metadata.sha256
                                }
                            } else {
                                surface.takeIf { orderingCoordinator.isCurrentBase(baseOrder) }
                            }
                            if (target == null) {
                                imageDecodeCoordinator.invalidate(key.surfaceId)?.recycleSafely()
                                return@post
                            }
                            recycleActiveImageUnless(decoded)
                            val published = target.copy(imageBitmap = decoded)
                            notifyReplacedInk(published)
                            clearInkRenderer()
                            cancelBackFailsafeOnMain(target.surfaceId)
                            DisplayWakePolicy.requestWake(
                                context,
                                DisplayWakeKind.SURFACE,
                                requested = true,
                            )
                            prepareRingInputForSurface(target.surfaceId)
                            AssistantDisplayEpisode.accept(
                                context,
                                assistantEpisodeSurfacePresentedSignal(published.ownerPluginId),
                            )
                            active = published
                            RingFocusBroadcastCoordinator.setSurfaceActive(
                                context,
                                active = true,
                                completesHandoff = completesRingHandoff,
                            )
                            notifyListeners(published)
                            displaySurface(context, published, null)
                        }
                    }
                }
            }
        }
    }

    private fun displaySurface(context: Context, surface: NexusSurface, forcedPath: SurfaceDisplayPath?) {
        // The launcher overlay dismisses itself on the keys it claims, but
        // nothing makes it step aside for a surface arriving some other way
        // (a plugin's own gesture, a phone-triggered show like typed notes) —
        // seen on hardware sitting behind a closed editable card's activity,
        // stuck open from whenever it was last shown.
        LauncherOverlayRenderer.hide()
        // A paused MainActivity is the one other task this app leaves lying
        // around; Android resumes it on its own once this surface's own
        // activity-backed task closes, with nothing asked for it (seen on
        // hardware: the Nexus launcher screen reappearing right after a
        // typed reply sent).
        MainActivity.finishIfStale()
        val path = surfaceDisplayPath(surface, forcedPath ?: displayPath(context))
        if (surface.isInk) {
            if (!SurfaceOverlayRenderer.show(context, surface)) {
                log("Ink surface overlay unavailable")
                onInkRendererError(surface, emptyList())
            }
            return
        }
        when (path) {
            SurfaceDisplayPath.ACTIVITY -> showActivity(context, surface)
            SurfaceDisplayPath.OVERLAY -> {
                if (!SurfaceOverlayRenderer.show(context, surface)) {
                    log("Surface overlay unavailable; falling back to activity")
                    showActivity(context, surface)
                }
            }
        }
    }

    private fun isAnchorOnlyUpdate(payload: JSONObject): Boolean {
        val kind = payload.optString("kind")
        return when (kind) {
            NexusSurface.KIND_TIMED_LINES -> !payload.has("lines")
            NexusSurface.KIND_MEDIA ->
                payload.has("anchor") && !payload.has("mediaTitle") && !payload.has("artwork")
            else -> false
        }
    }

    private fun hideRemote(surfaceId: String, seq: Long) {
        if (surfaceId.isBlank()) return
        when (val decision = orderingCoordinator.onHide(surfaceId, seq)) {
            SurfaceOrderDecision.ApplyHide -> {
                val endReason = if (backFailsafeSurfaceId == surfaceId) {
                    DisplayHoldReleaseReason.WEARER_DISMISSED
                } else {
                    DisplayHoldReleaseReason.SESSION_CLOSED
                }
                val pending = pendingInk?.takeIf { it.surfaceId == surfaceId }
                cancelBackFailsafeOnMain(surfaceId)
                if (active?.surfaceId == surfaceId) {
                    if (active?.isInk == true) {
                        sendInkClosed(surfaceId, InkSurfaceContract.CLOSE_PLUGIN)
                    }
                    imageDecodeCoordinator.invalidate(surfaceId)?.recycleSafely()
                    hideLocalOnMain(endReason)
                } else if (pending != null) {
                    pendingInk = null
                    clearInkRenderer()
                    AssistantDisplayEpisode.accept(
                        null,
                        assistantEpisodeSurfaceEndedSignal(pending.ownerPluginId, endReason),
                    )
                }
            }
            is SurfaceOrderDecision.Drop -> logOrderDrop(surfaceId, seq, decision)
            else -> Unit
        }
    }

    private fun hideLocal(reason: DisplayHoldReleaseReason) {
        runOnMain { hideLocalOnMain(reason) }
    }

    private fun hideLocalOnMain(reason: DisplayHoldReleaseReason) {
        // Same reasoning as the show-side call in displaySurface: once this
        // surface's own activity-backed task is gone, Android falls back to
        // whatever task is next in line, and a paused MainActivity is the
        // only one this app ever leaves behind.
        MainActivity.finishIfStale()
        active?.let { ending ->
            AssistantDisplayEpisode.accept(
                null,
                assistantEpisodeSurfaceEndedSignal(ending.ownerPluginId, reason),
            )
        }
        val activeSurfaceId = active?.surfaceId
        if (pendingInk?.surfaceId == activeSurfaceId) pendingInk = null
        val returnToLauncher = activeSurfaceId?.let(launcherReturnCoordinator::consumeReturnOnHide) == true
        activeSurfaceId?.let { cancelBackFailsafeOnMain(it) }
        activeSurfaceId?.let(orderingCoordinator::deactivate)
        val coordinated = activeSurfaceId?.let(imageDecodeCoordinator::invalidate)
        coordinated?.recycleSafely()
        recycleActiveImageUnless(coordinated)
        clearInkRenderer()
        resetRingInputOnMain()
        active = null
        notifyListeners(null)
        SurfaceOverlayRenderer.hide()
        if (returnToLauncher) LauncherOverlayRenderer.show()
        RingFocusBroadcastCoordinator.setSurfaceInactive()
    }

    private fun deactivateReplacedSurface(surfaceId: String) {
        active?.surfaceId
            ?.takeIf { it != surfaceId }
            ?.let(orderingCoordinator::deactivate)
    }

    private fun notifyReplacedInk(next: NexusSurface) {
        if (next.isInk) {
            inkPresentationGate.retainForSurface(next.surfaceId, next.seq)
        } else {
            inkPresentationGate.cancel()
        }
        val previous = active?.takeIf(NexusSurface::isInk) ?: return
        if (
            next.isInk && next.surfaceId == previous.surfaceId &&
            next.ownerPluginId == previous.ownerPluginId
        ) {
            return
        }
        sendInkClosed(previous.surfaceId, InkSurfaceContract.CLOSE_REPLACED)
    }

    private fun ensureDisplayStateMonitoring(context: Context) {
        if (displayStateReceiverRegistered) return
        inkDisplayTransitioning =
            context.getSystemService(PowerManager::class.java)?.isInteractive != true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(displayStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(displayStateReceiver, filter)
        }
        displayStateReceiverRegistered = true
    }

    private fun clearInkRenderer() {
        pendingInk = null
        inkPresentationGate.cancel()
        inkRendererLayer.clear()
        if (inkFrameMeterRunning) {
            HudFrameMeter.stop()
            inkFrameMeterRunning = false
        }
    }

    private fun syncInkFrameMeter(surface: NexusSurface) {
        val enabled = surface.isInk && surface.ink?.debugFrameMeter == true
        if (!enabled && inkFrameMeterRunning) {
            HudFrameMeter.stop()
            inkFrameMeterRunning = false
        } else if (enabled && !inkFrameMeterRunning) {
            HudFrameMeter.start("ink-debug")
            inkFrameMeterRunning = true
        }
    }

    private fun onInkResyncNeeded(request: InkResyncRequest) {
        log(
            "Ink resync needed current=${request.currentDocumentId}@${request.currentRevision} " +
                "patch=${request.patchDocumentId}@${request.patchBaseRevision}",
        )
        val surfaceId = active?.takeIf(NexusSurface::isInk)?.surfaceId ?: return
        sendInkEvent(
            surfaceId = surfaceId,
            type = InkSurfaceContract.EVENT_RESYNC,
            extra = JSONObject()
                .put("documentId", request.currentDocumentId)
                .put("revision", request.currentRevision)
                .put("patchDocumentId", request.patchDocumentId)
                .put("patchBaseRevision", request.patchBaseRevision),
        )
        inkResyncListener?.invoke(request)
    }

    private fun onInkAction(actionId: String, dataset: Map<String, Any?>) {
        val surfaceId = active?.takeIf(NexusSurface::isInk)?.surfaceId ?: return
        sendInkEvent(
            surfaceId = surfaceId,
            type = InkSurfaceContract.EVENT_ACTION,
            extra = JSONObject()
                .put("actionId", actionId)
                .put("dataset", JSONObject(dataset)),
        )
    }

    private fun onInkRendererError(
        surface: NexusSurface,
        problems: List<com.anezium.rokidbus.ink.InkProblem>,
    ) {
        problems.forEach { problem ->
            log("Ink renderer error code=${problem.code} feature=${problem.feature.orEmpty()}")
        }
        if (
            pendingInk?.surfaceId == surface.surfaceId &&
            pendingInk?.seq == surface.seq
        ) {
            pendingInk = null
        }
        AssistantDisplayEpisode.accept(
            null,
            assistantEpisodeSurfaceEndedSignal(
                surface.ownerPluginId,
                DisplayHoldReleaseReason.RENDERER_ERROR,
            ),
        )
        sendInkClosed(surface.surfaceId, InkSurfaceContract.CLOSE_RENDERER_ERROR)
        if (active?.surfaceId == surface.surfaceId) {
            hideLocalOnMain(DisplayHoldReleaseReason.RENDERER_ERROR)
        }
    }

    private fun sendInkClosed(surfaceId: String, reason: String) {
        sendInkEvent(
            surfaceId = surfaceId,
            type = InkSurfaceContract.EVENT_CLOSED,
            extra = JSONObject().put("reason", reason),
        )
    }

    private fun sendInkEvent(
        surfaceId: String,
        type: String,
        extra: JSONObject = JSONObject(),
    ) {
        val payload = JSONObject(extra.toString())
            .put("surfaceId", surfaceId)
            .put("type", type)
        val error = GlassesHub.sendInkEvent(payload)
        if (error != null) log("Ink event send failed type=$type code=$error")
    }

    private fun JSONObject.toSurfaceOrder(): SurfaceOrder = SurfaceOrder(
        surfaceId = optString("surfaceId"),
        seq = optLong("seq", 0L),
        kind = optString("kind", NexusSurface.KIND_CARD).ifBlank { NexusSurface.KIND_CARD },
        contentKey = optString("contentKey"),
    )

    private fun NexusSurface.toSurfaceOrder(): SurfaceOrder = SurfaceOrder(
        surfaceId = surfaceId,
        seq = seq,
        kind = kind,
        contentKey = contentKey,
    )

    private fun logOrderDrop(
        surfaceId: String,
        seq: Long,
        decision: SurfaceOrderDecision.Drop,
    ) {
        val label = when (decision.reason) {
            SurfaceOrderDropReason.STALE_BASE -> "Surface stale base drop"
            SurfaceOrderDropReason.STALE_ANCHOR -> "Surface stale anchor drop"
            SurfaceOrderDropReason.STALE_HIDE -> "Surface stale hide drop"
        }
        log(
            "$label id=$surfaceId seq=$seq latestBase=${decision.latestBaseSeq} " +
                "latest=${decision.latestSeq}",
        )
    }

    private fun shouldSuppressDpadEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in DPAD_DIRECTION_KEYS) return false
        if (event.action == KeyEvent.ACTION_UP && suppressedDpadUps.remove(event.keyCode)) {
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val direction = inputDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)
        if (direction != null) return false
        suppressedDpadUps += event.keyCode
        return true
    }

    private fun resolveRingTaps() {
        applyRingResolution(ringInputPolicy.resolveExpired(SystemClock.uptimeMillis()))
    }

    private fun applyRingResolution(resolution: RingSurfaceInputPolicy.Resolution?) {
        when (resolution) {
            is RingSurfaceInputPolicy.Resolution.Forward -> {
                val readerDirection = active?.takeIf { it.isReader }?.let {
                    resolution.events.firstNotNullOfOrNull { event ->
                        readerScrollDirection(event.keyCode)
                    }
                }
                if (readerDirection != null) {
                    requestReaderScroll(readerDirection)
                } else {
                    // Route through the full key pipeline so the ink renderer's
                    // local-consumption hook sees ring input like any other key.
                    resolution.events.forEach { event ->
                        val now = SystemClock.uptimeMillis()
                        handleKeyEvent(KeyEvent(now, now, event.action, event.keyCode, 0))
                    }
                }
            }
            RingSurfaceInputPolicy.Resolution.Back -> {
                val surface = active ?: return
                forwardSurfaceInput(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
                handleBackDown(surface)
            }
            RingSurfaceInputPolicy.Resolution.Ignore,
            null,
            -> Unit
        }
    }

    private fun requestReaderScroll(direction: Int) {
        runOnMain {
            readerScrollListeners.forEach { listener ->
                runCatching { listener(direction) }
            }
        }
    }

    private fun readerScrollDirection(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        -> 1
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        -> -1
        else -> null
    }

    private fun handleBackDown(surface: NexusSurface) {
        if (surface.handlesBack) {
            armBackFailsafe(surface.surfaceId)
        } else {
            if (surface.isInk) sendInkClosed(surface.surfaceId, InkSurfaceContract.CLOSE_USER)
            if (surface.kind == NexusSurface.KIND_CARD && surface.editable != null) {
                forwardSurfaceText("", cancelled = true)
            }
            hideLocal(DisplayHoldReleaseReason.WEARER_DISMISSED)
        }
    }

    private fun prepareRingInputForSurface(surfaceId: String) {
        if (active?.surfaceId != surfaceId) resetRingInputOnMain()
    }

    private fun resetRingInputOnMain() {
        main.removeCallbacks(ringTapExpiry)
        ringInputPolicy.reset()
    }

    private fun armBackFailsafe(surfaceId: String) {
        runOnMain {
            cancelBackFailsafeOnMain()
            val runnable = Runnable {
                if (active?.surfaceId == surfaceId) {
                    if (active?.isInk == true) {
                        sendInkClosed(surfaceId, InkSurfaceContract.CLOSE_USER)
                    }
                    hideLocalOnMain(DisplayHoldReleaseReason.WEARER_DISMISSED)
                }
                if (backFailsafeSurfaceId == surfaceId) {
                    backFailsafeSurfaceId = null
                    backFailsafe = null
                }
            }
            backFailsafeSurfaceId = surfaceId
            backFailsafe = runnable
            main.postDelayed(runnable, BACK_FAILSAFE_MS)
        }
    }

    private fun cancelBackFailsafeOnMain(surfaceId: String? = null) {
        if (surfaceId != null && backFailsafeSurfaceId != surfaceId) return
        backFailsafe?.let { main.removeCallbacks(it) }
        backFailsafeSurfaceId = null
        backFailsafe = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post(action)
        }
    }

    private fun showActivity(context: Context, surface: NexusSurface) {
        SurfaceOverlayRenderer.hide()
        runCatching {
            context.startActivity(
                Intent(context, SurfaceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("surfaceId", surface.surfaceId),
            )
        }.onFailure {
            logError("SurfaceActivity start failed; trying overlay", it)
            SurfaceOverlayRenderer.show(context, surface)
        }
    }

    private fun notifyListeners(surface: NexusSurface?) {
        listeners.forEach { listener ->
            runCatching { listener(surface) }
        }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private fun recycleActiveImageUnless(kept: Bitmap?) {
        active?.imageBitmap?.takeUnless { it === kept }?.recycleSafely()
    }

    private val FORWARDED_KEYS = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    private val DPAD_DIRECTION_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
    )

    private val READER_SCROLL_KEYS = DPAD_DIRECTION_KEYS + setOf(
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    private val READER_FORWARDED_KEYS = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
    )
}
