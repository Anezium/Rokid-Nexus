package com.anezium.rokidbus.phone;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.anezium.rokidbus.client.PluginRegistrationResult;
import com.anezium.rokidbus.client.plugin.*;
import com.anezium.rokidbus.plugin.nav.*;
import com.anezium.rokidbus.shared.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 32)
@LooperMode(LooperMode.Mode.PAUSED)
public class RenderedMapReconnectTest {
    @Test
    public void serviceNegotiationGatesRetainedStreetsBeforeQueuedSdkReconnectCallbacksFlushThem() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON);
        BusHubService hub = Robolectric.buildService(BusHubService.class).get();
        PhoneRenderedMapNegotiation negotiation = ReflectionHelpers.getField(hub, "renderedMapNegotiation");
        ReflectionHelpers.setField(hub, "remoteLauncherInteractiveVersion", LauncherInteractiveContract.VERSION);
        ReflectionHelpers.setField(hub, "remoteActivitySurfaceVersion", ActivitySurfaceContract.VERSION);
        List<RouteGeometry> delivered = new ArrayList<>();
        NexusPluginTransport transport = new NexusPluginTransport() {
            @Override public void connect(Listener listener) { }
            @Override public boolean send(String path, String id, JSONObject payload) { return true; }
            @Override public boolean sendImmediate(String path, String id, JSONObject payload) {
                if (path.equals(BusPaths.ACTIVITY_GEOMETRY)) {
                    delivered.add(ActivitySurfaceContract.INSTANCE.parseGeometry(payload).getGeometry());
                }
                return true;
            }
            @Override public boolean sendBinary(String path, String id, JSONObject payload, byte[] data) { return true; }
            @Override public int capabilities() { return ReflectionHelpers.callInstanceMethod(hub, "capabilities"); }
            @Override public String approvedCapabilities() { return "surfaces"; }
            @Override public void close() { }
        };
        NavMapPublisher[] publisher = new NavMapPublisher[1];
        Handler main = new Handler(Looper.getMainLooper());
        NexusPluginCallbacks callbacks = (NexusPluginCallbacks) Proxy.newProxyInstance(
            NexusPluginCallbacks.class.getClassLoader(), new Class<?>[] { NexusPluginCallbacks.class },
            (proxy, method, args) -> {
                if (method.getName().equals("onLinkState") && publisher[0] != null) publisher[0].linkChanged();
                // The journey's demand callback is posted behind the already queued link-up callback.
                if (method.getName().equals("onRenderedMapsChanged")) main.post(() -> { });
                return null;
            });
        NexusPluginClient client = new NexusPluginClient("nav", callbacks, transport, SystemClock::elapsedRealtime);
        try {
            connect(hub, negotiation);
            negotiation.announce(announcement(true));
            client.onRegistrationState(PluginRegistrationResult.APPROVED);
            client.onLinkState(LinkStateBits.SPP_DATA_UP);
            assertTrue(client.getSupportsRenderedMaps());
            List<GeoPoint> points = List.of(new GeoPoint(52.5163, 13.3765), new GeoPoint(52.5163, 13.371));
            NavRoute route = new NavRoute(new NavDestination("There", points.get(1)), points, 600,
                List.of(new RouteStep(new RouteLine(points).getLengthMeters(), "ARRIVE", "There")), Collections.emptyList());
            TileKey key = new TileKey(14, 8800, 5373);
            LocalTile tile = new LocalTile(key, List.of(new LocalLine(StreetClass.STREET,
                new double[] {-100, 100}, new double[] {30, 30}, null)), Collections.emptyList());
            NavRouteSnapshot retained = new NavRouteSnapshot(route, 0, points.get(0), SystemClock.elapsedRealtime(), null, null,
                RouteQuality.TRACKING, SystemClock.elapsedRealtime(), new NavBasemapTiles("© OpenStreetMap", 14, Map.of(key, tile)));
            publisher[0] = new NavMapPublisher(client::supportsLauncherInteractive, client::publishActivityGeometry,
                (deg, accuracy) -> NexusSdkResult.SENT, SystemClock::elapsedRealtime,
                state -> NavMapProjection.INSTANCE.geometry(state, NavMapView.FOLLOW, new NavMapBudget(24, 1024, 16384)),
                client::getSupportsRenderedMaps, (revision, state) -> Unit.INSTANCE);
            publisher[0].update(retained, false);
            publisher[0].activityStarted();
            assertNotNull(delivered.get(delivered.size() - 1).getAttribution());
            ReflectionHelpers.callInstanceMethod(hub, "closeSocket", ClassParameter.from(BluetoothSocket.class, (BluetoothSocket) null));
            assertEquals(0, transport.capabilities() & BusCapabilityBits.RENDERED_MAP);
            assertNotEquals(0, transport.capabilities() & BusCapabilityBits.LAUNCHER_INTERACTIVE);
            connect(hub, negotiation);
            assertEquals(0, transport.capabilities() & BusCapabilityBits.RENDERED_MAP);
            main.post(() -> client.onLinkState(0));
            main.post(() -> client.onLinkState(LinkStateBits.SPP_DATA_UP));
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(client.getSupportsRenderedMaps());
            assertNull(delivered.get(delivered.size() - 1).getAttribution());
            negotiation.announce(announcement(false));
            client.onLinkState(LinkStateBits.SPP_DATA_UP);
            assertFalse(client.getSupportsRenderedMaps());
            assertNull(delivered.get(delivered.size() - 1).getAttribution());
            negotiation.announce(announcement(true));
            client.onLinkState(LinkStateBits.SPP_DATA_UP);
            assertTrue(client.getSupportsRenderedMaps());
            assertNotNull(delivered.get(delivered.size() - 1).getAttribution());
            // A fresh CXR announcement may precede SPP authentication for the same new session.
            ReflectionHelpers.callInstanceMethod(hub, "closeSocket", ClassParameter.from(BluetoothSocket.class, (BluetoothSocket) null));
            negotiation.announce(announcement(true));
            assertEquals(0, transport.capabilities() & BusCapabilityBits.RENDERED_MAP);
            connect(hub, negotiation);
            client.onLinkState(LinkStateBits.SPP_DATA_UP);
            assertTrue(client.getSupportsRenderedMaps());
            assertNotNull(delivered.get(delivered.size() - 1).getAttribution());
        } finally {
            ReflectionHelpers.callInstanceMethod(hub, "closeSocket", ClassParameter.from(BluetoothSocket.class, (BluetoothSocket) null));
            client.close();
        }
    }

    private static GlassesHubCapabilities announcement(boolean current) throws Exception {
        return GlassesHubCapabilitiesContract.INSTANCE.parse(new JSONObject()
            .put("version", GlassesHubCapabilitiesContract.VERSION)
            .put("features", BusCapabilityBits.LAUNCHER_INTERACTIVE | (current ? BusCapabilityBits.RENDERED_MAP : 0))
            .put(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION, LauncherInteractiveContract.VERSION));
    }

    private static void connect(BusHubService hub, PhoneRenderedMapNegotiation negotiation) throws Exception {
        BluetoothSocket socket = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01")
            .createInsecureRfcommSocketToServiceRecord(BusConstants.INSTANCE.getSPP_UUID());
        socket.connect(); // Robolectric's socket shadow; no device or radio.
        java.lang.reflect.Constructor<SppAuthProtocol.Session> constructor = SppAuthProtocol.Session.class
            .getDeclaredConstructor(byte[].class, byte[].class);
        constructor.setAccessible(true);
        SppAuthProtocol.Session session = constructor.newInstance(new byte[32], new byte[32]);
        negotiation.sessionStarted(session);
        ReflectionHelpers.setField(hub, "socket", socket);
        ReflectionHelpers.setField(hub, "sppSession", session);
    }
}
