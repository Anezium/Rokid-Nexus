package com.anezium.rokidbus.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo

object ServiceInfoCompat {
    fun connectedDeviceType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    /**
     * Hub service types. The location bit lets a while-in-use grant reach the
     * hub while the app is backgrounded (Transit polls from the refresh loop);
     * declaring it without the runtime grant throws, so it is conditional.
     */
    fun hubTypes(context: Context): Int {
        val hasLocation =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
    }
}
