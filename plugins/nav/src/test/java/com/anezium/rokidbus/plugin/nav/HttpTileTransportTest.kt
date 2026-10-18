package com.anezium.rokidbus.plugin.nav

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** What a tile request sends and accepts, as the tile service's policy requires, against a local server. */
class HttpTileTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val seen = CopyOnWriteArrayList<Map<String, String>>()

    @After
    fun stop() = server.stop(0)

    @Test
    fun `a request identifies the app, sends no no-cache and keeps gzip as it came`() {
        val body = MvtFixtures.gzip(ByteArray(100) { it.toByte() })
        serve("/tile") { exchange ->
            exchange.responseHeaders.add("Cache-Control", "public,max-age=300")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = HttpTileTransport().call(url("/tile"), mapOf("If-Modified-Since" to "Sun, 18 Oct 2026 05:10:24 GMT"), 1024).execute()
        assertEquals(200, response.code)
        assertArrayEquals(body, response.body)
        assertEquals("public,max-age=300", response.headers["cache-control"])
        val request = seen.single()
        assertEquals(HttpTileTransport.USER_AGENT, request["user-agent"])
        assertEquals("gzip", request["accept-encoding"])
        assertEquals("Sun, 18 Oct 2026 05:10:24 GMT", request["if-modified-since"])
        assertNull(request["cache-control"])
        assertNull(request["pragma"])
    }

    @Test
    fun `a redirect is not followed and a 304 has no body`() {
        serve("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "http://example.invalid/elsewhere")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        serve("/same") { exchange ->
            exchange.sendResponseHeaders(304, -1)
            exchange.close()
        }
        val moved = HttpTileTransport().call(url("/moved"), emptyMap(), 1024).execute()
        assertEquals(302, moved.code)
        assertNull(moved.body)
        assertEquals(1, seen.size)
        assertNull(HttpTileTransport().call(url("/same"), emptyMap(), 1024).execute().body)
    }

    @Test
    fun `a body past the bound fails, declared or not`() {
        serve("/declared") { exchange ->
            exchange.sendResponseHeaders(200, 2048)
            exchange.responseBody.use { it.write(ByteArray(2048)) }
        }
        serve("/chunked") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out -> repeat(4) { out.write(ByteArray(512)) } }
        }
        assertThrows(IOException::class.java) { HttpTileTransport().call(url("/declared"), emptyMap(), 1024).execute() }
        assertThrows(IOException::class.java) { HttpTileTransport().call(url("/chunked"), emptyMap(), 1024).execute() }
    }

    @Test
    fun `a cancelled call never reports a response`() {
        serve("/tile") { exchange ->
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        val call = HttpTileTransport().call(url("/tile"), emptyMap(), 1024)
        call.cancel()
        assertThrows(IOException::class.java) { call.execute() }
        assertTrue(seen.isEmpty())
    }

    private fun serve(path: String, handler: (com.sun.net.httpserver.HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            seen += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            handler(exchange)
        }
        startOnce()
    }

    private var started = false

    private fun startOnce() {
        if (started) return
        started = true
        server.start()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
}
