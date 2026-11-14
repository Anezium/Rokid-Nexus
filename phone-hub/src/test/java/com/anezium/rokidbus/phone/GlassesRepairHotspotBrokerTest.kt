package com.anezium.rokidbus.phone

import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesHotspotCredentials
import com.anezium.rokidbus.shared.GlassesRepairHotspotContract as Wire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GlassesRepairHotspotBrokerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val messages = mutableListOf<BusEnvelope>()
    private val released = mutableListOf<String>()
    private val offers = mutableListOf<(GlassesFilesHotspotCredentials) -> Unit>()
    private val closed = mutableListOf<() -> Unit>()
    private var acquisitions = 0
    private val id = "a".repeat(32)
    private val credentials = GlassesFilesHotspotCredentials("temporary", "ephemeral-password", "wpa2")

    private fun broker(): GlassesRepairHotspotBroker {
        shadowOf(context).grantPermissions(GlassesFilesHotspotHost.permission())
        return GlassesRepairHotspotBroker(context,
            acquire = { _, _, offered, onClosed -> acquisitions++; offers += offered; closed += onClosed; true },
            release = { released += it }, send = { messages += it; null })
    }

    private fun request(broker: GlassesRepairHotspotBroker, requestId: String = id) {
        broker.handle(BusEnvelope(path = Wire.PATH, id = requestId,
            payload = Wire.message("request").put("automatic", true)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `duplicates retain one reservation and replay its offer without extending the deadline`() {
        val broker = broker()
        try {
            request(broker)
            request(broker)
            assertEquals(1, acquisitions)
            assertTrue(messages.isEmpty())
            offers.single()(credentials)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Wire.TIMEOUT_MS / 2))
            request(broker)
            assertEquals(listOf("offer", "offer"), messages.map { Wire.kind(it.payload) })
            assertEquals(1, acquisitions)
            assertTrue(released.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Wire.TIMEOUT_MS / 2 + 1))
            assertEquals(listOf(id), released)
            assertEquals("unavailable", Wire.kind(messages.last().payload))
        } finally { broker.close() }
    }

    @Test fun `a stale release and an old close callback cannot end the current attempt`() {
        val broker = broker()
        try {
            request(broker)
            broker.handle(BusEnvelope(path = Wire.PATH, id = "b".repeat(32), payload = Wire.message("release")))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(released.isEmpty())
            broker.handle(BusEnvelope(path = Wire.PATH, id = id, payload = Wire.message("release")))
            shadowOf(Looper.getMainLooper()).idle()
            request(broker, "b".repeat(32))
            closed.first()()
            shadowOf(Looper.getMainLooper()).idle()
            offers.last()(credentials)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, acquisitions)
            assertEquals(listOf(id), released)
            assertEquals("offer", Wire.kind(messages.last().payload))
            assertEquals("b".repeat(32), messages.last().id)
        } finally { broker.close() }
    }
}
