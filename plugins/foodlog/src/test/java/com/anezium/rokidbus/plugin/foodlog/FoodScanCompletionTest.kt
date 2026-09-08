package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class FoodScanCompletionTest {
    @Test fun serviceShutdownStillReleasesScanResources() {
        var released = false
        var delivered = false
        finishFoodScan(Executor { throw RejectedExecutionException("closed") }, { false },
            { released = true }, { FoodBarcodeScanner.Result.NotFound }, { delivered = true })
        assertTrue(released)
        assertFalse(delivered)
    }

    @Test fun closingBeforeQueuedDeliverySuppressesTheResult() {
        var queued: Runnable? = null
        var closed = false
        var released = false
        var delivered = false
        finishFoodScan(Executor { queued = it }, { closed }, { released = true },
            { FoodBarcodeScanner.Result.Found("12345678") }, { delivered = true })
        assertTrue(released)
        closed = true
        requireNotNull(queued).run()
        assertFalse(delivered)
    }

    @Test fun closeBeforeCompletionReleasesWithoutReadingOrDeliveringResult() {
        var released = false
        finishFoodScan(Executor { throw AssertionError("Unexpected dispatch") }, { true }, { released = true },
            { throw AssertionError("Unexpected result read") }, { throw AssertionError("Unexpected callback") })
        assertTrue(released)
    }

    @Test fun resultFailureStillDeliversFailureAndReleasesResources() {
        var released = false
        var result: FoodBarcodeScanner.Result? = null
        finishFoodScan(Executor { it.run() }, { false }, { released = true },
            { throw IllegalStateException("decode failed") }, { result = it })
        assertTrue(released)
        assertTrue(result is FoodBarcodeScanner.Result.Failure)
    }
}
