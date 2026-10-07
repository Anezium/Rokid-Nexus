package com.anezium.rokidbus.plugin.patcher

import android.content.Context
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromDex
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class BundleStore internal constructor(
    private val directory: File,
    private val openAsset: (String) -> InputStream,
    private val pluginBuild: String,
    private val fetchText: (String) -> String = ::fetch,
    private val download: (String, OutputStream) -> Unit = ::downloadRelease,
    private val loadPatches: (File) -> List<Patch<*>> = { compatible(loadPatchesFromDex(setOf(it))) },
    private val target: PatchTarget = PatchTargets.default,
) {
    // lastUpdateTime changes on every reinstall, so even a rebuild with the same versionCode counts as a new build.
    constructor(context: Context, target: PatchTarget = PatchTargets.default) : this(File(context.filesDir, "bundles/${target.id}"), context.assets::open,
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.longVersionCode}:${it.lastUpdateTime}" },
        loadPatches = { file -> PatchStorage.loadBundle(context.cacheDir) { compatible(loadPatchesFromDex(setOf(file)), target) } }, target = target)
    data class Loaded(val file: File, val version: String, val hash: String, val sourceHash: String?, val patches: List<Patch<*>>,
                      val notice: String? = null)
    data class Update(val message: String?, val switched: Boolean)
    private val pointer = File(directory.apply { mkdirs() }, "active.json")
    suspend fun current(): Loaded {
        val job = coroutineContext
        val checkCancelled = { job.ensureActive() }
        val saved = if (pointer.exists()) Json.parseToJsonElement(pointer.readText()).jsonObject else null
        ReadOnlyBundleFile.cleanUnused(directory, saved?.let { it["file"]?.jsonPrimitive?.content ?: "bundled.mpp" })
        var notice: String? = null
        try {
            val metadata = Json.parseToJsonElement(openAsset(target.bundle.assetMetadata).use {
                it.readBytes().toString(Charsets.UTF_8)
            }).jsonObject
            val bundledHash = metadata.getValue("sha256").jsonPrimitive.content
            require(metadata.getValue("source_sha256").jsonPrimitive.content == target.bundle.pinnedSourceSha256) { "Included bundle source does not match the target pin." }
            // A plugin build that ships a different bundle adopts it once, replacing any downloaded
            // bundle and rejection record. Pointers written before this key existed migrate once.
            if (saved?.get("bundled_sha256")?.jsonPrimitive?.content != bundledHash) {
                val file = ReadOnlyBundleFile.install(directory, checkCancelled, write = { output ->
                    openAsset(target.bundle.asset).use { PatchPolicy.copyBounded(it, output) }
                }, validate = { candidate ->
                    require(PatchPolicy.sha256(candidate) == bundledHash) { "Included bundle does not match its recorded hash." }
                    PatchPolicy.requireDex(candidate)
                })
                try {
                    atomicPointer(JsonObject(metadata + mapOf("file" to JsonPrimitive(file.name),
                        "bundled_sha256" to JsonPrimitive(bundledHash))).toString(), checkCancelled)
                } catch (e: Throwable) { file.delete(); throw e }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Without a saved bundle there is nothing validated to fall back to. Otherwise keep
            // the saved pointer untouched so the adoption is retried on the next open.
            if (saved == null) throw e
            notice = "Could not install the bundle included in this version (${PatchErrors.reason(e, "Bundle validation failed.")}). Keeping the saved bundle; Patcher will retry next time."
        }
        val json = Json.parseToJsonElement(pointer.readText()).jsonObject
        var file = File(directory, json["file"]?.jsonPrimitive?.content ?: "bundled.mpp")
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        val hash = PatchPolicy.sha256(file)
        require(hash == json.getValue("sha256").jsonPrimitive.content) { "Saved bundle hash mismatch." }
        PatchPolicy.requireDex(file)
        // Migrate a previously installed writable bundle by copying into a NEW protected inode.
        val protected = runCatching { ReadOnlyBundleFile.requireReadOnly(file) }.isSuccess
        if (!protected) {
            val old = file
            file = ReadOnlyBundleFile.install(directory, checkCancelled, write = { output ->
                old.inputStream().use { PatchPolicy.copyBounded(it, output) }
            }, validate = { require(PatchPolicy.sha256(it) == hash); PatchPolicy.requireDex(it) })
            try { atomicPointer(JsonObject(json + ("file" to JsonPrimitive(file.name))).toString(), checkCancelled) }
            catch (e: Throwable) { file.delete(); throw e }
        }
        checkCancelled()
        ReadOnlyBundleFile.requireReadOnly(file)
        val patches = loadPatches(file).sortedWith(compareBy({ target.priority(it.name!!) }, { it.name }))
        checkCancelled()
        require(patches.isNotEmpty()) { "Bundle contains no compatible ${target.displayName} patches." }
        return Loaded(file, json.getValue("version").jsonPrimitive.content, hash, json["source_sha256"]?.jsonPrimitive?.content, patches, notice)
    }
    /** [activeVersion] is the bundle already loaded by [current]; it is not loaded again. */
    suspend fun checkForUpdate(activeVersion: String): Update {
        val job = coroutineContext
        val checkCancelled = { job.ensureActive() }
        val metadata = PatchPolicy.metadata(fetchText(target.bundle.metadataUrl), target)
        if (metadata.version == activeVersion) return Update(null, false)
        val active = Json.parseToJsonElement(pointer.readText()).jsonObject
        rejection(active, metadata, pluginBuild)?.let { reason ->
            return Update("Bundle ${metadata.version} was rejected. Using saved bundle $activeVersion.", false)
        }
        var rejected: String? = null
        val staging = try {
            ReadOnlyBundleFile.install(directory, checkCancelled, write = { download(metadata.url, it) }, validate = {
                ReadOnlyBundleFile.requireReadOnly(it)
                try {
                    PatchPolicy.requireDex(it)
                    require(loadPatches(it).isNotEmpty()) { "New bundle has no compatible patches." }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    rejected = PatchErrors.reason(e, "Bundle validation failed.")
                    throw e
                }
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason = rejected ?: throw e
            // Remember content rejections only; network failures stay retryable. The metadata
            // carries no hash, so a re-upload under the same version and URL is retried only
            // once the plugin build changes.
            atomicPointer(JsonObject(active + mapOf("rejected_version" to JsonPrimitive(metadata.version),
                "rejected_url" to JsonPrimitive(metadata.url), "rejected_plugin" to JsonPrimitive(pluginBuild),
                "rejected_reason" to JsonPrimitive(reason))).toString(), checkCancelled)
            throw e
        }
        try {
            val json = buildJsonObject {
                put("file", staging.name); put("version", metadata.version); put("sha256", PatchPolicy.sha256(staging)); put("download_url", metadata.url)
                active["bundled_sha256"]?.let { put("bundled_sha256", it) }
            }.toString()
            atomicPointer(json, checkCancelled)
            return Update("Bundle updated to ${metadata.version}.", true)
        } catch (e: Throwable) { staging.delete(); throw e }
    }
    private fun atomicPointer(text: String, checkCancelled: () -> Unit) {
        val temp = File.createTempFile("pointer-", ".partial", directory)
        try {
            temp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            checkCancelled()
            Files.move(temp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    companion object {
        internal fun rejection(active: JsonObject, metadata: PatchPolicy.Metadata, pluginBuild: String): String? =
            active["rejected_reason"]?.jsonPrimitive?.content?.takeIf {
                active["rejected_version"]?.jsonPrimitive?.content == metadata.version &&
                    active["rejected_url"]?.jsonPrimitive?.content == metadata.url &&
                    active["rejected_plugin"]?.jsonPrimitive?.content == pluginBuild
            }
        fun compatible(patches: Set<Patch<*>>, target: PatchTarget = PatchTargets.default) = patches.filter { patch ->
            patch.compatiblePackages?.any { (name, versions) ->
                name == target.stockPackage && (versions == null || target.acceptedVersions.any { it in versions })
            } ?: true
        }
    }
}

private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = false
}
// Release downloads redirect to GitHub's CDN; never allow arbitrary redirect hosts.
private fun downloadRelease(url: String, output: OutputStream) {
    var next = url
    repeat(5) {
        val c = connection(next)
        try {
            if (c.responseCode in 300..399) {
                val target = URL(URL(next), c.getHeaderField("Location") ?: error("Missing redirect.")).toURI()
                require(target.scheme == "https" && target.userInfo == null && target.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Untrusted bundle redirect." }
                next = target.toString()
            } else {
                require(c.responseCode == 200) { "Bundle download HTTP ${c.responseCode}." }
                c.inputStream.use { PatchPolicy.copyBounded(it, output, 128L * 1024 * 1024) }
                return
            }
        } finally { c.disconnect() }
    }
    error("Too many bundle redirects.")
}
private fun fetch(url: String): String {
    val c = connection(url)
    try {
        require(c.responseCode == 200) { "Metadata HTTP ${c.responseCode}." }
        val output = java.io.ByteArrayOutputStream()
        c.inputStream.use { PatchPolicy.copyBounded(it, output, 65536) }
        return output.toString("UTF-8")
    } finally { c.disconnect() }
}
