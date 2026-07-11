package com.anezium.rokidbus.plugin.feeds.xtransaction

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun convertRotationToMatrix(rotation: Double): List<Double> {
    val radians = rotation * PI / 180
    return listOf(cos(radians), -sin(radians), sin(radians), cos(radians))
}
