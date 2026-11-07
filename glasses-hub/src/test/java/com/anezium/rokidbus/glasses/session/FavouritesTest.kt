package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEvent.Enter
import com.anezium.rokidbus.glasses.session.SessionEvent.Step
import com.anezium.rokidbus.glasses.session.SessionEvent.Tick
import com.anezium.rokidbus.glasses.session.SessionEvent.TripleTap
import com.anezium.rokidbus.shared.LauncherFavourite
import com.anezium.rokidbus.shared.LauncherFavourites
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {
    private val apps = listOf(
        AppEntry("plugin:meta-messages", "Meta Messages", AppKind.PAGES, "meta-messages"),
        AppEntry("plugin:agents", "Agents", AppKind.IMMERSION, "agents"),
        AppEntry("app:com.android.camera", "Camera", AppKind.NATIVE, icon = IconRef.Native("com.android.camera")),
    )

    @Test
    fun `favourites resolve in the owner's order and open as their Applications row`() {
        val stops = Favourites.resolve(listOf("app:com.android.camera", "plugin:meta-messages", "plugin:agents"), apps)
        assertEquals(listOf("Camera", "Meta Messages", "Agents"), stops.map { it.label })
        assertEquals(FavouriteOpen.Local("app:com.android.camera", "Camera"), stops[0].open)
        assertEquals(FavouriteOpen.Page("meta-messages:main"), stops[1].open)
        assertEquals(FavouriteOpen.Launch("agents"), stops[2].open)
        // No catalog mark draws the generic grid, never a guessed one.
        assertEquals(IconRef.Place(IconPlace.GRID), stops[1].icon)
    }

    @Test
    fun `an entry the catalog no longer lists is left out`() {
        val stops = Favourites.resolve(listOf("plugin:gone", "plugin:agents"), apps)
        assertEquals(listOf("plugin:agents"), stops.map { it.key })
    }

    @Test
    fun `the root shows favourites under the activities and above Notifications`() {
        val activity = ActivityStop("maps", "maps:route", "300 m", pinned = true)
        val stops = RootStops.build(listOf(activity), null, 0, Favourites.resolve(apps.map { it.key }, apps))
        assertEquals(
            listOf(RootStopKind.ACTIVITY, RootStopKind.FAVOURITE, RootStopKind.FAVOURITE, RootStopKind.FAVOURITE, RootStopKind.NOTIFICATIONS, RootStopKind.APPLICATIONS),
            stops.map { it.kind },
        )
    }

    @Test
    fun `a full root keeps Notifications and Applications with four favourites`() {
        val many = (1..4).map { ActivityStop("a$it", "a$it:main", "x$it", ownerPluginId = "a$it") }
        val four = (1..4).map { AppEntry("plugin:p$it", "P$it", AppKind.IMMERSION, "p$it") }
        val stops = RootStops.build(many, NoticePreview("relay:thread", "x"), 1, Favourites.resolve(four.map { it.key }, four))
        assertEquals(4, stops.count { it.kind == RootStopKind.FAVOURITE })
        assertEquals(listOf(RootStopKind.NOTIFICATIONS, RootStopKind.APPLICATIONS), stops.takeLast(2).map { it.kind })
    }

    @Test
    fun `tapping a favourite opens its page, launches its app or launches the native app`() {
        val reducer = SessionReducer(favourites = { Favourites.resolve(apps.map { it.key }, apps) })
        var model = SessionModel()
        fun send(event: SessionEvent): List<SessionEffect> = reducer.reduce(model, event).also { model = it.model }.effects
        fun openRootOn(index: Int) {
            send(TripleTap(1_000))
            send(Tick(1_000 + PageSurfaceContract.GATE_MS))
            repeat(index) { send(Step(1, 1_500)) }
        }

        openRootOn(0)
        val page = send(Enter(2_000)).filterIsInstance<SessionEffect.RequestPage>().single()
        assertEquals("meta-messages:main", page.request.correlation.pageId)

        model = SessionModel()
        openRootOn(1)
        assertEquals("agents", send(Enter(2_000)).filterIsInstance<SessionEffect.SendLauncherOpen>().single().pluginId)

        model = SessionModel()
        openRootOn(2)
        assertEquals(listOf(SessionEffect.LaunchLocal("app:com.android.camera", "Camera")), send(Enter(2_000)))
    }

    @Test
    fun `the phone's list replaces the last one within a link and survives its end`() {
        val state = FavouritesState()
        state.restore(listOf("plugin:agents"))
        assertEquals(listOf("plugin:agents"), state.keys)
        assertTrue(state.apply(LauncherFavourites(5, listOf(LauncherFavourite.Plugin("meta-messages")))))
        // An older revision on the same link is late, not newer.
        assertFalse(state.apply(LauncherFavourites(4, listOf(LauncherFavourite.Plugin("agents")))))
        assertEquals(listOf("plugin:meta-messages"), state.keys)
        // A phone reinstalled meanwhile counts from zero again.
        state.linkReset()
        assertTrue(state.apply(LauncherFavourites(0, listOf(LauncherFavourite.NativeApp("com.android.camera")))))
        assertEquals(listOf("app:com.android.camera"), state.keys)
    }

    @Test
    fun `an empty list after a cold start is taken and replaces the stored one`() {
        val state = FavouritesState()
        state.restore(listOf("plugin:agents"))
        assertTrue(state.apply(LauncherFavourites(1, emptyList())))
        assertEquals(emptyList<String>(), state.keys)
        // A later restore in the same process never brings the old list back.
        state.restore(listOf("plugin:agents"))
        assertEquals(emptyList<String>(), state.keys)
    }
}
