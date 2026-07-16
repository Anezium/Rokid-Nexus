package com.anezium.rokidbus.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller as AndroidPackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PluginInstallState {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : PluginInstallState
    data object Verifying : PluginInstallState
    data object Installing : PluginInstallState
    data object AwaitingUserConfirmation : PluginInstallState
    data class Success(val pluginId: String, val packageName: String) : PluginInstallState
    data object Cancelled : PluginInstallState
    data class Failure(val message: String) : PluginInstallState
}

fun interface ArtifactDownloader {
    @Throws(IOException::class)
    fun download(
        url: String,
        destination: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    )
}

sealed interface PackageInstallEvent {
    data object AwaitingUserConfirmation : PackageInstallEvent
    data object Success : PackageInstallEvent
    data object Cancelled : PackageInstallEvent
    data class Failure(val message: String, val statusCode: Int? = null) : PackageInstallEvent
}

fun interface PackageInstallSession {
    fun cancel()
}

fun interface PackageInstallGateway {
    @Throws(Exception::class)
    fun install(
        apk: File,
        expectedPackageName: String,
        expectedPluginId: String,
        callback: (PackageInstallEvent) -> Unit,
    ): PackageInstallSession
}

data class ArtifactArchiveInfo(
    val packageName: String,
    val versionCode: Long,
    val signingCertificates: List<ByteArray>,
    val versionName: String? = null,
    val signingCertificateHistory: List<ByteArray> = signingCertificates,
)

fun interface ArtifactPackageInspector {
    fun inspect(apk: File): ArtifactArchiveInfo?
}

internal sealed interface ArtifactArchiveVerification {
    data object Verified : ArtifactArchiveVerification
    data object PackageNameMismatch : ArtifactArchiveVerification
    data object SignerSetUnsupported : ArtifactArchiveVerification
    data class SignerMismatch(val expected: String, val actual: String) : ArtifactArchiveVerification
    data object VersionCodeMismatch : ArtifactArchiveVerification
}

internal object ArtifactArchiveVerifier {
    fun verify(
        expected: RegistryArtifact,
        actual: ArtifactArchiveInfo?,
    ): ArtifactArchiveVerification {
        if (actual == null || actual.packageName != expected.packageName) {
            return ArtifactArchiveVerification.PackageNameMismatch
        }
        if (actual.signingCertificates.size != 1) {
            return ArtifactArchiveVerification.SignerSetUnsupported
        }
        val actualSignerSha256 = signingCertificateSha256(actual.signingCertificates.single())
        if (actualSignerSha256 != expected.signerSha256) {
            return ArtifactArchiveVerification.SignerMismatch(expected.signerSha256, actualSignerSha256)
        }
        if (actual.versionCode != expected.versionCode) {
            return ArtifactArchiveVerification.VersionCodeMismatch
        }
        return ArtifactArchiveVerification.Verified
    }
}

class PluginInstallOperation internal constructor(
    private val cancelled: AtomicBoolean,
    private val cancelAction: () -> Unit,
) {
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) cancelAction()
    }
}

internal data class ArtifactInstallRequest(
    val cacheFileName: String,
    val url: String,
    val sha256: String?,
    val expectedPackageName: String,
    val installationId: String,
    val sha256FailureMessage: String,
    val fallbackFailureMessage: String,
    val verifyArchive: (ArtifactArchiveInfo?) -> String?,
    val mapInstallFailure: (PackageInstallEvent.Failure) -> String = { it.message },
    val successState: () -> PluginInstallState.Success,
    val postInstallFailureMessage: String,
)

