package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesResync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class GlassesFilesFallbackTest {
    @Test
    fun `download resumes at completed MediaStore writes and ignores late lane bytes`() {
        val source = ByteArray(400_019) { (it * 13).toByte() }
        val output = ByteArrayOutputStream()
        val resync = GlassesFilesResync()
        val receiver = GlassesFilesDownloadReceiver(resync) { output }
        resync.opened()
        assertEquals(GlassesFilesDownloadReceiver.Write.WRITTEN, receiver.write(0, source.copyOfRange(0, 123_457), true))
        assertTrue(resync.begin(100))
        assertTrue(resync.offer(receiver.staged, source.size.toLong()))
        assertEquals(GlassesFilesDownloadReceiver.Write.IGNORED, receiver.write(123_457, byteArrayOf(1, 2, 3), true))
        assertEquals(123_457L, receiver.staged)
        // Binary bus data can overtake the control confirmation.
        assertEquals(GlassesFilesDownloadReceiver.Write.WRITTEN, receiver.write(123_457, source.copyOfRange(123_457, source.size), false))
        assertFalse(resync.confirm(123_458))
        assertTrue(resync.confirm(123_457))
        assertFalse(resync.begin(200))
        assertArrayEquals(source, output.toByteArray())
        assertTrue(receiver.verify(source.size.toLong(), sha(source)))
    }

    @Test
    fun `a failed MediaStore write never advances the advertised staged offset`() {
        val resync = GlassesFilesResync()
        val receiver = GlassesFilesDownloadReceiver(resync) {
            object : OutputStream() {
                override fun write(value: Int) = throw IOException("full")
            }
        }
        resync.opened()
        assertEquals(GlassesFilesDownloadReceiver.Write.NO_SPACE, receiver.write(0, byteArrayOf(1), true))
        assertTrue(resync.begin(0))
        assertTrue(resync.offer(receiver.staged, 1))
        assertEquals(0L, resync.offset)
    }

    @Test
    fun `resumed download still rejects corruption`() {
        val output = ByteArrayOutputStream()
        val resync = GlassesFilesResync()
        val receiver = GlassesFilesDownloadReceiver(resync) { output }
        resync.opened()
        receiver.write(0, byteArrayOf(1), true)
        resync.begin(0)
        resync.offer(receiver.staged, 2)
        resync.confirm(1)
        receiver.write(1, byteArrayOf(9), false)
        assertFalse(receiver.verify(2, sha(byteArrayOf(1, 2))))
    }

    private fun sha(bytes: ByteArray) = GlassesFilesContract.hex(GlassesFilesContract.newDigest().apply { update(bytes) })
}
