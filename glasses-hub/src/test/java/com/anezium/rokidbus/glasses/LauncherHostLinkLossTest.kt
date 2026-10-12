package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.glasses.session.ActivityStop
import com.anezium.rokidbus.glasses.session.BubbleSide
import com.anezium.rokidbus.glasses.session.CommandFacts
import com.anezium.rokidbus.glasses.session.DraftState
import com.anezium.rokidbus.glasses.session.FrameMode
import com.anezium.rokidbus.glasses.session.FrameStatus
import com.anezium.rokidbus.glasses.session.NoticeEntry
import com.anezium.rokidbus.glasses.session.NoticePreview
import com.anezium.rokidbus.glasses.session.RootStop
import com.anezium.rokidbus.glasses.session.RootStopKind
import com.anezium.rokidbus.glasses.session.RootStops
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionFocus
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionRunner
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.SessionScreens
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.TypedPageItems
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ActivityPersistence
import com.anezium.rokidbus.shared.ActivityState
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
import com.anezium.rokidbus.shared.MediaPlaybackState
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
import org.junit.Assert.fail
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
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /** The server's own authentication deadlines run for real, as in production. */
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val sppStates = CopyOnWriteArrayList<Boolean>()
    private val spp = AuthenticatedSppServer(
        MemoryKeys(key),
        execute = { workers.execute(it) },
        schedule = { delay, task ->
            val future = deadlines.schedule(task, delay, TimeUnit.MILLISECONDS)
            ({ future.cancel(false); Unit })
        },
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
    private var previousAppContext: Any? = null
    private var previousSessions: Any? = null

    @Before
    fun setUp() {
        previousSessions = staticField(SppServerManager::class.java, "sessions").get(null)
        previousAppContext = staticField(GlassesHub::class.java, "appContext").get(null)
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
        val plugins = JSONArray()
        listOf("relay" to "Relay", "maps" to "Maps", "media" to "Media Deck", "todo" to "Todo").forEach { (id, name) ->
            plugins.put(JSONObject().put("id", id).put("displayName", name).put("pageSessionVersion", 1))
        }
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.LAUNCHER_LIST, payload = JSONObject().put("plugins", plugins)))
        idle()
    }

    /**
     * Every step runs even when an earlier one fails, so a broken test never leaves the process-wide
     * runtime, an injected singleton or a thread behind; the first failure is reported, the rest ride
     * along suppressed, and JUnit still reports the test's own failure first.
     */
    @After
    fun tearDown() {
        val failures = mutableListOf<Throwable>()
        fun step(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        step {
            NexusSession.dispatch(SessionEvent.Abort)
            idle()
        }
        // The runtime is process-wide: no draft or notice of this test reaches the next one.
        step { freshSessionModel() }
        step {
            GlassesHub.onRemoteEnvelope(
                BusEnvelope(path = BusPaths.ACTIVITY_END, payload = ActivitySurfaceContract.emptySlotAssertPayload(SEQ.incrementAndGet())),
            )
        }
        // An all-default announcement resets the collection's size and the bundle the hub holds.
        step {
            if (!GlassesHub.isPhoneConnected()) cxrUp(true)
            announce(oldPhone())
        }
        step {
            phone?.close()
            phone = null
        }
        step { cxrUp(false) }
        step { awaitIdle { !SppServerManager.isConnected() && !GlassesHub.isPhoneConnected() } }
        step { unobserve?.invoke() }
        step { ActivityController.onServiceDestroyed() }
        step { NexusSession.onServiceDestroyed() }
        step { NexusInput.onServiceDestroyed() }
        step { NexusInput.restartProcess(LauncherBackend.BUNDLE_DEFAULT) }
        step {
            GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.LAUNCHER_LIST, payload = JSONObject().put("plugins", JSONArray())))
            idle()
        }
        step { staticField(SppServerManager::class.java, "sessions").set(null, previousSessions) }
        step { staticField(GlassesHub::class.java, "appContext").set(null, previousAppContext) }
        step {
            workers.shutdownNow()
            deadlines.shutdownNow()
            val stopped = workers.awaitTermination(3, TimeUnit.SECONDS) and deadlines.awaitTermination(3, TimeUnit.SECONDS)
            check(stopped) { "fixture threads still running" }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
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

        outage(sppFirst = true, retained = ::assertConversationRetained)
        outage(sppFirst = false, retained = ::assertConversationRetained)

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

        outage(sppFirst = false, retained = ::assertRouteRetained)
        outage(sppFirst = true, retained = ::assertRouteRetained)

        assertEquals("maps:route", top().pageId)
        assertEquals(draft, model().draft)
    }

    @Test
    fun `an outage in either order keeps what was playing readable and none of its controls`() {
        linkUp()
        startActivity(
            "media",
            card().copy(primary = "Nightcall", destination = PageDestination("media:player"), activation = ActivityActivation.DESTINATION),
        )
        openRoot()
        selectRootStop { it.kind == RootStopKind.ACTIVITY && it.id.startsWith("media") }
        freshPress()
        assertEquals("media:player", top().pageId)
        answer(
            PageBody.Media(
                title = "Nightcall",
                state = MediaPlaybackState.PLAYING,
                artist = "Kavinsky",
                album = "OutRun",
                controls = listOf("toggle"),
                defaultControl = "toggle",
            ),
            title = "Media Deck",
            actions = JSONArray().put(
                JSONObject().put("id", "toggle").put("label", "Pause").put("kind", "plugin").put("confirm", false)
                    .put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(PageActionType.MediaPause))
                    .put(QuickActionPolicyContract.FIELD_HUB_QUICK, false),
            ),
        )
        // Live, the page carries its control.
        assertTrue(screen().blocks.any { it is ScreenBlock.Media })

        outage(sppFirst = true, retained = ::assertMediaRetained)
        outage(sppFirst = false, retained = ::assertMediaRetained)
    }

    @Test
    fun `a retained conversation too tall for the viewport is read page by page, and only Retry asks again`() {
        linkUp()
        val messages = (1..20).map { index ->
            ConversationMessage(
                "m$index",
                if (index % 2 == 0) ConversationRole.OURS else ConversationRole.RECEIVED,
                "Message $index " + "about the evening plans and who brings what ".repeat(2),
            )
        }
        openConversation(conversation(messages))
        val from = logs().size
        linkDown(sppFirst = true)

        // The renderer measures the retained bubbles taller than the viewport and says so.
        val fit = requireNotNull(screen().fit) { "${screen()}" }
        NexusSession.dispatch(SessionEvent.ContentOverflowed(fit.frame, fit.pageId, fit.revision))
        idle()
        assertEquals(FrameMode.READING, top().mode)
        val reading = screen()
        val document = reading.blocks.filterIsInstance<ScreenBlock.Document>().single()
        assertTrue(document.dimmed)
        assertEquals(messages.map { it.text }, document.segments.map { it.text })
        assertTrue(reading.blocks.none { it is ScreenBlock.Row })
        assertNull(reading.footer)

        linkUp()
        assertTrue(top().status is FrameStatus.Unavailable)
        assertTrue(outbound(logs().drop(from)).none { it in REPLAYABLE })
        // A tap on the text reaches Retry, asking nothing; the wearer's Retry is what asks again.
        freshPress()
        assertEquals(FrameMode.ROWS, top().mode)
        assertEquals(0, top().selected)
        assertTrue(outbound(logs().drop(from)).none { it in REPLAYABLE })
        freshPress()
        assertTrue("${top().status}", top().status is FrameStatus.Loading)
        assertEquals(listOf(BusPaths.PAGE_REQUEST), outbound(logs().drop(from)).filter { it in REPLAYABLE })
    }

    @Test
    fun `an outage keeps what the bundle validated on the cards, opens a destination inert, and an older phone ends it`() {
        linkUp()
        val knownAt = System.currentTimeMillis() - 3_600_000
        val params = JSONObject().put("trip", "home").put("mode", "walk").toString()
        // The phone's stamps: Maps pinned with a destination, Todo's owner already gone.
        startActivity(
            "maps",
            card().copy(
                primary = "120 m",
                destination = PageDestination("maps:route", params),
                activation = ActivityActivation.DESTINATION,
                state = ActivityState.LIVE,
                userPinned = true,
            ),
        )
        startActivity("alpha", card().copy(primary = "Timer"))
        startActivity(
            "todo",
            card().copy(
                primary = "3 left",
                destination = PageDestination("todo:list"),
                activation = ActivityActivation.DESTINATION,
                persistence = ActivityPersistence.ONGOING,
                state = ActivityState.OFFLINE,
                knownAtWallMs = knownAt,
            ),
        )
        val live = cards()
        assertEquals(params, live.getValue("maps").destination?.paramsJson)
        val rootOrder = rootOrder()
        val activitiesOrder = activitiesPageOrder()

        val from = logs().size
        linkDown(sppFirst = true)
        val held = cards()
        // What the bundle validated stays: destination, activation, pin, the owner's own offline time.
        assertEquals(live.getValue("maps").destination, held.getValue("maps").destination)
        assertEquals(ActivityActivation.DESTINATION, held.getValue("maps").activation)
        assertTrue(held.getValue("maps").userPinned)
        assertTrue(held.getValue("todo").offline)
        assertEquals(knownAt, held.getValue("todo").knownAtWallMs)
        // Last-known for both reasons: the live ones from the link, the offline one from its owner.
        assertTrue(held.values.all { it.lastKnown })
        assertTrue(held.getValue("maps").stale && held.getValue("alpha").stale)
        assertFalse(held.getValue("todo").stale)
        assertTrue(held.values.all { it.commands.isEmpty() })
        // A newly opened root and the Activities page order them as they were.
        assertEquals(rootOrder, rootOrder())
        assertEquals(activitiesOrder, activitiesPageOrder())

        // The destination opens exactly, inert, asking the absent phone nothing.
        openRoot()
        selectRootStop { it.kind == RootStopKind.ACTIVITY && it.id.startsWith("maps") }
        freshPress()
        assertEquals("maps:route", top().pageId)
        assertEquals(params, top().paramsJson)
        val inert = top().status as FrameStatus.Unavailable
        assertNull(inert.lastSnapshot)
        assertEquals(SessionReducer.UNAVAILABLE_LINK_LOST, inert.reason)
        assertTrue(outbound(logs().drop(from)).none { it in REPLAYABLE || it.startsWith(COMPOSE_PREFIX) })

        // Back with the same phone: nothing is asked until the wearer's Retry, with the same params.
        linkUp()
        assertTrue(top().status is FrameStatus.Unavailable)
        freshPress()
        assertTrue(top().status is FrameStatus.Loading)
        assertEquals(params, top().paramsJson)
        backToRoot()
        NexusSession.dispatch(SessionEvent.Back(now()))
        idle()

        // An older phone's announcement after the loss is fresh truth: v1 cards, no bundle fields.
        linkDown(sppFirst = false)
        cxrUp(true)
        connectSpp()
        announce(oldPhone())
        val v1 = cards()
        assertTrue(v1.values.all { it.destination == null && it.activation == null && !it.userPinned && !it.offline })
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
    }

    @Test
    fun `an authentication that stalls ends within its bound, and the server's own deadline still closes a silent peer`() {
        // Nobody answers this phone: the fixture's watchdog ends the attempt.
        val unanswered = PhoneEnd()
        val started = System.nanoTime()
        try {
            authenticate(unanswered, boundMs = 300)
            fail("authenticated without a server")
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("did not finish"))
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        assertTrue(unanswered.closed.await(0, TimeUnit.SECONDS))

        // A peer the server accepts but that never says hello: the real hello deadline closes it.
        sppClock += 5_000
        val silent = PhoneEnd()
        spp.accept(silent)
        assertTrue(silent.closed.await(SppAuthProtocol.HELLO_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS))
        assertFalse(SppServerManager.isConnected())
        assertTrue(sppStates.isEmpty())
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
        owners.forEach { startActivity(it) }
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
        // In the collection's eviction order: the primary (first started) and the most recent stay.
        assertEquals(setOf("alpha:activity", "echo:activity"), surfaceIds())
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
    }

    // The outage and its return.

    /**
     * Both transports go, in the order given, and come back; the phone then announces the same
     * bundle again, as it does on every reconnect. Nothing about the session may move, and while
     * the phone is gone the open page reads as [retained] says.
     */
    private fun outage(sppFirst: Boolean, retained: (SessionScreen) -> Unit) {
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
        assertTrue("${top().status}", top().status is FrameStatus.Unavailable)
        val screen = screen()
        // Dimmed and inert: the failure's Retry and Back are all there is to select.
        assertEquals("$screen", listOf("retry", "back"), screen.blocks.filterIsInstance<ScreenBlock.Row>().map { it.key })
        assertNull(screen.footer)
        retained(screen)

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

    private fun assertConversationRetained(screen: SessionScreen) {
        val conversation = screen.blocks.filterIsInstance<ScreenBlock.Conversation>().single()
        assertTrue(conversation.dimmed)
        assertEquals(listOf("on est devant", "j'arrive"), conversation.bubbles.map { it.text })
        assertEquals(listOf(BubbleSide.LEFT, BubbleSide.RIGHT), conversation.bubbles.map { it.side })
        assertEquals("captured 14:02", conversation.caption)
    }

    private fun assertRouteRetained(screen: SessionScreen) {
        val texts = screen.blocks.filterIsInstance<ScreenBlock.Text>()
        assertTrue(texts.single { it.key == "last:distance" }.let { it.dimmed && it.text == "~120 m" })
        assertTrue(texts.single { it.key == "last:street" }.let { it.dimmed && it.text == "Rue de Rivoli" })
        assertTrue(screen.blocks.filterIsInstance<ScreenBlock.Fact>().single().let { it.dimmed && it.value == "09:52" })
        // A retained position is no position: no map, no live manoeuvre.
        assertTrue(screen.blocks.none { it is ScreenBlock.Map || it is ScreenBlock.Maneuver })
    }

    private fun assertMediaRetained(screen: SessionScreen) {
        val texts = screen.blocks.filterIsInstance<ScreenBlock.Text>()
        assertTrue(texts.single { it.key == "last:title" }.let { it.dimmed && it.text == "Nightcall" })
        assertTrue(texts.single { it.key == "last:artist" }.let { it.dimmed && it.text == "Kavinsky · OutRun" })
        assertTrue(screen.blocks.none { it is ScreenBlock.Media })
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
        val session = authenticate(peer)
        // Whatever the glasses send is read off the wire like the phone would.
        workers.execute {
            runCatching { while (session.read(peer.phoneInput) != null) Unit }
        }
        awaitIdle { sppStates.size > count && sppStates.last() }
        assertTrue(SppServerManager.isConnected())
    }

    /**
     * The phone's side of the handshake, bounded: past [boundMs] a watchdog closes the peer, which
     * ends the blocked read, and the attempt fails here instead of hanging the test.
     */
    private fun authenticate(peer: PhoneEnd, boundMs: Long = AUTH_BOUND_MS): SppAuthProtocol.Session {
        val expired = AtomicBoolean(false)
        val watchdog = deadlines.schedule({
            expired.set(true)
            peer.close()
        }, boundMs, TimeUnit.MILLISECONDS)
        try {
            return SppAuthProtocol.connect(peer.phoneInput, peer.phoneOutput, key, SppAuthProtocol.RecentNonces())
        } catch (failure: IOException) {
            if (expired.get()) throw AssertionError("SPP authentication did not finish within $boundMs ms", failure)
            throw failure
        } finally {
            watchdog.cancel(false)
        }
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

    private fun openConversation(body: PageBody.Conversation = conversation()) {
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
        answer(body, title = "Ana")
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

    private fun conversation(
        messages: List<ConversationMessage> = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "on est devant"),
            ConversationMessage("m2", ConversationRole.OURS, "j'arrive"),
        ),
    ) = PageBody.Conversation(
        threadKey = "t1",
        messages = messages,
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

    private fun closeSession() {
        while (NexusSession.state != SessionState.Closed) {
            NexusSession.dispatch(SessionEvent.Back(now()))
            idle()
        }
    }

    private fun selectRootStop(predicate: (RootStop) -> Boolean) {
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

    /** What the session window draws now, built by the host's own screen builder. */
    private fun screen(): SessionScreen {
        val screens = staticField(NexusSession::class.java, "screens").get(null) as SessionScreens
        val model = model()
        return screens.screen(model.state, null, model.generation, CommandFacts.of(model))
    }

    /** The session model of a process that just started, as every test here assumes. */
    private fun freshSessionModel() {
        SessionRunner::class.java.getDeclaredField("model").apply { isAccessible = true }.set(runner(), SessionModel())
    }

    private fun top() = (NexusSession.state as SessionState.InPage).frames.last()

    /** The session's cards by owner, as the activity tier last projected them. */
    private fun cards(): Map<String, ActivityStop> = model().activities.associateBy { requireNotNull(it.ownerPluginId) }

    /** A newly opened root's stops, then closed again. */
    private fun rootOrder(): List<Pair<String, RootStopKind>> {
        closeSession()
        openRoot()
        val order = (NexusSession.state as SessionState.Root).stops.map { it.id to it.kind }
        closeSession()
        return order
    }

    /** The Activities page's rows in a newly opened session, answered by the hub itself. */
    private fun activitiesPageOrder(): List<String> {
        closeSession()
        openRoot()
        selectRootStop { it.kind == RootStopKind.ACTIVITIES }
        freshPress()
        val shown = top().status as FrameStatus.Shown
        val items = staticField(NexusSession::class.java, "items").get(null) as TypedPageItems
        val rows = items.rows(shown.snapshot.page).map { it.label }
        closeSession()
        return rows
    }

    /**
     * The root underneath: its stops in order and the one selected. A card's own content may
     * change meanwhile (it turns last-known, its commands wait for the phone), never which cards
     * there are or where the wearer was.
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
        val closed = CountDownLatch(1)
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
            closed.countDown()
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
        const val AUTH_BOUND_MS = 5_000L
    }
}
