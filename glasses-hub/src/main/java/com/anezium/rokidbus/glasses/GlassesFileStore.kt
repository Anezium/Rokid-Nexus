package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFileEntry
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Listing outcome before it is framed for the wire. */
internal sealed interface GlassesFolderListing {
    data class Page(val entries: List<GlassesFileEntry>, val offset: Int, val total: Int) : GlassesFolderListing
    data class Failed(val error: GlassesFilesError) : GlassesFolderListing
}

/**
 * The glasses' side of the explorer: every filesystem decision, confined to [root].
 *
 * Wire paths are already validated by the contract, but resolution is still re-checked here
 * against the canonical root, so a symlink or an unexpected mount cannot carry a request outside
 * shared storage. Android's scoped storage then decides what is visible and writable; this class
 * reports those refusals honestly instead of guessing ahead of them.
 */
internal class GlassesFileStore(
    private val root: File,
    private val fs: GlassesFsPrimitives = AndroidFsPrimitives,
) {
    private val canonicalRoot: File by lazy { root.canonicalFile }

    fun list(
        path: String,
        offset: Int,
        pageSize: Int = GlassesFilesContract.MAX_PAGE_ENTRIES,
        nowMillis: Long = System.currentTimeMillis(),
    ): GlassesFolderListing {
        if (GlassesFilesContract.isProtected(path)) return GlassesFolderListing.Failed(GlassesFilesError.PROTECTED)
        val folder = resolve(path) ?: return GlassesFolderListing.Failed(GlassesFilesError.INVALID_REQUEST)
        if (!folder.exists()) return GlassesFolderListing.Failed(GlassesFilesError.NOT_FOUND)
        if (!folder.isDirectory) return GlassesFolderListing.Failed(GlassesFilesError.NOT_A_FOLDER)
        val children = folder.listFiles() ?: return GlassesFolderListing.Failed(GlassesFilesError.NOT_PERMITTED)
        removeStalePartials(children, nowMillis)
        val entries = children.asSequence()
            .filter { GlassesFilesContract.isValidName(it.name) && !isPartial(it.name) }
            .map { file ->
                val directory = file.isDirectory
                GlassesFileEntry(
                    name = file.name,
                    directory = directory,
                    sizeBytes = if (directory) 0L else file.length().coerceIn(0L, GlassesFilesContract.MAX_SAFE_INTEGER),
                    modifiedMillis = file.lastModified().coerceIn(0L, GlassesFilesContract.MAX_SAFE_INTEGER),
                )
            }
            .sortedWith(compareBy<GlassesFileEntry>({ !it.directory }, { it.name.lowercase(Locale.ROOT) }, { it.name }))
            .toList()
        val start = offset.coerceIn(0, entries.size)
        val end = minOf(entries.size, start + pageSize.coerceIn(0, GlassesFilesContract.MAX_PAGE_ENTRIES))
        return GlassesFolderListing.Page(entries.subList(start, end), start, entries.size)
    }

    fun makeFolder(parentPath: String, name: String): GlassesFilesError? {
        val path = GlassesFilesContract.child(parentPath, name)
        if (GlassesFilesContract.isProtected(parentPath) || GlassesFilesContract.isProtected(path)) {
            return GlassesFilesError.PROTECTED
        }
        val parent = resolve(parentPath) ?: return GlassesFilesError.INVALID_REQUEST
        if (!parent.isDirectory) return GlassesFilesError.NOT_FOUND
        val folder = resolve(path) ?: return GlassesFilesError.INVALID_REQUEST
        if (folder.exists()) return GlassesFilesError.EXISTS
        return if (runCatching { folder.mkdir() }.getOrDefault(false)) null else GlassesFilesError.NOT_PERMITTED
    }

    /**
     * Deletes one file or one empty folder, and only if it is still the kind of entry the user
     * confirmed: something swapped in at the same path since the listing is refused, not removed.
     */
    fun delete(path: String, expectDirectory: Boolean): GlassesFilesError? {
        if (path.isEmpty()) return GlassesFilesError.INVALID_REQUEST
        if (GlassesFilesContract.isProtected(path)) return GlassesFilesError.PROTECTED
        val target = resolve(path) ?: return GlassesFilesError.INVALID_REQUEST
        if (!target.exists()) return GlassesFilesError.NOT_FOUND
        if (target.isDirectory != expectDirectory) return GlassesFilesError.CHANGED
        if (expectDirectory) {
            val children = target.list() ?: return GlassesFilesError.NOT_PERMITTED
            // Recursive delete is deliberately absent: one mistaken tap must never empty a tree.
            if (children.isNotEmpty()) return GlassesFilesError.NOT_EMPTY
        }
        // The checks above give the precise answer in the common case. The guarantee comes from
        // isolating the entry first: once it sits under a private random name nobody else knows,
        // its type can no longer change, so what gets removed is exactly what was verified.
        val held = isolate(target) ?: return GlassesFilesError.NOT_PERMITTED
        val sameType = held.isDirectory == expectDirectory
        val empty = !expectDirectory || held.list()?.isEmpty() == true
        if (sameType && empty && runCatching { held.delete() }.getOrDefault(false)) return null
        restore(held, target)
        return when {
            !sameType -> GlassesFilesError.CHANGED
            !empty -> GlassesFilesError.NOT_EMPTY
            else -> GlassesFilesError.NOT_PERMITTED
        }
    }

    /** Is a file with this exact name and size already in [folderPath]? Then sending it again is waste. */
    fun alreadyPresent(folderPath: String, name: String, sizeBytes: Long): Boolean {
        val file = resolve(GlassesFilesContract.child(folderPath, name)) ?: return false
        return file.isFile && file.length() == sizeBytes
    }

    /**
     * Staging files left by an upload that died with the link or the hub. A live upload writes
     * continuously, so anything untouched for [STALE_PARTIAL_MS] is an orphan.
     */
    private fun removeStalePartials(children: Array<File>, nowMillis: Long) {
        children.forEach { file ->
            if (isPartial(file.name) && nowMillis - file.lastModified() > STALE_PARTIAL_MS) {
                runCatching { file.delete() }
            }
        }
    }

    /** A readable regular file under the root, or the reason there is none. */
    fun openForDownload(path: String): Pair<File?, GlassesFilesError?> {
        if (GlassesFilesContract.isProtected(path)) return null to GlassesFilesError.PROTECTED
        val file = resolve(path) ?: return null to GlassesFilesError.INVALID_REQUEST
        if (!file.exists()) return null to GlassesFilesError.NOT_FOUND
        if (!file.isFile) return null to GlassesFilesError.INVALID_REQUEST
        if (!file.canRead()) return null to GlassesFilesError.NOT_PERMITTED
        return file to null
    }

    /**
     * Opens a staging file beside the destination. The final name is fixed now, so the phone can
     * show it from the first byte; collisions get a numbered suffix rather than overwriting.
     */
    fun beginUpload(folderPath: String, requestedName: String, sizeBytes: Long, transferId: String): Result<GlassesUpload> {
        if (GlassesFilesContract.isProtected(folderPath)) return failure(GlassesFilesError.PROTECTED)
        if (isPartial(requestedName)) return failure(GlassesFilesError.INVALID_REQUEST)
        val folder = resolve(folderPath) ?: return failure(GlassesFilesError.INVALID_REQUEST)
        if (!folder.exists()) return failure(GlassesFilesError.NOT_FOUND)
        if (!folder.isDirectory) return failure(GlassesFilesError.NOT_A_FOLDER)
        if (runCatching { folder.usableSpace }.getOrDefault(Long.MAX_VALUE) < sizeBytes + FREE_SPACE_MARGIN_BYTES) {
            return failure(GlassesFilesError.NO_SPACE)
        }
        val name = GlassesFilesContract.uniqueName(requestedName) { File(folder, it).exists() }
            ?: return failure(GlassesFilesError.EXISTS)
        val staging = File(folder, ".nexus-${transferId.take(32)}${GlassesFilesContract.PARTIAL_SUFFIX}")
        val output = try {
            FileOutputStream(staging)
        } catch (_: IOException) {
            return failure(GlassesFilesError.NOT_PERMITTED)
        } catch (_: SecurityException) {
            return failure(GlassesFilesError.NOT_PERMITTED)
        }
        return Result.success(GlassesUpload(folder, requestedName, name, staging, sizeBytes, output, fs, this))
    }

    /** Moves [target] to a fresh private name in the same folder; null when it could not. */
    internal fun isolate(target: File): File? {
        val parent = target.parentFile ?: return null
        val held = File(parent, "$HOLD_PREFIX${java.util.UUID.randomUUID().toString().replace("-", "")}")
        return held.takeIf { runCatching { fs.rename(target, it) }.getOrDefault(false) }
    }

    /**
     * Puts an isolated entry back. If something took its name meanwhile, it comes back under a
     * numbered name instead: nothing is ever lost, though rename has no no-replace form here, so
     * a writer racing for that same fresh name in the same instant is the one remaining window.
     */
    internal fun restore(held: File, target: File) {
        val parent = target.parentFile ?: return
        if (!target.exists() && runCatching { fs.rename(held, target) }.getOrDefault(false)) return
        val fallback = GlassesFilesContract.uniqueName(target.name) { File(parent, it).exists() } ?: return
        runCatching { fs.rename(held, File(parent, fallback)) }
    }

    internal fun resolve(path: String): File? {
        if (GlassesFilesContract.normalizePath(path) != path) return null
        val file = if (path.isEmpty()) root else File(root, path)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val rootPath = canonicalRoot.path
        return canonical.takeIf { it.path == rootPath || it.path.startsWith(rootPath + File.separator) }
    }

    private fun failure(error: GlassesFilesError): Result<GlassesUpload> = Result.failure(GlassesFilesException(error))

    companion object {
        /** Leaves room for the rest of the system; filling the glasses to the last byte breaks them. */
        const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        const val STALE_PARTIAL_MS = 10L * 60 * 1000

        /** Isolated entries are never partials: if restoring one ever failed, it must stay visible. */
        private const val HOLD_PREFIX = ".nexus-hold-"

        fun isPartial(name: String): Boolean =
            name.startsWith(".nexus-") && name.endsWith(GlassesFilesContract.PARTIAL_SUFFIX)
    }
}