/** Shared download, verification and user-confirmed PackageInstaller pipeline. */
internal class ArtifactInstallPipeline(
    private val cacheDirectory: File,
    private val downloader: ArtifactDownloader,
    private val packageInspector: ArtifactPackageInspector,
    private val packageInstaller: PackageInstallGateway,
    private val ioExecutor: Executor,
    private val callbackExecutor: Executor,
) {
    fun install(
        request: ArtifactInstallRequest,
        listener: (PluginInstallState) -> Unit,
    ): PluginInstallOperation {
        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        var activeSession: PackageInstallSession? = null

        fun emit(state: PluginInstallState) {
            callbackExecutor.execute { listener(state) }
        }

        fun finish(state: PluginInstallState) {
            if (completed.compareAndSet(false, true)) emit(state)
        }

        val operation = PluginInstallOperation(cancelled) {
            runCatching { activeSession?.cancel() }
            finish(PluginInstallState.Cancelled)
        }

        ioExecutor.execute {
            cacheDirectory.mkdirs()
            val apk = File(cacheDirectory, request.cacheFileName)
            try {
                apk.delete()
                downloader.download(
                    url = request.url,
                    destination = apk,
                    isCancelled = cancelled::get,
                    onProgress = { downloaded, total ->
                        if (!completed.get()) emit(PluginInstallState.Downloading(downloaded, total))
                    },
                )
                if (cancelled.get()) {
                    finish(PluginInstallState.Cancelled)
                    return@execute
                }
                emit(PluginInstallState.Verifying)
                if (request.sha256 != null && !PluginInstaller.sha256Matches(apk, request.sha256)) {
                    finish(PluginInstallState.Failure(request.sha256FailureMessage))
                    return@execute
                }
                request.verifyArchive(packageInspector.inspect(apk))?.let { message ->
                    finish(PluginInstallState.Failure(message))
                    return@execute
                }
                if (cancelled.get()) {
                    finish(PluginInstallState.Cancelled)
                    return@execute
                }
                emit(PluginInstallState.Installing)
                activeSession = packageInstaller.install(
                    apk = apk,
                    expectedPackageName = request.expectedPackageName,
                    expectedPluginId = request.installationId,
                ) { event ->
                    when (event) {
                        PackageInstallEvent.AwaitingUserConfirmation ->
                            if (!completed.get()) emit(PluginInstallState.AwaitingUserConfirmation)
                        PackageInstallEvent.Success -> {
                            if (completed.compareAndSet(false, true)) {
                                val terminal = runCatching(request.successState)
                                    .getOrElse { PluginInstallState.Failure(request.postInstallFailureMessage) }
                                emit(terminal)
                            }
                        }
                        PackageInstallEvent.Cancelled -> finish(PluginInstallState.Cancelled)
                        is PackageInstallEvent.Failure ->
                            finish(PluginInstallState.Failure(request.mapInstallFailure(event)))
                    }
                }
                if (cancelled.get()) runCatching { activeSession?.cancel() }
            } catch (_: DownloadCancelledException) {
                finish(PluginInstallState.Cancelled)
            } catch (failure: Exception) {
                finish(PluginInstallState.Failure(failure.message ?: request.fallbackFailureMessage))
            } finally {
                apk.delete()
            }
        }
        return operation
    }
}

