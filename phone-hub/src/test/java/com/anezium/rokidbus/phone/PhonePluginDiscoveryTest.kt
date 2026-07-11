package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePluginDiscoveryTest {
    private fun record(
        packageName: String = "dev.example.hello",
        serviceName: String = "dev.example.hello.PluginService",
        uid: Int = 10001,
        pluginId: String = "hello",
        signer: ByteArray = byteArrayOf(1, 2, 3),
        extraMetadata: List<Pair<String, String?>> = emptyList(),
    ) = PhonePluginDiscovery.PackageRecord(
        packageName = packageName,
        serviceClassName = serviceName,
        uid = uid,
        exported = true,
        signingCertificates = listOf(signer),
        metadata = listOf(
            BusConstants.META_PLUGIN_ID to pluginId,
            BusConstants.META_PLUGIN_DISPLAY_NAME to pluginId.replaceFirstChar(Char::uppercase),
            BusConstants.META_PLUGIN_API_VERSION to "3",
            BusConstants.META_PLUGIN_CAPABILITIES to "surfaces",
            BusConstants.META_PLUGIN_RECEIVE_PREFIXES to "/system/plugin,/plugin/$pluginId",
        ) + extraMetadata,
    )

    @Test
    fun `valid record binds package uid component signer and descriptor`() {
        val result = PhonePluginDiscovery.evaluate(listOf(record())).single()
        assertTrue(result is PhonePluginCandidate.Valid)
        val principal = (result as PhonePluginCandidate.Valid).principal
        assertEquals("dev.example.hello", principal.packageName)
        assertEquals(10001, principal.uid)
        assertEquals("hello", principal.descriptor.id)
        assertEquals(64, principal.signingDigestSha256.length)
    }

    @Test
    fun `malformed metadata remains a stable invalid candidate`() {
        val result = PhonePluginDiscovery.evaluate(
            listOf(record(extraMetadata = listOf(BusConstants.META_PLUGIN_CAPABILITIES to "root"))),
        ).single() as PhonePluginCandidate.Invalid
        assertEquals("CONFLICTING_METADATA", result.reason)
    }

    @Test
    fun `duplicate plugin IDs are conflicts`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(
                record(packageName = "dev.one", serviceName = "dev.one.Service", uid = 10001),
                record(packageName = "dev.two", serviceName = "dev.two.Service", uid = 10002),
            ),
        )
        assertEquals(setOf("DUPLICATE_PLUGIN_ID"), results.map { (it as PhonePluginCandidate.Invalid).reason }.toSet())
    }

    @Test
    fun `multiple principals sharing one uid are unsupported`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(
                record(packageName = "dev.one", serviceName = "dev.one.Service", pluginId = "one", uid = 10001),
                record(packageName = "dev.two", serviceName = "dev.two.Service", pluginId = "two", uid = 10001),
            ),
        )
        assertEquals(setOf("SHARED_UID_UNSUPPORTED"), results.map { (it as PhonePluginCandidate.Invalid).reason }.toSet())
    }

    @Test
    fun `one package cannot publish multiple plugin services`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(record(), record(serviceName = "dev.example.hello.SecondService")),
        )
        assertEquals("MULTIPLE_PLUGIN_SERVICES", (results.single() as PhonePluginCandidate.Invalid).reason)
    }

    @Test
    fun `candidates sort by display name then package`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(
                record(packageName = "dev.z", serviceName = "dev.z.Service", pluginId = "zulu", uid = 3),
                record(packageName = "dev.a", serviceName = "dev.a.Service", pluginId = "alpha", uid = 2),
            ),
        )
        assertEquals(listOf("alpha", "zulu"), results.map { it.displayName.lowercase() })
    }
}
