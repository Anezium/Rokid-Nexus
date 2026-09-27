package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YoutubeApkPolicyTest {
    private val archive = ArtifactArchiveInfo(YoutubeSetupContract.YOUTUBE, 200, listOf(byteArrayOf(1, 2, 3)))

    private fun release(): JSONObject = JSONObject().put("tag_name", "7.1.1")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray().put(
            JSONObject().put("name", "microg-7.1.1.apk").put("size", 112259771)
                .put("digest", "sha256:${"a".repeat(64)}")
                .put("browser_download_url", "https://github.com/MorpheApp/MicroG-RE/releases/download/7.1.1/microg-7.1.1.apk"),
        ))

    private fun inventory(version: Long = 100, signer: String = YoutubeApkPolicy.signer(archive)) =
        YoutubeInventory("request", 32, YoutubeSetupContract.PACKAGES.map {
            if (it == archive.packageName) YoutubePackage(it, version, signer, true) else YoutubePackage(it)
        })

    @Test fun `chooses only icon enabled MicroG from the official repository`() {
        assertTrue(YoutubeApkPolicy.microGRelease(release().toString()).url.endsWith("/microg-7.1.1.apk"))
        for (name in listOf("microg-7.1.1-noicon.apk", "microg-7.1.1-noicon-arm64-v8a.apk")) {
            val json = release()
            json.getJSONArray("assets").getJSONObject(0).put("name", name)
            assertTrue(runCatching { YoutubeApkPolicy.microGRelease(json.toString()) }.isFailure)
        }
    }

    @Test fun `prefers the arm64 MicroG asset over the universal one`() {
        val json = release()
        val universal = json.getJSONArray("assets").getJSONObject(0)
        json.getJSONArray("assets").put(JSONObject(universal.toString())
            .put("name", "microg-7.1.1-arm64-v8a.apk").put("size", 44821575)
            .put("browser_download_url", "https://github.com/MorpheApp/MicroG-RE/releases/download/7.1.1/microg-7.1.1-arm64-v8a.apk"))
        val release = YoutubeApkPolicy.microGRelease(json.toString())
        assertTrue(release.url.endsWith("/microg-7.1.1-arm64-v8a.apk"))
        assertEquals(44821575L, release.size)
    }

    @Test fun `rejects substituted release URL missing digest and prereleases`() {
        val badUrl = release()
        badUrl.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/microg.apk")
        val noDigest = release()
        noDigest.getJSONArray("assets").getJSONObject(0).remove("digest")
        for (json in listOf(badUrl, noDigest, release().put("prerelease", true))) {
            assertTrue(runCatching { YoutubeApkPolicy.microGRelease(json.toString()) }.isFailure)
        }
    }

    @Test fun `rejects unrelated APKs stock YouTube and incorrect microG`() {
        for (packageName in listOf("com.google.android.youtube", "com.google.android.gms", "app.revanced.android.youtube")) {
            assertTrue(runCatching { YoutubeApkPolicy.validatePackage(archive.copy(packageName = packageName), false) }.isFailure)
        }
        assertTrue(runCatching { YoutubeApkPolicy.validatePackage(archive, true) }.isFailure)
        YoutubeApkPolicy.validatePackage(archive.copy(packageName = YoutubeSetupContract.MICROG), true)
        YoutubeApkPolicy.validatePackage(archive.copy(packageName = YoutubeSetupContract.YOUTUBE_TEST), false)
    }

    @Test fun `warns when the patched YouTube is not the build the Rokid patch targets`() {
        assertNull(YoutubeApkPolicy.versionNotice(archive.copy(versionName = YoutubeApkPolicy.STOCK_YOUTUBE_VERSION)))
        assertNull(YoutubeApkPolicy.versionNotice(archive.copy(packageName = YoutubeSetupContract.MICROG, versionName = "7.1.1")))
        assertTrue(YoutubeApkPolicy.versionNotice(archive.copy(versionName = "21.32.2"))!!.contains("21.32.2"))
        assertNotNull(YoutubeApkPolicy.versionNotice(archive))
    }

    @Test fun `updates preserve signer and reject downgrades and incompatible Android`() {
        assertNull(YoutubeApkPolicy.updateError(archive, 28, inventory()))
        assertNull(YoutubeApkPolicy.updateError(archive, 28, inventory(version = 200)))
        assertNotNull(YoutubeApkPolicy.updateError(archive, 28, inventory(version = 300)))
        assertNotNull(YoutubeApkPolicy.updateError(archive, 33, inventory()))
        assertNotNull(YoutubeApkPolicy.updateError(archive, 28, inventory(signer = "b".repeat(64))))
        assertNotNull(YoutubeApkPolicy.updateError(archive, 28, inventory(signer = "")))
    }

    @Test fun `adds the Rokid fork through Morphe's own add-source link`() {
        // Morphe only accepts https://morphe.software/add-source with a github= repository.
        assertEquals(
            "https://morphe.software/add-source?github=Anezium/morphe-patches&name=Rokid%20glasses",
            YoutubeApkPolicy.ROKID_PATCHES_SOURCE_URL,
        )
        assertEquals("com.google.android.youtube", YoutubeApkPolicy.STOCK_YOUTUBE_PACKAGE)
    }
}