internal class GlassesFilesException(val error: GlassesFilesError) : Exception(error.wireValue)

/** One in-flight upload: bytes go to [staging] and only become [name] once verified. */
internal class GlassesUpload(
    private val folder: File,
    private val requestedName: String,
    name: String,
    private val staging: File,
    val sizeBytes: Long,
    private val output: FileOutputStream,
    private val fs: GlassesFsPrimitives,
    private val store: GlassesFileStore,
) {
    var name: String = name
        private set
    var stagedBytes = 0L
        private set
    private val digest: MessageDigest = GlassesFilesContract.newDigest()
    private var closed = false

    /** Accepts exactly the next bytes; anything out of place fails the whole upload. */
    fun write(offset: Long, bytes: ByteArray): GlassesFilesError? {
        if (closed) return GlassesFilesError.INVALID_REQUEST
        if (offset != stagedBytes || stagedBytes + bytes.size > sizeBytes) return GlassesFilesError.INVALID_REQUEST
        return try {
            output.write(bytes)
            digest.update(bytes)
            stagedBytes += bytes.size
            null
        } catch (_: IOException) {
            GlassesFilesError.IO_ERROR
        }
    }

    /** Verifies the digest and publishes the file under its final name. */
    fun finish(expectedSha256: String): GlassesFilesError? {
        if (closed) return GlassesFilesError.INVALID_REQUEST
        if (stagedBytes != sizeBytes) {
            abort()
            return GlassesFilesError.INTEGRITY
        }
        val flushed = runCatching {
            output.fd.sync()
            output.close()
        }.isSuccess
        closed = true
        if (!flushed) {
            staging.delete()
            return GlassesFilesError.IO_ERROR
        }
        if (GlassesFilesContract.hex(digest) != expectedSha256) {
            staging.delete()
            return GlassesFilesError.INTEGRITY
        }
        return publish()
    }

    /**
     * Copies the verified bytes into a file created exclusively under the final name. No rename
     * is involved, because rename replaces whatever holds the destination and FUSE offers no
     * variant that refuses; with an exclusive create, every byte lands in an inode this upload
     * made, so a file another app writes at the same name at any moment is never touched.
     */
    private fun publish(): GlassesFilesError? {
        var target: File? = null
        var handle: GlassesExclusiveFile? = null
        for (attempt in 0 until MAX_CLAIM_ATTEMPTS) {
            val candidate = File(folder, name)
            handle = runCatching { fs.createExclusive(candidate) }.getOrElse {
                staging.delete()
                return GlassesFilesError.NOT_PERMITTED
            }
            if (handle != null) {
                target = candidate
                break
            }
            name = GlassesFilesContract.uniqueName(requestedName) { File(folder, it).exists() } ?: break
        }
        if (handle == null || target == null) {
            staging.delete()
            return GlassesFilesError.EXISTS
        }
        val copied = handle.use { exclusive ->
            runCatching {
                staging.inputStream().use { it.copyTo(exclusive.output, GlassesFilesContract.CHUNK_BYTES) }
                exclusive.output.flush()
                exclusive.sync()
            }.isSuccess
        }
        staging.delete()
        if (copied) return null
        // Remove the half-copied file only if what sits at the name is still our own inode;
        // isolating it first means the check and the removal concern the same entry.
        store.isolate(target)?.let { held ->
            if (handle.isSameFileAs(held)) runCatching { held.delete() } else store.restore(held, target)
        }
        return GlassesFilesError.IO_ERROR
    }

    fun abort() {
        if (!closed) {
            closed = true
            runCatching { output.close() }
        }
        runCatching { staging.delete() }
    }

    fun finalFile(): File = File(folder, name)

    private companion object {
        const val MAX_CLAIM_ATTEMPTS = 8
    }
}
