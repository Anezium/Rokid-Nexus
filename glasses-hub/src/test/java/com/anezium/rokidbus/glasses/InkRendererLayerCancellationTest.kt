package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.PageItem
import com.anezium.rokidbus.glasses.session.PageItemResolver
import com.anezium.rokidbus.glasses.session.SessionEffect
import com.anezium.rokidbus.glasses.session.SessionEffectSink
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionRunner
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.SessionTimer
import com.anezium.rokidbus.ink.RenderChange
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderNode
import com.anezium.rokidbus.ink.RenderPatch
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * PR2's deferred F1 at the Ink projection boundary. A real overlay window holds a live
 * [InkHudView] showing an accepted document; the session launches an owner and the wearer
 * cancels it; the owner's late Ink content then arrives and is decoded off the main thread.
 * Every store the renderer layer hands to a view is recorded as it is handed over, so content
 * that reached the view even for a moment, before being closed unseen, is caught.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class InkRendererLayerCancellationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val requests = mutableListOf<SessionEffect.RequestPage>()
    private val closes = mutableListOf<SessionEffect.CloseSurface>()
    private val projected = mutableListOf<Pair<String, Int>>()
    private val projectedText = mutableListOf<String>()
    private var attachedView: InkHudView? = null
    private var now = 0L
    private var duringDecision: ((NexusSurface) -> Unit)? = null
    private lateinit var service: OverlayService
    private val runner = SessionRunner(
        reducer = SessionReducer(PageItemResolver { page -> page.actions.map { PageItem.Launch(it.id) } }),
        clock = { now },
        timer = object : SessionTimer {
            override fun schedule(atUptimeMs: Long, task: () -> Unit) = Unit
            override fun cancel() = Unit
        },
        sink = object : SessionEffectSink {
            override fun execute(effect: SessionEffect) {
                when (effect) {
                    is SessionEffect.RequestPage -> requests += effect
                    is SessionEffect.CloseSurface -> {
                        closes += effect
                        SurfaceController.closeUnseen(effect.surfaceId)
                    }
                    else -> Unit
                }
            }

            override fun settled(model: SessionModel) = Unit
        },
    )

    @Before
    fun setUp() {
        service = Robolectric.setupService(OverlayService::class.java)
        SurfaceOverlayRenderer.onServiceConnected(service)
        SurfaceController.observeInkProjection { view, store ->
            attachedView = view
            projected += store.documentId to store.revision
            store.rootNodes().forEach { projectedText += it.allText() }
        }
        SurfaceController.setPresentationGate { surface ->
            duringDecision?.invoke(surface)
            runner.dispatch(SessionEvent.SurfaceShown(surface.surfaceId, surface.ownerPluginId, now))
        }
    }

    @After
    fun tearDown() {
        SurfaceController.setPresentationGate(null)
        SurfaceController.activeSurface()?.let { SurfaceController.closeUnseen(it.surfaceId) }
        idle()
        SurfaceController.observeInkProjection(null)
        SurfaceOverlayRenderer.onServiceDestroyed(service)
    }

    @Test
    fun `a cancelled late Ink document never reaches the live attached view`() {
        val notes = surfaceId("notes")
        showDocument(notes, "notes", "notes-doc", 1, "Notes page")
        awaitProjected("notes-doc", 1)
        assertTrue(viewText().contains("Notes page"))

        cancelOpen("maps")
        val maps = surfaceId("maps")
        showDocument(maps, "maps", "maps-doc", 1, "Cancelled maps")
        awaitClose(maps)

        assertTrue("projected=$projected", projected.none { it.first == "maps-doc" })
        assertTrue("projectedText=$projectedText", projectedText.none { "Cancelled maps" in it })
        assertTrue(viewText().none { "Cancelled maps" in it })
        assertNull(SurfaceController.activeSurface())

        // Positive control: an Ink document nobody cancelled reaches the view.
        val lens = surfaceId("lens")
        showDocument(lens, "lens", "lens-doc", 1, "Lens page")
        awaitProjected("lens-doc", 1)
        assertEquals(lens, SurfaceController.activeSurface()?.surfaceId)
        assertTrue(viewText().contains("Lens page"))
    }

    @Test
    fun `a cancelled late Ink patch never reaches the live attached view`() {
        val maps = surfaceId("maps")
        showDocument(maps, "maps", "route-doc", 1, "Route ready")
        awaitProjected("route-doc", 1)

        cancelOpen("maps")
        showPatch(maps, "maps", "route-doc", 1, "Cancelled patch")
        awaitClose(maps)

        assertTrue("projected=$projected", projected.none { it == ("route-doc" to 2) })
        assertTrue("projectedText=$projectedText", projectedText.none { "Cancelled patch" in it })
        assertTrue(viewText().none { "Cancelled patch" in it })
        assertNull(SurfaceController.activeSurface())
    }

    @Test
    fun `an accepted Ink patch still reaches the live attached view`() {
        val lens = surfaceId("lens")
        showDocument(lens, "lens", "lens-route", 1, "Lens first")
        awaitProjected("lens-route", 1)

        showPatch(lens, "lens", "lens-route", 1, "Lens patched")
        awaitProjected("lens-route", 2)

        assertEquals(lens, SurfaceController.activeSurface()?.surfaceId)
        assertTrue(viewText().contains("Lens patched"))
        assertTrue(closes.isEmpty())
    }

    @Test
    fun `a replacement during the presentation decision keeps the staged Ink unprojected`() {
        val notes = surfaceId("notes")
        showDocument(notes, "notes", "notes-doc", 1, "Notes page")
        awaitProjected("notes-doc", 1)

        val maps = surfaceId("maps")
        val card = surfaceId("card")
        duringDecision = { surface ->
            // The plugin's own card replaces the Ink surface while the session decides on it.
            if (surface.surfaceId == maps) {
                duringDecision = null
                showCard(card, "cards", "Replacement")
            }
        }
        showDocument(maps, "maps", "maps-doc", 1, "Replaced maps")
        awaitActive(card)

        assertTrue("projected=$projected", projected.none { it.first == "maps-doc" })
        assertTrue(projectedText.none { "Replaced maps" in it })
        assertTrue(viewText().none { "Replaced maps" in it })
    }

    @Test
    fun `a clear during the presentation decision keeps the staged Ink unprojected`() {
        val notes = surfaceId("notes")
        showDocument(notes, "notes", "notes-doc", 1, "Notes page")
        awaitProjected("notes-doc", 1)

        val maps = surfaceId("maps")
        var hidden = false
        duringDecision = { surface ->
            // The plugin hides its own surface while the session decides on it.
            if (surface.surfaceId == maps) {
                duringDecision = null
                hide(maps)
                hidden = true
            }
        }
        showDocument(maps, "maps", "maps-doc", 1, "Hidden maps")
        drainUntil { hidden && SurfaceController.activeSurface() == null }

        assertTrue("projected=$projected", projected.none { it.first == "maps-doc" })
        assertTrue(projectedText.none { "Hidden maps" in it })
        assertTrue(viewText().none { "Hidden maps" in it })
    }

    @Test
    fun `a reentrant attach during the decision sees only the accepted store`() {
        val notes = surfaceId("notes")
        showDocument(notes, "notes", "notes-doc", 1, "Notes page")
        awaitProjected("notes-doc", 1)
        val live = requireNotNull(attachedView)

        cancelOpen("maps")
        val maps = surfaceId("maps")
        val seenDuringDecision = mutableListOf<Pair<String, Int>>()
        duringDecision = { surface ->
            if (surface.surfaceId == maps) {
                duringDecision = null
                val before = projected.size
                SurfaceController.attachInkRenderer(live, debugActions = false)
                seenDuringDecision += projected.drop(before)
            }
        }
        showDocument(maps, "maps", "maps-doc", 1, "Cancelled maps")
        awaitClose(maps)

        assertEquals(listOf("notes-doc" to 1), seenDuringDecision)
        assertTrue("projected=$projected", projected.none { it.first == "maps-doc" })
        assertTrue(viewText().none { "Cancelled maps" in it })
    }

    @Test
    fun `a reentrant attach before an accepted commit sees the previous store then the new one`() {
        val notes = surfaceId("notes")
        showDocument(notes, "notes", "notes-doc", 1, "Notes page")
        awaitProjected("notes-doc", 1)
        val live = requireNotNull(attachedView)

        val lens = surfaceId("lens")
        val seenDuringDecision = mutableListOf<Pair<String, Int>>()
        duringDecision = { surface ->
            if (surface.surfaceId == lens) {
                duringDecision = null
                val before = projected.size
                SurfaceController.attachInkRenderer(live, debugActions = false)
                seenDuringDecision += projected.drop(before)
            }
        }
        showDocument(lens, "lens", "lens-doc", 1, "Lens page")
        awaitProjected("lens-doc", 1)

        assertEquals(listOf("notes-doc" to 1), seenDuringDecision)
        assertEquals(lens, SurfaceController.activeSurface()?.surfaceId)
        assertTrue(viewText().contains("Lens page"))
    }

    /** Opens [pluginId] from a page, cancels the open with BACK, then closes the session. */
    private fun cancelOpen(pluginId: String) {
        at(1_000, SessionEvent.TripleTap(1_000))
        at(1_800, SessionEvent.Tick(1_800))
        at(3_000, SessionEvent.Enter(3_000))
        val request = requests.last().request
        val page = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", 1)
            .put("template", "summary")
            .put("title", "Page")
            .put("body", JSONObject())
            .put(
                "actions",
                JSONArray().put(
                    JSONObject().put("id", pluginId).put("label", pluginId).put("kind", "immersion").put("confirm", false),
                ),
            )
            .put("live", false)
        at(3_100, SessionEvent.PageResponse(page, 3_100))
        at(4_000, SessionEvent.Enter(4_000))
        assertTrue(runner.state is SessionState.Launching)
        at(4_500, SessionEvent.Back(4_500))
        at(4_600, SessionEvent.Back(4_600))
        at(4_700, SessionEvent.Back(4_700))
        assertTrue(runner.state == SessionState.Closed)
        assertTrue(pluginId in runner.model.cancelledOpen)
        // The late content arrives before the cancellation expires at 14000.
        now = 9_000
    }

    private fun at(time: Long, event: SessionEvent) {
        now = time
        runner.dispatch(event)
    }

    private fun showDocument(surfaceId: String, owner: String, documentId: String, revision: Int, text: String) {
        val document = RenderDocument(
            listOf(RenderNode("root", "view", children = listOf(RenderNode("line", "#text", text = text)))),
            documentId = documentId,
            revision = revision,
        )
        sendInk(surfaceId, owner, JSONObject().put("document", document.toWireJson()))
    }

    private fun showPatch(surfaceId: String, owner: String, documentId: String, baseRevision: Int, text: String) {
        val patch = RenderPatch(
            listOf(RenderChange.TextChanged("line", text)),
            documentId = documentId,
            baseRevision = baseRevision,
            targetRevision = baseRevision + 1,
        )
        sendInk(surfaceId, owner, JSONObject().put("patch", patch.toWireJson()), path = BusPaths.SURFACE_UPDATE)
    }

    private fun sendInk(surfaceId: String, owner: String, ink: JSONObject, path: String = BusPaths.SURFACE_SHOW) {
        val payload = JSONObject()
            .put("surfaceId", surfaceId)
            .put("ownerPluginId", owner)
            .put("seq", nextSeq++)
            .put("kind", "ink")
            .put("ink", ink)
        SurfaceController.handleSurfaceEnvelope(app, BusEnvelope(path = path, payload = payload))
    }

    private fun showCard(surfaceId: String, owner: String, title: String) {
        val payload = JSONObject()
            .put("surfaceId", surfaceId)
            .put("ownerPluginId", owner)
            .put("seq", nextSeq++)
            .put("kind", "card")
            .put("title", title)
        SurfaceController.handleSurfaceEnvelope(app, BusEnvelope(path = BusPaths.SURFACE_SHOW, payload = payload))
    }

    private fun hide(surfaceId: String) {
        SurfaceController.handleSurfaceEnvelope(
            app,
            BusEnvelope(
                path = BusPaths.SURFACE_HIDE,
                payload = JSONObject().put("surfaceId", surfaceId).put("seq", nextSeq++),
            ),
        )
    }

    private fun awaitProjected(documentId: String, revision: Int) =
        drainUntil { (documentId to revision) in projected }

    private fun awaitClose(surfaceId: String) =
        drainUntil { closes.any { it.surfaceId == surfaceId } && SurfaceController.activeSurface() == null }

    private fun awaitActive(surfaceId: String) =
        drainUntil { SurfaceController.activeSurface()?.surfaceId == surfaceId }

    /** Runs the main looper until [condition] holds while the decoder thread finishes; bounded. */
    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (true) {
            idle()
            if (condition()) {
                idle()
                return
            }
            check(System.nanoTime() < deadline) { "timed out; projected=$projected closes=$closes" }
            Thread.sleep(5)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewText(): List<String> = attachedView?.textViews()?.map { it.text.toString() }.orEmpty()

    private fun surfaceId(owner: String): String = "$owner:ink-${nextSeq++}"

    private fun RenderNode.allText(): String = (listOfNotNull(text) + children.map { it.allText() }).joinToString(" ")

    private fun View.textViews(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).textViews() }
        else -> emptyList()
    }

    /** The smallest accessibility service whose window manager accepts overlay windows. */
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private companion object {
        const val DRAIN_TIMEOUT_NS = 5_000_000_000L

        // The surface controller is a process-wide singleton that remembers each surface's last
        // sequence number across tests, so every envelope here is newer than any before it.
        var nextSeq = 1_000_000L
    }
}
