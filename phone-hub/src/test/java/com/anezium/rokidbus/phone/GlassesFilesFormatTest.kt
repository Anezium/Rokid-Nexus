package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class GlassesFilesFormatTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = LocalDateTime.of(2026, 10, 20, 18, 30).atZone(zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `sizes follow the spec table`() {
        assertEquals("512 B", GlassesFilesFormat.size(512, Locale.US))
        assertEquals("128 KB", GlassesFilesFormat.size(128 * 1024, Locale.US))
        assertEquals("2.4 MB", GlassesFilesFormat.size((2.4 * 1024 * 1024).toLong(), Locale.US))
        assertEquals("2,4 MB", GlassesFilesFormat.size((2.4 * 1024 * 1024).toLong(), Locale.FRANCE))
        assertEquals("340 MB", GlassesFilesFormat.size(340L * 1024 * 1024, Locale.US))
        assertEquals("1.2 GB", GlassesFilesFormat.size((1.2 * 1024 * 1024 * 1024).toLong(), Locale.US))
    }

    @Test
    fun `modified times read naturally`() {
        assertEquals("Just now", GlassesFilesFormat.modified(now - 30_000, now, zone, locale = Locale.UK))
        assertEquals("Today 09:38", GlassesFilesFormat.modified(at(2026, 10, 20, 9, 38), now, zone, locale = Locale.UK))
        assertEquals("Yesterday 21:40", GlassesFilesFormat.modified(at(2026, 10, 19, 21, 40), now, zone, locale = Locale.UK))
        assertEquals("12 Oct", GlassesFilesFormat.modified(at(2026, 10, 12, 8, 0), now, zone, locale = Locale.UK))
        assertEquals("12 Oct 2025", GlassesFilesFormat.modified(at(2025, 10, 12, 8, 0), now, zone, locale = Locale.UK))
        assertEquals(
            "Today 9:38 AM",
            GlassesFilesFormat.modified(at(2026, 10, 20, 9, 38), now, zone, is24Hour = false, locale = Locale.US),
        )
    }

    @Test
    fun `extension tags are short and upper case`() {
        assertEquals("PDF", GlassesFilesFormat.extensionTag("book.pdf"))
        assertEquals("EPUB", GlassesFilesFormat.extensionTag("Le superpouvoir.epub"))
        assertEquals("—", GlassesFilesFormat.extensionTag("README"))
        assertEquals("—", GlassesFilesFormat.extensionTag(".bashrc"))
        assertEquals("—", GlassesFilesFormat.extensionTag("archive.tar.gzipped"))
    }

    @Test
    fun `remaining time rounds as the spec says`() {
        assertEquals("ESTIMATING…", GlassesFilesFormat.remaining(null))
        assertEquals("A FEW SECONDS LEFT", GlassesFilesFormat.remaining(7))
        assertEquals("ABOUT 35 S LEFT", GlassesFilesFormat.remaining(33))
        assertEquals("ABOUT 2 MIN LEFT", GlassesFilesFormat.remaining(110))
    }

    @Test
    fun `copy estimates use the measured link speed`() {
        assertEquals("a few seconds", GlassesFilesFormat.estimate(1_000_000))
        assertEquals("about 40 seconds", GlassesFilesFormat.estimate(7_000_000))
        assertEquals("about a minute", GlassesFilesFormat.estimate(14_000_000))
        assertEquals("about 2 minutes", GlassesFilesFormat.estimate(21_000_000))
        assertEquals("about 6 minutes", GlassesFilesFormat.estimate(60_000_000))
        assertEquals("a few seconds", GlassesFilesFormat.estimate(21_000_000, overWifi = true))
        assertEquals("about 40 seconds", GlassesFilesFormat.estimate(300_000_000, overWifi = true))
    }

    @Test
    fun `folder names are checked before they reach the glasses`() {
        val existing = mapOf("Books" to true, "notes.txt" to false)
        assertEquals("", GlassesFilesFormat.folderNameError("  ", existing))
        assertNull(GlassesFilesFormat.folderNameError(" Manga ", existing))
        assertEquals("A FOLDER CALLED “BOOKS” IS ALREADY HERE", GlassesFilesFormat.folderNameError("books", existing))
        assertEquals("A FILE CALLED “NOTES.TXT” IS ALREADY HERE", GlassesFilesFormat.folderNameError("Notes.txt", existing))
        assertEquals("A NAME CAN’T START WITH A DOT", GlassesFilesFormat.folderNameError(".hidden", existing))
        assertTrue(GlassesFilesFormat.folderNameError("a/b", existing)!!.startsWith("THESE CHARACTERS"))
        assertTrue(GlassesFilesFormat.folderNameError("x".repeat(81), existing)!!.startsWith("KEEP IT UNDER"))
    }

    @Test
    fun `system folders at the root are never offered for deletion`() {
        assertFalse(GlassesFilesFormat.canDeleteFolder(""))
        assertFalse(GlassesFilesFormat.canDeleteFolder("Download"))
        assertTrue(GlassesFilesFormat.canDeleteFolder("Download/Books"))
        assertTrue(GlassesFilesFormat.canDeleteFolder("Books"))
    }

    @Test
    fun `limited access writes only under download and documents`() {
        assertTrue(GlassesFilesFormat.isWritableWithLimitedAccess("Download"))
        assertTrue(GlassesFilesFormat.isWritableWithLimitedAccess("Documents/Books"))
        assertFalse(GlassesFilesFormat.isWritableWithLimitedAccess(""))
        assertFalse(GlassesFilesFormat.isWritableWithLimitedAccess("Books"))
    }

    @Test
    fun `transfer rate stays quiet while warming up then estimates`() {
        val rate = GlassesTransferRate()
        rate.record(0, 0)
        assertNull(rate.remainingSeconds(1_000, 350_000, 3_500_000))
        rate.record(1_000, 350_000)
        rate.record(4_000, 1_400_000)
        assertEquals(6L, rate.remainingSeconds(4_000, 1_400_000, 3_500_000))
        assertNull(rate.remainingSeconds(4_000, 1_400_000, null))
    }

    @Test
    fun `glasses errors map to the card's stop reasons`() {
        assertEquals(GlassesTransferStop.FULL, GlassesTransferStop.from(GlassesFilesError.NO_SPACE))
        assertEquals(GlassesTransferStop.LINK_LOST, GlassesTransferStop.from(GlassesFilesError.TIMEOUT))
        assertEquals(GlassesTransferStop.REFUSED, GlassesTransferStop.from(GlassesFilesError.PROTECTED))
    }
}
