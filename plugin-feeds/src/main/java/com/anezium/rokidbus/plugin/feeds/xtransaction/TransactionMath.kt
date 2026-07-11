package com.anezium.rokidbus.plugin.feeds.xtransaction

import kotlin.math.ceil
import kotlin.math.floor

internal fun javascriptRound(number: Double): Int {
    val floor = floor(number)
    return if (number - floor >= 0.5) ceil(number).toInt() else floor.toInt()
}
internal fun dartRound(number: Double): Long =
    if (number >= 0) floor(number + 0.5).toLong() else ceil(number - 0.5).toLong()

internal fun roundToTwo(value: Double): Double = dartRound(value * 100) / 100.0

internal fun oddLowerBound(index: Int): Double = if (index % 2 != 0) -1.0 else 0.0

internal fun floatToHex(value: Double): String {
    val result = StringBuilder()
    val fraction = value - value.toInt()
    var quotient = value.toInt()
    var current = value
    while (quotient > 0) {
        quotient = (current / 16).toInt()
        val remainder = (current - quotient * 16).toInt()
        result.insert(0, if (remainder > 9) (remainder + 55).toChar() else remainder.toString())
        current = quotient.toDouble()
    }
    if (fraction == 0.0) return result.toString()

    result.append('.')
    var remaining = fraction
    while (remaining > 0) {
        remaining *= 16
        val integer = remaining.toInt()
        remaining -= integer
        result.append(if (integer > 9) (integer + 55).toChar() else integer.toString())
    }
    return result.toString()
}
