package com.anezium.rokidbus.plugin.foodlog

import android.graphics.BitmapFactory
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes food barcodes from a JPEG snapshot.
 *
 * The supplied [callbackExecutor] owns decoding and result delivery. ML Kit completion
 * always releases its scanner and image, even after that executor has stopped.
 */
internal class FoodBarcodeScanner(
    private val callbackExecutor: Executor,
) : Closeable {

    sealed interface Result {
        data class Found(val code: String) : Result
        data object NotFound : Result
        data class Ambiguous(val codes: List<String>) : Result
        data class Failure(val cause: Throwable) : Result
    }

    @Volatile
    private var closed = false

    /**
     * Starts an asynchronous scan. [callback] is always invoked on [callbackExecutor].
     */
    fun scan(jpeg: ByteArray, callback: (Result) -> Unit) {
        callbackExecutor.execute {
            if (closed) {
                callback(Result.Failure(IllegalStateException("Food barcode scanner is closed")))
                return@execute
            }
            if (jpeg.isEmpty()) {
                callback(Result.NotFound)
                return@execute
            }

            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            }.getOrElse {
                callback(Result.Failure(it))
                return@execute
            } ?: run {
                callback(Result.NotFound)
                return@execute
            }

            if (closed) {
                bitmap.recycle()
                return@execute
            }
            val scanner = runCatching { BarcodeScanning.getClient(options) }.getOrElse {
                bitmap.recycle()
                callback(Result.Failure(it))
                return@execute
            }
            val released = AtomicBoolean(false)
            val release = {
                if (released.compareAndSet(false, true)) {
                    try { scanner.close() } finally { if (!bitmap.isRecycled) bitmap.recycle() }
                }
                Unit
            }
            try {
                scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnCompleteListener(Executor { it.run() }) { task ->
                        finishFoodScan(callbackExecutor, { closed }, release, {
                            if (task.isSuccessful) resultFor(task.result)
                            else Result.Failure(task.exception ?: IllegalStateException("Barcode scan cancelled"))
                        }, callback)
                    }
            } catch (error: Exception) {
                finishFoodScan(callbackExecutor, { closed }, release, { Result.Failure(error) }, callback)
            }
        }
    }

    override fun close() {
        closed = true
    }

    private fun resultFor(barcodes: List<Barcode>): Result {
        val codes = barcodes.asSequence()
            .mapNotNull { normalizeBarcode(it.rawValue.orEmpty()) }
            .distinct()
            .toList()
        return when (codes.size) {
            0 -> Result.NotFound
            1 -> Result.Found(codes.single())
            else -> Result.Ambiguous(codes)
        }
    }

    private companion object {
        val options: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_ITF,
            )
            .build()
    }
}

/** Cleanup must not be queued on an executor whose owner may already have closed. */
internal fun finishFoodScan(
    executor: Executor,
    isClosed: () -> Boolean,
    release: () -> Unit,
    result: () -> FoodBarcodeScanner.Result,
    callback: (FoodBarcodeScanner.Result) -> Unit,
) {
    try {
        if (!isClosed()) {
            val outcome = runCatching(result).getOrElse { FoodBarcodeScanner.Result.Failure(it) }
            try { executor.execute { if (!isClosed()) callback(outcome) } }
            catch (_: RejectedExecutionException) { /* The service has finished; only cleanup remains. */ }
        }
    } finally { release() }
}
