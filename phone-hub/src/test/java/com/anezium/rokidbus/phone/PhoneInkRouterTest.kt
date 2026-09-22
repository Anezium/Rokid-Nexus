package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class PhoneInkRouterTest {
    private val sink = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.INK_SURFACE)
    private val released = mutableListOf<Pair<String, Boolean>>()
    private val surfaceRouter = PhoneSurfaceRouter(
        sink,
        onPluginSelfHid = { pluginId, detach -> released += pluginId to detach },
        nowMs = { 1_000L },
    )
    private val worker = QueuedExecutor()
    private val posted = ArrayDeque<() -> Unit>()
    private val coordinator = PhoneInkSurfaceCoordinator(
        postResult = { posted.addLast(it) },
        worker = worker,
    )
    private val router = PhoneInkRouter(sink, coordinator, surfaceRouter)

    @After
    fun close() {
        router.close()
    }

    @Test
    fun `an invalid owner is rejected before compiling or publishing`() {
        val envelope = command(BusPaths.INK_SHOW).also { it.payload.put("surfaceId", "other:main") }

        router.handleLocal(envelope, hudSender("hello"))
        drain()

        assertEquals("INVALID_SURFACE_ID", sink.errors.single().second)
        assertEquals(PluginBusJournal.Verdict.REJECTED, sink.localRoutes.single().verdict)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `binary Ink is rejected with a typed owner-scoped problem`() {
        router.handleLocal(command(BusPaths.INK_SHOW).copy(binary = byteArrayOf(1)), hudSender("hello"))

        val reply = sink.local.single()
        assertEquals(BusPaths.INK_EVENT, reply.path)
        assertEquals("hello", reply.payload.getString("pluginId"))
        assertEquals("main", reply.payload.getString("surfaceId"))
        assertEquals("INK_WIRE_TYPE", reply.payload.getJSONArray("problems").getJSONObject(0).getString("code"))
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `missing capability rejects a show but still allows closing a live session`() {
        show()
        sink.capabilityBits = 0
        router.handleLocal(command(BusPaths.INK_SHOW, "other"), hudSender("other"))
        router.handleLocal(command(BusPaths.INK_HIDE), hudSender("hello"))
        drain()

        assertEquals("CAPABILITY_NOT_AVAILABLE", sink.local.single().payload.getJSONArray("problems")
            .getJSONObject(0).getString("code"))
        assertEquals(listOf(BusPaths.SURFACE_SHOW, BusPaths.SURFACE_HIDE), sink.remote.map { it.path })
        assertTrue(released.isEmpty())
    }

    @Test
    fun `show and update publish sequential ordinary surface envelopes`() {
        show()
        router.handleLocal(
            command(BusPaths.INK_UPDATE).also { it.payload.put("data", JSONObject().put("value", "updated")) },
            hudSender("hello"),
        )
        drain()

        assertEquals(listOf(BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE), sink.remote.map { it.path })
        assertEquals(listOf(1_001L, 1_002L), sink.remote.map { it.payload.getLong("seq") })
        assertTrue(sink.remote.all { it.payload.getString("surfaceId") == "hello:main" })
        assertTrue(sink.remote.all { it.payload.getString("kind") == InkSurfaceContract.KIND })
        assertTrue(sink.remote.none { it.payload.has("epoch") })
        assertTrue(sink.localRoutes.all { it.verdict == PluginBusJournal.Verdict.OK })
    }

    @Test
    fun `a failed publish closes both the replaced session and the new session`() {
        show()
        sink.sendRemoteError = "NO_LINK"
        router.handleLocal(command(BusPaths.INK_SHOW, "other"), hudSender("other"))
        drain()

        val error = sink.local.single { it.payload.getString("type") == InkSurfaceContract.EVENT_ERROR }
        assertEquals("other", error.payload.getString("pluginId"))
        assertEquals("CAPABILITY_NOT_AVAILABLE", error.payload.getJSONArray("problems")
            .getJSONObject(0).getString("code"))
        val closed = sink.local.filter { it.payload.getString("type") == InkSurfaceContract.EVENT_CLOSED }
        assertEquals(setOf("hello", "other"), closed.map { it.payload.getString("pluginId") }.toSet())
        assertTrue(closed.all { it.payload.getString("reason") == InkSurfaceContract.CLOSE_LINK_LOST })
        assertEquals(setOf("hello" to false, "other" to false), released.toSet())
        assertTrue(surfaceRouter.hidePlugin("other").isEmpty())
    }

    @Test
    fun `a successful replacement closes only the outgoing owner`() {
        show()
        router.handleLocal(command(BusPaths.INK_SHOW, "other"), hudSender("other"))
        drain()

        val closed = sink.local.single()
        assertEquals("hello", closed.payload.getString("pluginId"))
        assertEquals(InkSurfaceContract.CLOSE_REPLACED, closed.payload.getString("reason"))
        assertEquals(listOf("hello" to false), released)
        assertEquals(listOf("other:main"), surfaceRouter.hidePlugin("other"))
    }

    @Test
    fun `glasses action targets the compiled owner and preserves its dataset`() {
        show()
        router.handleGlassesEvent(event(InkSurfaceContract.EVENT_ACTION).also {
            it.payload.put("pluginId", "other")
                .put("actionId", "pick")
                .put("dataset", JSONObject().put("row", 7))
        })
        drain()

        val reply = sink.local.single()
        assertEquals("hello", reply.payload.getString("pluginId"))
        assertEquals("main", reply.payload.getString("surfaceId"))
        assertEquals("pick", reply.payload.getString("actionId"))
        assertEquals(7, reply.payload.getJSONObject("dataset").getInt("row"))
    }

    @Test
    fun `only the matching glasses close releases the compiled surface`() {
        show()
        router.handleGlassesEvent(event(InkSurfaceContract.EVENT_CLOSED, "other").also {
            it.payload.put("reason", InkSurfaceContract.CLOSE_PLUGIN)
        })
        drain()
        assertTrue(released.isEmpty())

        router.handleGlassesEvent(event(InkSurfaceContract.EVENT_CLOSED).also {
            it.payload.put("reason", InkSurfaceContract.CLOSE_PLUGIN)
        })
        drain()
        assertEquals(listOf("hello" to false), released)
        assertEquals(InkSurfaceContract.CLOSE_PLUGIN, sink.local.single().payload.getString("reason"))
    }

    @Test
    fun `a resync republishes the document with a newer sequence`() {
        show()
        router.handleGlassesEvent(event(InkSurfaceContract.EVENT_RESYNC))
        drain()

        assertEquals(listOf(BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE), sink.remote.map { it.path })
        assertEquals(1_002L, sink.remote.last().payload.getLong("seq"))
        assertTrue(sink.remote.last().payload.getJSONObject("ink").has("document"))
    }

    @Test
    fun `link loss notifies the owner once and removes the live session`() {
        show()
        router.clearForLinkLoss()
        drain()
        router.clearForLinkLoss()
        drain()

        assertEquals(1, sink.local.size)
        assertEquals("hello", sink.local.single().payload.getString("pluginId"))
        assertEquals(InkSurfaceContract.CLOSE_LINK_LOST, sink.local.single().payload.getString("reason"))
        assertEquals(listOf("hello" to false), released)
        assertTrue(surfaceRouter.hidePlugin("hello").isEmpty())
    }

    @Test
    fun `revoking an owner cancels a compiled result queued for publication`() {
        router.handleLocal(command(BusPaths.INK_SHOW), hudSender("hello"))
        worker.runAll()
        val pendingPublish = posted.removeFirst()
        var cleared = emptyList<PhoneInkSurfaceOwner>()

        router.clearOwner("hello") { cleared = it }
        pendingPublish()
        drain()

        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.local.isEmpty())
        assertEquals(listOf(PhoneInkSurfaceOwner("hello", "main", "hello:main")), cleared)
    }

    @Test
    fun `link loss before a queued compile prevents its later publication`() {
        router.handleLocal(command(BusPaths.INK_SHOW), hudSender("hello"))
        router.clearForLinkLoss()
        drain()

        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.local.isEmpty())
        assertTrue(released.isEmpty())
    }

    @Test
    fun `closing the router prevents a previously compiled result from publishing`() {
        router.handleLocal(command(BusPaths.INK_SHOW), hudSender("hello"))
        worker.runAll()
        val pendingPublish = posted.removeFirst()

        router.close()
        pendingPublish()

        assertTrue(sink.remote.isEmpty())
        assertTrue(worker.isShutdown())
    }

    @Test
    fun `malformed glasses events never reach the plugin`() {
        listOf(
            event(InkSurfaceContract.EVENT_READY).copy(binary = byteArrayOf(1)),
            event("unknown"),
            event(InkSurfaceContract.EVENT_CLOSED).also { it.payload.put("reason", "unknown") },
            event(InkSurfaceContract.EVENT_ACTION),
        ).forEach(router::handleGlassesEvent)
        drain()

        assertEquals(
            listOf("INK_EVENT_BINARY", "INVALID_INK_EVENT", "INVALID_INK_CLOSE_REASON", "INVALID_INK_ACTION"),
            sink.remoteRoutes.map { it.reason },
        )
        assertTrue(sink.local.isEmpty())
    }

    private fun show() {
        router.handleLocal(command(BusPaths.INK_SHOW), hudSender("hello"))
        drain()
    }

    private fun command(path: String, pluginId: String = "hello") = BusEnvelope(
        path = path,
        payload = JSONObject()
            .put("ownerPluginId", pluginId)
            .put("localSurfaceId", "main")
            .put("surfaceId", "$pluginId:main")
            .put("page", PAGE),
    )

    private fun event(type: String, pluginId: String = "hello") = BusEnvelope(
        path = BusPaths.INK_EVENT,
        payload = JSONObject().put("surfaceId", "$pluginId:main").put("type", type),
    )

    private fun drain() {
        while (worker.hasTasks() || posted.isNotEmpty()) {
            worker.runAll()
            while (posted.isNotEmpty()) posted.removeFirst().invoke()
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        fun hasTasks(): Boolean = tasks.isNotEmpty()

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.addLast(command)
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated()
    }

    private companion object {
        val PAGE = """
            <script type="application/json" def>{"data":{"value":"zero"}}</script>
            <page><view><text>{{ value }}</text></view></page>
            <style></style>
        """.trimIndent()
    }
}
