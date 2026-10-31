package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.ActivityField
import com.anezium.rokidbus.shared.ActivityState
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatch
import com.anezium.rokidbus.glasses.session.ActivityFeed
import com.anezium.rokidbus.glasses.session.ActivitySource
import com.anezium.rokidbus.glasses.session.LocalPages
import com.anezium.rokidbus.glasses.session.RootStopKind
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.TypedPageItems
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 028a §1.3 and §1.4 on the glasses: the effective collection, its order of eviction, islands and offline entries. */
class ActivityCollectionTest {
    private val state = ActivityStateMachine()
    private val now = 10_000L

    @Test
    fun `the v1 pair holds two entries and the bundle holds eight`() {
        (1..3).forEach { state.start("a$it:activity", "a$it", it.toLong(), content("$it"), now) }
        assertEquals(2, state.surfaceIds().size)

        val bundled = ActivityStateMachine()
        bundled.setCapacity(ActivitySurfaceContract.effectiveCollectionCapacity(1))
        (1..9).forEach { bundled.start("a$it:activity", "a$it", it.toLong(), content("$it"), now) }
        assertEquals(ActivitySurfaceContract.MAX_COLLECTION_ACTIVITIES, bundled.surfaceIds().size)
        assertEquals(8, snapshot(bundled).collection.size)
    }

    @Test
    fun `only the primary and one other live entry get a corner island`() {
        state.setCapacity(8)
        state.start("a:activity", "a", 1, content("a"), now)
        state.start("b:activity", "b", 2, content("b"), now)
        state.start("c:activity", "c", 3, content("c"), now)
        state.start("d:activity", "d", 4, content("d", offline = true, knownAtWallMs = 5), now)

        val render = snapshot(state)
        assertEquals(2, render.items.size)
        assertEquals(4, render.collection.size)
        assertTrue(render.items.none { it.activity.surfaceId == "d:activity" })
        assertEquals(state.primarySurfaceId(), render.items.single { it.primary }.activity.surfaceId)
    }

    @Test
    fun `eviction takes offline first, then unpinned live non-primary, and protects the pin`() {
        state.setCapacity(3)
        state.start("pin:activity", "pin", 1, content("p", userPinned = true), now)
        state.start("live:activity", "live", 2, content("l"), now)
        state.start("off:activity", "off", 3, content("o", offline = true, knownAtWallMs = 1), now)

        val first = state.start("new:activity", "new", 4, content("n"), now) as ActivityMutation.Applied
        assertEquals("off:activity", first.replacedSurfaceId)

        // Now the pinned one is the oldest; the unpinned non-primary still leaves before it.
        val second = state.start("next:activity", "next", 5, content("x"), now) as ActivityMutation.Applied
        assertTrue(second.replacedSurfaceId != "pin:activity")
        assertTrue("pin:activity" in state.surfaceIds())
    }

    @Test
    fun `a capacity drop trims the collection in eviction order`() {
        state.setCapacity(8)
        state.start("pin:activity", "pin", 1, content("p", userPinned = true), now)
        state.start("a:activity", "a", 2, content("a"), now)
        state.start("off:activity", "off", 3, content("o", offline = true), now)

        val evicted = state.setCapacity(ActivitySurfaceContract.effectiveCollectionCapacity(0))
        assertEquals(listOf("off:activity"), evicted)
        assertEquals(setOf("pin:activity", "a:activity"), state.surfaceIds())
    }

    @Test
    fun `an offline entry comes back only through a fresh start`() {
        state.start("m:activity", "m", 1, content("song", offline = true, knownAtWallMs = 3), now)
        val revived = ActivitySurfacePatch(state = ActivityField(ActivityState.LIVE))
        assertEquals(ActivityMutation.Ignored, state.update("m:activity", 2, revived, now))
        assertTrue(snapshot(state).collection.single().content.isOffline)

        assertTrue(state.start("m:activity", "m", 3, content("song"), now) is ActivityMutation.Applied)
        assertTrue(!snapshot(state).collection.single().content.isOffline)
    }

    @Test
    fun `a destination is only ever one of the owner's own pages`() {
        val foreign = content("x").copy(destination = PageDestination("bank:transfer"))
        assertEquals(ActivityMutation.Ignored, state.start("media:activity", "media", 1, foreign, now))
        assertTrue(state.surfaceIds().isEmpty())

        state.start("media:activity", "media", 2, content("x"), now)
        val patch = ActivitySurfacePatch(destination = ActivityField(PageDestination("bank:transfer")))
        assertEquals(ActivityMutation.Ignored, state.update("media:activity", 3, patch, now))
        val own = ActivitySurfacePatch(destination = ActivityField(PageDestination("media:now")))
        assertTrue(state.update("media:activity", 4, own, now) is ActivityMutation.Applied)
    }

