package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.MediaArtworkContract
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object SurfaceController {
    private const val PREFS = "surface_renderer"
    private const val PREF_DISPLAY_PATH = "display_path"
    private const val BACK_FAILSAFE_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private val orderingCoordinator = SurfaceOrderingCoordinator<JSONObject>()
    private val listeners = CopyOnWriteArrayList<(NexusSurface?) -> Unit>()
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
    @Volatile private var active: NexusSurface? = null

    fun activeSurface(): NexusSurface? = active

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
        if (carriesImage && !(surface.isMedia && surface.imageBitmap != null)) {
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
        if (shouldSuppressDpadEvent(event)) {
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
        if (active == null) return false
        applyRingResolution(ringInputPolicy.onKeyDown(keyCode, eventTimeMs))
        if (keyCode == RingSurfaceInputPolicy.RING_KEYCODE_TAP) {
            main.removeCallbacks(ringTapExpiry)
            main.postDelayed(ringTapExpiry, RingTapPolicy.DEFAULT_WINDOW_MS + 1L)
        }
        return true
    }

    fun cancelRingInput() {
        runOnMain(::resetRingInputOnMain)
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

    private fun showOrUpdate(
        context: Context,
        surface: NexusSurface,
        forcedPath: SurfaceDisplayPath? = null,
        launcherShow: Boolean = false,
    ) {
        val completesRingHandoff =
            launcherShow && launcherReturnCoordinator.onSurfaceShown(surface.surfaceId)
        runOnMain {
            val keepMediaDecode = surface.isMedia && surface.mediaArtworkMetadata != null &&
                imageDecodeCoordinator.isCurrent(surface.surfaceId, surface.contentKey)
            val coordinated = if (keepMediaDecode) null else imageDecodeCoordinator.invalidate()
            coordinated?.recycleSafely()
            recycleActiveImageUnless(surface.imageBitmap ?: coordinated)
            cancelBackFailsafeOnMain(surface.surfaceId)
            wakeScreen(context)
            deactivateReplacedSurface(surface.surfaceId)
            prepareRingInputForSurface(surface.surfaceId)
            active = surface
            RingFocusBroadcastCoordinator.setSurfaceActive(
                context,
                active = true,
                completesHandoff = completesRingHandoff,
            )
            notifyListeners(surface)
            displaySurface(context, surface, forcedPath)
        }
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
                recycleActiveImageUnless(surface.imageBitmap)
                cancelBackFailsafeOnMain(surface.surfaceId)
                wakeScreen(context)
                deactivateReplacedSurface(surface.surfaceId)
                prepareRingInputForSurface(surface.surfaceId)
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
                            cancelBackFailsafeOnMain(target.surfaceId)
                            wakeScreen(context)
                            prepareRingInputForSurface(target.surfaceId)
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
        when (forcedPath ?: displayPath(context)) {
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
                cancelBackFailsafeOnMain(surfaceId)
                if (active?.surfaceId == surfaceId) {
                    imageDecodeCoordinator.invalidate(surfaceId)?.recycleSafely()
                    hideLocalOnMain()
                }
            }
            is SurfaceOrderDecision.Drop -> logOrderDrop(surfaceId, seq, decision)
            else -> Unit
        }
    }

    private fun hideLocal() {
        runOnMain { hideLocalOnMain() }
    }

    private fun hideLocalOnMain() {
        val activeSurfaceId = active?.surfaceId
        val returnToLauncher = activeSurfaceId?.let(launcherReturnCoordinator::consumeReturnOnHide) == true
        activeSurfaceId?.let { cancelBackFailsafeOnMain(it) }
        activeSurfaceId?.let(orderingCoordinator::deactivate)
        val coordinated = activeSurfaceId?.let(imageDecodeCoordinator::invalidate)
        coordinated?.recycleSafely()
        recycleActiveImageUnless(coordinated)
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
            is RingSurfaceInputPolicy.Resolution.Forward ->
                resolution.events.forEach { event ->
                    forwardSurfaceInput(event.keyCode, event.action)
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

    private fun handleBackDown(surface: NexusSurface) {
        if (surface.handlesBack) {
            armBackFailsafe(surface.surfaceId)
        } else {
            hideLocal()
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
                    hideLocalOnMain()
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

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rokidbus:surface-wake",
            ).acquire(WAKE_MS)
        }.onFailure { logError("Surface screen wake failed", it) }
    }

    private const val WAKE_MS = 3_000L

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
}
