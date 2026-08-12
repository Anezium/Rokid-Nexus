package com.anezium.rokidbus.plugin.foodlog

import android.graphics.BitmapFactory
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Decodes food barcodes from a JPEG snapshot.
 *
 * The supplied [callbackExecutor] owns all decoding work and callback delivery. Callers should
 * keep it alive until callbacks have completed, then shut it down if they own its lifecycle.
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

            val scanner = BarcodeScanning.getClient(options)
            try {
                scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(callbackExecutor) { barcodes ->
                        finish(scanner, bitmap) {
                            callback(resultFor(barcodes))
                        }
                    }
                    .addOnFailureListener(callbackExecutor) { error ->
                        finish(scanner, bitmap) {
                            callback(Result.Failure(error))
                        }
                    }
            } catch (error: Throwable) {
                finish(scanner, bitmap) {
                    callback(Result.Failure(error))
                }
            }
        }
    }

    override fun close() {
        closed = true
    }

    private fun finish(
        scanner: BarcodeScanner,
        bitmap: android.graphics.Bitmap,
        deliver: () -> Unit,
    ) {
        try {
            deliver()
        } finally {
            scanner.close()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
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
