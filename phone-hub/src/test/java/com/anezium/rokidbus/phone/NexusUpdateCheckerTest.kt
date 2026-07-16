package com.anezium.rokidbus.phone

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusUpdateCheckerTest {
    private class MemoryCache(var record: NexusUpdateCacheRecord? = null) : NexusUpdateCache {
        override fun read(): NexusUpdateCacheRecord? = record
        override fun write(record: NexusUpdateCacheRecord) {
            this.record = record
        }
    }

    @Test
    fun `only bare app release tags are accepted`() {
        assertEquals(NexusSemVersion(1, 0, 1), NexusSemVersion.fromAppTag("v1.0.1"))
        assertNull(NexusSemVersion.fromAppTag("transit-v1.0.0"))
        assertNull(NexusSemVersion.fromAppTag("sdk-v0.1.0"))
        assertNull(NexusSemVersion.fromAppTag("v1.0"))
    }

    @Test
    fun `semantic versions compare numeric components`() {
        assertTrue(requireNotNull(NexusSemVersion.parse("1.0.10")) > requireNotNull(NexusSemVersion.parse("1.0.9")))
        assertFalse(requireNotNull(NexusSemVersion.parse("1.0.0")) > requireNotNull(NexusSemVersion.parse("1.0.0")))
    }

    @Test
    fun `update decision requires a strictly newer app version`() {
        val release = NexusAppRelease(
            version = NexusSemVersion(1, 0, 10),
            apkUrl = "https://example.com/nexus-phone-1.0.10.apk",
            sha256 = null,
        )

        assertTrue(NexusUpdatePolicy.isUpdateAvailable(release, InstalledNexusVersion("1.0.9", 10009)))
        assertFalse(NexusUpdatePolicy.isUpdateAvailable(release, InstalledNexusVersion("1.0.10", 10010)))
        assertFalse(NexusUpdatePolicy.isUpdateAvailable(null, InstalledNexusVersion("1.0.9", 10009)))
    }

    @Test
    fun `release parser ignores plugin and sdk releases and selects exact phone asset`() {
        val release = NexusUpdateChecker.parseLatestAppRelease(
            """
            [
              {
                "tag_name": "transit-v9.0.0",
                "assets": [{
                  "name": "nexus-phone-9.0.0.apk",
                  "browser_download_url": "https://example.com/plugin.apk"
                }]
              },
              {
                "tag_name": "sdk-v8.0.0",
                "assets": [{
                  "name": "nexus-phone-8.0.0.apk",
                  "browser_download_url": "https://example.com/sdk.apk"
                }]
              },
              {
                "tag_name": "v1.0.1",
                "assets": [
                  {
                    "name": "nexus-glasses-1.0.1.apk",
                    "browser_download_url": "https://example.com/glasses.apk"
                  },
                  {
                    "name": "nexus-phone-1.0.1.apk",
                    "browser_download_url": "https://example.com/phone.apk",
                    "digest": "sha256:${"ab".repeat(32)}"
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        assertNotNull(release)
        assertEquals("1.0.1", release?.versionName)
        assertEquals("https://example.com/phone.apk", release?.apkUrl)
        assertEquals("ab".repeat(32), release?.sha256)
    }

    @Test
    fun `release parser selects latest exact glasses asset`() {
        val release = NexusReleaseAssetResolver.parseLatest(
            """
            [
              {
                "tag_name": "v1.0.2",
                "assets": [{
                  "name": "nexus-glasses-1.0.2-debug.apk",
                  "browser_download_url": "https://example.com/wrong.apk"
                }]
              },
              {
                "tag_name": "v1.0.1",
                "assets": [{
                  "name": "nexus-glasses-1.0.1.apk",
                  "browser_download_url": "https://example.com/old.apk"
                }]
              },
              {
                "tag_name": "v1.0.3",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "nexus-phone-1.0.3.apk",
                    "browser_download_url": "https://example.com/phone.apk"
                  },
                  {
                    "name": "nexus-glasses-1.0.3.apk",
                    "browser_download_url": "https://example.com/glasses.apk",
                    "digest": "sha256:${"cd".repeat(32)}"
                  }
                ]
              }
            ]
            """.trimIndent(),
            NexusReleaseArtifact.GLASSES,
        )

        assertNotNull(release)
        assertEquals(NexusSemVersion(1, 0, 3), release?.version)
        assertEquals("https://example.com/glasses.apk", release?.apkUrl)
        assertEquals("cd".repeat(32), release?.sha256)
    }

    @Test
    fun `signature mismatch install failure gives release reinstall guidance`() {
        val message = selfUpdateInstallFailureMessage(
            PackageInstallEvent.Failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"),
        )

        assertEquals(NEXUS_UPDATE_SIGNATURE_MISMATCH_MESSAGE, message)
        assertTrue(message.contains("Reinstall Rokid Nexus from the release"))
    }

    @Test
    fun `debug and release signer mismatch is detected before installation`() {
        val installed = ArtifactArchiveInfo("com.anezium.rokidbus.phone", 10000, listOf(byteArrayOf(1)))
        val update = ArtifactArchiveInfo("com.anezium.rokidbus.phone", 10001, listOf(byteArrayOf(2)))

        assertFalse(NexusSelfUpdater.hasCompatibleSigner(installed, update))
    }

    @Test
    fun `cached etag is revalidated and 304 refreshes the cached release`() {
        val cache = MemoryCache(NexusUpdateCacheRecord(appReleaseBody(), "etag-old", 100L))
        var requestedEtag: String? = null
        val checker = NexusUpdateChecker(
            transport = NexusUpdateTransport { etag ->
                requestedEtag = etag
                NexusUpdateHttpResponse(304)
            },
            cache = cache,
            clock = { 999L },
        )

        val result = checker.check(InstalledNexusVersion("1.0.0", 10000), allowNetwork = true)

        assertEquals("etag-old", requestedEtag)
        assertTrue(result is NexusUpdateCheckResult.Available)
        assertEquals(999L, cache.record?.lastFetchEpochMillis)
    }

    @Test
    fun `offline update check falls back to the cached app release`() {
        val checker = NexusUpdateChecker(
            transport = NexusUpdateTransport { throw IOException("offline") },
            cache = MemoryCache(NexusUpdateCacheRecord(appReleaseBody(), "etag", 100L)),
        )

        val result = checker.check(InstalledNexusVersion("1.0.0", 10000), allowNetwork = true)

        assertTrue(result is NexusUpdateCheckResult.Available)
        assertEquals("1.0.1", (result as NexusUpdateCheckResult.Available).release.versionName)
    }

    private fun appReleaseBody(): String = """
        [{
          "tag_name": "v1.0.1",
          "draft": false,
          "prerelease": false,
          "assets": [{
            "name": "nexus-phone-1.0.1.apk",
            "browser_download_url": "https://example.com/nexus-phone-1.0.1.apk",
            "digest": "sha256:${"ab".repeat(32)}"
          }]
        }]
    """.trimIndent()
}
