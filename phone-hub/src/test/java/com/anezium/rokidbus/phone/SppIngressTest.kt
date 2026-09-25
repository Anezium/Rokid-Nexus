package com.anezium.rokidbus.phone

import android.os.IBinder
import android.os.Process
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.SppKeyProvisioning
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SppIngressTest {
    private val hub = Robolectric.buildService(BusHubService::class.java).get()
    private val received = mutableListOf<String>()
    private val callback = object : IBusCallback.Stub() {
        override fun onMessage(path: String, id: String, payload: ByteArray) { received += path }
        override fun onBinaryMessage(path: String, id: String, meta: ByteArray, data: ByteArray) { received += path }
        override fun onLinkState(state: Int) = Unit
        override fun onGlassesAiButton(active: Boolean) = Unit
    }
    private val binder get() = field("binder") as IBusService
    @Suppress("UNCHECKED_CAST")
    private val registrations get() = field("registrations") as MutableList<Any>

    @Before fun registerObserverWithoutStartingRadios() {
        val type = BusHubService::class.java.declaredClasses.single { it.simpleName == "Registration" }
        val constructor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        registrations += constructor.newInstance(
            "observer", listOf("/hub"), Process.myUid() + 1,
            callback.asBinder(), callback, IBinder.DeathRecipient {}, null, emptySet<Any>(),
        )
    }

    @After fun clearObserver() {
        registrations.clear()
    }

    @Test fun binderJsonAndBinaryDropReservedControlsBeforeDispatch() {
        for (path in listOf("/hub/spp", "/hub/spp/", SppKeyProvisioning.PATH, "/hub/spp/unknown")) {
            val offer = SppKeyProvisioning.offer(ByteArray(32) { 42 }).copy(path = path)
            val payload = offer.payload.toString().toByteArray()
            binder.send(path, offer.id, payload)
            binder.sendBinary(path, offer.id, payload, byteArrayOf(1))
        }
        assertTrue(received.isEmpty())
        binder.send("/hub/spp-visible", "control", "{}".toByteArray())
        assertEquals(listOf("/hub/spp-visible"), received)
    }

    @Test fun sppIngressDropsReservedControlsBeforeLocalDelivery() {
        val ingress = BusHubService::class.java.getDeclaredMethod("routeRemote", BusEnvelope::class.java).apply { isAccessible = true }
        for (path in listOf("/hub/spp", "/hub/spp/", SppKeyProvisioning.PATH, "/hub/spp/unknown")) {
            val offer = FrameProtocol.fromJsonBytes(FrameProtocol.toJsonBytes(SppKeyProvisioning.offer(ByteArray(32) { 42 })))
            ingress.invoke(hub, offer.copy(path = path))
            ingress.invoke(hub, offer.copy(path = path, binary = byteArrayOf(1)))
        }
        assertTrue(received.isEmpty())
        ingress.invoke(hub, BusEnvelope("/hub/spp-visible"))
        assertEquals(listOf("/hub/spp-visible"), received)
    }

    private fun field(name: String): Any = BusHubService::class.java.getDeclaredField(name).run {
        isAccessible = true
        requireNotNull(get(hub))
    }
}
