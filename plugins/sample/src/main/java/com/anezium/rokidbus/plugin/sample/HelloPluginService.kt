package com.anezium.rokidbus.plugin.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityProgress
import com.anezium.rokidbus.client.plugin.NexusActivityTrack
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusInkCloseReason
import com.anezium.rokidbus.client.plugin.NexusInkProblem
import com.anezium.rokidbus.client.plugin.NexusInkSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeImage
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusPin
import com.anezium.rokidbus.client.plugin.NexusPinEmphasis
import com.anezium.rokidbus.client.plugin.NexusPinLine
import com.anezium.rokidbus.client.plugin.NexusPinSize
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONArray
import org.json.JSONObject

class HelloPluginService : NexusPluginService() {
    private fun log(message: String) = android.util.Log.i("ROKIDBUS", message)

    /**
     * Pushes the demo band after a delay, so a wake can be observed on a screen
     * that went dark on its own. Every other route into a notice starts with the
     * wearer tapping something, which lights the display before the notice can
     * ask for it — useless for judging what a notice does to a dark one.
     *
     *     adb shell am start-foreground-service \
     *       -n com.anezium.rokidbus.plugin.sample/.HelloPluginService \
     *       -a com.anezium.rokidbus.plugin.sample.DEMO_NOTICE --ei delayMs 12000
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DEMO_NOTICE) {
            val delayMs = intent.getIntExtra("delayMs", 10_000).toLong().coerceIn(0L, 120_000L)
            log("demo notice scheduled in ${delayMs}ms")
            Handler(Looper.getMainLooper()).postDelayed({
                log("demo notice push result=${nexusClient?.showNotice(DEMO_NOTICE_BAND)}")
            }, delayMs)
        }
        if (intent?.action == ACTION_DEMO_ACTIVITY) {
            val step = intent.getStringExtra("step")
            // An activity ends when its owner disconnects, so the demo holds the
            // process in the foreground for as long as its route runs. Promote
            // before anything else: the OS allows a started foreground service
            // only a few seconds.
            if (step == "end") releaseDemoForeground() else holdDemoForeground()
            demoActivityStep(step)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private var demoForeground = false

    private fun holdDemoForeground() {
        if (demoForeground) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(DEMO_CHANNEL_ID, "Demo route", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, DEMO_CHANNEL_ID)
            .setContentTitle("Sample demo route")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
        demoForeground = runCatching {
            startForeground(
                DEMO_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure { log("demo activity foreground refused: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    private fun releaseDemoForeground() {
        if (!demoForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        demoForeground = false
    }

    /**
     * Walks a scripted route through the activity tier, one step per intent, so
     * the fitted panel, badge, track and urgent band can be judged on hardware.
     * Steps: start, long, ride, stops, urgent, arrive, end.
     *
     *     adb shell am start-foreground-service \
     *       -n com.anezium.rokidbus.plugin.sample/.HelloPluginService \
     *       -a com.anezium.rokidbus.plugin.sample.DEMO_ACTIVITY --es step ride
     */
    private fun demoActivityStep(step: String?, attempt: Int = 0) {
        val client = nexusClient ?: run {
            log("demo activity step=$step: no client")
            return
        }
        // A service the intent just created has not finished registering yet.
        if (!client.supportsActivitySurface && attempt < DEMO_REGISTRATION_ATTEMPTS) {
            Handler(Looper.getMainLooper()).postDelayed({ demoActivityStep(step, attempt + 1) }, 250L)
            return
        }
        val result = when (step) {
            "start" -> client.startActivity(DEMO_ROUTE_WALK)
            "long" -> client.updateActivity(DEMO_ROUTE_LEAVE)
            "ride" -> client.updateActivity(DEMO_ROUTE_RIDE, significant = true)
            "stops" -> client.updateActivity(DEMO_ROUTE_RIDE_ON)
            "tostop" -> client.updateActivity(DEMO_ROUTE_TO_STOP)
            "urgent" -> client.updateActivity(DEMO_ROUTE_GET_OFF, significant = true, urgent = true)
            "arrive" -> client.updateActivity(DEMO_ROUTE_ARRIVED, significant = true)
            "end" -> client.endActivity()
            else -> null
        }
        log("demo activity step=$step result=$result extras=${client.supportsActivityExtras}")
    }

