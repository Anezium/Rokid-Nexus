package com.anezium.rokidbus.plugin.feeds.xtransaction

import kotlin.math.abs

internal class CubicCurve(private val curves: List<Double>) {
    fun getValue(time: Double): Double {
        if (time <= 0.0) {
            val startGradient = when {
                curves[0] > 0.0 -> curves[1] / curves[0]
                curves[1] == 0.0 && curves[2] > 0.0 -> curves[3] / curves[2]
                else -> 0.0
            }
            return startGradient * time
        }
        if (time >= 1.0) {
            val endGradient = when {
                curves[2] < 1.0 -> (curves[3] - 1.0) / (curves[2] - 1.0)
                curves[2] == 1.0 && curves[0] < 1.0 -> (curves[1] - 1.0) / (curves[0] - 1.0)
                else -> 0.0
            }
            return 1.0 + endGradient * (time - 1.0)
        }

        var start = 0.0
        var middle = 0.0
        var end = 1.0
        while (start < end) {
            middle = (start + end) / 2
            val xEstimate = calculate(curves[0], curves[2], middle)
            if (abs(time - xEstimate) < 0.00001) {
                return calculate(curves[1], curves[3], middle)
            }
            if (xEstimate < time) start = middle else end = middle
        }
        return calculate(curves[1], curves[3], middle)
    }

    private fun calculate(a: Double, b: Double, middle: Double): Double =
        3.0 * a * (1 - middle) * (1 - middle) * middle +
            3.0 * b * (1 - middle) * middle * middle +
            middle * middle * middle
}
