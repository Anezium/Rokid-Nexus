package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GlassesFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val transferId = "transfer_0123456789"

    private fun store(): Pair<GlassesFileStore, File> {
        val root = temp.newFolder("emulated0")
        return GlassesFileStore(root) to root
    }

    @Test
    fun `listing puts folders first then names case-insensitively and hides staging files`() {
        val (store, root) = store()
        File(root, "b.pdf").writeText("b")
        File(root, "A.pdf").writeText("aa")
        File(root, "Zeta").mkdir()
        File(root, "alpha").mkdir()
        File(root, ".nexus-old${GlassesFilesContract.PARTIAL_SUFFIX}").writeText("partial")

        val page = store.list("", 0) as GlassesFolderListing.Page

        assertEquals(listOf("alpha", "Zeta", "A.pdf", "b.pdf"), page.entries.map { it.name })
        assertEquals(listOf(true, true, false, false), page.entries.map { it.directory })
        assertEquals(2L, page.entries[2].sizeBytes)
        assertEquals(4, page.total)
    }

    @Test
    fun `listing removes orphaned staging files but keeps live ones`() {
        val (store, root) = store()
        val orphan = File(root, ".nexus-dead${GlassesFilesContract.PARTIAL_SUFFIX}").apply { writeText("x") }
        val live = File(root, ".nexus-live${GlassesFilesContract.PARTIAL_SUFFIX}").apply { writeText("y") }
        orphan.setLastModified(1_000_000L)
        live.setLastModified(1_000_000L + GlassesFileStore.STALE_PARTIAL_MS)

        store.list("", 0, nowMillis = 1_000_000L + GlassesFileStore.STALE_PARTIAL_MS + 1)

        assertFalse(orphan.exists())
        assertTrue(live.exists())
    }

    @Test
    fun `same name and size counts as already present`() {
        val (store, root) = store()
        File(root, "book.pdf").writeText("abc")
        assertTrue(store.alreadyPresent("", "book.pdf", 3))
        assertFalse(store.alreadyPresent("", "book.pdf", 4))
        assertFalse(store.alreadyPresent("", "other.pdf", 3))
    }

    @Test
    fun `listing pages through large folders`() {
        val (store, root) = store()
        repeat(5) { File(root, "f$it").writeText("x") }

        val page = store.list("", 3, pageSize = 2) as GlassesFolderListing.Page

        assertEquals(listOf("f3", "f4"), page.entries.map { it.name })
        assertEquals(3, page.offset)
        assertEquals(5, page.total)
    }

    @Test
    fun `listing reports missing, file and protected targets`() {
        val (store, root) = store()
        File(root, "a.pdf").writeText("a")
        File(root, "Android/data").mkdirs()

        assertEquals(GlassesFolderListing.Failed(GlassesFilesError.NOT_FOUND), store.list("missing", 0))
        assertEquals(GlassesFolderListing.Failed(GlassesFilesError.NOT_A_FOLDER), store.list("a.pdf", 0))
        assertEquals(GlassesFolderListing.Failed(GlassesFilesError.PROTECTED), store.list("Android/data", 0))
        assertEquals(GlassesFolderListing.Failed(GlassesFilesError.INVALID_REQUEST), store.list("../x", 0))
    }

    @Test
    fun `resolution never leaves the root`() {
        val (store, root) = store()
        assertEquals(root.canonicalFile, store.resolve(""))
        assertNull(store.resolve(".."))
        assertNull(store.resolve("a/../.."))
        assertNull(store.resolve("/etc"))
    }

    @Test
    fun `make folder creates once and refuses duplicates and protected parents`() {
        val (store, root) = store()

        assertNull(store.makeFolder("", "Books"))
        assertTrue(File(root, "Books").isDirectory)
        assertEquals(GlassesFilesError.EXISTS, store.makeFolder("", "Books"))
        assertEquals(GlassesFilesError.NOT_FOUND, store.makeFolder("nope", "x"))
        assertEquals(GlassesFilesError.PROTECTED, store.makeFolder("Android", "data"))
        assertEquals(GlassesFilesError.PROTECTED, store.makeFolder("Android/obb", "x"))
    }

    @Test
    fun `delete removes files and empty folders only`() {
        val (store, root) = store()
        File(root, "a.pdf").writeText("a")
        File(root, "full/inner.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        File(root, "empty").mkdir()

        assertNull(store.delete("a.pdf"))
        assertFalse(File(root, "a.pdf").exists())
        assertEquals(GlassesFilesError.NOT_EMPTY, store.delete("full"))
        assertTrue(File(root, "full/inner.txt").exists())
        assertNull(store.delete("empty"))
        assertEquals(GlassesFilesError.NOT_FOUND, store.delete("empty"))
        assertEquals(GlassesFilesError.INVALID_REQUEST, store.delete(""))
    }

    @Test
    fun `an upload is staged, verified and renamed into place`() {
        val (store, root) = store()
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val upload = store.beginUpload("", "book.pdf", bytes.size.toLong(), transferId).getOrThrow()

        assertEquals("book.pdf", upload.name)
        assertNull(upload.write(0, bytes.copyOfRange(0, 32_768)))
        assertNull(upload.write(32_768, bytes.copyOfRange(32_768, bytes.size)))
        assertFalse(File(root, "book.pdf").exists())

        assertNull(upload.finish(sha256(bytes)))

        assertArrayEquals(bytes, File(root, "book.pdf").readBytes())
        assertEquals(listOf("book.pdf"), root.list()!!.toList())
    }

    @Test
    fun `an upload never overwrites an existing file`() {
        val (store, root) = store()
        File(root, "book.pdf").writeText("original")
        val upload = store.beginUpload("", "book.pdf", 3, transferId).getOrThrow()
        assertEquals("book (1).pdf", upload.name)

        upload.write(0, "new".toByteArray())
        // A file appears under the chosen name while the bytes are in flight.
        File(root, "book (1).pdf").writeText("racer")
        assertNull(upload.finish(sha256("new".toByteArray())))

        assertEquals("book (2).pdf", upload.name)
        assertEquals("original", File(root, "book.pdf").readText())
        assertEquals("racer", File(root, "book (1).pdf").readText())
        assertEquals("new", File(root, "book (2).pdf").readText())
    }

    @Test
    fun `out of place chunks, short files and bad digests leave nothing behind`() {
        val (store, root) = store()
        val gap = store.beginUpload("", "gap.pdf", 10, transferId).getOrThrow()
        assertEquals(GlassesFilesError.INVALID_REQUEST, gap.write(5, ByteArray(5)))
        assertEquals(GlassesFilesError.INVALID_REQUEST, gap.write(0, ByteArray(11)))
        assertNull(gap.write(0, ByteArray(4)))
        assertEquals(GlassesFilesError.INTEGRITY, gap.finish("0".repeat(64)))

        val corrupt = store.beginUpload("", "bad.pdf", 3, "transfer_abcdefghij").getOrThrow()
        corrupt.write(0, "abc".toByteArray())
        assertEquals(GlassesFilesError.INTEGRITY, corrupt.finish(sha256("xyz".toByteArray())))

        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test
    fun `aborting an upload removes its staging file`() {
        val (store, root) = store()
        val upload = store.beginUpload("", "a.pdf", 3, transferId).getOrThrow()
        upload.write(0, "ab".toByteArray())
        upload.abort()
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test
    fun `empty files upload too`() {
        val (store, root) = store()
        val upload = store.beginUpload("", "empty.txt", 0, transferId).getOrThrow()
        assertNull(upload.finish(sha256(ByteArray(0))))
        assertEquals(0L, File(root, "empty.txt").length())
    }

    @Test
    fun `uploads into missing or protected folders are refused`() {
        val (store, _) = store()
        assertEquals(GlassesFilesError.NOT_FOUND, uploadError(store, "missing"))
        assertEquals(GlassesFilesError.PROTECTED, uploadError(store, "Android/data"))
        val partialName = store.beginUpload("", ".nexus-x${GlassesFilesContract.PARTIAL_SUFFIX}", 1, transferId)
        assertEquals(GlassesFilesError.INVALID_REQUEST, (partialName.exceptionOrNull() as GlassesFilesException).error)
    }

    @Test
    fun `downloads open readable regular files only`() {
        val (store, root) = store()
        File(root, "a.pdf").writeText("a")
        File(root, "dir").mkdir()
        assertEquals(File(root, "a.pdf").canonicalFile, store.openForDownload("a.pdf").first)
        assertEquals(GlassesFilesError.INVALID_REQUEST, store.openForDownload("dir").second)
        assertEquals(GlassesFilesError.NOT_FOUND, store.openForDownload("b.pdf").second)
    }

    private fun uploadError(store: GlassesFileStore, folder: String): GlassesFilesError =
        (store.beginUpload(folder, "a.pdf", 1, transferId).exceptionOrNull() as GlassesFilesException).error

    private fun sha256(bytes: ByteArray): String =
        GlassesFilesContract.newDigest().apply { update(bytes) }.let(GlassesFilesContract::hex)
}
