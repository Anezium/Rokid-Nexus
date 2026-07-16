package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesAppUpdateStateTest {
    private fun release(version: NexusSemVersion) = NexusReleaseAsset(
        version = version,
        apkUrl = "https://example.com/nexus-glasses-$version.apk",
        sha256 = null,
    )

    @Test
    fun `older installed glasses version has an update available`() {
        val state = GlassesAppUpdatePolicy.compare(
            installedVersionName = "1.0.0",
            latestRelease = release(NexusSemVersion(1, 0, 1)),
        )

        assertEquals(
            GlassesAppUpdateState.UpdateAvailable(
                installed = NexusSemVersion(1, 0, 0),
                latest = NexusSemVersion(1, 0, 1),
            ),
            state,
        )
    }

    @Test
    fun `equal and newer installed glasses versions are up to date`() {
        val latest = release(NexusSemVersion(1, 0, 1))

        assertEquals(
            GlassesAppUpdateState.UpToDate(
                installed = NexusSemVersion(1, 0, 1),
                latest = NexusSemVersion(1, 0, 1),
            ),
            GlassesAppUpdatePolicy.compare("1.0.1", latest),
        )
        assertEquals(
            GlassesAppUpdateState.UpToDate(
                installed = NexusSemVersion(1, 1, 0),
                latest = NexusSemVersion(1, 0, 1),
            ),
            GlassesAppUpdatePolicy.compare("1.1.0", latest),
        )
    }

    @Test
    fun `missing or unparseable versions remain unknown`() {
        val latest = release(NexusSemVersion(1, 0, 1))

        assertEquals(GlassesAppUpdateState.Unknown, GlassesAppUpdatePolicy.compare(null, latest))
        assertEquals(GlassesAppUpdateState.Unknown, GlassesAppUpdatePolicy.compare("dev", latest))
        assertEquals(GlassesAppUpdateState.Unknown, GlassesAppUpdatePolicy.compare("1.0.0", null))
    }
}