    private val state = HelloPluginState()
    private var surface: NexusSurfaceSession? = null
    private var inkSurface: NexusInkSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var speech: NexusSpeechSession? = null
    private var tts: NexusTtsSession? = null
    private var stopSpeechWhenStarted = false
    private var showingImage = false
    private var pinStep = PIN_HIDDEN
    private var showingInk = false
    private var showingBackgroundAudioControl = false
    private var backgroundAudioFrames = 0L
    private var backgroundAudioPinUpdatedAtMs = 0L
    private var inkRevision = 0
    private val speechCallbacks = object : NexusSpeechCallbacks {
        override fun onSpeechStarted(realtime: Boolean) {
            val currentSpeech = speech ?: return
            if (stopSpeechWhenStarted || state.mode != HelloPluginMode.DICTATION_LIVE) {
                currentSpeech.stop()
                return
            }
            if (state.onSpeechStarted(realtime)) render(show = false)
        }

        override fun onSpeechState(state: NexusSpeechState) {
            if (this@HelloPluginService.state.onSpeechState(state)) render(show = false)
        }

        override fun onSpeechPartial(text: String) {
            if (state.onSpeechPartial(text)) render(show = false)
        }

        override fun onSpeechFinal(text: String) {
            if (state.onSpeechFinal(text)) render(show = false)
        }

        override fun onSpeechStopped(
            reason: NexusSpeechStopReason,
            error: NexusSpeechError?,
        ) {
            speech = null
            stopSpeechWhenStarted = false
            if (state.onSpeechStopped(reason, error)) render(show = false)
        }
    }
    private val ttsCallbacks = object : NexusTtsCallbacks {
        override fun onTtsStarted(utteranceId: String) {
            log("TTS demo started")
        }

        override fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason) {
            log("TTS demo done reason=$reason")
        }
    }
    private val audioCallbacks = object : NexusAudioCallbacks {
        override fun onAudioStarted(format: NexusAudioFormat) {
            backgroundAudioFrames = 0L
            backgroundAudioPinUpdatedAtMs = SystemClock.elapsedRealtime()
            val pinResult = nexusClient?.showPin(BACKGROUND_AUDIO_PIN)
            if (pinResult != NexusSdkResult.SENT) log("Background audio pin refused: $pinResult")
            log("Background audio started ${format.sampleRate}Hz ${format.channels}ch ${format.encoding}")
            surface?.detach()
        }

        override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
            backgroundAudioFrames += 1
            val now = SystemClock.elapsedRealtime()
            if (now - backgroundAudioPinUpdatedAtMs >= 2_000L) {
                backgroundAudioPinUpdatedAtMs = now
                // Only live audio renews the pin, so a crashed or stalled session cannot leave it stuck.
                nexusClient?.showPin(BACKGROUND_AUDIO_PIN)
            }
        }

        override fun onAudioStopped(reason: NexusAudioStopReason) {
            audio = null
            nexusClient?.hidePin()
            log("Background audio stopped reason=$reason frames=$backgroundAudioFrames")
            Handler(Looper.getMainLooper()).post {
                // A final PLUGIN_CLOSE releases audio immediately before onNexusClose. Posting
                // avoids briefly re-showing the sample surface during that full-close boundary.
                if (isNexusSessionOpen && audio == null) {
                    showingBackgroundAudioControl = false
                    state.resetToMenu()
                    render(show = false)
                }
            }
        }
    }

    override fun onNexusOpen() {
        openDemoSurface()
    }

    override fun onNexusOpen(openType: String) {
        if (openType == PluginOpenTypes.RESUME && audio?.isActive == true) {
            state.resetToMenu()
            inkSurface = null
            showingInk = false
            surface = nexusSurfaceSession(SURFACE_ID)
            showBackgroundAudioCard(
                status = "Microphone active · $backgroundAudioFrames frames",
                footer = "tap to stop · back to detach",
            )
            return
        }
        openDemoSurface()
    }

    private fun openDemoSurface() {
        state.resetToMenu()
        if (speech != null) {
            stopSpeechWhenStarted = true
            speech?.stop()
        } else {
            stopSpeechWhenStarted = false
        }
        inkSurface = nexusInkSurfaceSession(INK_SURFACE_ID)
        inkRevision = 0
        showingInk = showInkDemo()
        if (showingInk) return
        surface = nexusSurfaceSession(SURFACE_ID)
        showingImage = showBundledImage()
        if (!showingImage) render(show = true)
    }

    override fun onNexusBackground() {
        surface = null
        inkSurface = null
        showingImage = false
        showingInk = false
        showingBackgroundAudioControl = false
    }

    override fun onNexusClose() {
        state.resetToMenu()
        stopSpeechWhenStarted = true
        speech?.stop()
        speech = null
        tts?.close()
        tts = null
        audio?.stop()
        audio = null
        nexusClient?.hidePin()
        stopSpeechWhenStarted = false
        inkSurface?.hide()
        inkSurface = null
        surface?.hide()
        surface = null
        showingImage = false
        showingInk = false
        showingBackgroundAudioControl = false
        backgroundAudioFrames = 0L
        pinStep = PIN_HIDDEN
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (showingInk) return
        if (showingBackgroundAudioControl) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> audio?.stop()
                KeyEvent.KEYCODE_BACK -> surface?.detach()
            }
            return
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (!state.move(1)) return
                showingImage = false
                render(show = false)
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> {
                if (!state.move(-1)) return
                showingImage = false
                render(show = false)
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                when (state.activate()) {
                    HelloPluginAction.RENDER -> cycleDemoHud()
                    HelloPluginAction.START_SPEECH -> startSpeech()
                    HelloPluginAction.START_BACKGROUND_AUDIO -> {
                        startBackgroundAudioDemo()
                        return
                    }
                    HelloPluginAction.SPEAK_TTS -> speakDemoLine()
                    HelloPluginAction.STOP_SPEECH -> {
                        stopSpeechWhenStarted = true
                        speech?.stop()
                        return
                    }
                    else -> return
                }
                showingImage = false
                render(show = false)
            }
            KeyEvent.KEYCODE_BACK -> handleBack()
            else -> return
        }
    }

    private fun handleBack() {
        when (state.back()) {
            HelloPluginAction.HIDE_SURFACE -> surface?.hide()
            HelloPluginAction.STOP_SPEECH_AND_SHOW_MENU -> {
                stopSpeechWhenStarted = true
                speech?.stop()
                showingImage = false
                render(show = false)
            }
            HelloPluginAction.SHOW_MENU -> {
                showingImage = false
                render(show = false)
            }
            else -> Unit
        }
    }

    private fun startSpeech() {
        stopSpeechWhenStarted = false
        val newSpeech = nexusSpeechSession(speechCallbacks)
        if (newSpeech == null) {
            state.onSpeechStartResult(null)
            return
        }
        speech = newSpeech
        val result = newSpeech.start()
        state.onSpeechStartResult(result)
        if (result != NexusSdkResult.SENT) {
            speech = null
        }
    }

    private fun speakDemoLine() {
        val session = tts ?: nexusTtsSession(ttsCallbacks)?.also { tts = it }
        val result = session?.speak("Hello from Rokid Nexus.")
        if (result != NexusSdkResult.SENT) log("TTS demo refused: $result")
    }

    private fun startBackgroundAudioDemo() {
        val client = nexusClient ?: return
        surface = surface ?: nexusSurfaceSession(SURFACE_ID)
        showingImage = false
        showBackgroundAudioCard(
            status = "Requesting the glasses microphone…",
            footer = "wait · back to cancel",
        )
        inkSurface?.hide()
        inkSurface = null
        showingInk = false

        if (!client.hasCapability(PluginCapability.MICROPHONE)) {
            showBackgroundAudioCard(
                status = "Grant Microphone in Nexus plugin access.",
                footer = "back",
            )
            return
        }
        if (audio != null) return
        val session = nexusAudioSession(audioCallbacks)
        audio = session
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            audio = null
            showBackgroundAudioCard(
                status = "Microphone unavailable: $result",
                footer = "back",
            )
        }
    }

    private fun showBackgroundAudioCard(status: String, footer: String) {
        showingBackgroundAudioControl = true
        val card = NexusCard(
            title = "Background microphone",
            lines = listOf(status, "A pin remains visible while the display may sleep."),
            footer = footer,
            contentKey = "hello-background-audio",
            handlesBack = true,
        )
        surface?.showCard(card)
    }

    private fun showBundledImage(): Boolean {
        if (nexusClient?.supportsImageSurface != true) return false
        val imageResource = resources.getIdentifier("image_surface_sample", "raw", packageName)
        if (imageResource == 0) return false
        val bytes = resources.openRawResource(imageResource).use { it.readBytes() }
        val image = NexusImage(
            contentKey = "sample-tree-v1",
            mimeType = ImageSurfaceContract.MIME_JPEG,
            pixelWidth = 480,
            pixelHeight = 480,
            title = "Hello Nexus image",
            caption = "Bundled JPEG over the SPP data plane",
            footer = "swipe for card demo · back",
            handlesBack = true,
        )
        return surface?.showImage(image, bytes) == NexusSdkResult.SENT
    }

    private fun render(show: Boolean) {
        val presentation = state.presentation()
        val card = NexusCard(
            title = presentation.title,
            lines = presentation.lines,
            footer = presentation.footer,
            contentKey = presentation.contentKey,
            handlesBack = presentation.handlesBack,
        )
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    override fun onNexusInkReady(surfaceId: String) {
        log("Ink demo ready surface=$surfaceId")
    }

    override fun onNexusInkAction(surfaceId: String, actionId: String, dataset: JSONObject) {
        if (surfaceId != INK_SURFACE_ID) return
        if (actionId == INK_BACKGROUND_AUDIO_ACTION) {
            startBackgroundAudioDemo()
            return
        }
        if (actionId != INK_REFRESH_ACTION) return
        inkRevision += 1
        val next = 72 + inkRevision * 3
        val result = inkSurface?.update(
            JSONObject()
                .put("metrics[0].value", next.toString())
                .put("metrics[2].value", inkRevision.toString())
                .put("rows[0].value", "updated $inkRevision")
                .put("chartPoints", demoChartPoints(inkRevision)),
        )
        log("Ink demo action source=${dataset.optString("source")} result=$result")
    }

    override fun onNexusInkClosed(surfaceId: String, reason: NexusInkCloseReason) {
        if (surfaceId == INK_SURFACE_ID) showingInk = false
        log("Ink demo closed surface=$surfaceId reason=$reason")
    }

    override fun onNexusInkError(surfaceId: String, problems: List<NexusInkProblem>) {
        log(
            "Ink demo error surface=$surfaceId " +
                problems.joinToString { problem -> "${problem.code}:${problem.message}" },
        )
    }

    private fun showInkDemo(): Boolean {
        if (nexusClient?.supportsInkSurface != true) return false
        val data = JSONObject()
            .put("title", "Ink Surface")
            .put(
                "metrics",
                JSONArray()
                    .put(JSONObject().put("label", "SYNC").put("value", "72"))
                    .put(JSONObject().put("label", "LINK").put("value", "SPP"))
                    .put(JSONObject().put("label", "REV").put("value", "0")),
            )
            .put(
                "rows",
                JSONArray()
                    .put(JSONObject().put("name", "Phone compile").put("value", "ready"))
                    .put(JSONObject().put("name", "Glasses render").put("value", "native"))
                    .put(JSONObject().put("name", "Tap action").put("value", "update")),
            )
            .put("chartPoints", demoChartPoints(0))
        return inkSurface?.show(
            page = INK_DEMO_PAGE,
            data = data,
            handlesBack = false,
        ) == NexusSdkResult.SENT
    }

    /**
     * The answer to a tap on a band that offers no choice. The demo notice
     * below carries actions, so this is here as the reference for the simpler
     * shape: one gesture, no row.
     */
    override fun onNexusNoticeInput(event: NexusInputEvent) {
        nexusClient?.updateNotice(NexusNoticeUpdate(footer = "Answered. Back to dismiss"))
    }

    /**
     * The wearer picked one of the band's answers, with no surface involved.
     * This is what the notice tier is for: until now a plugin could only be
     * reached while it held a screen open, and only ever with one answer.
     */
    override fun onNexusNoticeAction(id: String) {
        val label = DEMO_NOTICE_BAND.actions.firstOrNull { it.id == id }?.label ?: id
        nexusClient?.updateNotice(NexusNoticeUpdate(footer = "$label · Back to dismiss"))
    }

    /**
     * Walks the ambient HUD tiers: both pin sizes, then a notice, then nothing.
     * The notice needs no hide step of its own -- it expires on its own deadline,
     * which is the difference between the two tiers in one gesture.
     */
    /**
     * A brightness ramp, a frame and a disc, drawn rather than shipped as an
     * asset so the sample stays one file. The ramp is the useful part: on
     * additive monochrome optics it shows immediately which levels reach the
     * eye and which ones disappear into the background.
     */
    private fun demoImageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(
            DEMO_IMAGE_WIDTH,
            DEMO_IMAGE_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val steps = 8
        val stepWidth = DEMO_IMAGE_WIDTH / steps.toFloat()
        for (step in 0 until steps) {
            val level = 255 * (step + 1) / steps
            paint.color = Color.rgb(level, level, level)
            canvas.drawRect(
                step * stepWidth,
                0f,
                (step + 1) * stepWidth,
                DEMO_IMAGE_HEIGHT * 0.55f,
                paint,
            )
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(
            1.5f,
            1.5f,
            DEMO_IMAGE_WIDTH - 1.5f,
            DEMO_IMAGE_HEIGHT - 1.5f,
            paint,
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(
            DEMO_IMAGE_WIDTH * 0.5f,
            DEMO_IMAGE_HEIGHT * 0.78f,
            DEMO_IMAGE_HEIGHT * 0.16f,
            paint,
        )
        val encoded = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, encoded)
        bitmap.recycle()
        return encoded.toByteArray()
    }

    private fun cycleDemoHud() {
        val client = nexusClient ?: return
        val next = (pinStep + 1) % 6
        val result = when (next) {
            PIN_SMALL -> client.showPin(SMALL_PIN)
            PIN_MEDIUM -> client.showPin(MEDIUM_PIN)
            DEMO_NOTICE -> {
                client.hidePin()
                client.showNotice(DEMO_NOTICE_BAND)
            }
            DEMO_NOTICE_PAGED -> client.showNotice(DEMO_NOTICE_LONG)
            DEMO_NOTICE_IMAGE -> client.showNotice(DEMO_NOTICE_PICTURE, demoImageBytes())
            else -> client.hidePin()
        }
        if (result != NexusSdkResult.SENT) log("HUD demo step $next refused: $result")
        // Advance whatever happened. A step that could not go out leaves nothing on
        // screen, and a reference plugin that wedges on it is worse than one that
        // moves to the next thing on the next press.
        pinStep = next
    }

    private companion object {
        const val ACTION_DEMO_NOTICE = "com.anezium.rokidbus.plugin.sample.DEMO_NOTICE"
        const val ACTION_DEMO_ACTIVITY = "com.anezium.rokidbus.plugin.sample.DEMO_ACTIVITY"
        const val DEMO_CHANNEL_ID = "demo_route"
        const val DEMO_NOTIFICATION_ID = 7302
        const val DEMO_REGISTRATION_ATTEMPTS = 20

        val DEMO_ROUTE_WALK = NexusActivity(
            glyph = "turn-right",
            primary = "120 m",
            secondary = "Rue de Rivoli",
            progress = NexusActivityProgress.Percent(8),
            eta = "12:24",
            detail = listOf("then the Chatelet stop"),
            maxDurationMs = 30 * 60 * 1000L,
            wakeDisplay = true,
        )

        // Twelve characters next to an ETA: fitted, never "Dep...".
        val DEMO_ROUTE_LEAVE = DEMO_ROUTE_WALK.copy(
            glyph = "walk",
            primary = "Depart 3 min",
            secondary = "Bus 38 at 12:09",
            progress = null,
            detail = listOf("4 min on foot to Chatelet"),
        )

        // Progress stays alongside the track for glasses without extras.
        val DEMO_ROUTE_RIDE = DEMO_ROUTE_WALK.copy(
            glyph = "bus",
            badge = "38",
            primary = "3 stops",
            secondary = "Get off at Luxembourg",
            progress = NexusActivityProgress.Percent(55),
            track = NexusActivityTrack(count = 5, at = 2, target = 4, label = "Luxembourg"),
            detail = listOf("towards Porte d'Orleans"),
        )

        // A quiet update: the panel, not a flare, with the badge and the track.
        /** A walk to the stop, timed and measured: "3 min - 250 m", folded "250 m" under "3 min". */
        val DEMO_ROUTE_TO_STOP = DEMO_ROUTE_WALK.copy(
            glyph = "walk",
            primary = "3 min",
            measure = "250 m",
            secondary = "Porte d'Orleans - Leclerc",
            progress = null,
            detail = listOf("Departs at 12:09"),
        )

        val DEMO_ROUTE_RIDE_ON = DEMO_ROUTE_RIDE.copy(
            primary = "2 stops",
            progress = NexusActivityProgress.Percent(68),
            track = NexusActivityTrack(count = 5, at = 3, target = 4, label = "Luxembourg"),
        )

        val DEMO_ROUTE_GET_OFF = DEMO_ROUTE_RIDE.copy(
            primary = "Get off",
            secondary = "Next stop: Luxembourg",
            progress = NexusActivityProgress.Percent(80),
            track = NexusActivityTrack(count = 5, at = 3, target = 4, label = "Luxembourg"),
        )

        val DEMO_ROUTE_ARRIVED = DEMO_ROUTE_WALK.copy(
            glyph = "arrive",
            primary = "Arrived",
            secondary = "Pantheon",
            progress = NexusActivityProgress.Percent(100),
            detail = emptyList(),
        )
        const val SURFACE_ID = "main"
        const val INK_SURFACE_ID = "ink-demo"
        const val INK_REFRESH_ACTION = "refreshMetrics"
        const val INK_BACKGROUND_AUDIO_ACTION = "startBackgroundAudio"
        const val PIN_HIDDEN = 0
        const val PIN_SMALL = 1
        const val PIN_MEDIUM = 2
        const val DEMO_NOTICE = 3
        const val DEMO_NOTICE_PAGED = 4
        const val DEMO_NOTICE_IMAGE = 5

        val INK_DEMO_PAGE = """
            <script type="application/json" def>{"data":{}}</script>
            <page>
              <view class="page">
                <text class="title">{{ title }}</text>
                <view class="metrics">
                  <view class="metric" wx:for="{{ metrics }}" wx:key="label">
                    <text class="metric-label">{{ item.label }}</text>
                    <text class="metric-value">{{ item.value }}</text>
                  </view>
                </view>
                <chart class="live-chart" type="line" series="value" data="{{ chartPoints }}"
                  animate="true" smooth="true" show-average="true" />
                <view class="action" bindtap="startBackgroundAudio" data-source="sample">
                  <text>Start background mic</text>
                </view>
                <view class="action" bindtap="refreshMetrics" data-source="sample">
                  <text>Tap to update</text>
                </view>
                <view class="rows">
                  <view class="row" wx:for="{{ rows }}" wx:key="name">
                    <text>{{ item.name }}</text>
                    <text class="row-value">{{ item.value }}</text>
                  </view>
                </view>
              </view>
            </page>
            <style>
              .page { display: flex; flex-direction: column; padding: 24rpx; gap: 18rpx; }
              .title { font-size: 44rpx; font-weight: 700; }
              .metrics { display: flex; flex-direction: row; gap: 12rpx; }
              .metric { display: flex; flex-direction: column; flex-grow: 1; border-width: 1rpx; padding: 12rpx; }
              .metric-label { font-size: 20rpx; opacity: 0.65; }
              .metric-value { font-size: 34rpx; font-weight: 700; }
              .live-chart { width: 100%; height: 160rpx; }
              .action { border-width: 2rpx; padding: 14rpx; }
              .rows { display: flex; flex-direction: column; gap: 8rpx; }
              .row { display: flex; flex-direction: row; justify-content: space-between; }
              .row-value { opacity: 0.7; }
            </style>
        """.trimIndent()

        private val CHART_VALUES = intArrayOf(48, 55, 51, 62, 58, 68, 72, 66, 76, 73, 82, 78)

        private fun demoChartPoints(revision: Int): JSONArray = JSONArray().also { points ->
            repeat(7) { index ->
                val sourceIndex = (revision + index) % CHART_VALUES.size
                points.put(
                    JSONObject()
                        .put("label", "T${revision + index}")
                        .put("value", CHART_VALUES[sourceIndex]),
                )
            }
        }

        /**
         * A band with more to say than one page holds. It offers no actions on
         * purpose: a notice is paged or it is answerable, never both, so this is
         * also the demo of what forward and backward mean when nothing is being
         * asked. The glasses decide how many pages this is -- they are the only
         * side that knows how wide a line runs.
         */
        val DEMO_NOTICE_LONG = NexusNotice(
            title = "Long notice",
            body = "This band carries more text than a single page can hold, which " +
                "is the whole point of it: a relayed message is as long as whoever " +
                "wrote it decided, and a tier that only ever showed the first four " +
                "lines was not relaying anything. Scroll forward to turn the page " +
                "and backward to go back. The first turn ends the countdown, so " +
                "the band now waits on the reader instead of on a deadline, and " +
                "leaves on its own once the gestures stop. Nothing here scrolls: " +
                "each page replaces the one before it, because a HUD that scrolls " +
                "asks the wearer to aim at something while walking.",
            footer = "Scroll to turn pages",
        )

        /**
         * The picture rides in the same message as the text, so the band cannot
         * appear before its image. Deliberately a test card rather than a photo:
         * the optics are additive and monochrome, and a brightness ramp shows
         * what actually survives that far better than a nice picture does.
         */
        val DEMO_NOTICE_PICTURE = NexusNotice(
            title = "Notice with an image",
            body = "The picture and this text arrived as one message.",
            footer = "Back to dismiss",
            image = NexusNoticeImage(
                contentKey = "sample-test-card-1",
                mimeType = ImageSurfaceContract.MIME_JPEG,
                pixelWidth = DEMO_IMAGE_WIDTH,
                pixelHeight = DEMO_IMAGE_HEIGHT,
            ),
        )

        const val DEMO_IMAGE_WIDTH = 480
        const val DEMO_IMAGE_HEIGHT = 160

        /**
         * A band that asks something. Scroll steps along the answers, tap fires
         * the selected one, and Back still dismisses the whole thing without
         * the plugin ever hearing about it.
         *
         * No `interactive` flag: carrying actions is already asking for an
         * answer. The full TTL, because a band worth choosing from is a band
         * worth being able to read first.
         */
        val DEMO_NOTICE_BAND = NexusNotice(
            title = "Nexus notice",
            body = "A band that arrives, asks one thing, and leaves on its own deadline.",
            footer = "Scroll to choose · Back to dismiss",
            actions = listOf(
                NexusNoticeAction(id = "reply", glyph = "phone", label = "Reply"),
                NexusNoticeAction(id = "later", glyph = "timer", label = "Later"),
                NexusNoticeAction(id = "ignore", glyph = "stop", label = "Ignore"),
            ),
            ttlMs = NoticeSurfaceContract.MAX_TTL_MS,
            // The demo band is the one notice a wearer asks for deliberately, so
            // it is also the honest place to show what the opt-in does on a dark
            // display. The hub still owns whether it is granted.
            wakeDisplay = true,
        )

        /** No `ttlMs` of its own, so it takes the hub's 30-minute default. */
        val SMALL_PIN = NexusPin(
            title = "NEXUS PIN",
            lines = listOf("sample overlay"),
        )

        val BACKGROUND_AUDIO_PIN = NexusPin(
            title = "MIC ON",
            lines = listOf("Stop on phone"),
            ttlMs = 5_000L,
        )

        /**
         * Shows the three emphasis levels, and carries a short `ttlMs` so the demo
         * also exercises self-expiry — the only thing bounding a pin whose owner
         * pushed it and went dormant without ever coming back to hide it.
         */
        val MEDIUM_PIN = NexusPin(
            title = "NEXUS PIN · MEDIUM",
            size = NexusPinSize.MEDIUM,
            ttlMs = 20_000L,
            richLines = listOf(
                NexusPinLine("bright headline row", NexusPinEmphasis.BRIGHT),
                NexusPinLine("default body row"),
                NexusPinLine("clears itself in 20s", NexusPinEmphasis.DIM),
            ),
        )
    }
}
