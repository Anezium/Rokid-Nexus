package com.anezium.rokidbus.glasses.page;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import com.anezium.rokidbus.client.HubTarget;
import com.anezium.rokidbus.client.PluginRegistrationResult;
import com.anezium.rokidbus.client.plugin.NexusPage;
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks;
import com.anezium.rokidbus.client.plugin.NexusPluginClient;
import com.anezium.rokidbus.client.plugin.NexusSdkResult;
import com.anezium.rokidbus.glasses.session.MapView;
import com.anezium.rokidbus.plugin.nav.*;
import com.anezium.rokidbus.shared.*;
import com.anezium.rokidbus.shared.plugin.PluginCapability;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Java can join the two apps' internal Kotlin implementations without publishing a new SDK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, qualifiers = "w480dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class RenderedMapJourneyFeedbackTest {
    @Test
    public void realPageDrawRenewalsKeepPendingJourneyTilesUntilCommandsHidesTheMap() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Handler main = new Handler(Looper.getMainLooper());
        Handler pageTimer = new Handler(Looper.getMainLooper());
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        AtomicInteger eventId = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NavJourneyService journey = Robolectric.buildService(NavJourneyService.class).get();
        NavRuntime runtime = ReflectionHelpers.callInstanceMethod(journey, "getRuntime");
        NavControl.INSTANCE.attachJourney(runtime, message -> Unit.INSTANCE);
        NexusPluginCallbacks callbacks = (NexusPluginCallbacks) Proxy.newProxyInstance(
            NexusPluginCallbacks.class.getClassLoader(), new Class<?>[] { NexusPluginCallbacks.class },
            (proxy, method, args) -> {
                if (method.getName().equals("onRenderedMapsChanged")) NavControl.INSTANCE.mapDemandChanged();
                return null;
            });
        NexusPluginClient client = NexusPluginClient.Companion.create(app, "nav", callbacks, HubTarget.Companion.getPHONE());
        ReflectionHelpers.setField(client, "registrationState", PluginRegistrationResult.APPROVED);
        ReflectionHelpers.setField(client, "approvedCapabilities", Collections.singleton(PluginCapability.SURFACES));
        ReflectionHelpers.setField(client, "hubCapabilities", BusCapabilityBits.LAUNCHER_INTERACTIVE | BusCapabilityBits.RENDERED_MAP);
        ReflectionHelpers.setField(client, "currentLinkState", LinkStateBits.SPP_DATA_UP);
        NavControl.INSTANCE.serviceCreated(client);
        List<GeoPoint> points = List.of(new GeoPoint(48.0, 2.0), new GeoPoint(48.01, 2.0));
        NavRoute route = new NavRoute(new NavDestination("There", points.get(1)), points, 900,
            List.of(new RouteStep(new RouteLine(points).getLengthMeters(), "ARRIVE", "There")), Collections.emptyList());
        NavRouteTracker tracker = new NavRouteTracker(route);
        NavBasemap loader = new NavBasemap(OsmShortbreadSource.INSTANCE, route.getLine(), null,
            (url, headers, limit) -> new TileCall() {
                @Override public TileResponse execute() {
                    entered.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { throw new AssertionError("Pending tile wait interrupted", interrupted); }
                    return new TileResponse(200, Collections.singletonMap("cache-control", "max-age=300"), new byte[0]);
                }
                @Override public void cancel() { cancelled.incrementAndGet(); release.countDown(); }
            },
            task -> { main.post(task); return Unit.INSTANCE; }, () -> Unit.INSTANCE,
            Executors.newSingleThreadExecutor(), SystemClock::elapsedRealtime, System::currentTimeMillis,
            (key, body, line) -> { throw new AssertionError("The pending cancelled response must not be decoded"); });
        ReflectionHelpers.setField(journey, "tracker", tracker);
        ReflectionHelpers.setField(journey, "basemap", loader);
        NavControl.INSTANCE.setMapDemandListener(ReflectionHelpers.getField(journey, "mapDemand"));
        NavRouteSnapshot initial = snapshot(route, 100);
        NavState.INSTANCE.setJourney(initial);
        runtime.onJourney(initial);
        NexusPage.Content[] accepted = new NexusPage.Content[1];
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create().start().resume();
        RouteMapView map = new RouteMapView(controller.get(), view -> new RenderedMapLease(view,
            client::getSupportsRenderedMaps, payload -> {
                assertTrue(RenderedMapContract.INSTANCE.validEvent(payload));
                if (payload.optBoolean("visible")) assertEquals(accepted[0].getRevision(), payload.optLong("revision"));
                else hides.incrementAndGet();
                client.onMessage(RenderedMapContract.PATH, "draw-" + eventId.incrementAndGet(),
                    RenderedMapContract.INSTANCE.delivery(payload, "nav"));
                return true;
            }, SystemClock::uptimeMillis));
        Runnable draw = () -> map.draw(new Canvas(Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888)));
        Runnable render = () -> {
            RouteGeometry geometry = ((PageBody.Route) accepted[0].getBody()).getFollow();
            map.bind(new MapInput(geometry, MapForm.COMPACT, MapView.FOLLOW, null, false, new MapInsets(0, 0), true,
                new RenderedMapContext("page", NavPages.MAIN, accepted[0].getRevision(), 0, 1, 1, "request")));
            draw.run();
        };
        NavPageProvider<String> pages = new NavPageProvider<>(id -> id, id -> null,
            (request, page) -> {
                accepted[0] = (NexusPage.Content) page;
                publications.incrementAndGet();
                main.post(render);
                return NexusSdkResult.SENT;
            },
            () -> new NavPageState(NavState.INSTANCE.getGuidance(), true, true, null, true,
                NavState.INSTANCE.getJourney(), null, null, true, NavState.INSTANCE.getBasemapState(), true),
            () -> new NavPageText("access", "off", "idle", "Arrived"), SystemClock::elapsedRealtime,
            (delay, task) -> { pageTimer.postDelayed(() -> task.invoke(), delay); return Unit.INSTANCE; },
            () -> { pageTimer.removeCallbacksAndMessages(null); return Unit.INSTANCE; },
            () -> NavControl.INSTANCE.getJourneyEpoch(), visible -> Unit.INSTANCE,
            (page, state) -> {
                NavControl.INSTANCE.getRenderedFrames().remember(page.getRevision(), state.getJourney(), NavPages.MAIN);
                return Unit.INSTANCE;
            });
        NavControl.INSTANCE.setPageListener(() -> { pages.guidanceChanged(); return Unit.INSTANCE; });
        try {
            accepted[0] = (NexusPage.Content) pages.onRequest(NavPages.MAIN);
            pages.onVisibility(NavPages.MAIN, true);
            VisibleMapTestWindow.attach(controller, map);
            assertEquals(View.VISIBLE, map.getWindowVisibility());
            render.run();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int renewal = 0; renewal < 3; renewal++) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RenderedMapContract.RENEW_MS));
                draw.run();
                shadowOf(Looper.getMainLooper()).idle();
            }
            assertEquals(0, publications.get());
            assertEquals(0, cancelled.get());
            NavRouteSnapshot changed = snapshot(route, 110);
            NavState.INSTANCE.setJourney(changed);
            runtime.onJourney(changed);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, publications.get());
            assertEquals(accepted[0].getRevision(), client.getRenderedMapFrames().iterator().next().getRevision());
            assertEquals(0, hides.get());
            assertEquals(0, cancelled.get());
            map.setVisibility(View.GONE);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(hides.get() > 0);
            assertTrue(cancelled.get() > 0);
            assertTrue(client.getRenderedMapFrames().isEmpty());
            assertEquals(BasemapState.NONE, NavState.INSTANCE.getBasemapState());
        } finally {
            NavControl.INSTANCE.setPageListener(null);
            NavControl.INSTANCE.setMapDemandListener(null);
            pages.forget();
            loader.close();
            release.countDown();
            NavControl.INSTANCE.serviceDestroyed(client);
            client.close();
            journey.onDestroy();
            controller.pause().stop().destroy();
        }
    }

    private static NavRouteSnapshot snapshot(NavRoute route, double progress) {
        long now = SystemClock.elapsedRealtime();
        return new NavRouteSnapshot(route, progress, route.getLine().pointAt(progress), now, null, null,
            RouteQuality.TRACKING, now, NavBasemapTiles.Companion.getNONE());
    }
}
