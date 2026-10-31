package com.anezium.rokidbus.client.plugin

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [NexusPluginServiceForegroundTest.SessionShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NexusPluginServiceForegroundTest {
    class Plugin : NexusPluginService() {
        var request: () -> NexusPage = {
            NexusPage.Content(1, "Tasks", PageBody.SelectableList(listOf(PageRow("eggs", "Eggs"))),
                actions = listOf(NexusPageActionSpec("check", "Check")))
        }
        var action: () -> NexusPageResult = { NexusPageResult.done() }
        var command: () -> NexusActivityCommandResult? = { NexusActivityCommandResult.done() }
        var pendingCommand: NexusActivityCommand? = null
        var opens = 0
        var closes = 0
        override fun onNexusOpen() { opens++ }
        override fun onNexusOpen(openType: String) {
            if (openType == PluginOpenTypes.ADOPTED) stopNexusSessionForeground() else onNexusOpen()
        }
        override fun onNexusClose() { closes++ }
        override fun onNexusInput(event: NexusInputEvent) = Unit
        override fun onNexusPageRequest(request: NexusPageRequest) = this.request()
        override fun onNexusPageAction(action: NexusPageAction) = this.action()
        override fun onNexusActivityCommand(command: NexusActivityCommand): NexusActivityCommandResult? {
            pendingCommand = command
            return this.command()
        }
        fun hold(types: Int = 0) = holdNexusOngoingWork(types)
        fun release() = releaseNexusOngoingWork()
        fun promote(types: Int = 0) = promoteNexusSessionForeground(types)
        fun stop() = stopNexusSessionForeground()
    }

    @Implements(Service::class)
    class SessionShadow : ShadowService() {
        var active = false
        var failure: RuntimeException? = null
        var notification: Notification? = null
        var types = 0
        var transitionThread: Thread? = null
        @Implementation
        override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            failure?.let { throw it }
            active = true
            transitionThread = Thread.currentThread()
            this.notification = notification
            types = foregroundServiceType
            super.startForeground(id, notification, foregroundServiceType)
        }
        @Implementation
        override fun stopForeground(flags: Int) {
            active = false
            transitionThread = Thread.currentThread()
            super.stopForeground(flags)
        }
    }

    private val service = Robolectric.buildService(Plugin::class.java).get()
    private val lifecycle: SessionShadow get() = Shadow.extract(service)
    private val sends = mutableListOf<String>()
    private var sending: (String) -> Unit = {}
    private val transport = object : NexusPluginTransport {
        override fun connect(listener: NexusPluginTransport.Listener) = Unit
        override fun capabilities() = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE
        override fun approvedCapabilities() = "surfaces"
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sending(path)
            sends += path
            return true
        }
        override fun sendImmediate(path: String, id: String, payload: JSONObject) = send(path, id, payload)
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun close() = Unit
    }
    private val client = NexusPluginClient("sample", service, transport, SystemClock::elapsedRealtime).also {
        ReflectionHelpers.setField(service, "client", it)
        it.foregroundDemandChanged = { ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshSessionForeground") }
        it.onRegistrationState(PluginRegistrationResult.APPROVED)
        it.onLinkState(LinkStateBits.SPP_DATA_UP)
    }
    private var event = 0
    private fun deliver(path: String, payload: JSONObject) =
        client.onMessage(path, "e${event++}", payload.put("pluginId", "sample"))

    private fun openPage(frame: Int = 1) {
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1).put("launcherInteractiveVersion", 1))
        deliver(BusPaths.PAGE_REQUEST, PageSurfaceContract.correlationPayload(PageCorrelation("r$frame", 1, frame, "sample:main"))
            .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"))
    }
    private fun visible(value: Boolean, lease: Long = 120_000, frame: Int = 1) = deliver(BusPaths.PAGE_VISIBILITY,
        JSONObject().put("pageId", "sample:main").put("sessionGeneration", 1).put("frameIndex", frame)
            .put("visible", value).apply {
                if (value) put("leaseUntilMs", 999_999L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, lease)
            }
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"))
    private fun closePage(frame: Int = 1) = deliver(BusPaths.PAGE_CLOSED,
        JSONObject().put("pageId", "sample:main").put("sessionGeneration", 1).put("frameIndex", frame)
            .put("reason", "back").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"))
    private fun command(id: String = "i1") = deliver(BusPaths.ACTIVITY_ACTION,
        JSONObject().put("activityId", "sample:activity").put("id", "check").put("invocationId", id).put("seq", 1))

    @Test
    fun `a page request promotes before callback and uses the existing special use notification`() {
        val answer = service.request
        service.request = { assertTrue(lifecycle.active); answer() }
        sending = { assertTrue(lifecycle.active) }
        openPage()
        assertTrue(lifecycle.active)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, lifecycle.types)
        assertEquals("Active on your glasses", lifecycle.notification!!.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf(BusPaths.PAGE_RESPONSE), sends)
        assertEquals(0, service.opens)
        assertEquals(0, service.closes)
    }

    @Test
    fun `page demand ends on cover close or the request handoff timeout`() {
        openPage()
        visible(true)
        visible(false)
        assertFalse(lifecycle.active)
        visible(true)
        assertTrue(lifecycle.active)
        closePage()
        assertFalse(lifecycle.active)
        openPage(2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PageSurfaceContract.PAGE_TIMEOUT_MS))
        assertFalse(lifecycle.active)
    }

    @Test
    fun `renewed visibility expires on the local lease and closing one frame preserves another`() {
        openPage()
        visible(true, 1_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        visible(true, 2_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(lifecycle.active)
        openPage(2)
        visible(true, 500, 2)
        closePage(2)
        assertTrue(lifecycle.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        assertFalse(lifecycle.active)
    }

    @Test
    fun `a page action stays foreground through result send after its visible lease expires`() {
        openPage()
        visible(true, 1)
        service.action = {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertTrue(lifecycle.active)
            NexusPageResult.done()
        }
        sending = { if (it == BusPaths.PAGE_RESULT) assertTrue(lifecycle.active) }
        deliver(BusPaths.PAGE_ACTION, JSONObject().put("pageId", "sample:main").put("sessionGeneration", 1)
            .put("frameIndex", 1).put("invocationId", "a1").put("revision", 1).put("actionId", "check")
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"))
        assertEquals(BusPaths.PAGE_RESULT, sends.last())
        assertFalse(lifecycle.active)
    }

    @Test
    fun `commands promote before handling and demote only after sending their result`() {
        service.command = { assertTrue(lifecycle.active); NexusActivityCommandResult.done() }
        sending = { assertTrue(lifecycle.active) }
        command()
        assertEquals(listOf(BusPaths.ACTIVITY_RESULT), sends)
        assertFalse(lifecycle.active)
        service.command = { null }
        command("null")
        assertTrue(lifecycle.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ActivitySurfaceContract.COMMAND_DEADLINE_MS))
        assertFalse(lifecycle.active)
        service.command = { error("rejected") }
        command("throws")
        assertEquals(2, sends.size)
        assertFalse(lifecycle.active)
    }

    @Test
    fun `an asynchronous command keeps promotion until its explicit result or registration loss`() {
        var pending: NexusActivityCommand? = null
        service.command = { null }
        // Capture the validated command while still using the service's actual callback wrapper.
        val callbacks = object : NexusPluginCallbacks by service {
            override fun onActivityCommand(command: NexusActivityCommand): NexusActivityCommandResult? {
                pending = command
                return service.onActivityCommand(command)
            }
        }
        val asynchronous = NexusPluginClient("sample", callbacks, transport, SystemClock::elapsedRealtime)
        ReflectionHelpers.setField(service, "client", asynchronous)
        asynchronous.foregroundDemandChanged = { ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshSessionForeground") }
        asynchronous.onRegistrationState(PluginRegistrationResult.APPROVED)
        asynchronous.onLinkState(LinkStateBits.SPP_DATA_UP)
        fun invoke(id: String) = asynchronous.onMessage(BusPaths.ACTIVITY_ACTION, id,
            JSONObject().put("pluginId", "sample").put("activityId", "sample:activity")
                .put("id", "check").put("invocationId", id).put("seq", 1))
        invoke("async")
        assertTrue(lifecycle.active)
        sending = { assertTrue(lifecycle.active) }
        assertEquals(NexusSdkResult.SENT, asynchronous.sendActivityCommandResult(pending!!, NexusActivityCommandResult.done()))
        assertFalse(lifecycle.active)
        invoke("lost")
        assertTrue(lifecycle.active)
        asynchronous.onRegistrationState(PluginRegistrationResult.DENIED)
        assertFalse(lifecycle.active)
    }

    @Test
    fun `a worker command result queues foreground reconciliation on the main thread`() {
        service.command = { null }
        command()
        assertTrue(lifecycle.active)
        val result = FutureTask { client.sendActivityCommandResult(service.pendingCommand!!, NexusActivityCommandResult.done()) }
        Thread(result).start()
        assertEquals(NexusSdkResult.SENT, result.get(5, TimeUnit.SECONDS))
        assertTrue(lifecycle.active)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(lifecycle.active)
        assertEquals(Looper.getMainLooper().thread, lifecycle.transitionThread)

        command("overlap")
        val overlap = FutureTask { client.sendActivityCommandResult(service.pendingCommand!!, NexusActivityCommandResult.done()) }
        Thread(overlap).start()
        assertEquals(NexusSdkResult.SENT, overlap.get(5, TimeUnit.SECONDS))
        openPage()
        visible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lifecycle.active)
        closePage()
        assertFalse(lifecycle.active)
    }

    @Test
    fun `an adopted session stop waits for both the page and asynchronous command holds`() {
        openPage()
        visible(true)
        service.command = { null }
        command()
        service.onOpen(PluginOpenTypes.ADOPTED)
        assertTrue(lifecycle.active)
        closePage()
        assertTrue(lifecycle.active)
        assertEquals(NexusSdkResult.SENT, client.sendActivityCommandResult(service.pendingCommand!!, NexusActivityCommandResult.done()))
        assertFalse(lifecycle.active)
        assertEquals(0, service.closes)

        openPage(2)
        visible(true, frame = 2)
        closePage(2)
        assertFalse(lifecycle.active)
        service.onOpen()
        assertTrue(lifecycle.active)
        service.onClose()
        assertFalse(lifecycle.active)
    }

    @Test
    fun `an adopted session stop takes effect when a visible page lease expires`() {
        openPage()
        visible(true, lease = 1_000)
        service.onOpen(PluginOpenTypes.ADOPTED)
        assertTrue(lifecycle.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertFalse(lifecycle.active)
    }

    @Test
    fun `a feature stop removes the location type while retaining page protection`() {
        service.onOpen()
        assertTrue(service.promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION))
        openPage()
        visible(true)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, lifecycle.types)
        service.onBackground()
        service.stop()
        assertTrue(lifecycle.active)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, lifecycle.types)
        closePage()
        assertFalse(lifecycle.active)
    }

    @Test
    fun `new explicit feature work supersedes a deferred foreground stop`() {
        openPage()
        visible(true)
        service.onOpen(PluginOpenTypes.ADOPTED)
        assertTrue(service.hold(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION))
        closePage()
        assertTrue(lifecycle.active)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, lifecycle.types)
        service.onClose()
        assertTrue(lifecycle.active)
        service.release()
        assertFalse(lifecycle.active)
    }

    @Test
    fun `new demand composes with an open surface and ongoing work`() {
        service.onOpen()
        command()
        assertTrue(lifecycle.active)
        openPage()
        service.onClose()
        assertTrue(lifecycle.active)
        service.hold()
        closePage()
        assertTrue(lifecycle.active)
        service.release()
        assertFalse(lifecycle.active)
        service.hold()
        openPage(2)
        service.release()
        assertTrue(lifecycle.active)
        closePage(2)
        assertFalse(lifecycle.active)
    }

    @Test
    fun `page demand preserves active feature types and ongoing release restores the session type`() {
        service.hold(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        openPage()
        visible(true)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, lifecycle.types)
        service.release()
        assertTrue(lifecycle.active)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, lifecycle.types)
        closePage()
        assertFalse(lifecycle.active)
    }

    @Test
    fun `registration loss link loss fresh registration and destroy clear page demand`() {
        openPage()
        client.onRegistrationState(PluginRegistrationResult.DENIED)
        assertFalse(lifecycle.active)
        client.onRegistrationState(PluginRegistrationResult.APPROVED)
        openPage(2)
        client.onLinkState(0)
        assertFalse(lifecycle.active)
        client.onLinkState(LinkStateBits.SPP_DATA_UP)
        openPage(3)
        client.onRegistrationState(PluginRegistrationResult.APPROVED)
        assertFalse(lifecycle.active)
        openPage(4)
        service.onDestroy()
        assertFalse(lifecycle.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertFalse(lifecycle.active)
    }

    @Test
    fun `missing foreground declarations and OS start restrictions leave callbacks and results working`() {
        lifecycle.failure = IllegalArgumentException("specialUse is not declared in the manifest")
        openPage()
        assertEquals(BusPaths.PAGE_RESPONSE, sends.last())
        assertFalse(lifecycle.active)
        lifecycle.failure = SecurityException("FOREGROUND_SERVICE_SPECIAL_USE is not declared")
        command()
        assertEquals(BusPaths.ACTIVITY_RESULT, sends.last())
        assertFalse(lifecycle.active)
        service.onOpen()
        assertEquals(1, service.opens)
    }

    @Test
    fun `an unavailable page releases its request promotion after the answer`() {
        service.request = { NexusPage.Unavailable }
        sending = { assertTrue(lifecycle.active) }
        openPage()
        assertEquals(listOf(BusPaths.PAGE_RESPONSE), sends)
        assertFalse(lifecycle.active)
    }
}
