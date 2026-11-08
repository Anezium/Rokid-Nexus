package com.anezium.rokidbus.phone

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpsArtifactDownloaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Response(
        val requestUrl: String,
        private val status: Int = 200,
        body: String = "apk",
        private val stream: InputStream = ByteArrayInputStream(body.toByteArray()),
        private val finalUrl: String = requestUrl,
        private val onResponse: () -> Unit = {},
    ) : HttpURLConnection(URL(requestUrl)) {
        var disconnected = false
        override fun getResponseCode(): Int { onResponse(); return status }
        override fun getInputStream(): InputStream = stream
        override fun getContentLengthLong(): Long = -1L
        override fun getURL(): URL = URL(finalUrl)
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private fun downloader(vararg responses: Response): HttpsArtifactDownloader {
        var next = 0
        return HttpsArtifactDownloader(
            openConnection = { url ->
                assertTrue("Unexpected request: $url", next < responses.size)
                responses[next++].also { assertEquals(it.requestUrl, url.toString()) }
            },
            clock = { 0L },
        )
    }

    @Test fun `successful download does not consult the API`() {
        val response = Response(BROWSER)
        val file = temporaryFolder.newFile()
        downloader(response).download(BROWSER, file, { false }) { _, _ -> }
        assertEquals("apk", file.readText())
        assertTrue(response.disconnected)
    }

    @Test fun `gateway failure resolves the exact release asset through the official API`() {
        val browser = Response(BROWSER, status = 504)
        val metadata = Response(RELEASE_API, body = metadata())
        val asset = Response(ASSET_API, finalUrl = "https://release-assets.githubusercontent.com/apk")
        val file = temporaryFolder.newFile()
        val progress = mutableListOf<Long>()

        downloader(browser, metadata, asset).download(BROWSER, file, { false }) { bytes, _ -> progress += bytes }

        assertEquals("apk", file.readText())
        assertEquals(listOf(0L, 3L), progress)
        assertEquals("application/vnd.github+json", metadata.getRequestProperty("Accept"))
        assertEquals("application/octet-stream", asset.getRequestProperty("Accept"))
        assertEquals("2022-11-28", asset.getRequestProperty("X-GitHub-Api-Version"))
        assertFalse(asset.useCaches)
        assertTrue(listOf(browser, metadata, asset).all { it.disconnected })
    }

    @Test fun `502 and 503 also use the fallback`() {
        for (status in listOf(502, 503)) {
            val file = temporaryFolder.newFile()
            downloader(Response(BROWSER, status), Response(RELEASE_API, body = metadata()), Response(ASSET_API))
                .download(BROWSER, file, { false }) { _, _ -> }
            assertEquals("apk", file.readText())
        }
    }

    @Test fun `timeout after partial content restarts the file instead of appending`() {
        val partial = object : InputStream() {
            var first = true
            override fun read(): Int = throw SocketTimeoutException()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw SocketTimeoutException()
                first = false
                "incomplete-original".toByteArray().copyInto(bytes, offset)
                return "incomplete-original".length
            }
        }
        val file = temporaryFolder.newFile()
        downloader(Response(BROWSER, stream = partial), Response(RELEASE_API, body = metadata()), Response(ASSET_API))
            .download(BROWSER, file, { false }) { _, _ -> }
        assertEquals("apk", file.readText())
    }

    @Test fun `permanent HTTP errors never start a fallback`() {
        for (status in listOf(401, 403, 404)) {
            val failure = assertThrows(IOException::class.java) {
                downloader(Response(BROWSER, status)).download(BROWSER, temporaryFolder.newFile(), { false }) { _, _ -> }
            }
            assertTrue(failure.message.orEmpty().contains("HTTP $status"))
        }
    }

    @Test fun `only canonical GitHub release URLs may use the fallback`() {
        val invalid = listOf(
            BROWSER.replace("github.com", "github.com.example.org"),
            BROWSER.replace("github.com", "example.org"),
            BROWSER.replace("github.com", "user@github.com"),
            BROWSER.replace("github.com", "github.com:8443"),
            "https://github.com/owner/repo/archive/main.zip",
        )
        for (url in invalid) {
            assertThrows(IOException::class.java) {
                downloader(Response(url, 504)).download(url, temporaryFolder.newFile(), { false }) { _, _ -> }
            }
        }
    }

    @Test fun `missing duplicate incomplete or differently tagged assets fail closed`() {
        val matching = """{"id":42,"name":"nexus-phone-2.2.0.apk","state":"uploaded"}"""
        val invalid = listOf(
            """{"tag_name":"v2.2.0","assets":[]}""",
            """{"tag_name":"v2.2.0","assets":[$matching,$matching]}""",
            metadata().replace("uploaded", "new"),
            metadata().replace("v2.2.0", "v2.0.4"),
            metadata().replace("\"id\":42", "\"id\":0"),
            """{"tag_name":"v2.2.0","draft":true,"assets":[$matching]}""",
            "not JSON",
        )
        for (body in invalid) {
            assertThrows(IOException::class.java) {
                downloader(Response(BROWSER, 504), Response(RELEASE_API, body = body))
                    .download(BROWSER, temporaryFolder.newFile(), { false }) { _, _ -> }
            }
        }
    }

    @Test fun `fallback failures terminate without retrying or changing releases`() {
        val failure = assertThrows(IOException::class.java) {
            downloader(Response(BROWSER, 504), Response(RELEASE_API, body = metadata()), Response(ASSET_API, 503))
                .download(BROWSER, temporaryFolder.newFile(), { false }) { _, _ -> }
        }
        assertTrue(failure.message.orEmpty().contains("HTTP 503"))
        assertTrue(failure.suppressed.single().message.orEmpty().contains("HTTP 504"))
    }

    @Test fun `API rate limits stop the fallback`() {
        assertThrows(IOException::class.java) {
            downloader(Response(BROWSER, 504), Response(RELEASE_API, 403))
                .download(BROWSER, temporaryFolder.newFile(), { false }) { _, _ -> }
        }
    }

    @Test fun `cancellation before download performs no request`() {
        assertThrows(DownloadCancelledException::class.java) {
            downloader().download(BROWSER, temporaryFolder.newFile(), { true }) { _, _ -> }
        }
    }

    @Test fun `cancellation before resolving the asset prevents the API request`() {
        var cancelled = false
        val browser = Response(BROWSER, 504, onResponse = { cancelled = true })
        assertThrows(DownloadCancelledException::class.java) {
            downloader(browser).download(BROWSER, temporaryFolder.newFile(), { cancelled }) { _, _ -> }
        }
        assertTrue(browser.disconnected)
    }

    @Test fun `cancellation during metadata prevents downloading the APK`() {
        var cancelled = false
        val metadata = Response(RELEASE_API, body = metadata(), onResponse = { cancelled = true })
        assertThrows(DownloadCancelledException::class.java) {
            downloader(Response(BROWSER, 504), metadata)
                .download(BROWSER, temporaryFolder.newFile(), { cancelled }) { _, _ -> }
        }
        assertTrue(metadata.disconnected)
    }

    @Test fun `HTTP redirect is rejected before writing content`() {
        val response = Response(BROWSER, finalUrl = "http://example.org/apk")
        val file = temporaryFolder.newFile().apply { writeText("existing") }
        assertThrows(IOException::class.java) {
            // Use the original request URL in the factory; only the response was redirected.
            HttpsArtifactDownloader(openConnection = { response }, clock = { 0L })
                .download(BROWSER, file, { false }) { _, _ -> }
        }
        assertEquals("existing", file.readText())
        assertTrue(response.disconnected)
    }

    @Test fun `encoded tags and filenames are resolved without treating plus as a space`() {
        val url = "https://github.com/owner/repo/releases/download/release%2Fv1/app+phone%20release.apk"
        val metadata = """{"tag_name":"release/v1","assets":[{"id":42,"name":"app+phone release.apk","state":"uploaded"}]}"""
        downloader(Response(url, 504), Response("https://api.github.com/repos/owner/repo/releases/tags/release%2Fv1", body = metadata), Response(ASSET_API))
            .download(url, temporaryFolder.newFile(), { false }) { _, _ -> }
    }

    private fun metadata() = """{"tag_name":"v2.2.0","assets":[{"id":42,"name":"nexus-phone-2.2.0.apk","state":"uploaded","url":"https://untrusted.example/apk"}]}"""

    private companion object {
        const val BROWSER = "https://github.com/owner/repo/releases/download/v2.2.0/nexus-phone-2.2.0.apk"
        const val RELEASE_API = "https://api.github.com/repos/owner/repo/releases/tags/v2.2.0"
        const val ASSET_API = "https://api.github.com/repos/owner/repo/releases/assets/42"
    }
}
