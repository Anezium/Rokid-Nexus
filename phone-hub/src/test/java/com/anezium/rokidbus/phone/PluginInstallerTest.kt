package com.anezium.rokidbus.phone

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class FakeGateway : PackageInstallGateway {
        var installCount = 0
        var installedPackage: String? = null
        var callback: ((PackageInstallEvent) -> Unit)? = null
        var cancelled = false

        override fun install(
            apk: File,
            expectedPackageName: String,
            callback: (PackageInstallEvent) -> Unit,
        ): PackageInstallSession {
            installCount++
            installedPackage = expectedPackageName
            this.callback = callback
            return PackageInstallSession { cancelled = true }
        }
    }

    @Test
    fun `verified download is installed and success refreshes catalogue`() {
        val bytes = "verified apk".toByteArray()
        val gateway = FakeGateway()
        val states = mutableListOf<PluginInstallState>()
        val refreshed = mutableListOf<String>()
        val installer = installer(bytes, gateway, postInstall = refreshed::add)

        installer.install(entry(bytes), states::add)
        gateway.callback?.invoke(PackageInstallEvent.AwaitingUserConfirmation)
        gateway.callback?.invoke(PackageInstallEvent.Success)

        assertEquals(1, gateway.installCount)
        assertEquals(PACKAGE, gateway.installedPackage)
        assertTrue(states.any { it is PluginInstallState.Downloading })
        assertTrue(states.contains(PluginInstallState.Verifying))
        assertTrue(states.contains(PluginInstallState.AwaitingUserConfirmation))
        assertEquals(PluginInstallState.Success("feeds", PACKAGE), states.last())
        assertEquals(listOf(PACKAGE), refreshed)
    }

    @Test
    fun `sha256 mismatch aborts before package installer and deletes apk`() {
        val gateway = FakeGateway()
        val states = mutableListOf<PluginInstallState>()
        val installer = installer("tampered".toByteArray(), gateway)

        installer.install(entry("expected".toByteArray()), states::add)

        assertEquals(0, gateway.installCount)
        assertEquals(
            PluginInstallState.Failure("Downloaded APK failed SHA-256 verification"),
            states.last(),
        )
        assertTrue(temporaryFolder.root.walkTopDown().none { it.extension == "apk" })
    }

    @Test
    fun `apk package mismatch aborts before package installer`() {
        val bytes = "verified apk".toByteArray()
        val gateway = FakeGateway()
        val states = mutableListOf<PluginInstallState>()
        val installer = installer(bytes, gateway, inspectedPackage = "dev.unexpected.plugin")

        installer.install(entry(bytes), states::add)

        assertEquals(0, gateway.installCount)
        assertEquals(
            PluginInstallState.Failure("Downloaded APK package does not match the registry"),
            states.last(),
        )
    }

    @Test
    fun `package install failure is surfaced`() {
        val bytes = "verified apk".toByteArray()
        val gateway = FakeGateway()
        val states = mutableListOf<PluginInstallState>()
        val installer = installer(bytes, gateway)

        installer.install(entry(bytes), states::add)
        gateway.callback?.invoke(PackageInstallEvent.Failure("installer rejected apk"))

        assertEquals(PluginInstallState.Failure("installer rejected apk"), states.last())
    }

    @Test
    fun `package install cancellation is surfaced`() {
        val bytes = "verified apk".toByteArray()
        val gateway = FakeGateway()
        val states = mutableListOf<PluginInstallState>()
        val installer = installer(bytes, gateway)

        installer.install(entry(bytes), states::add)
        gateway.callback?.invoke(PackageInstallEvent.Cancelled)

        assertEquals(PluginInstallState.Cancelled, states.last())
    }

    @Test
    fun `operation cancellation before download prevents install`() {
        val queued = mutableListOf<Runnable>()
        val gateway = FakeGateway()
        val states = mutableListOf<PluginInstallState>()
        val bytes = "verified apk".toByteArray()
        val installer = installer(bytes, gateway, ioExecutor = Executor(queued::add))

        val operation = installer.install(entry(bytes), states::add)
        operation.cancel()
        queued.single().run()

        assertEquals(listOf(PluginInstallState.Cancelled), states)
        assertEquals(0, gateway.installCount)
    }

    @Test
    fun `host-incompatible and installed entries never download`() {
        var downloadCount = 0
        val gateway = FakeGateway()
        val installer = PluginInstaller(
            cacheDirectory = temporaryFolder.root,
            hostVersionCode = 6,
            downloader = ArtifactDownloader { _, _, _, _ -> downloadCount++ },
            packageInspector = ArtifactPackageInspector { PACKAGE },
            packageInstaller = gateway,
            ioExecutor = Executor(Runnable::run),
        )
        val states = mutableListOf<PluginInstallState>()

        installer.install(entry("apk".toByteArray(), state = StoreEntryState.REQUIRES_HOST, minHost = 7), states::add)

        assertEquals(0, downloadCount)
        assertEquals(0, gateway.installCount)
        assertTrue(states.last() is PluginInstallState.Failure)
    }

    private fun installer(
        bytes: ByteArray,
        gateway: FakeGateway,
        ioExecutor: Executor = Executor(Runnable::run),
        postInstall: (String) -> Unit = {},
        inspectedPackage: String? = PACKAGE,
    ) = PluginInstaller(
        cacheDirectory = temporaryFolder.root,
        hostVersionCode = 6,
        downloader = ArtifactDownloader { _, destination, _, progress ->
            destination.writeBytes(bytes)
            progress(bytes.size.toLong(), bytes.size.toLong())
        },
        packageInspector = ArtifactPackageInspector { inspectedPackage },
        packageInstaller = gateway,
        ioExecutor = ioExecutor,
        postInstall = postInstall,
    )

    private fun entry(
        bytes: ByteArray,
        state: StoreEntryState = StoreEntryState.AVAILABLE,
        minHost: Long = 6,
    ): StoreEntry {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val plugin = RegistryPlugin(
            id = "feeds",
            name = "Feeds",
            category = "Social",
            summary = "Read feeds.",
            description = "Read feeds.",
            author = "Anezium",
            sourceUrl = "https://github.com/Anezium/Rokid-Nexus",
            publishedAt = "2026-07-12",
            iconAsset = "feeds.png",
            screenshotAssets = emptyList(),
            listingDescriptionMarkdown = "Feeds",
            releases = emptyList(),
            nexus = RegistryNexus("feeds", 3, listOf("surfaces"), true, null, minHost),
            artifact = RegistryArtifact(
                target = "phone",
                url = "https://example.com/feeds.apk",
                sha256 = sha,
                sizeBytes = bytes.size.toLong(),
                packageName = PACKAGE,
                versionCode = 7,
                versionName = "0.1.0",
            ),
        )
        return StoreEntry("feeds", "Feeds", "Social", "Read feeds.", state, plugin, null, null)
    }

    companion object {
        private const val PACKAGE = "com.anezium.rokidbus.plugin.feeds"
    }
}
