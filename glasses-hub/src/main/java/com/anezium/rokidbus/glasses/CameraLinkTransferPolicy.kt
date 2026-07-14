package com.anezium.rokidbus.glasses

/** Pure state for keeping live video off the socket for the full frozen-mode lifetime. */
internal class CameraLinkTransferPolicy {
    private val frozenModeRequestIds = linkedSetOf<Long>()
    private var awaitingResumeKeyFrame = false

    val frozenModeActive: Boolean
        get() = frozenModeRequestIds.isNotEmpty()

    fun beginFrozenMode(requestId: Long) {
        frozenModeRequestIds += requestId
    }

    /** Explicitly ends frozen mode; writing its JPEG does not call this transition. */
    fun endFrozenMode(requestId: Long): Boolean {
        if (!frozenModeRequestIds.remove(requestId) || frozenModeRequestIds.isNotEmpty()) return false
        awaitingResumeKeyFrame = true
        return true
    }

    fun shouldAdmitVideo(isKeyFrame: Boolean): Boolean =
        !frozenModeActive && (!awaitingResumeKeyFrame || isKeyFrame)

    fun onVideoWriteStarted(isKeyFrame: Boolean) {
        if (awaitingResumeKeyFrame && isKeyFrame) awaitingResumeKeyFrame = false
    }

    fun reset() {
        frozenModeRequestIds.clear()
        awaitingResumeKeyFrame = false
    }
}
