package com.anezium.rokidbus.shared

import com.anezium.rokidbus.ink.InkWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesHubCapabilitiesContractTest {
    @Test
    fun `capabilities carry the optional glasses version name`() {
        val capabilities = GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.IMAGE_SURFACE or
                BusCapabilityBits.PIN_SURFACE or
                BusCapabilityBits.NOTICE_SURFACE or
                BusCapabilityBits.NOTICE_TEXT_INPUT or
                BusCapabilityBits.ACTIVITY_SURFACE or
                BusCapabilityBits.TTS or
                BusCapabilityBits.INK_SURFACE,
            imageSurfaceVersion = ImageSurfaceContract.VERSION,
            pinSurfaceVersion = PinSurfaceContract.VERSION,
            noticeSurfaceVersion = NoticeSurfaceContract.VERSION,
            activitySurfaceVersion = ActivitySurfaceContract.VERSION,
            inkSurfaceVersion = InkWire.VERSION,
            maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
            versionName = " 1.0.1 ",
            setupComplete = true,
            ttsVersion = TtsContract.VERSION,
        )
        val payload = GlassesHubCapabilitiesContract.toJson(capabilities)
            .put("futureField", true)
        val parsed = GlassesHubCapabilitiesContract.parse(payload)

        assertEquals("1.0.1", payload.getString("versionName"))
        assertTrue(payload.getBoolean("setupComplete"))
        assertEquals("1.0.1", parsed.versionName)
        assertTrue(parsed.setupComplete)
        assertEquals(
            BusCapabilityBits.IMAGE_SURFACE or
                BusCapabilityBits.PIN_SURFACE or
                BusCapabilityBits.NOTICE_SURFACE or
                BusCapabilityBits.NOTICE_TEXT_INPUT or
                BusCapabilityBits.ACTIVITY_SURFACE or
                BusCapabilityBits.TTS or
                BusCapabilityBits.INK_SURFACE,
            parsed.features,
        )
        assertEquals(PinSurfaceContract.VERSION, parsed.pinSurfaceVersion)
        assertEquals(NoticeSurfaceContract.VERSION, parsed.noticeSurfaceVersion)
        assertEquals(ActivitySurfaceContract.VERSION, parsed.activitySurfaceVersion)
        assertEquals(TtsContract.VERSION, parsed.ttsVersion)
        assertEquals(InkWire.VERSION, parsed.inkSurfaceVersion)
        assertTrue(GlassesHubCapabilitiesContract.supportsInkSurface(parsed))
        assertEquals(128, BusCapabilityBits.ACTIVITY_SURFACE)
        assertEquals(512, BusCapabilityBits.TTS)
        assertEquals(1024, BusCapabilityBits.INK_SURFACE)
        assertEquals(2048, BusCapabilityBits.NOTICE_TEXT_INPUT)
    }

    @Test
    fun `legacy capabilities without a glasses version remain valid`() {
        val legacyPayload = JSONObject()
            .put("version", GlassesHubCapabilitiesContract.VERSION)
            .put("features", BusCapabilityBits.IMAGE_SURFACE)
            .put("imageSurfaceVersion", ImageSurfaceContract.VERSION)
            .put("maxImageBytes", ImageSurfaceContract.MAX_IMAGE_BYTES)
        val parsed = GlassesHubCapabilitiesContract.parse(legacyPayload)
        val versionlessPayload = GlassesHubCapabilitiesContract.toJson(
            GlassesHubCapabilitiesContract.create(
                features = parsed.features,
                imageSurfaceVersion = parsed.imageSurfaceVersion,
                maxImageBytes = parsed.maxImageBytes,
                versionName = null,
            ),
        )

        assertNull(parsed.versionName)
        assertFalse(parsed.setupComplete)
        assertEquals("", parsed.setupSessionId)
        assertEquals("", parsed.setupStage)
        assertFalse(parsed.setupRunning)
        assertFalse(parsed.setupRequiresUserAction)
        assertEquals("", parsed.setupSupportCode)
        assertEquals("", parsed.setupCompletionMode)
        assertFalse(parsed.coreReady)
        assertFalse(parsed.maintenanceReady)
        assertEquals(SetupStage.UNKNOWN, GlassesHubCapabilitiesContract.effectiveStage(parsed))
        assertEquals(0, parsed.pinSurfaceVersion)
        assertEquals(0, parsed.noticeSurfaceVersion)
        assertEquals(0, parsed.activitySurfaceVersion)
        assertEquals(0, parsed.inkSurfaceVersion)
        assertEquals(0, parsed.ttsVersion)
        assertFalse(versionlessPayload.has("versionName"))
        assertFalse(versionlessPayload.getBoolean("setupComplete"))
    }

    @Test
    fun `setup stage normalization rejects hostile wire input`() {
        assertEquals(SetupStage.WAITING_FOR_WIFI, SetupStage.normalize(" WAITING_FOR_WIFI "))
        assertEquals(SetupStage.ENABLING_WIFI, SetupStage.normalize(" ENABLING_WIFI "))
        assertEquals(SetupStage.UNKNOWN, SetupStage.normalize("../../pairing_locally"))
        assertEquals(SetupStage.UNKNOWN, SetupStage.normalize("failed\u0000complete"))
        assertTrue(SetupStage.isTerminal(" COMPLETE "))
        assertTrue(SetupStage.requiresUserAction("failed"))
        assertFalse(SetupStage.requiresUserAction("pairing_locally"))
        assertFalse(SetupStage.requiresUserAction("enabling_wifi"))
    }

    @Test
    fun `new phone derives effective stages from old glasses payloads`() {
        val complete = GlassesHubCapabilitiesContract.parse(
            legacyPayload(setupComplete = true),
        )
        val failed = GlassesHubCapabilitiesContract.parse(
            legacyPayload(setupFailureState = "wireless_setup_timeout"),
        )

        assertEquals(SetupStage.COMPLETE, GlassesHubCapabilitiesContract.effectiveStage(complete))
        assertEquals(SetupStage.FAILED, GlassesHubCapabilitiesContract.effectiveStage(failed))
    }

    @Test
    fun `old phone ignores additive setup progress fields`() {
        val payload = GlassesHubCapabilitiesContract.toJson(
            GlassesHubCapabilitiesContract.create(
                features = BusCapabilityBits.IMAGE_SURFACE,
                imageSurfaceVersion = ImageSurfaceContract.VERSION,
                maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
                versionName = "1.2.3",
                setupComplete = true,
                setupSessionId = "0123456789abcdef",
                setupStage = SetupStage.COMPLETE,
                setupRunning = false,
                setupRequiresUserAction = false,
                setupSupportCode = "ABCD-1234",
                setupCompletionMode = SetupCompletionMode.AUTOMATIC,
                coreReady = true,
                maintenanceReady = true,
            ),
        )

        val legacyParsed = LegacyCapabilitiesParser.parse(payload)

        assertEquals("1.2.3", legacyParsed.versionName)
        assertTrue(legacyParsed.setupComplete)
    }

    @Test
    fun `new setup fields are normalized on create and parse`() {
        val created = GlassesHubCapabilitiesContract.create(
            features = 0,
            imageSurfaceVersion = 0,
            maxImageBytes = 0,
            versionName = null,
            setupSessionId = "ABCDEF",
            setupStage = " PAIRING_LOCALLY ",
            setupSupportCode = " abcd-1234 ",
            setupCompletionMode = " AUTOMATIC ",
        )
        val parsed = GlassesHubCapabilitiesContract.parse(
            JSONObject()
                .put("setupSessionId", "0123456789abcdef")
                .put("setupStage", "not-a-stage")
                .put("setupSupportCode", "123456")
                .put("setupCompletionMode", "not-a-mode"),
        )

        assertEquals("", created.setupSessionId)
        assertEquals(SetupStage.PAIRING_LOCALLY, created.setupStage)
        assertEquals("ABCD-1234", created.setupSupportCode)
        assertEquals(SetupCompletionMode.AUTOMATIC, created.setupCompletionMode)
        assertEquals("0123456789abcdef", parsed.setupSessionId)
        assertEquals(SetupStage.UNKNOWN, parsed.setupStage)
        assertEquals("", parsed.setupSupportCode)
        assertEquals(SetupCompletionMode.UNKNOWN, parsed.setupCompletionMode)
    }

    @Test
    fun `support code is a session hash and never a pairing code`() {
        val supportCode = GlassesHubCapabilitiesContract.deriveSetupSupportCode(
            "0123456789abcdef",
        )

        assertEquals(8, supportCode.length)
        assertTrue(Regex("[A-F0-9]{8}").matches(supportCode))
        assertFalse(supportCode.contains("123456"))
        assertFalse(
            GlassesHubCapabilitiesContract.deriveSetupSupportCode("123456").contains("123456"),
        )
    }

    private fun legacyPayload(
        setupComplete: Boolean = false,
        setupFailureState: String = "",
    ): JSONObject = JSONObject()
        .put("version", GlassesHubCapabilitiesContract.VERSION)
        .put("features", 0)
        .put("imageSurfaceVersion", 0)
        .put("maxImageBytes", 0)
        .put("setupComplete", setupComplete)
        .put("setupFailureState", setupFailureState)

    private data class LegacyCapabilities(
        val versionName: String?,
        val setupComplete: Boolean,
    )

    private object LegacyCapabilitiesParser {
        fun parse(payload: JSONObject): LegacyCapabilities = LegacyCapabilities(
            versionName = payload.optString("versionName", "").takeIf(String::isNotBlank),
            setupComplete = payload.optBoolean("setupComplete", false),
        )
    }
}
