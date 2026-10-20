package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class GlassesTransferDirection { SEND, COPY }

enum class GlassesTransferState { QUEUED, RUNNING, DONE, STOPPED }

/** Why a transfer stopped, in the words the transfers card uses. */
enum class GlassesTransferStop(val label: String) {
    LINK_LOST("THE GLASSES LINK DROPPED"),
    FULL("THE GLASSES ARE FULL"),
    REFUSED("THE GLASSES REFUSED IT"),
    CHANGED("THE FILE CHANGED ON THE PHONE"),
    UNREADABLE("THE PHONE COULDN’T READ IT"),
    PHONE_FULL("THE PHONE COULDN’T SAVE IT"),
    ;

    companion object {
        fun from(error: GlassesFilesError): GlassesTransferStop = when (error) {
            GlassesFilesError.NO_SPACE -> FULL
            GlassesFilesError.LINK_LOST,
            GlassesFilesError.TIMEOUT,
            -> LINK_LOST
            GlassesFilesError.CHANGED -> CHANGED
            else -> REFUSED
        }
    }
}

data class GlassesTransferItem(
    val id: String,
    val direction: GlassesTransferDirection,
    val name: String,
    /** Glasses folder a send goes into, or the folder a copied file came from. */
    val folderPath: String,
    /** Content URI for a send; glasses path for a copy. */
    val source: String,
    val totalBytes: Long?,
    val doneBytes: Long = 0L,
    val state: GlassesTransferState = GlassesTransferState.QUEUED,
    val stop: GlassesTransferStop? = null,
    val stoppedAtPercent: Int? = null,
    val savedAs: String? = null,
    val alreadyPresent: Boolean = false,
    val remainingSeconds: Long? = null,
    val finishedAtMillis: Long = 0L,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { ((doneBytes * 100) / it).toInt().coerceIn(0, 100) }
}

/** Rolling transfer rate over the last few seconds; quiet until it has enough to say. */
class GlassesTransferRate(private val windowMillis: Long = 5_000L, private val warmupMillis: Long = 3_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var startedAt = -1L

    fun reset() {
        samples.clear()
        startedAt = -1L
    }

    fun record(nowMillis: Long, doneBytes: Long) {
        if (startedAt < 0) startedAt = nowMillis
        samples.addLast(nowMillis to doneBytes)
        while (samples.size > 2 && nowMillis - samples.first().first > windowMillis) samples.removeFirst()
    }

    /** Seconds left, or null while estimating or when the total is unknown. */
    fun remainingSeconds(nowMillis: Long, doneBytes: Long, totalBytes: Long?): Long? {
        if (totalBytes == null || startedAt < 0 || nowMillis - startedAt < warmupMillis) return null
        val first = samples.firstOrNull() ?: return null
        val elapsed = nowMillis - first.first
        val moved = doneBytes - first.second
        if (elapsed <= 0 || moved <= 0) return null
        val left = (totalBytes - doneBytes).coerceAtLeast(0L)
        return left * elapsed / moved / 1000L
    }
}

/** Copy and formatting rules from the explorer spec, kept out of the activity so they are testable. */
object GlassesFilesFormat {
    /**
     * Phone-side estimate before a copy starts. Measured on an S23 with Rokid glasses: 5 MiB in
     * about 28 s in either direction, with Wi-Fi on or off and any ack window from 128 KiB up,
     * so this is the Bluetooth link's own ceiling rather than a protocol setting.
     */
    const val ESTIMATED_BYTES_PER_SECOND = 180_000L
    const val MAX_FOLDER_NAME_CHARS = 80
    private const val FORBIDDEN = "/\\:*?\"<>|"

    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < mb -> "${(bytes / kb).roundToInt()} KB"
            bytes < 100 * mb -> String.format(locale, "%.1f MB", bytes / mb)
            bytes < gb -> "${(bytes / mb).roundToInt()} MB"
            else -> String.format(locale, "%.1f GB", bytes / gb)
        }
    }

    fun modified(
        millis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        is24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (nowMillis - millis in 0 until 60_000) return "Just now"
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val clock = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
        val date: LocalDate = time.toLocalDate()
        return when {
            date == today -> "Today ${time.format(clock)}"
            date == today.minusDays(1) -> "Yesterday ${time.format(clock)}"
            date.year == today.year -> time.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> time.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
    }

    /** Extension tag shown in a file's tile: `PDF`, `EPUB`; a dash when there is none or it is long. */
    fun extensionTag(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return "—"
        val extension = name.substring(dot + 1)
        return if (extension.length <= 4) extension.uppercase(Locale.ROOT) else "—"
    }

    fun remaining(seconds: Long?): String = when {
        seconds == null -> "ESTIMATING…"
        seconds < 10 -> "A FEW SECONDS LEFT"
        seconds < 60 -> "ABOUT ${maxOf(10, ((seconds + 4) / 5) * 5)} S LEFT"
        else -> "ABOUT ${(seconds + 30) / 60} MIN LEFT"
    }

    fun estimate(bytes: Long): String {
        val seconds = bytes / ESTIMATED_BYTES_PER_SECOND
        return when {
            bytes < 2L * 1024 * 1024 -> "a few seconds"
            seconds < 60 -> "about ${maxOf(10, ((seconds + 5) / 10) * 10)} seconds"
            seconds < 90 -> "about a minute"
            else -> "about ${(seconds + 30) / 60} minutes"
        }
    }

    /** Null when [raw] is a usable new folder name here; otherwise the line to show under the field. */
    fun folderNameError(raw: String, existing: Map<String, Boolean>): String? {
        val name = raw.trim()
        if (name.isEmpty()) return ""
        if (name.length > MAX_FOLDER_NAME_CHARS) return "KEEP IT UNDER $MAX_FOLDER_NAME_CHARS CHARACTERS"
        if (name.any { it in FORBIDDEN || it.isISOControl() }) {
            return "THESE CHARACTERS CAN’T BE USED: / \\ : * ? \" < > |"
        }
        if (name.startsWith(".")) return "A NAME CAN’T START WITH A DOT"
        if (!GlassesFilesContract.isValidName(name)) return "THIS NAME CAN’T BE USED"
        existing.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let { (match, directory) ->
            val kind = if (directory) "FOLDER" else "FILE"
            return "A $kind CALLED “${match.uppercase()}” IS ALREADY HERE"
        }
        return null
    }

    /** Folders at the top of shared storage that Android and apps expect to exist. */
    private val SYSTEM_FOLDERS = setOf(
        "Android", "Download", "Documents", "DCIM", "Pictures", "Movies", "Music",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings",
    )

    fun canDeleteFolder(path: String): Boolean =
        path.isNotEmpty() && !(GlassesFilesContract.parent(path).isEmpty() && path in SYSTEM_FOLDERS)

    /** Without full access, scoped storage only lets the hub write in these two trees. */
    fun isWritableWithLimitedAccess(path: String): Boolean {
        val top = path.substringBefore('/')
        return top == "Download" || top == "Documents"
    }
}
