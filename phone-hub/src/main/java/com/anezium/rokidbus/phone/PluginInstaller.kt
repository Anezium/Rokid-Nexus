package com.anezium.rokidbus.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller as AndroidPackageInstaller
import android.os.Handler
import android.os.Looper
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
    data class Failure(val message: String) : PackageInstallEvent
}

fun interface PackageInstallSession {
    fun cancel()
}

fun interface PackageInstallGateway {
    @Throws(Exception::class)
    fun install(
        apk: File,
        expectedPackageName: String,
        callback: (PackageInstallEvent) -> Unit,
    ): PackageInstallSession
}

class PluginInstallOperation internal constructor(
    private val cancelled: AtomicBoolean,
    private val cancelAction: () -> Unit,
) {
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) cancelAction()
    }
}

class PluginInstaller(
    private val cacheDirectory: File,
    private val hostVersionCode: Long,
    private val downloader: ArtifactDownloader,
    private val packageInstaller: PackageInstallGateway,
    private val ioExecutor: Executor = DEFAULT_IO_EXECUTOR,
    private val callbackExecutor: Executor = Executor(Runnable::run),
    private val postInstall: (packageName: String) -> Unit = {},
) {
    fun install(
        entry: StoreEntry,
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

        val plugin = entry.registryPlugin
        if (plugin == null || entry.state !in INSTALLABLE_STATES) {
            finish(PluginInstallState.Failure("This plugin is not installable"))
            return operation
        }
        if (plugin.nexus.minHostVersionCode > hostVersionCode) {
            finish(PluginInstallState.Failure("This plugin requires a newer Nexus host"))
            return operation
        }

        ioExecutor.execute {
            cacheDirectory.mkdirs()
            val apk = File(cacheDirectory, "plugin-${plugin.artifact.sha256.take(16)}.apk")
            try {
                apk.delete()
                downloader.download(
                    url = plugin.artifact.url,
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
                if (!sha256Matches(apk, plugin.artifact.sha256)) {
                    finish(PluginInstallState.Failure("Downloaded APK failed SHA-256 verification"))
                    return@execute
                }
                if (cancelled.get()) {
                    finish(PluginInstallState.Cancelled)
                    return@execute
                }
                emit(PluginInstallState.Installing)
                activeSession = packageInstaller.install(
                    apk = apk,
                    expectedPackageName = plugin.artifact.packageName,
                ) { event ->
                    when (event) {
                        PackageInstallEvent.AwaitingUserConfirmation ->
                            if (!completed.get()) emit(PluginInstallState.AwaitingUserConfirmation)
                        PackageInstallEvent.Success -> {
                            if (completed.compareAndSet(false, true)) {
                                runCatching { postInstall(plugin.artifact.packageName) }
                                    .onFailure {
                                        emit(PluginInstallState.Failure("Plugin installed, but catalogue refresh failed"))
                                    }
                                    .onSuccess {
                                        emit(PluginInstallState.Success(plugin.nexus.pluginId, plugin.artifact.packageName))
                                    }
                            }
                        }
                        PackageInstallEvent.Cancelled -> finish(PluginInstallState.Cancelled)
                        is PackageInstallEvent.Failure -> finish(PluginInstallState.Failure(event.message))
                    }
                }
                if (cancelled.get()) runCatching { activeSession?.cancel() }
            } catch (_: DownloadCancelledException) {
                finish(PluginInstallState.Cancelled)
            } catch (failure: Exception) {
                finish(PluginInstallState.Failure(failure.message ?: "Plugin installation failed"))
            } finally {
                apk.delete()
            }
        }
        return operation
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
                packageInstaller = AndroidPackageInstallGateway(appContext),
                callbackExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
                postInstall = postInstall,
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
    }
}

private class DownloadCancelledException : IOException("Download cancelled")

private class HttpsArtifactDownloader : ArtifactDownloader {
    override fun download(
        url: String,
        destination: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https") { "Plugin artifact URL must use HTTPS" }
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
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

private class AndroidPackageInstallGateway(private val context: Context) : PackageInstallGateway {
    override fun install(
        apk: File,
        expectedPackageName: String,
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
        PluginInstallResultReceiver.register(token, callback)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("plugin.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val statusIntent = Intent(context, PluginInstallResultReceiver::class.java)
                    .setAction("${context.packageName}.PLUGIN_INSTALL_RESULT.$token")
                    .putExtra(PluginInstallResultReceiver.EXTRA_TOKEN, token)
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
        val callback = callbacks[token] ?: return
        when (val status = intent.getIntExtra(AndroidPackageInstaller.EXTRA_STATUS, AndroidPackageInstaller.STATUS_FAILURE)) {
            AndroidPackageInstaller.STATUS_PENDING_USER_ACTION -> {
                callback(PackageInstallEvent.AwaitingUserConfirmation)
                confirmationIntent(intent)?.let { confirmation ->
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                        .onFailure {
                            callbacks.remove(token)
                            callback(PackageInstallEvent.Failure("Could not open package confirmation"))
                        }
                } ?: run {
                    callbacks.remove(token)
                    callback(PackageInstallEvent.Failure("Package confirmation is unavailable"))
                }
            }
            AndroidPackageInstaller.STATUS_SUCCESS -> {
                callbacks.remove(token)
                callback(PackageInstallEvent.Success)
            }
            AndroidPackageInstaller.STATUS_FAILURE_ABORTED -> {
                callbacks.remove(token)
                callback(PackageInstallEvent.Cancelled)
            }
            else -> {
                callbacks.remove(token)
                val message = intent.getStringExtra(AndroidPackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf(String::isNotBlank)
                    ?: "Package installation failed (status $status)"
                callback(PackageInstallEvent.Failure(message))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

    companion object {
        internal const val EXTRA_TOKEN = "plugin_install_token"
        private val callbacks = ConcurrentHashMap<String, (PackageInstallEvent) -> Unit>()

        internal fun register(token: String, callback: (PackageInstallEvent) -> Unit) {
            callbacks[token] = callback
        }

        internal fun unregister(token: String) {
            callbacks.remove(token)
        }
    }
}
