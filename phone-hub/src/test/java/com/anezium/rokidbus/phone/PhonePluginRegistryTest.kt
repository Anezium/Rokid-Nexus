package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.GlyphContract
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhonePluginRegistryTest {
    @Test
    fun sendBinary_routesBinaryEnvelopeWithSurfaceMetadata() {
        var routed: BusEnvelope? = null
        val registry = registry(sendEnvelope = { envelope ->
            routed = envelope
            null
        })
        val bytes = byteArrayOf(1, 2, 3)

        registry.sendBinary(
            BusPaths.SURFACE_UPDATE,
            "image-request",
            JSONObject().put("surfaceId", "feeds").put("kind", "image").put("epoch", 99),
            bytes,
        )

        val envelope = assertNotNull(routed).let { routed!! }
        assertEquals(BusPaths.SURFACE_UPDATE, envelope.path)
        assertEquals("image-request", envelope.id)
        assertEquals("feeds", envelope.payload.getString("surfaceId"))
        assertTrue(envelope.payload.getLong("seq") > 0L)
        assertEquals(1L, envelope.payload.getLong("epoch"))
        assertArrayEquals(bytes, envelope.binary)
        registry.close()
    }

    @Test
    fun supportsImageSurface_readsCurrentCapabilityValue() {
        var capabilities = 0
        val registry = registry(capabilitiesProvider = { capabilities })

        assertFalse(registry.supportsImageSurface())
        capabilities = BusCapabilityBits.IMAGE_SURFACE
        assertTrue(registry.supportsImageSurface())
        capabilities = 0
        assertFalse(registry.supportsImageSurface())
        registry.close()
    }

    @Test
    fun `launcher rejection flows into journal`() {
        val journal = PluginBusJournal().apply { enabled.set(true) }
        val registry = registry(journal = journal)

        assertFalse(
            registry.handleRemote(
                BusEnvelope(
                    path = BusPaths.LAUNCHER_OPEN,
                    payload = JSONObject().put("pluginId", "missing"),
                ),
            ),
        )

        val event = journal.snapshot().single()
        assertEquals("missing", event.pluginId)
        assertEquals(PluginBusJournal.Category.LAUNCHER, event.category)
        assertEquals(PluginBusJournal.Direction.GLASSES_TO_HUB, event.direction)
        assertEquals(PluginBusJournal.Verdict.REJECTED, event.verdict)
        assertEquals("OPEN_FAILED", event.reason)
        registry.close()
    }

    @Test
    fun `undeliverable surface input flows into journal`() {
        val journal = PluginBusJournal().apply { enabled.set(true) }
        val registry = registry(journal = journal)

        assertTrue(
            registry.handleRemote(
                BusEnvelope(
                    path = BusPaths.SURFACE_INPUT,
                    payload = JSONObject()
                        .put("surfaceId", "missing:main")
                        .put("ownerPluginId", "missing"),
                ),
            ),
        )

        val event = journal.snapshot().single()
        assertEquals("missing", event.pluginId)
        assertEquals(PluginBusJournal.Category.INPUT, event.category)
        assertEquals(PluginBusJournal.Verdict.REJECTED, event.verdict)
        assertEquals("NO_ACTIVE_PLUGIN", event.reason)
        registry.close()
    }

    @Test
    fun `launcher list payload stays unchanged when plugins have no glyphs`() {
        val principal = principal("hello", launchable = true, iconKey = "star")
        val catalog = PluginCatalog(
            listOf(catalogEntry(principal, PluginCatalogState.ENABLED, launchable = true)),
        )
        val sent = mutableListOf<BusEnvelope>()
        val registry = registry(
            sendEnvelope = { envelope ->
                sent += envelope
                null
            },
            catalogProvider = { catalog },
            glyphReader = { emptyList() },
        )

        registry.syncLauncherList()

        val actual = sent.single()
        val expected = BusEnvelope(
            path = BusPaths.LAUNCHER_LIST,
            id = actual.id,
            payload = JSONObject().put(
                "plugins",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("id", "hello")
                        .put("displayName", "Hello")
                        .put("iconKey", "star"),
                ),
            ),
        )
        assertArrayEquals(FrameProtocol.toJsonBytes(expected), FrameProtocol.toJsonBytes(actual))
        registry.close()
    }

    @Test
    fun `only enabled principals emit glyph envelopes including headless plugins`() {
        val enabled = principal("photosync", launchable = false, iconKey = "photosync", glyphsResId = 10)
        val pending = principal("pending", launchable = true, iconKey = "pending", glyphsResId = 11)
        val catalog = PluginCatalog(
            listOf(
                catalogEntry(enabled, PluginCatalogState.ENABLED, launchable = false),
                catalogEntry(pending, PluginCatalogState.PENDING, launchable = false),
            ),
        )
        val photosyncPath =
            "M2,9 A2,2 0 0 1 4,7 L7.2,7 L8.5,4.8 L13.5,4.8 L14.8,7 L18,7 " +
                "A2,2 0 0 1 20,9 L20,17 A2,2 0 0 1 18,19 L4,19 A2,2 0 0 1 2,17 Z " +
                "M7.8,13 A3.2,3.2 0 1 0 14.2,13 A3.2,3.2 0 1 0 7.8,13 " +
                "M18.6,5.6 L18.6,1.6 M18.6,1.6 L16.4,3.8 M18.6,1.6 L20.8,3.8"
        val readerCalls = mutableListOf<String>()
        val sent = mutableListOf<BusEnvelope>()
        val registry = registry(
            sendEnvelope = { envelope ->
                sent += envelope
                null
            },
            catalogProvider = { catalog },
            glyphReader = { principal ->
                readerCalls += principal.descriptor.id
                listOf(GlyphContract.CustomGlyph("photosync", photosyncPath))
            },
        )

        registry.syncLauncherList()

        assertEquals(listOf(BusPaths.LAUNCHER_LIST, BusPaths.LAUNCHER_GLYPHS), sent.map { it.path })
        assertEquals(listOf("photosync"), readerCalls)
        val glyphEnvelope = sent.last()
        assertEquals("photosync", glyphEnvelope.payload.getString("pluginId"))
        val glyph = glyphEnvelope.payload.getJSONArray("glyphs").getJSONObject(0)
        assertEquals("photosync", glyph.getString("name"))
        assertEquals(photosyncPath, glyph.getString("pathData"))
        assertTrue(FrameProtocol.toJsonBytes(glyphEnvelope).size < BusConstants.CXR_CONTROL_MAX_BYTES)
        registry.close()
    }

    @Test
    fun `ink and card surfaces compete for the same external foreground owner`() {
        val runtime = object : ExternalPluginRuntime {
            override fun bind(principal: PhonePluginPrincipal) = true
            override fun isRegistered(principal: PhonePluginPrincipal) = true
            override fun deliver(
                principal: PhonePluginPrincipal,
                path: String,
                id: String,
                payload: JSONObject,
            ) = true
            override fun hideOwnedSurfaces(pluginId: String) = Unit
            override fun unbind(principal: PhonePluginPrincipal) = Unit
        }
        val scheduler = object : ExternalPluginScheduler {
            override fun schedule(key: String, delayMs: Long, action: () -> Unit) = Unit
            override fun cancel(key: String) = Unit
        }
        val controller = ExternalPluginController(runtime, scheduler)
        val registry = registry(externalController = controller)
        val inkOwner = principal("inkowner", launchable = true, iconKey = "star")
        val cardOwner = principal("cardowner", launchable = true, iconKey = "star")

        assertTrue(registry.allowExternalSurface(inkOwner, BusPaths.INK_SHOW))
        assertTrue(registry.allowExternalSurface(inkOwner, BusPaths.SURFACE_UPDATE))
        assertFalse(registry.allowExternalSurface(cardOwner, BusPaths.SURFACE_SHOW))
        assertFalse(registry.allowExternalSurface(cardOwner, BusPaths.INK_UPDATE))
        controller.onPluginSelfHid(inkOwner.descriptor.id)
        assertTrue(registry.allowExternalSurface(cardOwner, BusPaths.SURFACE_SHOW))
        assertFalse(registry.allowExternalSurface(inkOwner, BusPaths.INK_SHOW))
        registry.close()
    }

    private fun registry(
        sendEnvelope: (BusEnvelope) -> String? = { null },
        capabilitiesProvider: () -> Int = { 0 },
        journal: PluginBusJournal? = null,
        catalogProvider: (() -> PluginCatalog)? = null,
        glyphReader: ((PhonePluginPrincipal) -> List<GlyphContract.CustomGlyph>)? = null,
        externalController: ExternalPluginController? = null,
    ) = PhonePluginRegistry(
        context = RuntimeEnvironment.getApplication(),
        plugins = emptyList(),
        sendEnvelope = sendEnvelope,
        capabilitiesProvider = capabilitiesProvider,
        logger = {},
        catalogProvider = catalogProvider,
        externalController = externalController,
        journal = journal,
        glyphReader = glyphReader,
    )

    private fun principal(
        id: String,
        launchable: Boolean,
        iconKey: String,
        glyphsResId: Int? = null,
    ) = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.PluginService"),
        uid = id.hashCode(),
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id = id,
            displayName = id.replaceFirstChar(Char::uppercase),
            apiVersion = BusConstants.API_VERSION,
            requestedCapabilities = if (launchable) setOf(PluginCapability.SURFACES) else emptySet(),
            receivePrefixes = listOf("/plugin/$id", "/system/plugin"),
            settingsActivity = null,
            launchable = launchable,
            iconKey = iconKey,
            glyphsResId = glyphsResId,
        ),
    )

    private fun catalogEntry(
        principal: PhonePluginPrincipal,
        state: PluginCatalogState,
        launchable: Boolean,
    ) = PluginCatalogEntry(
        catalogKey = "external:${principal.packageName}:${principal.descriptor.id}",
        id = principal.descriptor.id,
        displayName = principal.descriptor.displayName,
        state = state,
        launchable = launchable,
        iconKey = principal.descriptor.iconKey,
        principal = principal,
    )
}