class PluginInstaller(
    private val cacheDirectory: File,
    private val hostVersionCode: Long,
    private val downloader: ArtifactDownloader,
    private val packageInspector: ArtifactPackageInspector,
    private val packageInstaller: PackageInstallGateway,
    private val ioExecutor: Executor = DEFAULT_IO_EXECUTOR,
    private val callbackExecutor: Executor = Executor(Runnable::run),
    private val postInstall: (packageName: String) -> Unit = {},
    private val logger: (String) -> Unit = {},
) {
    fun install(
        entry: StoreEntry,
        listener: (PluginInstallState) -> Unit,
    ): PluginInstallOperation {
        val plugin = entry.registryPlugin
        if (plugin == null || entry.state !in INSTALLABLE_STATES) {
            return rejected(listener, "This plugin is not installable")
        }
        if (plugin.nexus.minHostVersionCode > hostVersionCode) {
            return rejected(listener, "This plugin requires a newer Nexus host")
        }
        val pipeline = ArtifactInstallPipeline(
            cacheDirectory,
            downloader,
            packageInspector,
            packageInstaller,
            ioExecutor,
            callbackExecutor,
        )
        return pipeline.install(
            ArtifactInstallRequest(
                cacheFileName = "plugin-${plugin.artifact.sha256.take(16)}.apk",
                url = plugin.artifact.url,
                sha256 = plugin.artifact.sha256,
                expectedPackageName = plugin.artifact.packageName,
                installationId = plugin.nexus.pluginId,
                sha256FailureMessage = "Downloaded APK failed SHA-256 verification",
                fallbackFailureMessage = "Plugin installation failed",
                verifyArchive = { actual -> pluginArchiveFailure(plugin, actual) },
                successState = {
                    postInstall(plugin.artifact.packageName)
                    PluginInstallState.Success(plugin.nexus.pluginId, plugin.artifact.packageName)
                },
                postInstallFailureMessage = "Plugin installed, but catalogue refresh failed",
            ),
            listener,
        )
    }

    private fun rejected(
        listener: (PluginInstallState) -> Unit,
        message: String,
    ): PluginInstallOperation {
        callbackExecutor.execute { listener(PluginInstallState.Failure(message)) }
        return PluginInstallOperation(AtomicBoolean(true)) {}
    }

    private fun pluginArchiveFailure(plugin: RegistryPlugin, actual: ArtifactArchiveInfo?): String? =
        when (val verification = ArtifactArchiveVerifier.verify(plugin.artifact, actual)) {
            ArtifactArchiveVerification.Verified -> null
            ArtifactArchiveVerification.PackageNameMismatch ->
                "Downloaded APK package does not match the registry"
            ArtifactArchiveVerification.SignerSetUnsupported ->
                "Downloaded APK uses an unsupported signer set"
            is ArtifactArchiveVerification.SignerMismatch -> {
                logger(
                    "Plugin APK signer mismatch package=${plugin.artifact.packageName} " +
                        "expected=${verification.expected} actual=${verification.actual}",
                )
                "Downloaded APK signer does not match the registry"
            }
            ArtifactArchiveVerification.VersionCodeMismatch ->
                "Downloaded APK version does not match the registry"
        }

    companion object {
        private val INSTALLABLE_STATES = setOf(StoreEntryState.AVAILABLE, StoreEntryState.UPDATE_AVAILABLE)
        private val DEFAULT_IO_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nexus-plugin-installer").apply { isDaemon = true }
        }

        fun create(
            context: Context,
            hostVersionCode: Long,
            postInstall: (packageName: String) -> Unit = {},
        ): PluginInstaller {
            val appContext = context.applicationContext
            return PluginInstaller(
                cacheDirectory = File(appContext.cacheDir, "plugin-installs"),
                hostVersionCode = hostVersionCode,
                downloader = HttpsArtifactDownloader(),
                packageInspector = AndroidArtifactPackageInspector(appContext.packageManager),
                packageInstaller = AndroidPackageInstallGateway(appContext),
                callbackExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
                postInstall = postInstall,
                logger = { message -> Log.w(LOG_TAG, message) },
            )
        }

        internal fun sha256Matches(file: File, expected: String): Boolean {
            if (!file.isFile) return false
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return actual.equals(expected, ignoreCase = true)
        }

        private const val LOG_TAG = "NexusStore"
    }
}

internal class AndroidArtifactPackageInspector(
    private val packageManager: PackageManager,
) : ArtifactPackageInspector {
    override fun inspect(apk: File): ArtifactArchiveInfo? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: return null
        return ArtifactArchiveInfo(
            packageName = packageInfo.packageName,
            versionCode = packageInfo.longVersionCode,
            signingCertificates = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
                .map { signer -> signer.toByteArray() },
            versionName = packageInfo.versionName,
            signingCertificateHistory = packageInfo.signingInfo?.signingCertificateHistory.orEmpty()
                .map { signer -> signer.toByteArray() },
        )
    }
}

internal class DownloadCancelledException : IOException("Download cancelled")

