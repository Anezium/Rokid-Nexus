package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssistantThreadStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun activeThreadRespectsInjectedClockAndIdleWindow() {
        var now = 1_000L
        val store = store { now }
        val id = store.appendTurn(null, "Paris", "Noted.", hadPhoto = false)

        now += 10 * 60_000L
        assertEquals(id, store.activeThreadOrNull(10)?.id)

        now += 1L
        assertNull(store.activeThreadOrNull(10))
    }

    @Test
    fun unlimitedWindowKeepsThreadActiveAfterFourHundredIdleDays() {
        var now = 1_000L
        val store = store { now }
        val id = store.appendTurn(null, "Paris", "Noted.", hadPhoto = false)

        now += 400 * 24 * 60 * 60_000L
        assertEquals(id, store.activeThreadOrNull(CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES)?.id)
        assertNull(store.activeThreadOrNull(CodexAuthStore.SEVEN_DAYS_IN_MINUTES))

        now = Long.MAX_VALUE
        assertEquals(id, store.activeThreadOrNull(CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES)?.id)

        store.startNewThread()
        assertNull(store.activeThreadOrNull(CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES))
    }

    @Test
    fun photoTurnStoresExactlyOnePrivateFileAndReadsIdenticalBytes() {
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val store = store { 1_000L }

        val threadId = store.appendTurn(
            threadId = null,
            userText = "What is this?",
            assistantText = "A label.",
            hadPhoto = true,
            photoJpeg = jpeg,
        )

        val message = store.thread(threadId)!!.messages.first()
        assertNotNull(message.photoPath)
        val photoPath = message.photoPath!!
        val photoFile = File(photoPath)
        assertTrue(message.hadPhoto)
        assertTrue(photoFile.isAbsolute)
        assertEquals(photoDirectory().canonicalFile, photoFile.parentFile!!.canonicalFile)
        assertEquals(listOf(photoFile.canonicalFile), storedPhotoFiles())
        assertArrayEquals(jpeg, store.photoBytes(photoPath))
        val jpegFiles = temporaryFolder.root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "jpg" }
            .toList()
        assertTrue(
            jpegFiles.all { file ->
                file.parentFile!!.canonicalFile == photoDirectory().canonicalFile
            },
        )
    }

    @Test
    fun photoSurvivesIdleExpiryAndStoreReopen() {
        var now = 1_000L
        val store = store { now }
        val threadId = store.appendTurn(
            null,
            "Keep this",
            "Kept.",
            hadPhoto = true,
            photoJpeg = byteArrayOf(4, 2),
        )

        now += 10 * 60_000L + 1L
        val reopened = store { now }

        assertNull(reopened.activeThreadOrNull(10))
        val photoPath = reopened.thread(threadId)!!.messages.first().photoPath!!
        assertArrayEquals(byteArrayOf(4, 2), reopened.photoBytes(photoPath))
        assertTrue(File(photoPath).isFile)
    }

    @Test
    fun nullPhotoBytesKeepMarkerWithoutWritingAFile() {
        val store = store { 1_000L }

        val threadId = store.appendTurn(
            threadId = null,
            userText = "What was there?",
            assistantText = "I cannot see it now.",
            hadPhoto = true,
            photoJpeg = null,
        )

        val message = store.thread(threadId)!!.messages.first()
        assertTrue(message.hadPhoto)
        assertNull(message.photoPath)
        assertTrue(storedPhotoFiles().isEmpty())
    }

    @Test
    fun startNewThreadForgetsActivePointerAndNextResolvedAppendCreatesThread() {
        var now = 1_000L
        val store = store { now }
        val firstId = store.appendTurn(null, "First", "One", hadPhoto = false)

        store.startNewThread()
        assertNull(store.activeThreadOrNull(10))

        now += 1L
        val secondId = store.appendTurn(
            threadId = store.activeThreadOrNull(10)?.id,
            userText = "Second",
            assistantText = "Two",
            hadPhoto = false,
        )

        assertNotEquals(firstId, secondId)
        assertEquals(2, store.threads().size)
    }

    @Test
    fun threadAndMessageCapsEvictOldestFirst() {
        var now = 0L
        val store = store { now }

        repeat(21) { index ->
            now += 1L
            store.appendTurn(null, "Thread $index", "Answer $index", hadPhoto = false)
        }

        val cappedThreads = store.threads()
        assertEquals(20, cappedThreads.size)
        assertFalse(cappedThreads.any { it.title == "Thread 0" })
        assertEquals("Thread 20", cappedThreads.first().title)

        store.deleteAll()
        now += 1L
        val threadId = store.appendTurn(null, "User 0", "Assistant 0", hadPhoto = false)
        repeat(20) { index ->
            now += 1L
            store.appendTurn(
                threadId = threadId,
                userText = "User ${index + 1}",
                assistantText = "Assistant ${index + 1}",
                hadPhoto = false,
            )
        }

        val messages = store.thread(threadId)!!.messages
        assertEquals(40, messages.size)
        assertEquals("User 1", messages.first().text)
        assertEquals("Assistant 20", messages.last().text)
    }

    @Test
    fun threadCapEvictionDeletesItsPhotoFile() {
        var now = 0L
        val store = store { now }
        val evictedThreadId = store.appendTurn(
            null,
            "Old photo",
            "Old answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(1),
        )
        val evictedPhoto = File(store.thread(evictedThreadId)!!.messages.first().photoPath!!)

        repeat(20) { index ->
            now += 1L
            store.appendTurn(null, "Thread $index", "Answer $index", hadPhoto = false)
        }

        assertNull(store.thread(evictedThreadId))
        assertFalse(evictedPhoto.exists())
        assertTrue(storedPhotoFiles().isEmpty())
    }

    @Test
    fun messageCapEvictionDeletesItsPhotoFile() {
        var now = 0L
        val store = store { now }
        val threadId = store.appendTurn(
            null,
            "Old photo",
            "Old answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(1),
        )
        val evictedPhoto = File(store.thread(threadId)!!.messages.first().photoPath!!)

        repeat(20) { index ->
            now += 1L
            store.appendTurn(
                threadId,
                "Follow-up $index",
                "Answer $index",
                hadPhoto = false,
            )
        }

        assertEquals(40, store.thread(threadId)!!.messages.size)
        assertFalse(evictedPhoto.exists())
        assertTrue(storedPhotoFiles().isEmpty())
    }

    @Test
    fun photoCountBudgetEvictsOldestFirstAndLogsDropCount() {
        var now = 0L
        val logs = mutableListOf<String>()
        val store = AssistantThreadStore(
            filesDir = temporaryFolder.root,
            clock = { now },
            maxStoredPhotos = 2,
            maxStoredPhotoBytes = Long.MAX_VALUE,
            logger = logs::add,
        )
        var threadId: String? = null
        repeat(3) { index ->
            now += 1L
            threadId = store.appendTurn(
                threadId,
                "Photo $index",
                "Answer $index",
                hadPhoto = true,
                photoJpeg = byteArrayOf((index + 1).toByte()),
            )
        }

        val photoMessages = store.thread(threadId!!)!!.messages.filter(AssistantThreadMessage::hadPhoto)
        assertNull(photoMessages[0].photoPath)
        assertNotNull(photoMessages[1].photoPath)
        assertNotNull(photoMessages[2].photoPath)
        assertArrayEquals(byteArrayOf(2), store.photoBytes(photoMessages[1].photoPath!!))
        assertArrayEquals(byteArrayOf(3), store.photoBytes(photoMessages[2].photoPath!!))
        assertEquals(2, storedPhotoFiles().size)
        assertTrue(logs.any { message -> message.contains("dropped 1 oldest photo") })
    }

    @Test
    fun photoByteBudgetEvictsOldestFirstAndLogsDropCount() {
        var now = 0L
        val logs = mutableListOf<String>()
        val store = AssistantThreadStore(
            filesDir = temporaryFolder.root,
            clock = { now },
            maxStoredPhotos = 10,
            maxStoredPhotoBytes = 5,
            logger = logs::add,
        )
        now += 1L
        val threadId = store.appendTurn(
            null,
            "First",
            "Answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(1, 1, 1),
        )
        now += 1L
        store.appendTurn(
            threadId,
            "Second",
            "Answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(2, 2, 2),
        )

        val photoMessages = store.thread(threadId)!!.messages.filter(AssistantThreadMessage::hadPhoto)
        assertNull(photoMessages[0].photoPath)
        assertNotNull(photoMessages[1].photoPath)
        assertArrayEquals(byteArrayOf(2, 2, 2), store.photoBytes(photoMessages[1].photoPath!!))
        assertEquals(1, storedPhotoFiles().size)
        assertTrue(logs.any { message -> message.contains("dropped 1 oldest photo") })
    }

    @Test
    fun deleteOperationsPersistAcrossStoreReopen() {
        var now = 100L
        val store = store { now }
        val firstId = store.appendTurn(null, "First", "A", hadPhoto = false)
        now += 1L
        val secondId = store.appendTurn(null, "Second", "B", hadPhoto = false)

        store.deleteThread(firstId)

        val reopenedAfterDelete = store { now }
        assertNull(reopenedAfterDelete.thread(firstId))
        assertEquals(secondId, reopenedAfterDelete.thread(secondId)?.id)

        reopenedAfterDelete.deleteAll()

        assertTrue(store { now }.threads().isEmpty())
    }

    @Test
    fun deleteThreadAndDeleteAllRemoveOnlyTheirReferencedPhotoFiles() {
        var now = 100L
        val store = store { now }
        val firstId = store.appendTurn(
            null,
            "First",
            "A",
            hadPhoto = true,
            photoJpeg = byteArrayOf(1),
        )
        val firstPhoto = File(store.thread(firstId)!!.messages.first().photoPath!!)
        now += 1L
        val secondId = store.appendTurn(
            null,
            "Second",
            "B",
            hadPhoto = true,
            photoJpeg = byteArrayOf(2),
        )
        val secondPhoto = File(store.thread(secondId)!!.messages.first().photoPath!!)

        store.deleteThread(firstId)

        assertFalse(firstPhoto.exists())
        assertTrue(secondPhoto.exists())
        assertEquals(listOf(secondPhoto.canonicalFile), storedPhotoFiles())

        store.deleteAll()

        assertFalse(secondPhoto.exists())
        assertTrue(store.threads().isEmpty())
        assertTrue(storedPhotoFiles().isEmpty())
    }

    @Test
    fun deleteAllPhotosKeepsThreadsTextAndPhotoMarkers() {
        var now = 100L
        val store = store { now }
        val threadId = store.appendTurn(
            null,
            "First photo",
            "First answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(1),
        )
        now += 1L
        store.appendTurn(
            threadId,
            "Second photo",
            "Second answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(2),
        )
        val before = store.thread(threadId)!!

        store.deleteAllPhotos()

        val after = store.thread(threadId)!!
        assertEquals(before.id, after.id)
        assertEquals(before.messages.map(AssistantThreadMessage::text), after.messages.map(AssistantThreadMessage::text))
        assertEquals(2, after.messages.count(AssistantThreadMessage::hadPhoto))
        assertTrue(after.messages.all { message -> message.photoPath == null })
        assertTrue(storedPhotoFiles().isEmpty())
    }

    @Test
    fun corruptJsonIsTreatedAsAnEmptyStore() {
        threadFile().writeText("{ definitely not valid JSON", Charsets.UTF_8)

        val reopened = store { 500L }

        assertTrue(reopened.threads().isEmpty())
        assertNull(reopened.activeThreadOrNull(10))
    }

    @Test
    fun missingPhotoFileDegradesToNullAndPreservesHadPhoto() {
        val store = store { 500L }
        val threadId = store.appendTurn(
            null,
            "Missing later",
            "Answer",
            hadPhoto = true,
            photoJpeg = byteArrayOf(1, 2),
        )
        val path = store.thread(threadId)!!.messages.first().photoPath!!
        assertTrue(File(path).delete())

        val reopenedMessage = store { 500L }.thread(threadId)!!.messages.first()

        assertTrue(reopenedMessage.hadPhoto)
        assertNull(reopenedMessage.photoPath)
        assertNull(store.photoBytes(path))
        assertFalse(threadFile().readText(Charsets.UTF_8).contains(path))
    }

    @Test
    fun orphanPhotoFileIsDeletedOnLoad() {
        val directory = photoDirectory()
        assertTrue(directory.mkdirs())
        val orphan = File(directory, "orphan.jpg")
        orphan.writeBytes(byteArrayOf(9, 9, 9))

        store { 500L }.threads()

        assertFalse(orphan.exists())
        assertTrue(storedPhotoFiles().isEmpty())
    }

    @Test
    fun normalAtomicWriteRoundTripsIdenticalContent() {
        var now = 200L
        val store = store { now }
        val threadId = store.appendTurn(
            null,
            "  A   title\nwith whitespace  ",
            "First answer",
            hadPhoto = false,
        )
        now += 1L
        store.appendTurn(threadId, "Follow-up", "Second answer", hadPhoto = false)
        val beforeReopen = store.threads()

        val afterReopen = store { now }.threads()

        assertEquals(beforeReopen, afterReopen)
        assertEquals("A title with whitespace", afterReopen.single().title)
        assertFalse(File(temporaryFolder.root, "${AssistantThreadStore.STORE_FILE_NAME}.tmp").exists())
    }

    @Test
    fun photoFlagRoundTripsAsMarkerWithoutWritingImagePayload() {
        val capturedImagePayload = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB_PRIVATE_IMAGE_BYTES"
        val store = store { 300L }
        store.appendTurn(
            null,
            "What is this?",
            "It is a sign.",
            hadPhoto = true,
        )

        val reopenedThread = store { 300L }.threads().single()
        assertTrue(reopenedThread.messages.first().hadPhoto)
        assertNull(reopenedThread.messages.first().photoPath)
        assertFalse(reopenedThread.messages.last().hadPhoto)
        val serialized = threadFile().readText(Charsets.UTF_8)
        assertFalse(serialized.contains(capturedImagePayload))
        assertFalse(serialized.contains(OMITTED_HISTORY_PHOTO_BASE64))

        val history = AssistantConversationThreading(store { 300L })
            .prepare(keepConversation = true, idleWindowMinutes = 10)
            .history
        val replay = ChatRequest(userText = "And now?", history = history)
            .toCodexResponsesInput()
            .toString()
        assertTrue(replay.contains("[photo]"))
        assertFalse(replay.contains(OMITTED_HISTORY_PHOTO_BASE64))
        assertFalse(replay.contains(capturedImagePayload))
    }

    @Test
    fun messagesAndTitlesAreTruncatedAtTheirCaps() {
        val store = store { 400L }
        val longText = "x".repeat(2_100)
        store.appendTurn(null, longText, longText, hadPhoto = false)

        val thread = store.threads().single()

        assertEquals(60, thread.title.length)
        assertTrue(thread.title.endsWith("…"))
        assertEquals(2_000, thread.messages.first().text.length)
        assertEquals(2_000, thread.messages.last().text.length)
    }

    private fun store(clock: () -> Long): AssistantThreadStore =
        AssistantThreadStore(temporaryFolder.root, clock)

    private fun threadFile(): File =
        File(temporaryFolder.root, AssistantThreadStore.STORE_FILE_NAME)

    private fun photoDirectory(): File =
        File(temporaryFolder.root, AssistantThreadStore.PHOTO_DIRECTORY_NAME)

    private fun storedPhotoFiles(): List<File> = photoDirectory()
        .listFiles()
        .orEmpty()
        .filter(File::isFile)
        .map(File::getCanonicalFile)
        .sortedBy(File::getName)
}
