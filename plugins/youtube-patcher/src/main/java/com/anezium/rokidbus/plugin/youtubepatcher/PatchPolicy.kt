package com.anezium.rokidbus.plugin.youtubepatcher

import com.anezium.rokidbus.shared.YoutubePatcherContract as Contract
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

object PatchPolicy {
    const val VERSION = "21.04.223"
    const val MAX_BYTES = 1024L * 1024 * 1024
    data class Stock(val packageName: String, val version: String?, val signers: Set<String>, val split: String?, val versionCode: Long)
    fun validate(stock: Stock, allowSplit: Boolean = false) {
        require(stock.packageName == Contract.STOCK_PACKAGE) { "Choose stock YouTube, not a patched app." }
        require(stock.version == VERSION) { "YouTube $VERSION is required." }
        require(stock.signers == setOf(Contract.STOCK_SIGNER_SHA256)) { "The APK is not signed by Google." }
        require(allowSplit || stock.split.isNullOrEmpty()) { "Choose the complete split bundle, not one split APK." }
    }
    data class Metadata(val version: String, val url: String)
    fun metadata(text: String): Metadata {
        val json = Json.parseToJsonElement(text).jsonObject
        val version = json.getValue("version").jsonPrimitive.content
        require(version.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid bundle version." }
        val url = json.getValue("download_url").jsonPrimitive.content
        val uri = URI(url)
        require(url.startsWith(Contract.BUNDLE_DOWNLOAD_PREFIX) && uri.host == "github.com" &&
            uri.scheme == "https" && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null &&
            uri.path == uri.normalize().path && uri.rawPath == uri.path && uri.path.endsWith(".mpp")) { "Bundle URL is not a pinned fork release." }
        return Metadata(version, url)
    }
    const val RESULT_GRACE_MS = 10L * 60 * 1000
    const val RESULT_MAX_AGE_MS = 24L * 60 * 60 * 1000
    fun isResult(name: String) = name.startsWith("youtube-") && name.endsWith(".apk")
    /** Results to delete. The hub may still be copying a recent result through its
     * FileProvider grant, so only the newest result survives the grace period. */
    fun expiredResults(results: Map<File, Long>, now: Long, keep: File? = null): Set<File> {
        val newest = results.entries.maxWithOrNull(compareBy({ it.value }, { it.key.name }))?.key
        return results.filter { (file, modified) ->
            val age = now - modified
            file != keep && (kotlin.math.abs(age) >= RESULT_MAX_AGE_MS || (file != newest && age >= RESULT_GRACE_MS))
        }.keys
    }
    fun mergeSelection(defaults: Map<String, Boolean>, previous: Map<String, Boolean>) =
        defaults.mapValues { (name, default) -> previous[name] ?: default }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_BYTES, progress: (Long) -> Unit = {}) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
            val n = input.read(buffer); if (n < 0) return
            total += n
            require(total <= limit) { "File exceeds size limit." }
            output.write(buffer, 0, n)
            progress(total)
        }
    }
    fun requireDex(file: File) = ZipFile(file).use { zip ->
        val dex = zip.getEntry("classes.dex")
        require(dex != null) {
            "This release contains only JVM classes. It must be prepared with Android D8 by its publisher or at plugin build time. Keeping the last working bundle."
        }
        zip.getInputStream(dex!!).use { input ->
            val header = ByteArray(8)
            java.io.DataInputStream(input).readFully(header)
            require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)) &&
                header[7] == 0.toByte() && header.slice(4..6).all { it in 48..57 } && dex.size >= 112) {
                "Bundle has invalid Android DEX; keeping the last working bundle."
            }
        }
        val manifest = zip.getEntry("META-INF/MANIFEST.MF") ?: error("Bundle manifest missing.")
        val text = zip.getInputStream(manifest).use { input ->
            java.io.ByteArrayOutputStream().also { copyBounded(input, it, 65536) }.toString("UTF-8")
        }
        require(Regex("""(?m)^Patcher-Version: 1\.7\.0\s*$""").containsMatchIn(text)) { "Bundle needs a different patcher API; keeping the last working bundle." }
    }
}
