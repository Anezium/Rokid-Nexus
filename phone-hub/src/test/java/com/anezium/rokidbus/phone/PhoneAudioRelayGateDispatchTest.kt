package com.anezium.rokidbus.phone

import android.bluetooth.BluetoothSocket
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import com.anezium.rokidbus.shared.SppAuthProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneAudioRelayGateDispatchTest {
    @Test fun `two thread requests evaluate fresh state on main and cannot connect after disable`() {
        val app = RuntimeEnvironment.getApplication()
        val hub = Robolectric.buildService(BusHubService::class.java).get()
        val store = PhoneAudioRelayStore(app)
        store.setEnabled(true)
        val audio = app.getSystemService(AudioManager::class.java)
        shadowOf(audio).setOutputDevices(listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()))
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java)
        socket.connect()
        assertTrue(socket.isConnected)
        assertEquals(PhoneTtsRoute.EXTERNAL_SINK, PhoneSpeakerRouteProbe(app) { null }.classifyRoute(PhoneAudioRelayPlayer.audioAttributes()))
        val session = SppAuthProtocol.Session::class.java.getDeclaredConstructor(ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.newInstance(ByteArray(32), ByteArray(32))
        val launched = CopyOnWriteArrayList<() -> Unit>()
        val client = PhoneAudioRelayClient({ error("Disabled gate must not connect") }, { _, _ -> error("No playback") }, {},
            { _, _ -> ({}) }, { 0 }, launch = { launched += it })
        fun field(name: String, value: Any) = BusHubService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(hub, value)
        field("audioRelayClient", client); field("audioRelayStore", store)
        field("audioRelayRouteProbe", PhoneSpeakerRouteProbe(app) { null })
        field("remoteAudioRelayCapable", true); field("sppSession", session); field("socket", socket)
        val update = BusHubService::class.java.getDeclaredMethod("updateAudioRelayGate").apply { isAccessible = true }
        try {
            Thread { update.invoke(hub) }.apply { start(); join() }
            assertTrue("Background request must not apply a true gate", launched.isEmpty())
            Thread { store.setEnabled(false); update.invoke(hub) }.apply { start(); join() }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Both queued evaluations must observe the current disabled setting", launched.isEmpty())
            assertEquals(PhoneAudioRelayStatus.OFF, store.status())
        } finally { client.close(); socket.close() }
    }
}
