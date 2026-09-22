package com.anezium.rokidbus.glasses

import android.graphics.Bitmap
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NoticeImageLifecycleTest {
    private val controllerType = NoticeController::class.java
    private val state = field("state").get(NoticeController) as NoticeStateMachine
    @Suppress("UNCHECKED_CAST")
    private val decoder = field("imageDecodeCoordinator").get(NoticeController) as ImageDecodeCoordinator<Bitmap>
    private val pendingField = field("pendingNoticeImage")
    private val first = NoticeInteractionIdentity("instance-a", "question-a")
    private val replacement = NoticeInteractionIdentity("instance-b", "question-b")
    private var baseSeq = 0L

    @Before
    fun setUp() {
        clearPending()
        state.close(NoticeCloseReason.DISCONNECT)
        val sequence = NoticeStateMachine::class.java.getDeclaredField("latestSeq").apply { isAccessible = true }
        baseSeq = maxOf(0L, sequence.getLong(state)) + 10L
    }

    @After
    fun tearDown() = clearPending()

    @Test
    fun `newer hide invalidates an older decode even for a missing replacement instance`() {
        val key = pendingImage(baseSeq + 1, first)

        hide(baseSeq + 3, replacement)

        assertNull(pendingField.get(NoticeController))
        assertFalse(decoder.isCurrent(key.surfaceId, key.contentKey))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertTrue(decoder.complete(key, bitmap) is ImageDecodeCompletion.Rejected)
        bitmap.recycle()
    }

    @Test
    fun `older hide preserves a newer decode arriving on the other transport`() {
        val key = pendingImage(baseSeq + 3, replacement)

        hide(baseSeq + 2, first)

        assertTrue(decoder.isCurrent(key.surfaceId, key.contentKey))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertTrue(decoder.complete(key, bitmap) is ImageDecodeCompletion.Accepted)
        assertFalse(state.isStaleSequence(key.seq))
    }

    private fun hide(seq: Long, identity: NoticeInteractionIdentity) {
        NoticeController.handleNoticeEnvelope(
            RuntimeEnvironment.getApplication(),
            BusEnvelope(
                path = BusPaths.NOTICE_HIDE,
                payload = NoticeSurfaceContract.withInteractionIdentity(
                    JSONObject().put("surfaceId", "relay:notice").put("seq", seq),
                    identity,
                ),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pendingImage(seq: Long, identity: NoticeInteractionIdentity): ImageDecodeKey {
        val key = ImageDecodeKey("relay:notice", seq, "fixture-image")
        decoder.begin(key)
        // Seed the point after wire validation and before asynchronous decode completes.
        val pendingType = Class.forName("${controllerType.name}\$PendingNoticeImage")
        val constructor = pendingType.getDeclaredConstructor(
            ImageDecodeKey::class.java,
            NoticeInteractionIdentity::class.java,
        ).apply { isAccessible = true }
        pendingField.set(NoticeController, constructor.newInstance(key, identity))
        return key
    }

    private fun clearPending() {
        pendingField.set(NoticeController, null)
        decoder.invalidate()?.recycle()
    }

    private fun field(name: String) = controllerType.getDeclaredField(name).apply { isAccessible = true }
}
