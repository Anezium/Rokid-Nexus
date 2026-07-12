package com.anezium.rokidbus.phone

import android.content.pm.ServiceInfo

object ServiceInfoCompat {
    fun connectedDeviceType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    fun hubTypes(locationActive: Boolean = false): Int = connectedDeviceType() or
        if (locationActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
}
