package com.anezium.rokidbus.glasses

import kotlin.math.abs

/** Continuous samples must cross from a settled neutral pose; interruptions never finish a hold. */
internal class HeadTiltDetector(private val angle: Float, private val holdMs: Long) {
    private var previousAt: Long? = null
    private var neutralSince: Long? = null
    private var raisedSince: Long? = null
    private var armed = false
    private var lastTrigger: Long? = null

    fun reset() {
        previousAt = null
        neutralSince = null
        raisedSince = null
        armed = false
        lastTrigger = null
    }

    fun sample(upDegrees: Float, sidewaysDegrees: Float, atMs: Long, allowed: Boolean): Boolean {
        val previous = previousAt
        if (!allowed || !upDegrees.isFinite() || !sidewaysDegrees.isFinite() || abs(sidewaysDegrees) > 25f ||
            (previous != null && (atMs <= previous || atMs - previous > 250))
        ) {
            neutralSince = null
            raisedSince = null
            armed = false
            previousAt = atMs
            return false
        }
        previousAt = atMs
        if (abs(upDegrees) <= 5f) {
            raisedSince = null
            val since = neutralSince ?: atMs.also { neutralSince = it }
            if (atMs - since >= 500) armed = true
            return false
        }
        neutralSince = null
        if (!armed || upDegrees < angle || lastTrigger?.let { atMs - it < 3_000 } == true) {
            raisedSince = null
            return false
        }
        val since = raisedSince ?: atMs.also { raisedSince = it }
        if (atMs - since < holdMs) return false
        lastTrigger = atMs
        armed = false
        raisedSince = null
        return true
    }
}

internal fun headTiltDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f

internal class HeadTiltCalibration {
    private var since: Long? = null
    private var previousAt: Long? = null
    private var pitch = 0f
    private var side = 0f

    fun sample(pitchDegrees: Float, sideDegrees: Float, atMs: Long): Pair<Float, Float>? {
        val previous = previousAt
        previousAt = atMs
        if (!pitchDegrees.isFinite() || !sideDegrees.isFinite()) { since = null; return null }
        if (since == null || previous == null || atMs <= previous || atMs - previous > 250 ||
            abs(headTiltDelta(pitch, pitchDegrees)) > 2f || abs(headTiltDelta(side, sideDegrees)) > 2f
        ) {
            since = atMs
            pitch = pitchDegrees
            side = sideDegrees
        }
        return if (atMs - (since ?: atMs) >= 1_000) pitch to side else null
    }
}