    @Test
    fun `a route started over playing media leads it through a new track, another player and the next session`() {
        state.setCapacity(8)
        state.start("media:activity", "media", 1, content("Song A"), now)
        state.start("nav:activity", "nav", 1, content("Turn left on Rue de Rivoli"), now)
        assertEquals(listOf("nav", "media"), nextSession())
        assertEquals("nav:activity", state.primarySurfaceId())

        // Media Deck starting again over the same registration (a lost send, a re-registration) is
        // not a new session: it keeps its place.
        state.start("media:activity", "media", 2, content("Song A"), now)
        assertEquals(listOf("nav", "media"), nextSession())

        // Media Deck sends a new track and a switch to another player as ordinary updates.
        state.update("media:activity", 3, ActivitySurfacePatch(primary = ActivityField("Song B")), now)
        assertEquals(listOf("nav", "media"), nextSession())
        state.update("media:activity", 4, ActivitySurfacePatch(primary = ActivityField("Podcast"), glyph = ActivityField("podcast")), now)
        assertEquals(listOf("nav", "media"), nextSession())

        // A real route event keeps its meaning: significant, and the route stays the primary.
        state.update("nav:activity", 2, ActivitySurfacePatch(primary = ActivityField("Turn right"), significant = true), now)
        state.update("media:activity", 5, ActivitySurfacePatch(primary = ActivityField("Song C")), now)
        assertEquals(listOf("nav", "media"), nextSession())
        val collection = snapshot(state).collection
        assertTrue(
            collection.single { it.ownerPluginId == "nav" }.lastSignificantOrder!! >
                collection.single { it.ownerPluginId == "media" }.lastSignificantOrder!!,
        )
        assertEquals("nav:activity", state.primarySurfaceId())

        // The order rule itself is unchanged: a significant media update would still lead.
        state.update("media:activity", 6, ActivitySurfacePatch(primary = ActivityField("Song D"), significant = true), now)
        assertEquals(listOf("media", "nav"), nextSession())
    }

    @Test
    fun `media started over a running route leads it, as any new session does`() {
        state.setCapacity(8)
        state.start("nav:activity", "nav", 1, content("Turn left on Rue de Rivoli"), now)
        state.start("media:activity", "media", 1, content("Song A"), now)
        assertEquals(listOf("media", "nav"), nextSession())

        // The route's next real event takes the lead back.
        state.update("nav:activity", 2, ActivitySurfacePatch(primary = ActivityField("Turn right"), significant = true), now)
        assertEquals(listOf("nav", "media"), nextSession())
    }

    private val feed = ActivityFeed(ownerLabel = { it }, pageIdFor = LocalPages::activityPageId, wallClock = { now })
    private val reducer = SessionReducer(
        TypedPageItems(isLocalPage = PageTemplateContract::isLocalPageId, providerPageAllowed = { true }, extended = { true }),
        isLocalPage = PageTemplateContract::isLocalPageId,
        launcherActive = { true },
        wallClock = { now },
    )
    private var session = SessionModel()

    /** The collection as the host feeds it to the session, then a new session's root activity owners, top first. */
    private fun nextSession(): List<String?> {
        val sources = snapshot(state).collection.map {
            ActivitySource(
                it.surfaceId, it.ownerPluginId, it.primary, it.content.primary, null, null,
                startedOrder = it.startedOrder, significantOrder = it.lastSignificantOrder,
            )
        }
        feed.update(sources).forEach { session = reducer.reduce(session, it).model }
        while (session.state != SessionState.Closed) session = reducer.reduce(session, SessionEvent.Back(now)).model
        session = reducer.reduce(session, SessionEvent.TripleTap(now)).model
        session = reducer.reduce(session, SessionEvent.Tick(now + PageSurfaceContract.GATE_MS)).model
        val root = session.state as SessionState.Root
        return root.stops.filter { it.kind == RootStopKind.ACTIVITY }.map { it.activity?.ownerPluginId }
    }

    private fun snapshot(machine: ActivityStateMachine) = machine.snapshot(
        now,
        ActivityPresentationContext.IDLE_HOME,
        pinCorner = null,
        alwaysExpanded = false,
    )

    private fun content(
        primary: String,
        offline: Boolean = false,
        userPinned: Boolean = false,
        knownAtWallMs: Long? = null,
    ) = ActivitySurfaceContent(
        glyph = "play",
        primary = primary,
        secondary = null,
        progress = null,
        eta = null,
        detail = emptyList(),
        actions = emptyList(),
        maxDurationMs = null,
        state = if (offline) ActivityState.OFFLINE else ActivityState.LIVE,
        userPinned = userPinned,
        knownAtWallMs = knownAtWallMs,
    )
}
