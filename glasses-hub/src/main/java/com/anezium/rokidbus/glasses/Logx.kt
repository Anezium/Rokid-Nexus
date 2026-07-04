package com.anezium.rokidbus.glasses

import android.util.Log

const val TAG = "ROKIDBUS"

fun log(message: String) {
    Log.i(TAG, message)
}

fun logError(message: String, throwable: Throwable? = null) {
    Log.e(TAG, message, throwable)
}
