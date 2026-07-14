package com.anezium.rokidbus.glasses

/** Pure state for keeping live video off the socket while a frozen image has priority. */
internal class CameraLinkTransferPolicy {
    private val frozenRequestIds = linkedSetOf<Long>()
    private var awaitingResumeKeyFrame = false

    val frozenTransferActive: Boolean
        get() = frozenRequestIds.isNotEmpty()

    fun beginFrozen(requestId: Long) {
        frozenRequestIds += requestId
    }

    /** Returns true when video may resume once the encoder supplies a fresh key frame. */
    fun finishFrozen(requestId: Long): Boolean {
        if (!frozenRequestIds.remove(requestId) || frozenRequestIds.isNotEmpty()) return false
        awaitingResumeKeyFrame = true
        return true
    }

    fun shouldAdmitVideo(isKeyFrame: Boolean): Boolean =
        !frozenTransferActive && (!awaitingResumeKeyFrame || isKeyFrame)

    fun onVideoWriteStarted(isKeyFrame: Boolean) {
        if (awaitingResumeKeyFrame && isKeyFrame) awaitingResumeKeyFrame = false
    }

    fun reset() {
        frozenRequestIds.clear()
        awaitingResumeKeyFrame = false
    }
}
