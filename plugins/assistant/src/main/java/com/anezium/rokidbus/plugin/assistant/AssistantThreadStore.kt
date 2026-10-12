package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AssistantThreadMessage(
    val role: String,
    val text: String,
    val hadPhoto: Boolean,
    val photoPath: String?,
    val timestampMs: Long,
)

data class AssistantThread(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messages: List<AssistantThreadMessage>,
)

/**
 * Blocking, thread-safe conversation persistence.
 *
 * Callers must invoke these functions from a background dispatcher because each one may
 * perform disk I/O.
 */
class AssistantThreadStore private constructor(
    private val storeFile: File,
    private val photosDirectory: File,
    private val clock: () -> Long,
    private val maxStoredPhotos: Int,
    private val maxStoredPhotoBytes: Long,
    private val logger: (String) -> Unit,
) {
    constructor(
        context: Context,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        storeFile = File(context.applicationContext.filesDir, STORE_FILE_NAME),
        photosDirectory = File(context.applicationContext.filesDir, PHOTO_DIRECTORY_NAME),
        clock = clock,
        maxStoredPhotos = MAX_STORED_PHOTOS,
        maxStoredPhotoBytes = MAX_STORED_PHOTO_BYTES,
        logger = { message -> Log.i(TAG, message) },
    )

    internal constructor(
        filesDir: File,
        clock: () -> Long = System::currentTimeMillis,
        maxStoredPhotos: Int = MAX_STORED_PHOTOS,
        maxStoredPhotoBytes: Long = MAX_STORED_PHOTO_BYTES,
        logger: (String) -> Unit = {},
    ) : this(
        storeFile = File(filesDir, STORE_FILE_NAME),
        photosDirectory = File(filesDir, PHOTO_DIRECTORY_NAME),
        clock = clock,
        maxStoredPhotos = maxStoredPhotos,
        maxStoredPhotoBytes = maxStoredPhotoBytes,
        logger = logger,
    )

    init {
        require(maxStoredPhotos > 0)
        require(maxStoredPhotoBytes > 0L)
    }

    fun threads(): List<AssistantThread> = synchronized(fileLock) {
        readState().threads.sortedByDescending(AssistantThread::updatedAtMs)
    }

    fun thread(id: String): AssistantThread? = synchronized(fileLock) {
        readState().threads.firstOrNull { it.id == id }
    }

    fun activeThreadOrNull(idleWindowMinutes: Int): AssistantThread? = synchronized(fileLock) {
        val state = readState()
        val activeThread = state.activeThreadId
            ?.let { activeId -> state.threads.firstOrNull { it.id == activeId } }
            ?: return@synchronized null
        if (idleWindowMinutes == CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES) {
            return@synchronized activeThread
        }
        val idleWindowMs = idleWindowMinutes.toLong() * MILLIS_PER_MINUTE
        activeThread.takeIf { clock() - it.updatedAtMs <= idleWindowMs }
    }

    internal fun newThreadId(): String = UUID.randomUUID().toString()

    fun appendTurn(
        threadId: String?,
        userText: String,
        assistantText: String,
        hadPhoto: Boolean,
    ): String = appendTurn(
        threadId = threadId,
        userText = userText,
        assistantText = assistantText,
        hadPhoto = hadPhoto,
        photoJpeg = null,
    )

    fun appendTurn(
        threadId: String?,
        userText: String,
        assistantText: String,
        hadPhoto: Boolean,
        photoJpeg: ByteArray?,
    ): String = synchronized(fileLock) {
        val state = readState()
        val now = clock()
        val existing = threadId?.let { requestedId ->
            state.threads.firstOrNull { it.id == requestedId }
        }
        val usedId = existing?.id ?: threadId
            ?.takeIf(::isThreadId)
            ?: newThreadId()
        val photoPath = photoJpeg
            ?.takeIf { it.isNotEmpty() }
            ?.let(::writePhotoAtomically)
        val messages = buildList {
            addAll(existing?.messages.orEmpty())
            add(
                AssistantThreadMessage(
                    role = ROLE_USER,
                    text = userText.take(MAX_MESSAGE_CHARS),
                    hadPhoto = hadPhoto,
                    photoPath = photoPath,
                    timestampMs = now,
                ),
            )
            add(
                AssistantThreadMessage(
                    role = ROLE_ASSISTANT,
                    text = assistantText.take(MAX_MESSAGE_CHARS),
                    hadPhoto = false,
                    photoPath = null,
                    timestampMs = now,
                ),
            )
        }
        val updatedThread = AssistantThread(
            id = usedId,
            title = existing?.title ?: titleFrom(userText),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            messages = messages,
        )
        val updatedState = StoreState(
            activeThreadId = usedId,
            threads = state.threads
                .filterNot { it.id == usedId }
                .plus(updatedThread),
        )
        try {
            persistState(updatedState)
        } catch (error: Throwable) {
            photoPath?.let(::deleteManagedPhoto)
            throw error
        }
        usedId
    }

    fun photoBytes(photoPath: String): ByteArray? = synchronized(fileLock) {
        val file = managedPhotoFile(photoPath)
            ?.takeIf { it.isFile && it.canRead() }
            ?: return@synchronized null
        runCatching { file.readBytes() }.getOrNull()
    }

    fun hasStoredPhotos(): Boolean = synchronized(fileLock) {
        readState().photoPaths().isNotEmpty()
    }

    fun deleteAllPhotos() {
        synchronized(fileLock) {
            val state = readState()
            if (state.photoPaths().isEmpty()) return@synchronized
            persistState(
                state.copy(
                    threads = state.threads.map { thread ->
                        thread.copy(
                            messages = thread.messages.map { message ->
                                message.copy(photoPath = null)
                            },
                        )
                    },
                ),
            )
        }
    }

    fun startNewThread() {
        synchronized(fileLock) {
            val state = readState()
            persistState(state.copy(activeThreadId = null))
        }
    }

    fun deleteThread(id: String) {
        synchronized(fileLock) {
            val state = readState()
            persistState(
                state.copy(
                    activeThreadId = state.activeThreadId?.takeUnless { it == id },
                    threads = state.threads.filterNot { it.id == id },
                ),
            )
        }
    }

    fun deleteAll() {
        synchronized(fileLock) {
            readState()
            persistState(StoreState())
        }
    }

    private val fileLock: Any
        get() = FILE_LOCKS.computeIfAbsent(storeFile.absoluteFile.normalize().path) { Any() }

    private fun readState(): StoreState {
        val parsed = parseState()
        val reconciled = parsed.reconcilePhotoPaths()
        val capped = reconciled.enforceCapsAndPhotoBudget()
        if (storeFile.isFile && capped.state != parsed) {
            writeStateFile(capped.state)
        }
        deleteUnreferencedPhotoFiles(capped.state)
        logPhotoBudgetDrop(capped.droppedPhotoCount)
        return capped.state
    }

    private fun parseState(): StoreState {
        if (!storeFile.isFile) return StoreState()
        return runCatching {
            val root = JSONObject(storeFile.readText(Charsets.UTF_8))
            val threadsJson = root.getJSONArray(JSON_THREADS)
            val threads = buildList {
                for (threadIndex in 0 until threadsJson.length()) {
                    val threadJson = threadsJson.getJSONObject(threadIndex)
                    val messagesJson = threadJson.getJSONArray(JSON_MESSAGES)
                    val messages = buildList {
                        for (messageIndex in 0 until messagesJson.length()) {
                            val messageJson = messagesJson.getJSONObject(messageIndex)
                            add(
                                AssistantThreadMessage(
                                    role = messageJson.getString(JSON_ROLE),
                                    text = messageJson.getString(JSON_TEXT),
                                    hadPhoto = messageJson.getBoolean(JSON_HAD_PHOTO),
                                    photoPath = messageJson.optString(JSON_PHOTO_PATH)
                                        .takeIf(String::isNotBlank),
                                    timestampMs = messageJson.getLong(JSON_TIMESTAMP_MS),
                                ),
                            )
                        }
                    }
                    add(
                        AssistantThread(
                            id = threadJson.getString(JSON_ID),
                            title = threadJson.getString(JSON_TITLE),
                            createdAtMs = threadJson.getLong(JSON_CREATED_AT_MS),
                            updatedAtMs = threadJson.getLong(JSON_UPDATED_AT_MS),
                            messages = messages,
                        ),
                    )
                }
            }
            StoreState(
                activeThreadId = root.optString(JSON_ACTIVE_THREAD_ID)
                    .takeIf(String::isNotBlank),
                threads = threads,
            )
        }.getOrElse { StoreState() }
    }

    private fun persistState(state: StoreState) {
        val capped = state.enforceCapsAndPhotoBudget()
        writeStateFile(capped.state)
        deleteUnreferencedPhotoFiles(capped.state)
        logPhotoBudgetDrop(capped.droppedPhotoCount)
    }

    private fun writeStateFile(state: StoreState) {
        storeFile.parentFile?.mkdirs()
        val tempFile = File(storeFile.parentFile, "$STORE_FILE_NAME.tmp")
        val bytes = state.toJson().toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    storeFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    storeFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun writePhotoAtomically(jpeg: ByteArray): String? = runCatching {
        if (!photosDirectory.isDirectory && !photosDirectory.mkdirs()) {
            error("Assistant photo directory could not be created.")
        }
        val id = UUID.randomUUID().toString()
        val targetFile = File(photosDirectory, "$id.jpg")
        val tempFile = File(photosDirectory, ".$id.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(jpeg)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), targetFile.toPath())
            }
            targetFile.canonicalFile.absolutePath
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }.onFailure { error ->
        log("Assistant photo storage failed: ${error.javaClass.simpleName}")
    }.getOrNull()

    private fun StoreState.toJson(): JSONObject = JSONObject().apply {
        put(JSON_VERSION, STORE_VERSION)
        activeThreadId?.let { put(JSON_ACTIVE_THREAD_ID, it) }
        put(
            JSON_THREADS,
            JSONArray().apply {
                threads.forEach { thread ->
                    put(
                        JSONObject()
                            .put(JSON_ID, thread.id)
                            .put(JSON_TITLE, thread.title)
                            .put(JSON_CREATED_AT_MS, thread.createdAtMs)
                            .put(JSON_UPDATED_AT_MS, thread.updatedAtMs)
                            .put(
                                JSON_MESSAGES,
                                JSONArray().apply {
                                    thread.messages.forEach { message ->
                                        put(
                                            JSONObject()
                                                .put(JSON_ROLE, message.role)
                                                .put(JSON_TEXT, message.text)
                                                .put(JSON_HAD_PHOTO, message.hadPhoto)
                                                .apply {
                                                    message.photoPath?.let { path ->
                                                        put(JSON_PHOTO_PATH, path)
                                                    }
                                                }
                                                .put(JSON_TIMESTAMP_MS, message.timestampMs),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
    }

    private fun StoreState.reconcilePhotoPaths(): StoreState = copy(
        threads = threads.map { thread ->
            thread.copy(
                messages = thread.messages.map { message ->
                    val readablePath = message.photoPath
                        ?.let(::managedPhotoFile)
                        ?.takeIf { it.isFile && it.canRead() }
                        ?.absolutePath
                    if (readablePath == message.photoPath) {
                        message
                    } else {
                        message.copy(photoPath = readablePath)
                    }
                },
            )
        },
    )

    private fun StoreState.enforceCapsAndPhotoBudget(): CappedState {
        val structurallyCapped = copy(
            threads = threads
                .map { thread ->
                    thread.copy(
                        messages = thread.messages
                            .takeLast(MAX_MESSAGES_PER_THREAD)
                            .map { message ->
                                message.copy(text = message.text.take(MAX_MESSAGE_CHARS))
                            },
                    )
                }
                .sortedByDescending(AssistantThread::updatedAtMs)
                .take(MAX_THREADS),
        ).let { capped ->
            capped.copy(
                activeThreadId = capped.activeThreadId?.takeIf { activeId ->
                    capped.threads.any { it.id == activeId }
                },
            )
        }
        val storedPhotos = structurallyCapped.threads
            .flatMap { thread ->
                thread.messages.mapNotNull { message ->
                    val path = message.photoPath ?: return@mapNotNull null
                    val file = managedPhotoFile(path)
                        ?.takeIf(File::isFile)
                        ?: return@mapNotNull null
                    StoredPhoto(
                        path = file.absolutePath,
                        timestampMs = message.timestampMs,
                        byteCount = file.length(),
                    )
                }
            }
            .groupBy(StoredPhoto::path)
            .map { (path, references) ->
                StoredPhoto(
                    path = path,
                    timestampMs = references.minOf(StoredPhoto::timestampMs),
                    byteCount = references.first().byteCount,
                )
            }
            .sortedWith(compareBy(StoredPhoto::timestampMs, StoredPhoto::path))
        var retainedCount = storedPhotos.size
        var retainedBytes = storedPhotos.sumOf(StoredPhoto::byteCount)
        val droppedPaths = mutableSetOf<String>()
        val oldestFirst = storedPhotos.iterator()
        while (
            (retainedCount > maxStoredPhotos || retainedBytes > maxStoredPhotoBytes) &&
            oldestFirst.hasNext()
        ) {
            val dropped = oldestFirst.next()
            droppedPaths += dropped.path
            retainedCount -= 1
            retainedBytes -= dropped.byteCount
        }
        if (droppedPaths.isEmpty()) {
            return CappedState(structurallyCapped, droppedPhotoCount = 0)
        }
        return CappedState(
            state = structurallyCapped.copy(
                threads = structurallyCapped.threads.map { thread ->
                    thread.copy(
                        messages = thread.messages.map { message ->
                            if (message.photoPath in droppedPaths) {
                                message.copy(photoPath = null)
                            } else {
                                message
                            }
                        },
                    )
                },
            ),
            droppedPhotoCount = droppedPaths.size,
        )
    }

    private fun StoreState.photoPaths(): Set<String> = threads
        .asSequence()
        .flatMap { thread -> thread.messages.asSequence() }
        .mapNotNull(AssistantThreadMessage::photoPath)
        .toSet()

    private fun deleteUnreferencedPhotoFiles(state: StoreState) {
        val referencedPaths = state.photoPaths()
            .mapNotNull(::managedPhotoFile)
            .map(File::getAbsolutePath)
            .toSet()
        photosDirectory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .filterNot { file -> file.canonicalOrAbsolutePath() in referencedPaths }
            .forEach(File::delete)
        if (photosDirectory.list().isNullOrEmpty()) {
            photosDirectory.delete()
        }
    }

    private fun deleteManagedPhoto(path: String) {
        managedPhotoFile(path)?.delete()
        if (photosDirectory.list().isNullOrEmpty()) {
            photosDirectory.delete()
        }
    }

    private fun managedPhotoFile(path: String): File? = runCatching {
        val source = File(path)
        if (!source.isAbsolute || !source.name.endsWith(PHOTO_FILE_SUFFIX)) {
            return@runCatching null
        }
        val directory = photosDirectory.canonicalFile
        source.canonicalFile.takeIf { file -> file.parentFile == directory }
    }.getOrNull()

    private fun File.canonicalOrAbsolutePath(): String =
        runCatching { canonicalFile.absolutePath }.getOrElse { absoluteFile.normalize().path }

    private fun logPhotoBudgetDrop(droppedPhotoCount: Int) {
        if (droppedPhotoCount > 0) {
            log("Assistant photo budget dropped $droppedPhotoCount oldest photo(s).")
        }
    }

    private fun log(message: String) {
        logger(message)
    }

    private fun isThreadId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private data class StoreState(
        val activeThreadId: String? = null,
        val threads: List<AssistantThread> = emptyList(),
    )

    private data class StoredPhoto(
        val path: String,
        val timestampMs: Long,
        val byteCount: Long,
    )

    private data class CappedState(
        val state: StoreState,
        val droppedPhotoCount: Int,
    )

    companion object {
        internal const val STORE_FILE_NAME = "assistant_threads_v1.json"
        internal const val PHOTO_DIRECTORY_NAME = "assistant_photos"
        private const val PHOTO_FILE_SUFFIX = ".jpg"
        private const val STORE_VERSION = 2
        private const val MAX_THREADS = 20
        private const val MAX_MESSAGES_PER_THREAD = 40
        private const val MAX_MESSAGE_CHARS = 2000
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_STORED_PHOTOS = 200
        private const val MAX_STORED_PHOTO_BYTES = 100L * 1024L * 1024L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val UNTITLED = "Untitled"
        private const val ELLIPSIS = "\u2026"
        private const val TAG = "NexusAssistant"

        private const val JSON_VERSION = "version"
        private const val JSON_ACTIVE_THREAD_ID = "activeThreadId"
        private const val JSON_THREADS = "threads"
        private const val JSON_ID = "id"
        private const val JSON_TITLE = "title"
        private const val JSON_CREATED_AT_MS = "createdAtMs"
        private const val JSON_UPDATED_AT_MS = "updatedAtMs"
        private const val JSON_MESSAGES = "messages"
        private const val JSON_ROLE = "role"
        private const val JSON_TEXT = "text"
        private const val JSON_HAD_PHOTO = "hadPhoto"
        private const val JSON_PHOTO_PATH = "photoPath"
        private const val JSON_TIMESTAMP_MS = "timestampMs"

        private val FILE_LOCKS = ConcurrentHashMap<String, Any>()

        private fun titleFrom(userText: String): String {
            val collapsed = userText.trim().replace(Regex("\\s+"), " ")
            if (collapsed.isBlank()) return UNTITLED
            if (collapsed.length <= MAX_TITLE_CHARS) return collapsed
            return collapsed.take(MAX_TITLE_CHARS - ELLIPSIS.length) + ELLIPSIS
        }
    }
}
