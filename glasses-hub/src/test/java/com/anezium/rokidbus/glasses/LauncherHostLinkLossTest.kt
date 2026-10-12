package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.glasses.session.DraftState
import com.anezium.rokidbus.glasses.session.FrameStatus
import com.anezium.rokidbus.glasses.session.NoticeEntry
import com.anezium.rokidbus.glasses.session.NoticePreview
import com.anezium.rokidbus.glasses.session.RootStopKind
import com.anezium.rokidbus.glasses.session.RootStops
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionFocus
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionRunner
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ComposeContract
import com.anezium.rokidbus.shared.ComposeDictateFinal
import com.anezium.rokidbus.shared.ComposeDictationReason
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageActionTypeContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.QuickActionPolicyContract
import com.anezium.rokidbus.shared.RouteManeuver
import com.anezium.rokidbus.shared.RoutePositionQuality
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppPairingKeyStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The launcher backend and the open session across a real phone outage, through the host itself:
 * the accessibility runtime's NexusInput and NexusSession, GlassesHub's capability ingress and link
 * callbacks, an authenticated SPP session that really closes, and CXR's state callback. Run under
 * the prospective SESSION bundle default, the one the acceptance flip ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherHostLinkLossTest {
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: OverlayService
    private val key = ByteArray(32) { (it * 7).toByte() }
    private var sppClock = 10_000L
    private val workers = Executors.newCachedThreadPool()
    private val sppStates = CopyOnWriteArrayList<Boolean>()
    private val spp = AuthenticatedSppServer(
        MemoryKeys(key),
        execute = { workers.execute(it) },
        schedule = { _, _ -> {} },
        nowMs = { sppClock },
        onConnected = { up ->
            GlassesHub.onSppConnected(up)
            sppStates += up
        },
        onEnvelope = GlassesHub::onRemoteEnvelope,
        log = ::log,
    )
    private var phone: PhoneEnd? = null
    private var collection: List<ActivityEntry> = emptyList()
    private var unobserve: (() -> Unit)? = null

    @Before
    fun setUp() {
        staticField(SppServerManager::class.java, "sessions").set(null, spp)
        staticField(GlassesHub::class.java, "appContext").set(null, app)
        service = Robolectric.setupService(OverlayService::class.java)
        freshSessionModel()
        NexusInput.restartProcess(LauncherBackend.SESSION)
        // The accessibility service's own order: the session, the activity tier, then the input.
        NexusSession.onServiceConnected(service)
        ActivityController.onServiceConnected(app)
        NexusInput.onServiceConnected(service)
        unobserve = ActivityController.observe { collection = it.collection }
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(
                path = BusPaths.LAUNCHER_LIST,
                payload = JSONObject().put(
                    "plugins",
                    JSONArray()
                        .put(JSONObject().put("id", "relay").put("displayName", "Relay").put("pageSessionVersion", 1))
                        .put(JSONObject().put("id", "maps").put("displayName", "Maps").put("pageSessionVersion", 1)),
                ),
            ),
        )
        idle()
    }

    @After
    fun tearDown() {
        NexusSession.dispatch(SessionEvent.Abort)
        idle()
        // The runtime is process-wide: no draft or notice of this test reaches the next one.
        freshSessionModel()
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(path = BusPaths.ACTIVITY_END, payload = ActivitySurfaceContract.emptySlotAssertPayload(SEQ.incrementAndGet())),
        )
        if (!GlassesHub.isPhoneConnected()) cxrUp(true)
        announce(oldPhone())
        phone?.close()
        cxrUp(false)
        awaitIdle { !SppServerManager.isConnected() && !GlassesHub.isPhoneConnected() }
        unobserve?.invoke()
        ActivityController.onServiceDestroyed()
        NexusSession.onServiceDestroyed()
        NexusInput.onServiceDestroyed()
        NexusInput.restartProcess(LauncherBackend.BUNDLE_DEFAULT)
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.LAUNCHER_LIST, payload = JSONObject().put("plugins", JSONArray())))
        idle()
        staticField(SppServerManager::class.java, "sessions").set(null, null)
        workers.shutdownNow()
        workers.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun `an outage in either order keeps the open conversation and its draft, and its return replays nothing`() {
        linkUp()
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)
        openConversation()
        dictate("see you at eight")
        val draft = model().draft
        assertEquals(DraftState.READY, draft?.state)
        assertEquals("see you at eight", draft?.text)
        assertEquals("Ana", draft?.recipient)

        outage(sppFirst = true)
        outage(sppFirst = false)

        // After both outages the same draft is still the one offered, never sent on its own.
        assertEquals("relay:thread", top().pageId)
        assertEquals(draft, model().draft)
    }

    @Test
    fun `an outage in either order keeps an open route page over a retained draft`() {
        linkUp()
        // Before the session opens: an open root's membership is frozen.
        startRoute()
        openConversation()
        dictate("on my way")
        val draft = model().draft
        backToRoot()
        openRouteFromRoot()
        assertEquals("maps:route", top().pageId)

        outage(sppFirst = false)
        outage(sppFirst = true)

        assertEquals("maps:route", top().pageId)
        assertEquals(draft, model().draft)
    }

    @Test
    fun `an offline boot keeps the announced launcher and an older phone's fresh announcement returns to legacy`() {
        // Nothing announced yet: the prospective default is never assumed.
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
        linkUp()
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)
        linkDown(sppFirst = true)
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)

        // A new process with no phone: the persisted announcement decides before any link.
        NexusSession.onServiceDestroyed()
        NexusInput.onServiceDestroyed()
        NexusInput.restartProcess(LauncherBackend.SESSION)
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
        NexusSession.onServiceConnected(service)
        NexusInput.onServiceConnected(service)
        idle()
        assertFalse(GlassesHub.isPhoneConnected())
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)

        // An older phone connects and announces nothing of the bundle, through the same ingress.
        cxrUp(true)
        connectSpp()
        announce(oldPhone())
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
        assertEquals(0, GlassesHub.launcherInteractiveVersion())
        assertFalse(GlassesHub.launcherInteractiveActive())
        assertFalse(GlassesHub.pageSessionSupported())
        assertEquals(LauncherBackend.LEGACY, rebootedBackend())
    }

    @Test
    fun `an outage keeps five activities and an older phone's all-default announcement trims them to two`() {
        linkUp()
        val owners = listOf("alpha", "bravo", "charlie", "delta", "echo")
        owners.forEach(::startActivity)
        assertEquals(owners.map { "$it:${ActivitySurfaceContract.LOCAL_SURFACE_ID}" }.toSet(), surfaceIds())

        linkDown(sppFirst = true)
        assertEquals(5, collection.size)
        // The same phone comes back and re-announces the bundle: nothing is trimmed.
        linkUp()
        assertEquals(5, collection.size)

        linkDown(sppFirst = false)
        assertEquals(5, collection.size)
        // An older phone: its announcement equals the capabilities the loss cleared to.
        cxrUp(true)
        connectSpp()
        announce(oldPhone())
        assertEquals(setOf("delta:activity", "echo:activity"), surfaceIds())
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
    }

    // The outage and its return.

    /**
     * Both transports go, in the order given, and come back; the phone then announces the same
     * bundle again, as it does on every reconnect. Nothing about the session may move.
     */
    private fun outage(sppFirst: Boolean) {
        val generation = NexusSession.generation
        val before = NexusSession.state as SessionState.InPage
        val draft = model().draft
        val from = logs().size

        linkDown(sppFirst)
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)
        assertEquals(generation, NexusSession.generation)
        val during = NexusSession.state as SessionState.InPage
        assertEquals(shape(before.root), shape(during.root))
        assertEquals(before.frames.map { it.pageId }, during.frames.map { it.pageId })
        assertEquals(draft, model().draft)
        assertTrue(model().linkLost)

        linkUp()
        val after = NexusSession.state as SessionState.InPage
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)
        assertEquals(generation, NexusSession.generation)
        assertEquals(shape(before.root), shape(after.root))
        assertEquals(before.frames.map { it.pageId }, after.frames.map { it.pageId })
        assertTrue(after.frames.none { it.status is FrameStatus.Loading })
        assertEquals(draft, model().draft)
        assertNull(model().capture)
        assertNull(model().pendingSend)
        assertFalse(model().linkLost)

        val since = logs().drop(from)
        assertTrue(since.joinToString("\n"), since.none { it.startsWith("Launcher backend=") })
        assertTrue(since.joinToString("\n"), outbound(since).none { it in REPLAYABLE || it.startsWith(COMPOSE_PREFIX) })
    }

    private fun linkUp() {
        cxrUp(true)
        connectSpp()
        announce(bundlePhone())
    }

    private fun linkDown(sppFirst: Boolean) {
        if (sppFirst) {
            closeSpp()
            assertTrue(GlassesHub.isPhoneConnected())
            cxrUp(false)
        } else {
            cxrUp(false)
            // SPP alone still carries the phone.
            assertTrue(GlassesHub.isPhoneConnected())
            closeSpp()
        }
        assertFalse(GlassesHub.isPhoneConnected())
        assertEquals(0, GlassesHub.launcherInteractiveVersion())
    }

    private fun cxrUp(up: Boolean) {
        staticField(CxrBusBridge::class.java, "connected").setBoolean(null, up)
        GlassesHub.onCxrState(up)
        idle()
    }

    /** The phone authenticates a fresh SPP session; the server's own callback reports it to the hub. */
    private fun connectSpp() {
        sppClock += 5_000
        val count = sppStates.size
        val peer = PhoneEnd().also { phone = it }
        spp.accept(peer)
        val session = SppAuthProtocol.connect(peer.phoneInput, peer.phoneOutput, key, SppAuthProtocol.RecentNonces())
        // Whatever the glasses send is read off the wire like the phone would.
        workers.execute {
            runCatching { while (session.read(peer.phoneInput) != null) Unit }
        }
        awaitIdle { sppStates.size > count && sppStates.last() }
        assertTrue(SppServerManager.isConnected())
    }

    /** The phone's end of the socket closes; the server notices and reports the loss itself. */
    private fun closeSpp() {
        val count = sppStates.size
        phone?.close()
        phone = null
        awaitIdle { sppStates.size > count && !sppStates.last() }
        assertFalse(SppServerManager.isConnected())
    }

    private fun announce(payload: JSONObject) {
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.HUB_CAPABILITIES, payload = payload))
        idle()
    }

    private fun bundlePhone(): JSONObject = PhoneHubCapabilitiesContract.toJson(
        PhoneHubCapabilitiesContract.create(
            features = 0,
            cameraConsumerName = null,
            pageSessionVersion = PageSurfaceContract.VERSION,
            launcherInteractiveVersion = LauncherInteractiveContract.VERSION,
        ),
    )

    /** A phone from before pages and the bundle: no features, versions, backend or display settings. */
    private fun oldPhone(): JSONObject =
        PhoneHubCapabilitiesContract.toJson(PhoneHubCapabilitiesContract.create(features = 0, cameraConsumerName = null))

    // The session's content.

    private fun openConversation() {
        NexusSession.dispatch(
            SessionEvent.NoticeArrived(
                NoticePreview(RootStops.NOTICE_PAGE_ID, "Ana: on est devant"),
                NoticeEntry(
                    ownerPluginId = "relay",
                    sourceLabel = "Relay",
                    title = "Ana",
                    body = "on est devant",
                    atWallMs = System.currentTimeMillis(),
                    destination = PageDestination("relay:thread", JSONObject().put("thread", "t1").toString()),
                    eventKey = "t1",
                    conversation = true,
                ),
            ),
        )
        openRoot()
        selectRootStop { it.kind == RootStopKind.NOTICE_PREVIEW }
        val from = logs().size
        freshPress()
        assertEquals("relay:thread", top().pageId)
        // The outbound record sees what a replay would be: the request went out over SPP.
        assertEquals(listOf(BusPaths.PAGE_REQUEST), outbound(logs().drop(from)).filter { it in REPLAYABLE })
        answer(conversation(), title = "Ana")
    }

    /** Reading → Dictate (first) → a fresh press records → a press stops → the phone's final text. */
    private fun dictate(text: String) {
        freshPress()
        freshPress()
        val capture = requireNotNull(model().capture) { "no capture started" }
        assertTrue(outbound(logs()).contains(ComposeContract.DICTATE_START_PATH))
        freshPress()
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(
                path = ComposeContract.DICTATE_FINAL_PATH,
                payload = ComposeContract.dictateFinal(ComposeDictateFinal(capture.captureId, ComposeDictationReason.COMPLETED, text)),
            ),
        )
        idle()
        assertNull(model().capture)
    }

    private fun startActivity(owner: String, content: ActivitySurfaceContent = card()) {
        val payload = ActivitySurfaceContract.toPayload("$owner:${ActivitySurfaceContract.LOCAL_SURFACE_ID}", content)
            .put("ownerPluginId", owner)
            .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
            .put("seq", SEQ.incrementAndGet())
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.ACTIVITY_START, payload = payload))
        idle()
    }

    private fun startRoute() = startActivity(
        "maps",
        card().copy(primary = "120 m", destination = PageDestination("maps:route"), activation = ActivityActivation.DESTINATION),
    )

    private fun openRouteFromRoot() {
        selectRootStop { it.kind == RootStopKind.ACTIVITY && it.id.startsWith("maps") }
        freshPress()
        assertEquals("maps:route", top().pageId)
        answer(
            PageBody.Route(
                position = RoutePositionQuality.LIVE,
                maneuver = RouteManeuver("turn-left", "120 m", street = "Rue de Rivoli", eta = "09:52"),
                facts = listOf(PageFact("eta", "Arrival", "09:52")),
                follow = null,
                overview = null,
            ),
            title = "Route",
            actions = JSONArray(),
        )
    }

    private fun card() = ActivitySurfaceContent(
        glyph = "music",
        primary = "Now",
        secondary = null,
        progress = null,
        eta = null,
        detail = emptyList(),
        actions = emptyList(),
        maxDurationMs = null,
    )

    private fun conversation() = PageBody.Conversation(
        threadKey = "t1",
        messages = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "on est devant"),
            ConversationMessage("m2", ConversationRole.OURS, "j'arrive"),
        ),
        caption = "captured 14:02",
        composer = ConversationComposer("Ana", "send", 500, true),
    )

    /** The phone's stamped answer to the request the top frame waits on, through the hub's ingress. */
    private fun answer(body: PageBody, title: String, actions: JSONArray = JSONArray().put(sendAction())) {
        val frame = top()
        val requestId = (frame.status as FrameStatus.Loading).requestId
        val frames = (NexusSession.state as SessionState.InPage).frames
        val payload = PageSurfaceContract.correlationPayload(PageCorrelation(requestId, NexusSession.generation, frames.size, frame.pageId))
            .put("revision", 1).put("template", body.template).put("title", title)
            .put("body", PageTemplateContract.encode(body))
            .put("actions", actions).put("live", true)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "e1")
            .put(PageSurfaceContract.FIELD_PLUGIN_ID, PageTemplateContract.namespaceOf(frame.pageId))
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.PAGE_RESPONSE, payload = payload))
        idle()
        assertTrue("${top().status}", top().status is FrameStatus.Shown)
    }

    private fun sendAction(): JSONObject = JSONObject().put("id", "send").put("label", "Send").put("kind", "plugin").put("confirm", false)
        .put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(PageActionType.ComposeSend))
        .put(QuickActionPolicyContract.FIELD_HUB_QUICK, false)

    // Session input, as the arbiter delivers it.

    private fun openRoot() {
        NexusSession.dispatch(SessionEvent.TripleTap(now()))
        advance(PageSurfaceContract.GATE_MS + 50)
        assertTrue("${NexusSession.state}", NexusSession.state is SessionState.Root)
    }

    private fun backToRoot() {
        while (NexusSession.state is SessionState.InPage) {
            NexusSession.dispatch(SessionEvent.Back(now()))
            idle()
        }
        assertTrue("${NexusSession.state}", NexusSession.state is SessionState.Root)
    }

    private fun selectRootStop(predicate: (com.anezium.rokidbus.glasses.session.RootStop) -> Boolean) {
        val root = NexusSession.state as SessionState.Root
        val index = root.stops.indexOfFirst(predicate)
        assertTrue("${root.stops}", index >= 0)
        val delta = index - root.selected
        repeat(kotlin.math.abs(delta)) {
            NexusSession.dispatch(SessionEvent.Step(if (delta > 0) 1 else -1, now()))
            idle()
        }
    }

    /** The fresh-contact rule: what is on screen is drawn, then a press begins after it. */
    private fun freshPress() {
        SessionFocus.tokenOf(model())?.let { NexusSession.dispatch(SessionEvent.FocusShown(it, now())) }
        advance(10)
        val start = now()
        advance(40)
        NexusSession.dispatch(SessionEvent.Enter(now(), pressStartMs = start))
        idle()
    }

    // Observation.

    private fun runner() = staticField(NexusSession::class.java, "runner").get(null) as SessionRunner

    private fun model(): SessionModel = runner().model

    /** The session model of a process that just started, as every test here assumes. */
    private fun freshSessionModel() {
        SessionRunner::class.java.getDeclaredField("model").apply { isAccessible = true }.set(runner(), SessionModel())
    }

    private fun top() = (NexusSession.state as SessionState.InPage).frames.last()

    /**
     * The root underneath: its stops in order and the one selected. A card's own content may
     * change meanwhile (it turns last-known, its bundle fields wait for the phone), never which
     * cards there are or where the wearer was.
     */
    private fun shape(root: SessionState.Root) = root.stops.map { Triple(it.id, it.kind, it.pageId) } to root.selected

    private fun surfaceIds(): Set<String> = collection.map { it.surfaceId }.toSet()

    private fun rebootedBackend(): LauncherBackend =
        com.anezium.rokidbus.glasses.input.LauncherBackendSelection(
            ActivityPresentationSettings.backendStore(app), {}, LauncherBackend.SESSION,
        ).current()

    private fun logs(): List<String> = ShadowLog.getLogsForTag(TAG).map { it.msg }

    /** The paths the glasses sent or tried to send the phone, on either transport. */
    private fun outbound(lines: List<String>): List<String> = lines.mapNotNull { line ->
        OUTBOUND.firstNotNullOfOrNull { prefix -> line.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.substringBefore(' ') }
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Background SPP work lands on the main looper; wait for [done] while draining it. */
    private fun awaitIdle(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            idle()
            if (done()) break
            check(System.nanoTime() < deadline) { "timed out; spp=$sppStates" }
            Thread.sleep(5)
        }
        idle()
    }

    private fun staticField(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    /** The phone's side of one RFCOMM socket, as two pipes. */
    private class PhoneEnd : SppPeer {
        override val bonded = true
        override val input = PipedInputStream(1 shl 16)
        val phoneOutput = PipedOutputStream(input)
        val phoneInput = PipedInputStream(1 shl 16)
        private val toPhone = PipedOutputStream(phoneInput)
        override val output: OutputStream = object : OutputStream() {
            override fun write(value: Int) = toPhone.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = toPhone.write(bytes, offset, length)
            override fun flush() = toPhone.flush()
        }

        override fun close() {
            runCatching { phoneOutput.close() }
            runCatching { toPhone.close() }
            runCatching { input.close() }
            runCatching { phoneInput.close() }
        }
    }

    private class MemoryKeys(private val key: ByteArray) : SppPairingKeyStore {
        override fun load(): ByteArray = key
        override fun save(key: ByteArray): Boolean = false
    }

    private companion object {
        val SEQ = AtomicLong(System.nanoTime())
        val REPLAYABLE = setOf(BusPaths.PAGE_REQUEST, BusPaths.PAGE_ACTION, BusPaths.ACTIVITY_ACTION)
        const val COMPOSE_PREFIX = "/core/compose/"
        val OUTBOUND = listOf("SPP TX ", "CXR-S TX ", "session send dropped path=")
    }
}
