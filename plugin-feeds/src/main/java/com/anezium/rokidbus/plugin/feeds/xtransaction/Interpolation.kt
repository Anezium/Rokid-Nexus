package com.anezium.rokidbus.plugin.feeds.xtransaction

internal fun interpolate(from: List<Double>, to: List<Double>, fraction: Double): List<Double> {
    require(from.size == to.size) { "Mismatched interpolation arguments: $from vs $to" }
    return List(from.size) { index -> from[index] * (1 - fraction) + to[index] * fraction }
}
