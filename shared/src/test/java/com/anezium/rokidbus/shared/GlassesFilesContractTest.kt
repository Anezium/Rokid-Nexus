package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesFilesContractTest {
    private val id = "files_0123456789abcdef"
    private val digest = "a".repeat(64)

    @Test
    fun `file explorer paths are reserved core routes`() {
        listOf(
            GlassesFilesContract.REQUEST_PATH,
            GlassesFilesContract.RESULT_PATH,
            GlassesFilesContract.DATA_PATH,
        ).forEach { path ->
            assertTrue(path, path.startsWith("/core/files/"))
            assertTrue(path, PathRules.isReserved(path))
        }
    }

    @Test
    fun `the whole explorer subtree is recognised for local-send refusal`() {
        assertTrue(GlassesFilesContract.isFilesPath("/core/files"))
        assertTrue(GlassesFilesContract.isFilesPath("/core/files/request"))
        assertTrue(GlassesFilesContract.isFilesPath(" /core/files/data/x"))
        assertFalse(GlassesFilesContract.isFilesPath("/core/filesystem"))
        assertFalse(GlassesFilesContract.isFilesPath("/core/native-apps/request"))
    }

    @Test
    fun `every request round trips`() {
        val requests = listOf(
            GlassesFilesRequest.ListFolder(id, "", 0),
            GlassesFilesRequest.ListFolder(id, "Download/Books", 200),
            GlassesFilesRequest.MakeFolder(id, "Download", "Books"),
            GlassesFilesRequest.Delete(id, "Download/old.pdf", directory = false),
            GlassesFilesRequest.Delete(id, "Download/Empty", directory = true),
            GlassesFilesRequest.GrantAccess(id),
            GlassesFilesRequest.UploadBegin(id, "Download", "Léger 👓.pdf", 1_234_567L),
            GlassesFilesRequest.UploadEnd(id, digest),
            GlassesFilesRequest.DownloadBegin(id, "DCIM/Camera/img.jpg"),
            GlassesFilesRequest.DownloadProgress(id, 65_536L),
            GlassesFilesRequest.Cancel(id),
        )
        requests.forEach { request ->
            val encoded = GlassesFilesContract.encode(request).put("future", true)
            assertEquals(request, GlassesFilesContract.parseRequest(JSONObject(encoded.toString())))
        }
    }

    @Test
    fun `every result round trips`() {
        val results = listOf(
            GlassesFilesResult.Listing(
                requestId = id,
                path = "Download",
                access = GlassesFilesAccess.FULL,
                entries = listOf(
                    GlassesFileEntry("Books", directory = true, sizeBytes = 0, modifiedMillis = 1_700_000_000_000),
                    GlassesFileEntry("a.pdf", directory = false, sizeBytes = 42, modifiedMillis = 1),
                ),
                offset = 0,
                total = 2,
            ),
            GlassesFilesResult.Listing(id, "Android/data", GlassesFilesAccess.LIMITED, emptyList(), 0, 0, GlassesFilesError.PROTECTED),
            GlassesFilesResult.Operation(id, GlassesFilesOperation.MKDIR),
            GlassesFilesResult.Operation(id, GlassesFilesOperation.DELETE, GlassesFilesError.NOT_EMPTY),
            GlassesFilesResult.Operation(id, GlassesFilesOperation.DELETE, GlassesFilesError.INTERNAL),
            GlassesFilesResult.Operation(
                id,
                GlassesFilesOperation.GRANT_ACCESS,
                access = GlassesFilesAccess.LIMITED,
                needsWearer = true,
            ),
            GlassesFilesResult.UploadReady(id, "book (1).pdf"),
            GlassesFilesResult.UploadProgress(id, 131_072L),
            GlassesFilesResult.UploadDone(id, "book (1).pdf"),
            GlassesFilesResult.UploadDone(id, "book.pdf", alreadyPresent = true),
            GlassesFilesResult.DownloadReady(id, 99L, 1_700_000_000_000),
            GlassesFilesResult.DownloadEnd(id, digest),
            GlassesFilesResult.TransferFailed(id, GlassesFilesError.NO_SPACE),
        )
        results.forEach { result ->
            val encoded = GlassesFilesContract.encode(result)
            assertEquals(result, GlassesFilesContract.parseResult(JSONObject(encoded.toString())))
        }
    }

    @Test
    fun `an internal error is not mistaken for success`() {
        val encoded = GlassesFilesContract.encode(
            GlassesFilesResult.Operation(id, GlassesFilesOperation.MKDIR, GlassesFilesError.INTERNAL),
        )
        val parsed = GlassesFilesContract.parseResult(encoded) as GlassesFilesResult.Operation
        assertEquals(GlassesFilesError.INTERNAL, parsed.error)
    }

    @Test
    fun `unknown error codes and wrong versions invalidate the message`() {
        val operation = GlassesFilesContract.encode(GlassesFilesResult.Operation(id, GlassesFilesOperation.MKDIR))
        assertNull(GlassesFilesContract.parseResult(JSONObject(operation.toString()).put("errorCode", "nope")))
        assertNull(GlassesFilesContract.parseResult(JSONObject(operation.toString()).put("version", 2)))
        assertNull(GlassesFilesContract.parseResult(JSONObject(operation.toString()).put("version", "1")))
    }

    @Test
    fun `requests with unsafe paths are rejected`() {
        val list = GlassesFilesContract.encode(GlassesFilesRequest.ListFolder(id, "Download", 0))
        listOf("../etc", "Download/../..", "/Download", "Download/", "Down//load", "a\u0000b", "a\\b", "./x")
            .forEach { bad ->
                assertNull(bad, GlassesFilesContract.parseRequest(JSONObject(list.toString()).put("path", bad)))
            }
    }

    @Test
    fun `delete and download never target the root`() {
        val delete = GlassesFilesContract.encode(GlassesFilesRequest.Delete(id, "x", directory = false))
        assertNull(GlassesFilesContract.parseRequest(JSONObject(delete.toString()).put("path", "")))
        assertNull(GlassesFilesContract.parseRequest(JSONObject(delete.toString()).apply { remove("dir") }))
        assertThrows(IllegalArgumentException::class.java) {
            GlassesFilesContract.encode(GlassesFilesRequest.DownloadBegin(id, ""))
        }
    }

    @Test
    fun `failed listings must be empty`() {
        val encoded = GlassesFilesContract.encode(
            GlassesFilesResult.Listing(id, "", GlassesFilesAccess.FULL, emptyList(), 0, 0, GlassesFilesError.NOT_FOUND),
        )
        encoded.put("entries", JSONArray().put(JSONObject().put("name", "x").put("dir", false).put("size", 1).put("mtime", 1)))
        assertNull(GlassesFilesContract.parseResult(encoded))
    }

    @Test
    fun `chunks need a bounded binary body`() {
        val meta = GlassesFilesContract.chunkMeta(GlassesFilesChunk(id, 32_768L))
        assertEquals(GlassesFilesChunk(id, 32_768L), GlassesFilesContract.parseChunk(meta, ByteArray(10)))
        assertNull(GlassesFilesContract.parseChunk(meta, null))
        assertNull(GlassesFilesContract.parseChunk(meta, ByteArray(0)))
        assertNull(GlassesFilesContract.parseChunk(meta, ByteArray(GlassesFilesContract.MAX_CHUNK_BYTES + 1)))
        assertNull(
            GlassesFilesContract.parseChunk(
                GlassesFilesContract.encode(GlassesFilesRequest.Cancel(id)),
                ByteArray(10),
            ),
        )
    }

    @Test
    fun `path normalization keeps only safe relative segments`() {
        assertEquals("", GlassesFilesContract.normalizePath(""))
        assertEquals("", GlassesFilesContract.normalizePath("/"))
        assertEquals("Download/Books", GlassesFilesContract.normalizePath("/Download/Books/"))
        assertNull(GlassesFilesContract.normalizePath(".."))
        assertNull(GlassesFilesContract.normalizePath("a/./b"))
        assertNull(GlassesFilesContract.normalizePath("a//b"))
        assertNull(GlassesFilesContract.normalizePath((1..40).joinToString("/") { "d" }))
        assertNull(GlassesFilesContract.normalizePath("x".repeat(256)))
    }

    @Test
    fun `names reject separators controls and lone surrogates`() {
        assertTrue(GlassesFilesContract.isValidName("Le superpouvoir de l'IA.epub"))
        assertTrue(GlassesFilesContract.isValidName(".hidden"))
        assertFalse(GlassesFilesContract.isValidName(""))
        assertFalse(GlassesFilesContract.isValidName("."))
        assertFalse(GlassesFilesContract.isValidName(".."))
        assertFalse(GlassesFilesContract.isValidName("a/b"))
        assertFalse(GlassesFilesContract.isValidName("a\nb"))
        assertFalse(GlassesFilesContract.isValidName("a\uD800"))
        assertFalse(GlassesFilesContract.isValidName("é".repeat(128)))
    }

    @Test
    fun `android data and obb are protected`() {
        assertTrue(GlassesFilesContract.isProtected("Android/data"))
        assertTrue(GlassesFilesContract.isProtected("android/OBB/com.x"))
        assertFalse(GlassesFilesContract.isProtected("Android"))
        assertFalse(GlassesFilesContract.isProtected("Android/media"))
        assertFalse(GlassesFilesContract.isProtected("Download/data"))
    }

    @Test
    fun `colliding names get a numbered suffix before the extension`() {
        val taken = setOf("book.pdf", "book (1).pdf", "notes")
        assertEquals("book (2).pdf", GlassesFilesContract.uniqueName("book.pdf", taken::contains))
        assertEquals("notes (1)", GlassesFilesContract.uniqueName("notes", taken::contains))
        assertEquals("fresh.pdf", GlassesFilesContract.uniqueName("fresh.pdf", taken::contains))
        assertEquals(".bashrc (1)", GlassesFilesContract.uniqueName(".bashrc") { it == ".bashrc" })
        assertNull(GlassesFilesContract.uniqueName("x.pdf") { true })
    }

    @Test
    fun `path helpers split parents and names`() {
        assertEquals("Download", GlassesFilesContract.parent("Download/a.pdf"))
        assertEquals("", GlassesFilesContract.parent("Download"))
        assertEquals("a.pdf", GlassesFilesContract.name("Download/a.pdf"))
        assertEquals("Download/a.pdf", GlassesFilesContract.child("Download", "a.pdf"))
        assertEquals("a.pdf", GlassesFilesContract.child("", "a.pdf"))
    }

    @Test
    fun `a full page of maximal names still fits one message`() {
        val entries = (0 until GlassesFilesContract.MAX_PAGE_ENTRIES).map {
            GlassesFileEntry("x".repeat(252) + "%03d".format(it), false, GlassesFilesContract.MAX_SAFE_INTEGER, GlassesFilesContract.MAX_SAFE_INTEGER)
        }
        val encoded = GlassesFilesContract.encode(
            GlassesFilesResult.Listing(id, "", GlassesFilesAccess.FULL, entries, 0, entries.size),
        )
        assertTrue(encoded.toString().toByteArray(Charsets.UTF_8).size <= GlassesFilesContract.MAX_MESSAGE_BYTES)
    }
}
