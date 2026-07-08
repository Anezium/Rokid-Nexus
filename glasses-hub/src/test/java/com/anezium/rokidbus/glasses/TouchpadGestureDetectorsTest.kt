package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchpadGestureDetectorsTest {
    @Test
    fun tripleTap_triggersOnThreeNotificationDownEventsInsideWindow() {
        val detector = TripleTapDetector()

        assertEquals(
            TripleTapDetector.Decision.PASS,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L),
        )
        assertEquals(
            TripleTapDetector.Decision.PASS,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_250L),
        )
        assertEquals(
            TripleTapDetector.Decision.TRIGGER,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_600L),
        )
    }

    @Test
    fun tripleTap_dropsContactsOutsideWindow() {
        val detector = TripleTapDetector()

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_650L)

        assertEquals(
            TripleTapDetector.Decision.PASS,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_700L),
        )
        assertEquals(
            TripleTapDetector.Decision.TRIGGER,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_800L),
        )
    }

    @Test
    fun tripleTap_suppressesTrailingBackAndEnterClassifications() {
        val detector = TripleTapDetector()

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_200L)
        assertEquals(
            TripleTapDetector.Decision.TRIGGER,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_400L),
        )

        assertEquals(
            TripleTapDetector.Decision.CONSUME,
            detector.onKey(TripleTapDetector.KEYCODE_BACK, TripleTapDetector.ACTION_DOWN, 0, 1_650L),
        )
        assertEquals(
            TripleTapDetector.Decision.CONSUME,
            detector.onKey(TripleTapDetector.KEYCODE_ENTER, TripleTapDetector.ACTION_UP, 0, 2_200L),
        )
        assertEquals(
            TripleTapDetector.Decision.PASS,
            detector.onKey(TripleTapDetector.KEYCODE_BACK, TripleTapDetector.ACTION_DOWN, 0, 2_201L),
        )
    }

    @Test
    fun tripleTap_normalClassificationsClearPendingContacts() {
        val detector = TripleTapDetector()

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_100L)
        detector.onKey(TripleTapDetector.KEYCODE_BACK, TripleTapDetector.ACTION_DOWN, 0, 1_300L)

        assertEquals(
            TripleTapDetector.Decision.PASS,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_400L),
        )
    }

    @Test
    fun tripleTap_swipeDpadCodesClearPendingContacts() {
        val detector = TripleTapDetector()

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L)
        detector.onKey(DpadPairDedupe.KEYCODE_DPAD_RIGHT, TripleTapDetector.ACTION_DOWN, 0, 1_050L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_200L)
        detector.onKey(DpadPairDedupe.KEYCODE_DPAD_RIGHT, TripleTapDetector.ACTION_DOWN, 0, 1_250L)

        assertEquals(
            TripleTapDetector.Decision.PASS,
            detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_400L),
        )
    }

    @Test
    fun tripleTap_expiryReturnsSingleAndDoubleTapCounts() {
        val detector = TripleTapDetector()

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L)
        assertEquals(0, detector.consumeExpiredTapCount(1_600L))
        assertEquals(1, detector.consumeExpiredTapCount(1_601L))
        assertEquals(0, detector.consumeExpiredTapCount(1_602L))

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 2_000L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 2_100L)
        assertEquals(0, detector.consumeExpiredTapCount(2_700L))
        assertEquals(2, detector.consumeExpiredTapCount(2_701L))
    }

    @Test
    fun tripleTap_expiryReturnsNothingAfterTriggerOrSwipeClear() {
        val detector = TripleTapDetector()

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_000L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_100L)
        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 1_200L)
        assertEquals(0, detector.consumeExpiredTapCount(1_901L))

        detector.onKey(TripleTapDetector.KEYCODE_NOTIFICATION, TripleTapDetector.ACTION_DOWN, 0, 2_000L)
        detector.onKey(DpadPairDedupe.KEYCODE_DPAD_RIGHT, TripleTapDetector.ACTION_DOWN, 0, 2_050L)
        assertEquals(0, detector.consumeExpiredTapCount(2_701L))
    }

    @Test
    fun dpadPairDedupe_countsPairedForwardCodesAsOneSwipe() {
        val dedupe = DpadPairDedupe()

        assertEquals(
            DpadPairDedupe.Direction.FORWARD,
            dedupe.onKey(DpadPairDedupe.KEYCODE_DPAD_RIGHT, TripleTapDetector.ACTION_DOWN, 0, 1_000L),
        )
        assertNull(
            dedupe.onKey(DpadPairDedupe.KEYCODE_DPAD_DOWN, TripleTapDetector.ACTION_DOWN, 0, 1_022L),
        )
        // Hardware duplicates arrive up to ~80ms after the accepted key (measured
        // on device 2026-07-08); they must be swallowed too.
        assertNull(
            dedupe.onKey(DpadPairDedupe.KEYCODE_DPAD_RIGHT, TripleTapDetector.ACTION_DOWN, 0, 1_080L),
        )
        // A deliberate follow-up swipe lands well past the pair window.
        assertEquals(
            DpadPairDedupe.Direction.FORWARD,
            dedupe.onKey(DpadPairDedupe.KEYCODE_DPAD_RIGHT, TripleTapDetector.ACTION_DOWN, 0, 1_300L),
        )
    }

    @Test
    fun dpadPairDedupe_countsPairedBackwardCodesAsOneSwipe() {
        val dedupe = DpadPairDedupe()

        assertEquals(
            DpadPairDedupe.Direction.BACKWARD,
            dedupe.onKey(DpadPairDedupe.KEYCODE_DPAD_LEFT, TripleTapDetector.ACTION_DOWN, 0, 2_000L),
        )
        assertNull(
            dedupe.onKey(DpadPairDedupe.KEYCODE_DPAD_UP, TripleTapDetector.ACTION_DOWN, 0, 2_022L),
        )
    }
}
