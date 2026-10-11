package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.glasses.page.HudGlyphs
import com.anezium.rokidbus.glasses.page.IslandFoot
import com.anezium.rokidbus.glasses.page.IslandForm
import com.anezium.rokidbus.glasses.page.MapForm
import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidance islands (plan 028a §8.1) and the launcher backend precedence (§8.3). */
class GuidanceIslandTest {
    @Test
    fun `an owner without a size or live geometry keeps the existing island`() {
        assertNull(GuidanceIslands.of(item(ActivityPresentation.PANEL, size = null), geometry = null, headingDeg = 30f))
    }

    @Test
    fun `an explicit size is drawn as is, an automatic one follows the collapse`() {
        val map = geometry(RoutePositionQuality.LIVE)
        assertEquals(IslandForm.PANEL, GuidanceIslands.of(item(ActivityPresentation.CHIP, AmbientSize.PANEL), map, 30f)?.form)
        assertEquals(IslandForm.TEXT, GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.TEXT), map, 30f)?.form)
        assertEquals(IslandForm.MINIMAP, GuidanceIslands.of(item(ActivityPresentation.PANEL, null), map, 30f)?.form)
        assertEquals(IslandForm.TEXT, GuidanceIslands.of(item(ActivityPresentation.CHIP, null), map, 30f)?.form)
        // A size without geometry is guidance text with no map.
        val noMap = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP), null, null)!!
        assertNull(noMap.content.map)
    }

    @Test
    fun `a live fix draws heading-up, a lost one north-up with the lost glyph and approximate figures`() {
        val live = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP), geometry(RoutePositionQuality.LIVE), 30f)!!
        assertEquals(30f, live.content.map?.headingDeg)
        assertEquals(MapForm.MINIMAP, live.content.map?.form)
        assertFalse(live.content.approximate)
        assertEquals("straight", live.content.glyph)

        val lost = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.PANEL), geometry(RoutePositionQuality.STALE), 30f)!!
        assertEquals(HudGlyphs.GPS_LOST, lost.content.glyph)
        assertTrue(lost.content.approximate)
        assertEquals(true, lost.content.map?.degraded)
        assertNull(lost.content.map?.headingDeg)
        assertEquals(MapForm.PANEL, lost.content.map?.form)

        // The text form draws no map at all.
        assertNull(GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.TEXT), geometry(RoutePositionQuality.LIVE), 30f)!!.content.map)
    }

    @Test
    fun `a minimap's foot says the arrival and the remaining measure, which leaves the head`() {
        val island = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP, measure = "1.2 km"), geometry(RoutePositionQuality.LIVE), 30f)!!
        assertEquals(IslandFoot(left = "09:52 arrival", right = "1.2 km", strong = "09:52"), island.content.foot)
        assertNull(island.content.measure)
        assertNull(island.content.mapHeightPx)

        // The panel keeps its time in its corner and its measure beside the figure: no foot.
        val panel = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.PANEL, measure = "1.2 km"), geometry(RoutePositionQuality.LIVE), 30f)!!
        assertNull(panel.content.foot)
        assertEquals("1.2 km", panel.content.measure)

        // Three residents at home: the minimap's map is shorter (r17 1.4).
        val crowded = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP), geometry(RoutePositionQuality.LIVE), 30f, crowded = true)!!
        assertEquals(GuidanceIslands.CROWDED_MAP_HEIGHT_PX, crowded.content.mapHeightPx)
    }

    @Test
    fun `a lost position says how old its last fix is on the island itself`() {
        val minimap = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP, measure = "1.2 km"), geometry(RoutePositionQuality.STALE), 30f)!!
        assertEquals("GPS lost · last fix 9 s", minimap.content.foot?.left)
        assertEquals("1.2 km", minimap.content.foot?.right)
        assertNull(minimap.content.foot?.strong)
        val panel = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.PANEL), geometry(RoutePositionQuality.NONE, fixAgeS = null), 30f)!!
        assertEquals("GPS lost", panel.content.foot?.left)
    }

    @Test
    fun `a source that draws no route is named by its plugin, never a guessed app`() {
        val streets = RouteGeometry(
            spanMeters = 400.0,
            paths = listOf(RoutePath(RoutePathRole.STREET, listOf(RoutePoint(0, 500), RoutePoint(1000, 500)))),
            position = RoutePosition(RoutePositionQuality.LIVE, RoutePoint(500, 500)),
        )
        val island = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP, measure = "1.2 km"), streets, 30f, sourceLabel = "Navigation")!!
        assertEquals(IslandFoot(left = "09:52 arrival", right = "Navigation", strong = "09:52"), island.content.foot)
        assertEquals("1.2 km", island.content.measure)
        // Without a catalog name the foot names nothing.
        assertNull(GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP), streets, 30f)!!.content.foot?.right)
    }

    @Test
    fun `while the phone link is down an island is last known, approximate and offers nothing`() {
        val clock = { wallMs: Long -> "t$wallMs" }
        val guided = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP, stale = true, knownAt = 42), null, null, clock = clock)!!
        assertTrue(guided.content.approximate)
        assertEquals("offline · last known t42", guided.content.foot?.left)
        val text = GuidanceIslands.of(item(ActivityPresentation.CHIP, AmbientSize.TEXT, stale = true, knownAt = 42), null, null, clock = clock)!!
        assertTrue(text.content.approximate)

        val generic = item(ActivityPresentation.PANEL, size = null, stale = true, knownAt = 42, actions = listOf(ActivityAction("next", "Next", "next")))
        val shown = GuidanceIslands.lastKnown(generic, clock)
        assertEquals("offline · last known t42", shown.secondary)
        assertEquals(listOf("Rue de Rivoli", "then left"), shown.detail)
        assertTrue(shown.actions.isEmpty())
        assertEquals("120 m", shown.primary)
        // A live one is drawn as received.
        val live = item(ActivityPresentation.PANEL, size = null)
        assertEquals(live.activity.content, GuidanceIslands.lastKnown(live, clock))
    }

    @Test
    fun `link loss marks live entries last known until their own next update, and keeps them all`() {
        val staleness = LinkStaleness()
        staleness.confirmed("maps:activity", 1_000)
        staleness.confirmed("media:activity", 2_000)
        val state = ActivityRenderState(
            items = listOf(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP)),
            collection = listOf(entry("maps:activity"), entry("media:activity")),
        )
        assertEquals(state, staleness.apply(state))

        staleness.linkLost(listOf("maps:activity", "media:activity"))
        val lost = staleness.apply(state)
        assertEquals(2, lost.collection.size)
        assertTrue(lost.collection.all { it.stale })
        assertEquals(listOf(1_000L, 2_000L), lost.collection.map { it.knownAtWallMs })
        assertTrue(lost.items.single().stale)
        assertEquals(1_000L, lost.items.single().knownAtWallMs)

        // The link coming back changes nothing by itself; an entry's own update restores it.
        staleness.confirmed("maps:activity", 5_000)
        val restored = staleness.apply(state)
        assertFalse(restored.collection.first { it.surfaceId == "maps:activity" }.stale)
        assertTrue(restored.collection.first { it.surfaceId == "media:activity" }.stale)

        // An entry no longer held is forgotten.
        assertFalse(staleness.apply(state.copy(collection = listOf(entry("maps:activity")))).collection.any { it.stale })
        assertFalse(staleness.isStale("media:activity"))
    }

    @Test
    fun `the phone's explicit backend outranks the glasses' stored one, which outranks the default`() {
        assertEquals(LauncherBackend.SESSION, LauncherBackend.resolve(phone = "session", stored = "LEGACY", bundleNegotiated = true))
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = "legacy", stored = "SESSION", bundleNegotiated = true))
        assertEquals(LauncherBackend.SESSION, LauncherBackend.resolve(phone = null, stored = "SESSION", bundleNegotiated = false))
        assertEquals(LauncherBackend.SESSION, LauncherBackend.resolve(phone = "unknown", stored = "SESSION", bundleNegotiated = true))
    }

    @Test
    fun `nobody's choice keeps the legacy default in this lane, negotiated or not`() {
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.DEFAULT)
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = null, stored = null, bundleNegotiated = true))
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = null, stored = null, bundleNegotiated = false))
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = null, stored = "garbage", bundleNegotiated = true))
        assertNull(LauncherBackend.fromPhone(null))
    }

    private fun entry(surfaceId: String) = ActivityEntry(
        surfaceId = surfaceId,
        ownerPluginId = surfaceId.substringBefore(':'),
        seq = 1,
        content = content(),
        primary = false,
        startedOrder = 1,
        lastSignificantOrder = null,
    )

    private fun content(measure: String? = null, actions: List<ActivityAction> = emptyList()) = ActivitySurfaceContent(
        glyph = "straight",
        primary = "120 m",
        secondary = "Rue de Rivoli",
        progress = null,
        eta = "09:52",
        detail = listOf("then left"),
        actions = actions,
        maxDurationMs = null,
        measure = measure,
    )

    private fun item(
        presentation: ActivityPresentation,
        size: AmbientSize?,
        measure: String? = null,
        stale: Boolean = false,
        knownAt: Long? = null,
        actions: List<ActivityAction> = emptyList(),
    ) = ActivityRenderItem(
        activity = NexusActivitySurface(
            surfaceId = "maps:activity",
            ownerPluginId = "maps",
            seq = 1,
            content = content(measure, actions),
            corner = PinSurfacePosition.TOP_LEFT,
            startedOrder = 1,
            lastUpdatedOrder = 1,
            lastSignificantOrder = null,
            collapseAtMs = 0,
            maxDurationDeadlineMs = null,
            selectedActionIndex = -1,
            motionToken = 1,
        ),
        primary = true,
        presentation = presentation,
        size = size,
        stale = stale,
        knownAtWallMs = knownAt,
    )

    private fun geometry(
        quality: RoutePositionQuality,
        fixAgeS: Int? = if (quality == RoutePositionQuality.LIVE) null else 9,
    ) = RouteGeometry(
        spanMeters = 400.0,
        paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 1000), RoutePoint(500, 0)))),
        position = RoutePosition(quality, RoutePoint(500, 660), fixAgeS = fixAgeS),
    )
}
