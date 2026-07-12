package com.anezium.liveocr.phone

import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One-in-flight Latin OCR loop; completion immediately samples the newest decoded frame. */
internal class LiveOcrRunner(
    private val holder: LatestFrameHolder,
    private val onComplete: (
        frameId: Long,
        recvNanos: Long,
        decodeDoneNanos: Long,
        ocrDoneNanos: Long,
        blockCount: Int,
        charCount: Int,
        text: String,
    ) -> Unit,
    private val onCadence: (Double, Long) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "live-ocr-phone-mlkit-callback").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    private val currentFrame = AtomicReference<DecodedFrame?>(null)
    @Volatile private var closed = false
    private var completedCount = 0L
    private var cadenceCount = 0L
    private var cadenceStartedNanos = SystemClock.elapsedRealtimeNanos()

    fun kick() {
        if (closed || !inFlight.compareAndSet(false, true)) return
        val frame = holder.takeNewest()
        if (frame == null) {
            inFlight.set(false)
            return
        }
        currentFrame.set(frame)
        val task = runCatching {
            recognizer.process(
                InputImage.fromByteArray(
                    frame.nv21,
                    frame.width,
                    frame.height,
                    0,
                    InputImage.IMAGE_FORMAT_NV21,
                ),
            )
        }.getOrElse { failure ->
            callbackExecutor.execute { finish(frame, null, failure.message) }
            return
        }
        task.addOnCompleteListener(callbackExecutor) { completed ->
            finish(
                frame,
                if (completed.isSuccessful) completed.result else null,
                completed.exception?.message,
            )
        }
    }

    private fun finish(
        frame: DecodedFrame,
        result: com.google.mlkit.vision.text.Text?,
        error: String?,
    ) {
        val ocrDoneNanos = SystemClock.elapsedRealtimeNanos()
        val text = result?.text.orEmpty()
        if (!closed) {
            onComplete(
                frame.frameId,
                frame.recvNanos,
                frame.decodeDoneNanos,
                ocrDoneNanos,
                result?.textBlocks?.size ?: 0,
                text.length,
                text,
            )
            if (error != null) onError(error)
        }
        currentFrame.compareAndSet(frame, null)
        frame.close()
        completedCount++
        cadenceCount++
        val interval = ocrDoneNanos - cadenceStartedNanos
        if (!closed && interval >= 1_000_000_000L) {
            onCadence(cadenceCount * 1_000_000_000.0 / interval, completedCount)
            cadenceCount = 0
            cadenceStartedNanos = ocrDoneNanos
        }
        inFlight.set(false)
        if (!closed) kick() else callbackExecutor.shutdown()
    }

    override fun close() {
        closed = true
        holder.close()
        recognizer.close()
        if (currentFrame.get() == null) callbackExecutor.shutdown()
    }
}
