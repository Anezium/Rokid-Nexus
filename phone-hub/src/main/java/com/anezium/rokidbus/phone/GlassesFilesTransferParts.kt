package com.anezium.rokidbus.phone

import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesResync
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Who still needs each persistable read grant the explorer took for a pick.
 *
 * Only grants this app acquired for the pick are ever released ([adopt]); a URI it already held
 * a grant for belongs to someone else. A grant is released once no queued, running or stopped item
 * uses it any more; finished rows linger on screen but no longer hold anything.
 */
internal class GlassesGrantLedger(private val release: (String) -> Unit) {
    private val owned = HashSet<String>()
    private val holders = HashMap<String, Int>()

    fun adopt(newlyTaken: Collection<String>) {
        owned += newlyTaken
    }

    fun hold(source: String) {
        holders[source] = (holders[source] ?: 0) + 1
    }

    fun drop(source: String) {
        val remaining = (holders[source] ?: 0) - 1
        if (remaining > 0) {
            holders[source] = remaining
        } else {
            holders.remove(source)
            releaseIfFree(source)
        }
    }

    fun dropAll() {
        holders.clear()
        owned.toList().forEach(release)
        owned.clear()
    }

    private fun releaseIfFree(source: String) {
        if (holders[source] == null && owned.remove(source)) release(source)
    }
}

/**
 * Reads picker metadata off the main thread, each query bounded: a provider that does not answer
 * within [timeoutMillis] has its query cancelled and its file skipped, so one stalled document
 * cannot hold up the rest of the pick.
 */
internal object GlassesPickIntake {
    /** [batch], once cancelled, stops the intake and cancels the query in flight. */
    fun <T> describeAll(
        sources: List<String>,
        io: ExecutorService,
        timeoutMillis: Long,
        batch: CancelHooks = CancelHooks(),
        describe: (source: String, registerCancel: (() -> Unit) -> Unit) -> T?,
    ): List<Pair<String, T>> = sources.mapNotNull { source ->
        if (batch.isCancelled) return@mapNotNull null
        val hooks = CancelHooks()
        batch.register { hooks.cancel() }
        val task = runCatching { io.submit<T?> { describe(source, hooks::register) } }.getOrNull()
            ?: return@mapNotNull null
        val value = try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            hooks.cancel()
            task.cancel(true)
            null
        }
        value?.takeUnless { batch.isCancelled }?.let { source to it }
    }
}

/** Cancel actions that run once: at [cancel], or on the spot if registered after it. */
internal class CancelHooks {
    private val lock = Any()
    private val hooks = ArrayList<() -> Unit>()
    private var cancelled = false

    val isCancelled: Boolean
        get() = synchronized(lock) { cancelled }

    fun register(hook: () -> Unit) {
        val runNow = synchronized(lock) { if (cancelled) true else false.also { hooks += hook } }
        if (runNow) runCatching { hook() }
    }

    /** Returns false when it had already run. */
    fun cancel(): Boolean {
        val pending = synchronized(lock) {
            if (cancelled) return false
            cancelled = true
            hooks.toList().also { hooks.clear() }
        }
        pending.forEach { runCatching { it() } }
        return true
    }
}

/** What a copy does when the glasses announce the file's actual size. */
internal enum class GlassesReadyAction { NONE, ACK_STAGED, CHANGED }

internal object GlassesDownloadAckPolicy {
    /**
     * `download_ready` rides control JSON while chunks ride the data plane, so it can arrive after
     * the last chunk. The chunk path decided its ack against the listing's size; if the real file
     * is smaller and already fully staged, that final ack would never be sent and both hubs would
     * wait on each other until the idle timeout. Reconciling here closes that gap.
     */
    fun onReady(stagedBytes: Long, announcedBytes: Long): GlassesReadyAction = when {
        stagedBytes > announcedBytes -> GlassesReadyAction.CHANGED
        stagedBytes == announcedBytes && stagedBytes > 0 -> GlassesReadyAction.ACK_STAGED
        else -> GlassesReadyAction.NONE
    }
}

/** The staged offset advances only after a completed MediaStore write, on the serial inbox. */
internal class GlassesFilesDownloadReceiver(
    private val resync: GlassesFilesResync,
    private val output: () -> OutputStream?,
) {
    enum class Write { IGNORED, WRITTEN, OUT_OF_ORDER, NO_SPACE }
    var staged = 0L
        private set
    private val digest = GlassesFilesContract.newDigest()

    fun write(offset: Long, bytes: ByteArray, viaLane: Boolean, busEpoch: Int = 0): Write {
        if (!resync.acceptsChunk(viaLane, busEpoch)) return Write.IGNORED
        val target = output() ?: return Write.IGNORED
        if (offset != staged) return Write.OUT_OF_ORDER
        if (runCatching { target.write(bytes) }.isFailure) return Write.NO_SPACE
        digest.update(bytes)
        staged += bytes.size
        return Write.WRITTEN
    }

    fun verify(expectedSize: Long?, sha256: String): Boolean =
        staged == expectedSize && GlassesFilesContract.hex(digest) == sha256
}

/**
 * Reads a document provider stream on a separate I/O thread so the transfer worker can walk away.
 *
 * A cloud provider can block in open or in any read for as long as its network stalls, and
 * neither responds reliably to interruption. Each read is handed to [io] and awaited in short
 * polls; a cancel, a dropped link or [stallMillis] without progress abandons it, closes what can
 * be closed, and returns control to the worker. An abandoned I/O thread finishes on its own time.
 */
internal class GlassesProviderReader(
    private val io: ExecutorService,
    private val stallMillis: Long,
    private val open: (registerCancel: (() -> Unit) -> Unit) -> InputStream,
    private val pollMillis: Long = 250L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    sealed interface Read {
        data class Bytes(val count: Int) : Read
        data object End : Read
        data object Aborted : Read
        data object Stalled : Read
        data object Failed : Read
    }

    @Volatile private var stream: InputStream? = null
    @Volatile private var pending: Future<Int>? = null
    @Volatile private var closed = false
    // An open that resumes after the worker walked away must still see its signal cancelled and
    // its descriptor closed, so late registrations run on the spot.
    private val hooks = CancelHooks()

    fun read(buffer: ByteArray, shouldAbort: () -> Boolean): Read {
        if (closed) return Read.Aborted
        val task = runCatching {
            io.submit<Int> {
                if (closed) throw IOException("closed")
                val source = stream ?: open(hooks::register).also { opened ->
                    stream = opened
                    if (closed) runCatching { opened.close() }
                }
                source.read(buffer)
            }
        }.getOrElse { return Read.Failed }
        pending = task
        val deadline = nowMillis() + stallMillis
        while (true) {
            if (shouldAbort()) {
                close()
                return Read.Aborted
            }
            val result = try {
                task.get(pollMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (nowMillis() >= deadline) {
                    close()
                    return Read.Stalled
                }
                continue
            } catch (_: InterruptedException) {
                close()
                return Read.Aborted
            } catch (_: Exception) {
                return if (closed) Read.Aborted else Read.Failed
            }
            pending = null
            return if (result < 0) Read.End else Read.Bytes(result)
        }
    }

    override fun close() {
        closed = true
        if (!hooks.cancel()) return
        runCatching { stream?.close() }
        pending?.cancel(true)
    }
}