private const val PROGRESS_EMIT_INTERVAL_MS = 250L

internal class HttpsArtifactDownloader : ArtifactDownloader {
    override fun download(
        url: String,
        destination: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https") { "Artifact URL must use HTTPS" }
        val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Rokid-Nexus-Store/1")
        }
        try {
            val status = connection.responseCode
            if (connection.url.protocol != "https") throw IOException("Artifact redirect left HTTPS")
            if (status !in 200..299) throw IOException("Artifact download failed with HTTP $status")
            val total = connection.contentLengthLong.takeIf { it >= 0L }
            destination.outputStream().buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    // A large APK reads in thousands of buffers; emitting progress for each
                    // one floods the UI thread. Throttle to a few updates per second and
                    // always emit the final byte count.
                    var lastEmit = SystemClock.elapsedRealtime()
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                            onProgress(downloaded, total)
                            lastEmit = now
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal class AndroidPackageInstallGateway(private val context: Context) : PackageInstallGateway {
    override fun install(
        apk: File,
        expectedPackageName: String,
        expectedPluginId: String,
        callback: (PackageInstallEvent) -> Unit,
    ): PackageInstallSession {
        val packageInstaller = context.packageManager.packageInstaller
        val params = AndroidPackageInstaller.SessionParams(AndroidPackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(expectedPackageName)
            setSize(apk.length())
            setRequireUserAction(AndroidPackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = packageInstaller.createSession(params)
        val token = UUID.randomUUID().toString()
        PluginInstallResultReceiver.register(token, expectedPackageName, expectedPluginId, callback)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("plugin.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val statusIntent = Intent(context, PluginInstallResultReceiver::class.java)
                    .setAction("${context.packageName}.PLUGIN_INSTALL_RESULT.$token")
                    .putExtra(PluginInstallResultReceiver.EXTRA_TOKEN, token)
                    .putExtra(PluginInstallResultReceiver.EXTRA_EXPECTED_PACKAGE, expectedPackageName)
                    .putExtra(PluginInstallResultReceiver.EXTRA_EXPECTED_PLUGIN_ID, expectedPluginId)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    token.hashCode(),
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (failure: Exception) {
            PluginInstallResultReceiver.unregister(token)
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw failure
        }
        return PackageInstallSession {
            PluginInstallResultReceiver.unregister(token)
            runCatching { packageInstaller.abandonSession(sessionId) }
            callback(PackageInstallEvent.Cancelled)
        }
    }
}

class PluginInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val pending = callbacks[token]
        val expectedPackageName = pending?.expectedPackageName
            ?: intent.getStringExtra(EXTRA_EXPECTED_PACKAGE)
            ?: return
        val expectedPluginId = pending?.expectedPluginId
            ?: intent.getStringExtra(EXTRA_EXPECTED_PLUGIN_ID)
            ?: return
        val callback = pending?.callback
        when (val status = intent.getIntExtra(AndroidPackageInstaller.EXTRA_STATUS, AndroidPackageInstaller.STATUS_FAILURE)) {
            AndroidPackageInstaller.STATUS_PENDING_USER_ACTION -> {
                callback?.invoke(PackageInstallEvent.AwaitingUserConfirmation)
                confirmationIntent(intent)?.let { confirmation ->
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                        .onFailure {
                            callbacks.remove(token)
                            dispatchTerminal(
                                context,
                                callback,
                                expectedPackageName,
                                expectedPluginId,
                                PackageInstallEvent.Failure("Could not open package confirmation"),
                            )
                        }
                } ?: run {
                    callbacks.remove(token)
                    dispatchTerminal(
                        context,
                        callback,
                        expectedPackageName,
                        expectedPluginId,
                        PackageInstallEvent.Failure("Package confirmation is unavailable"),
                    )
                }
            }
            AndroidPackageInstaller.STATUS_SUCCESS -> {
                callbacks.remove(token)
                val installedPackage = intent.getStringExtra(AndroidPackageInstaller.EXTRA_PACKAGE_NAME)
                val event = if (installedPackage == expectedPackageName) {
                    PackageInstallEvent.Success
                } else {
                    PackageInstallEvent.Failure("Installed package identity did not match the request")
                }
                dispatchTerminal(context, callback, expectedPackageName, expectedPluginId, event)
            }
            AndroidPackageInstaller.STATUS_FAILURE_ABORTED -> {
                callbacks.remove(token)
                dispatchTerminal(
                    context,
                    callback,
                    expectedPackageName,
                    expectedPluginId,
                    PackageInstallEvent.Cancelled,
                )
            }
            else -> {
                callbacks.remove(token)
                val message = intent.getStringExtra(AndroidPackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf(String::isNotBlank)
                    ?: "Package installation failed (status $status)"
                dispatchTerminal(
                    context,
                    callback,
                    expectedPackageName,
                    expectedPluginId,
                    PackageInstallEvent.Failure(message, status),
                )
            }
        }
    }

    private fun dispatchTerminal(
        context: Context,
        callback: ((PackageInstallEvent) -> Unit)?,
        expectedPackageName: String,
        expectedPluginId: String,
        event: PackageInstallEvent,
    ) {
        if (callback != null) {
            callback(event)
            return
        }
        if (expectedPluginId == NEXUS_SELF_UPDATE_INSTALL_ID) {
            val recovered = when (event) {
                PackageInstallEvent.Success -> RecoveredNexusUpdateInstall.Success
                PackageInstallEvent.Cancelled -> RecoveredNexusUpdateInstall.Cancelled
                is PackageInstallEvent.Failure ->
                    RecoveredNexusUpdateInstall.Failure(selfUpdateInstallFailureMessage(event))
                PackageInstallEvent.AwaitingUserConfirmation -> return
            }
            NexusUpdateInstallRecoveryStore(context).save(recovered)
            return
        }
        val recoveryStore = PluginInstallRecoveryStore(context)
        when (event) {
            PackageInstallEvent.Success -> {
                val coordinator = PluginPostInstallCoordinator(
                    discoverPackage = PhonePluginDiscovery(context.packageManager)::discoverPackage,
                    grantState = PluginGrantStore(context)::stateFor,
                    refreshCatalog = {},
                )
                when (val result = coordinator.onInstalled(expectedPackageName, expectedPluginId)) {
                    is PluginPostInstallResult.Ready -> {
                        recoveryStore.save(RecoveredPluginInstall.Success(result.target))
                        runCatching {
                            context.startActivity(
                                PluginPermissionsActivity.intent(context, result.target)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    is PluginPostInstallResult.Failure -> {
                        recoveryStore.save(RecoveredPluginInstall.Failure(result.reason))
                    }
                }
            }
            PackageInstallEvent.Cancelled ->
                recoveryStore.save(RecoveredPluginInstall.Cancelled)
            is PackageInstallEvent.Failure ->
                recoveryStore.save(RecoveredPluginInstall.Failure(event.message))
            PackageInstallEvent.AwaitingUserConfirmation -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

    companion object {
        internal const val EXTRA_TOKEN = "plugin_install_token"
        internal const val EXTRA_EXPECTED_PACKAGE = "plugin_install_expected_package"
        internal const val EXTRA_EXPECTED_PLUGIN_ID = "plugin_install_expected_plugin_id"
        private data class PendingResult(
            val expectedPackageName: String,
            val expectedPluginId: String,
            val callback: (PackageInstallEvent) -> Unit,
        )

        private val callbacks = ConcurrentHashMap<String, PendingResult>()

        internal fun register(
            token: String,
            expectedPackageName: String,
            expectedPluginId: String,
            callback: (PackageInstallEvent) -> Unit,
        ) {
            callbacks[token] = PendingResult(expectedPackageName, expectedPluginId, callback)
        }

        internal fun unregister(token: String) {
            callbacks.remove(token)
        }
    }
}
