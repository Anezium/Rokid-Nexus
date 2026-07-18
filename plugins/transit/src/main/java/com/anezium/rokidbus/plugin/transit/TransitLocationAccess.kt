package com.anezium.rokidbus.plugin.transit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

enum class TransitLocationAccess {
    MISSING_PRECISE,
    MISSING_BACKGROUND,
    READY,
    ;

    companion object {
        fun from(preciseGranted: Boolean, backgroundGranted: Boolean): TransitLocationAccess = when {
            !preciseGranted -> MISSING_PRECISE
            !backgroundGranted -> MISSING_BACKGROUND
            else -> READY
        }
    }
}

fun Context.transitLocationAccess(): TransitLocationAccess = TransitLocationAccess.from(
    preciseGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
    backgroundGranted = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED,
)
