package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Whether the glasses hub can see and write all of shared storage, or only the scoped subset. */
enum class GlassesFilesAccess(val wireValue: String) {
    FULL("full"),
    LIMITED("limited"),
    ;

    companion object {
        fun fromWireValue(value: String?): GlassesFilesAccess? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class GlassesFilesError(val wireValue: String) {
    NOT_FOUND("not_found"),
    NOT_PERMITTED("not_permitted"),
    PROTECTED("protected"),
    EXISTS("exists"),
    NOT_EMPTY("not_empty"),
    NOT_A_FOLDER("not_a_folder"),
    NO_SPACE("no_space"),
    CHANGED("changed"),
    INTEGRITY("integrity"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    LINK_LOST("link_lost"),
    INVALID_REQUEST("invalid_request"),
    IO_ERROR("io_error"),
    INTERNAL("internal"),
    ;

    companion object {
        fun fromWireValue(value: String?): GlassesFilesError? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class GlassesFilesOperation(val wireValue: String) {
    MKDIR("mkdir"),
    DELETE("delete"),
    GRANT_ACCESS("grant_access"),
    ;

    companion object {
        fun fromWireValue(value: String?): GlassesFilesOperation? = entries.firstOrNull { it.wireValue == value }
    }
}

data class GlassesFileEntry(
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/** Phone to glasses, on [GlassesFilesContract.REQUEST_PATH]. */
sealed interface GlassesFilesRequest {
    data class ListFolder(val requestId: String, val path: String, val offset: Int) : GlassesFilesRequest
    data class MakeFolder(val requestId: String, val path: String, val name: String) : GlassesFilesRequest
    /** [directory] is the type the user saw and confirmed; the glasses refuse a changed entry. */
    data class Delete(val requestId: String, val path: String, val directory: Boolean) : GlassesFilesRequest
    data class GrantAccess(val requestId: String) : GlassesFilesRequest
    data class UploadBegin(
        val transferId: String,
        val path: String,
        val name: String,
        val sizeBytes: Long,
    ) : GlassesFilesRequest
    data class UploadEnd(val transferId: String, val sha256: String) : GlassesFilesRequest
    data class DownloadBegin(val transferId: String, val path: String) : GlassesFilesRequest
    data class DownloadProgress(val transferId: String, val stagedBytes: Long) : GlassesFilesRequest
    data class Cancel(val transferId: String) : GlassesFilesRequest
}

/** Glasses to phone, on [GlassesFilesContract.RESULT_PATH]. */
sealed interface GlassesFilesResult {
    data class Listing(
        val requestId: String,
        val path: String,
        val access: GlassesFilesAccess,
        val entries: List<GlassesFileEntry>,
        val offset: Int,
        val total: Int,
        val error: GlassesFilesError? = null,
    ) : GlassesFilesResult

    data class Operation(
        val requestId: String,
        val operation: GlassesFilesOperation,
        val error: GlassesFilesError? = null,
        val access: GlassesFilesAccess? = null,
        /** True when the glasses opened their own settings screen for the wearer to finish. */
        val needsWearer: Boolean = false,
    ) : GlassesFilesResult

    data class UploadReady(val transferId: String, val name: String) : GlassesFilesResult
    data class UploadProgress(val transferId: String, val stagedBytes: Long) : GlassesFilesResult
    /** [alreadyPresent]: a file with the same name and size was there, so nothing was sent. */
    data class UploadDone(val transferId: String, val name: String, val alreadyPresent: Boolean = false) : GlassesFilesResult
    data class DownloadReady(val transferId: String, val sizeBytes: Long, val modifiedMillis: Long) : GlassesFilesResult
    data class DownloadEnd(val transferId: String, val sha256: String) : GlassesFilesResult
    data class TransferFailed(val transferId: String, val error: GlassesFilesError) : GlassesFilesResult
}

data class GlassesFilesChunk(val transferId: String, val offset: Long)

/**
 * Private hub-to-hub file explorer for the glasses' shared storage.
 *
 * Paths on the wire are always relative to the storage root and use `/` separators; the root is
 * the empty string. Neither hub ever sends or accepts an absolute path, so the glasses resolve
 * every request against their own root and nothing on the wire can name a location outside it.
 *
 * Bytes move as [BusEnvelope.binary] chunks on [DATA_PATH] in either direction, under the same
 * acknowledged-window discipline as photo sync: the receiver acks what it has durably written,
 * the sender never runs more than [ACK_WINDOW_BYTES] ahead, and the terminator is only sent once
 * every byte is acknowledged, because control JSON and binary chunks ride different transports
 * and one can overtake the other.
 */
object GlassesFilesContract {
    const val VERSION = 1
    const val REQUEST_PATH = "/core/files/request"
    const val RESULT_PATH = "/core/files/result"
    const val DATA_PATH = "/core/files/data"
    const val ROOT_PATH = "/core/files"

    const val CHUNK_BYTES = 32 * 1024
    const val MAX_CHUNK_BYTES = 64 * 1024
    const val ACK_WINDOW_BYTES = 128 * 1024
    const val ACK_EVERY_CHUNKS = 2

    /** A transfer with no progress in either direction for this long is abandoned by both hubs. */
    const val IDLE_TIMEOUT_MS = 30_000L

    const val MAX_PAGE_ENTRIES = 200
    const val MAX_MESSAGE_BYTES = 128 * 1024
    const val MAX_PATH_CHARS = 1024
    const val MAX_NAME_BYTES = 255
    const val MAX_DEPTH = 32
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    /** Suffix of the staging file an upload writes before it is verified and renamed. */
    const val PARTIAL_SUFFIX = ".nexuspart"

    private const val TYPE_LIST = "list"
    private const val TYPE_MKDIR = "mkdir"
    private const val TYPE_DELETE = "delete"
    private const val TYPE_GRANT_ACCESS = "grant_access"
    private const val TYPE_UPLOAD_BEGIN = "upload_begin"
    private const val TYPE_UPLOAD_END = "upload_end"
    private const val TYPE_DOWNLOAD_BEGIN = "download_begin"
    private const val TYPE_DOWNLOAD_PROGRESS = "download_progress"
    private const val TYPE_CANCEL = "cancel"

    private const val TYPE_LIST_RESULT = "list_result"
    private const val TYPE_OPERATION_RESULT = "operation_result"
    private const val TYPE_UPLOAD_READY = "upload_ready"
    private const val TYPE_UPLOAD_PROGRESS = "upload_progress"
    private const val TYPE_UPLOAD_DONE = "upload_done"
    private const val TYPE_DOWNLOAD_READY = "download_ready"
    private const val TYPE_DOWNLOAD_END = "download_end"
    private const val TYPE_TRANSFER_FAILED = "transfer_failed"
    private const val TYPE_CHUNK = "chunk"

    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun encode(request: GlassesFilesRequest): JSONObject = when (request) {
        is GlassesFilesRequest.ListFolder -> {
            requireId(request.requestId)
            require(normalizePath(request.path) == request.path) { "Invalid path" }
            require(request.offset in 0..MAX_SAFE_INT) { "Invalid offset" }
            base(TYPE_LIST).put("requestId", request.requestId).put("path", request.path).put("offset", request.offset)
        }
        is GlassesFilesRequest.MakeFolder -> {
            requireId(request.requestId)
            require(normalizePath(request.path) == request.path) { "Invalid path" }
            require(isValidName(request.name)) { "Invalid name" }
            base(TYPE_MKDIR).put("requestId", request.requestId).put("path", request.path).put("name", request.name)
        }
        is GlassesFilesRequest.Delete -> {
            requireId(request.requestId)
            require(request.path.isNotEmpty() && normalizePath(request.path) == request.path) { "Invalid path" }
            base(TYPE_DELETE).put("requestId", request.requestId).put("path", request.path).put("dir", request.directory)
        }
        is GlassesFilesRequest.GrantAccess -> {
            requireId(request.requestId)
            base(TYPE_GRANT_ACCESS).put("requestId", request.requestId)
        }
        is GlassesFilesRequest.UploadBegin -> {
            requireId(request.transferId)
            require(normalizePath(request.path) == request.path) { "Invalid path" }
            require(isValidName(request.name)) { "Invalid name" }
            require(request.sizeBytes in 0..MAX_FILE_BYTES) { "Invalid size" }
            base(TYPE_UPLOAD_BEGIN)
                .put("transferId", request.transferId)
                .put("path", request.path)
                .put("name", request.name)
                .put("size", request.sizeBytes)
        }
        is GlassesFilesRequest.UploadEnd -> {
            requireId(request.transferId)
            require(sha256Pattern.matches(request.sha256)) { "Invalid digest" }
            base(TYPE_UPLOAD_END).put("transferId", request.transferId).put("sha256", request.sha256)
        }
        is GlassesFilesRequest.DownloadBegin -> {
            requireId(request.transferId)
            require(request.path.isNotEmpty() && normalizePath(request.path) == request.path) { "Invalid path" }
            base(TYPE_DOWNLOAD_BEGIN).put("transferId", request.transferId).put("path", request.path)
        }
        is GlassesFilesRequest.DownloadProgress -> {
            requireId(request.transferId)
            require(request.stagedBytes in 0..MAX_FILE_BYTES) { "Invalid offset" }
            base(TYPE_DOWNLOAD_PROGRESS).put("transferId", request.transferId).put("staged", request.stagedBytes)
        }
        is GlassesFilesRequest.Cancel -> {
            requireId(request.transferId)
            base(TYPE_CANCEL).put("transferId", request.transferId)
        }
    }

    fun parseRequest(payload: JSONObject): GlassesFilesRequest? {
        if (!hasValidEnvelope(payload)) return null
        return when (payload.strictString("type")) {
            TYPE_LIST -> GlassesFilesRequest.ListFolder(
                requestId = payload.id("requestId") ?: return null,
                path = payload.path("path") ?: return null,
                offset = payload.strictLong("offset")?.takeIf { it in 0..MAX_SAFE_INT }?.toInt() ?: return null,
            )
            TYPE_MKDIR -> GlassesFilesRequest.MakeFolder(
                requestId = payload.id("requestId") ?: return null,
                path = payload.path("path") ?: return null,
                name = payload.strictString("name")?.takeIf(::isValidName) ?: return null,
            )
            TYPE_DELETE -> GlassesFilesRequest.Delete(
                requestId = payload.id("requestId") ?: return null,
                path = payload.path("path")?.takeIf(String::isNotEmpty) ?: return null,
                directory = payload.strictBoolean("dir") ?: return null,
            )
            TYPE_GRANT_ACCESS -> GlassesFilesRequest.GrantAccess(payload.id("requestId") ?: return null)
            TYPE_UPLOAD_BEGIN -> GlassesFilesRequest.UploadBegin(
                transferId = payload.id("transferId") ?: return null,
                path = payload.path("path") ?: return null,
                name = payload.strictString("name")?.takeIf(::isValidName) ?: return null,
                sizeBytes = payload.strictLong("size")?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null,
            )
            TYPE_UPLOAD_END -> GlassesFilesRequest.UploadEnd(
                transferId = payload.id("transferId") ?: return null,
                sha256 = payload.strictString("sha256")?.takeIf(sha256Pattern::matches) ?: return null,
            )
            TYPE_DOWNLOAD_BEGIN -> GlassesFilesRequest.DownloadBegin(
                transferId = payload.id("transferId") ?: return null,
                path = payload.path("path")?.takeIf(String::isNotEmpty) ?: return null,
            )
            TYPE_DOWNLOAD_PROGRESS -> GlassesFilesRequest.DownloadProgress(
                transferId = payload.id("transferId") ?: return null,
                stagedBytes = payload.strictLong("staged")?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null,
            )
            TYPE_CANCEL -> GlassesFilesRequest.Cancel(payload.id("transferId") ?: return null)
            else -> null
        }
    }

    fun encode(result: GlassesFilesResult): JSONObject = when (result) {
        is GlassesFilesResult.Listing -> {
            requireId(result.requestId)
            require(normalizePath(result.path) == result.path) { "Invalid path" }
            require(result.entries.size <= MAX_PAGE_ENTRIES) { "Listing page too large" }
            require(result.error == null || result.entries.isEmpty()) { "Failed listing must be empty" }
            require(result.offset in 0..MAX_SAFE_INT && result.total in 0..MAX_SAFE_INT) { "Invalid page" }
            val entries = JSONArray().apply {
                result.entries.forEach { entry ->
                    require(isValidEntry(entry)) { "Invalid entry" }
                    put(
                        JSONObject()
                            .put("name", entry.name)
                            .put("dir", entry.directory)
                            .put("size", entry.sizeBytes)
                            .put("mtime", entry.modifiedMillis),
                    )
                }
            }
            base(TYPE_LIST_RESULT)
                .put("requestId", result.requestId)
                .put("path", result.path)
                .put("access", result.access.wireValue)
                .put("entries", entries)
                .put("offset", result.offset)
                .put("total", result.total)
                .putOpt("errorCode", result.error?.wireValue)
                .also { require(isWithinMessageLimit(it)) { "Listing result too large" } }
        }
        is GlassesFilesResult.Operation -> {
            requireId(result.requestId)
            base(TYPE_OPERATION_RESULT)
                .put("requestId", result.requestId)
                .put("operation", result.operation.wireValue)
                .putOpt("errorCode", result.error?.wireValue)
                .putOpt("access", result.access?.wireValue)
                .apply { if (result.needsWearer) put("needsWearer", true) }
        }
        is GlassesFilesResult.UploadReady -> {
            requireId(result.transferId)
            require(isValidName(result.name)) { "Invalid name" }
            base(TYPE_UPLOAD_READY).put("transferId", result.transferId).put("name", result.name)
        }
        is GlassesFilesResult.UploadProgress -> {
            requireId(result.transferId)
            require(result.stagedBytes in 0..MAX_FILE_BYTES) { "Invalid offset" }
            base(TYPE_UPLOAD_PROGRESS).put("transferId", result.transferId).put("staged", result.stagedBytes)
        }
        is GlassesFilesResult.UploadDone -> {
            requireId(result.transferId)
            require(isValidName(result.name)) { "Invalid name" }
            base(TYPE_UPLOAD_DONE).put("transferId", result.transferId).put("name", result.name)
                .apply { if (result.alreadyPresent) put("alreadyPresent", true) }
        }
        is GlassesFilesResult.DownloadReady -> {
            requireId(result.transferId)
            require(result.sizeBytes in 0..MAX_FILE_BYTES) { "Invalid size" }
            require(result.modifiedMillis in 0..MAX_SAFE_INTEGER) { "Invalid time" }
            base(TYPE_DOWNLOAD_READY)
                .put("transferId", result.transferId)
                .put("size", result.sizeBytes)
                .put("mtime", result.modifiedMillis)
        }
        is GlassesFilesResult.DownloadEnd -> {
            requireId(result.transferId)
            require(sha256Pattern.matches(result.sha256)) { "Invalid digest" }
            base(TYPE_DOWNLOAD_END).put("transferId", result.transferId).put("sha256", result.sha256)
        }
        is GlassesFilesResult.TransferFailed -> {
            requireId(result.transferId)
            base(TYPE_TRANSFER_FAILED).put("transferId", result.transferId).put("errorCode", result.error.wireValue)
        }
    }

    fun parseResult(payload: JSONObject): GlassesFilesResult? {
        if (!hasValidEnvelope(payload)) return null
        return when (payload.strictString("type")) {
            TYPE_LIST_RESULT -> parseListing(payload)
            TYPE_OPERATION_RESULT -> GlassesFilesResult.Operation(
                requestId = payload.id("requestId") ?: return null,
                operation = GlassesFilesOperation.fromWireValue(payload.strictString("operation")) ?: return null,
                error = (payload.optionalError() ?: return null).error,
                access = if (payload.has("access")) {
                    GlassesFilesAccess.fromWireValue(payload.strictString("access")) ?: return null
                } else {
                    null
                },
                needsWearer = if (payload.has("needsWearer")) payload.strictBoolean("needsWearer") ?: return null else false,
            )
            TYPE_UPLOAD_READY -> GlassesFilesResult.UploadReady(
                transferId = payload.id("transferId") ?: return null,
                name = payload.strictString("name")?.takeIf(::isValidName) ?: return null,
            )
            TYPE_UPLOAD_PROGRESS -> GlassesFilesResult.UploadProgress(
                transferId = payload.id("transferId") ?: return null,
                stagedBytes = payload.strictLong("staged")?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null,
            )
            TYPE_UPLOAD_DONE -> GlassesFilesResult.UploadDone(
                transferId = payload.id("transferId") ?: return null,
                name = payload.strictString("name")?.takeIf(::isValidName) ?: return null,
                alreadyPresent = if (payload.has("alreadyPresent")) {
                    payload.strictBoolean("alreadyPresent") ?: return null
                } else {
                    false
                },
            )
            TYPE_DOWNLOAD_READY -> GlassesFilesResult.DownloadReady(
                transferId = payload.id("transferId") ?: return null,
                sizeBytes = payload.strictLong("size")?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null,
                modifiedMillis = payload.strictLong("mtime")?.takeIf { it in 0..MAX_SAFE_INTEGER } ?: return null,
            )
            TYPE_DOWNLOAD_END -> GlassesFilesResult.DownloadEnd(
                transferId = payload.id("transferId") ?: return null,
                sha256 = payload.strictString("sha256")?.takeIf(sha256Pattern::matches) ?: return null,
            )
            TYPE_TRANSFER_FAILED -> GlassesFilesResult.TransferFailed(
                transferId = payload.id("transferId") ?: return null,
                error = GlassesFilesError.fromWireValue(payload.strictString("errorCode")) ?: return null,
            )
            else -> null
        }
    }

    fun chunkMeta(chunk: GlassesFilesChunk): JSONObject {
        requireId(chunk.transferId)
        require(chunk.offset in 0..MAX_FILE_BYTES) { "Invalid offset" }
        return base(TYPE_CHUNK).put("transferId", chunk.transferId).put("offset", chunk.offset)
    }

    fun parseChunk(payload: JSONObject, binary: ByteArray?): GlassesFilesChunk? {
        if (binary == null || binary.isEmpty() || binary.size > MAX_CHUNK_BYTES) return null
        if (!hasValidEnvelope(payload) || payload.strictString("type") != TYPE_CHUNK) return null
        return GlassesFilesChunk(
            transferId = payload.id("transferId") ?: return null,
            offset = payload.strictLong("offset")?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null,
        )
    }

    /**
     * Canonical relative form of [value], or null when it is not a safe storage path. Leading and
     * trailing separators are tolerated; empty, `.` and `..` segments are not, so a path can never
     * climb out of the root however it is resolved.
     */
    fun normalizePath(value: String): String? {
        if (value.length > MAX_PATH_CHARS) return null
        val trimmed = value.trim('/')
        if (trimmed.isEmpty()) return ""
        val segments = trimmed.split('/')
        if (segments.size > MAX_DEPTH) return null
        if (!segments.all(::isValidName)) return null
        return segments.joinToString("/")
    }

    fun isValidName(value: String): Boolean =
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES &&
            value.none { it == '/' || it == '\\' || it.isISOControl() } &&
            hasValidSurrogates(value)

    /**
     * Any bus path in the explorer's subtree. Only the hubs themselves may originate these, in
     * every build: the debug-only legacy client bypass must not reach file deletion or the grant.
     */
    fun isFilesPath(path: String): Boolean = PathRules.matchesPrefix(path, ROOT_PATH)

    fun child(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun name(path: String): String = path.substringAfterLast('/')

    /**
     * `Android/data` and `Android/obb` belong to other apps' private storage. Android refuses them
     * to every app regardless of grants, so the explorer treats them as off limits up front rather
     * than surfacing a confusing permission failure.
     */
    fun isProtected(path: String): Boolean {
        val segments = path.split('/')
        return segments.size >= 2 &&
            segments[0].equals("Android", ignoreCase = true) &&
            (segments[1].equals("data", ignoreCase = true) || segments[1].equals("obb", ignoreCase = true))
    }

    /** `book.pdf`, then `book (1).pdf`, `book (2).pdf`, ...; null when every candidate is taken. */
    fun uniqueName(name: String, taken: (String) -> Boolean): String? {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val (stem, extension) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        for (index in 1..MAX_RENAME_ATTEMPTS) {
            val candidate = "$stem ($index)$extension"
            if (!isValidName(candidate)) return null
            if (!taken(candidate)) return candidate
        }
        return null
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    fun isValidId(value: String): Boolean = value.length <= 128 && idPattern.matches(value)

    private fun parseListing(payload: JSONObject): GlassesFilesResult.Listing? {
        val array = payload.opt("entries") as? JSONArray ?: return null
        if (array.length() > MAX_PAGE_ENTRIES) return null
        val entries = ArrayList<GlassesFileEntry>(array.length())
        for (index in 0 until array.length()) {
            val json = array.opt(index) as? JSONObject ?: return null
            val entry = GlassesFileEntry(
                name = json.strictString("name") ?: return null,
                directory = json.strictBoolean("dir") ?: return null,
                sizeBytes = json.strictLong("size") ?: return null,
                modifiedMillis = json.strictLong("mtime") ?: return null,
            )
            if (!isValidEntry(entry)) return null
            entries += entry
        }
        val error = (payload.optionalError() ?: return null).error
        val listing = GlassesFilesResult.Listing(
            requestId = payload.id("requestId") ?: return null,
            path = payload.path("path") ?: return null,
            access = GlassesFilesAccess.fromWireValue(payload.strictString("access")) ?: return null,
            entries = entries,
            offset = payload.strictLong("offset")?.takeIf { it in 0..MAX_SAFE_INT }?.toInt() ?: return null,
            total = payload.strictLong("total")?.takeIf { it in 0..MAX_SAFE_INT }?.toInt() ?: return null,
            error = error,
        )
        if (listing.error != null && entries.isNotEmpty()) return null
        return listing
    }

    private fun isValidEntry(entry: GlassesFileEntry): Boolean =
        isValidName(entry.name) &&
            entry.sizeBytes in 0..MAX_SAFE_INTEGER &&
            entry.modifiedMillis in 0..MAX_SAFE_INTEGER

    /** Absent means success; a present but unknown code makes the whole message invalid (null). */
    private class ErrorField(val error: GlassesFilesError?)

    private fun JSONObject.optionalError(): ErrorField? {
        if (!has("errorCode")) return ErrorField(null)
        return GlassesFilesError.fromWireValue(strictString("errorCode"))?.let(::ErrorField)
    }

    private fun hasValidEnvelope(payload: JSONObject): Boolean =
        isWithinMessageLimit(payload) && payload.strictInt("version") == VERSION

    private fun base(type: String): JSONObject = JSONObject().put("version", VERSION).put("type", type)

    private fun requireId(value: String) = require(isValidId(value)) { "Invalid id" }

    private fun JSONObject.id(key: String): String? = strictString(key)?.takeIf(::isValidId)

    private fun JSONObject.path(key: String): String? =
        strictString(key)?.let { raw -> normalizePath(raw)?.takeIf { it == raw } }

    private fun isWithinMessageLimit(payload: JSONObject): Boolean =
        payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES

    private fun hasValidSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.strictLong(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private const val MAX_SAFE_INT = Int.MAX_VALUE.toLong()
    private const val MAX_RENAME_ATTEMPTS = 999
}
