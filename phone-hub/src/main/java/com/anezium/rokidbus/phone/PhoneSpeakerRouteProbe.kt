package com.anezium.rokidbus.phone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build

internal enum class PhoneTtsRoute {
    EXTERNAL_SINK,
    GLASSES_LINK,
    PHONE_SPEAKER,
}

internal enum class PhoneTtsRouteDeviceType {
    BLUETOOTH_EXTERNAL,
    OTHER_EXTERNAL,
    PHONE,
    UNKNOWN,
}

internal data class PhoneTtsRouteDevice(
    val type: PhoneTtsRouteDeviceType,
    val address: String? = null,
)

internal object PhoneTtsRouteClassifier {
    fun classifyWinningDevice(
        devices: List<PhoneTtsRouteDevice>,
        glassesAddress: String?,
    ): PhoneTtsRoute = devices.firstOrNull()
        ?.classify(glassesAddress)
        ?: PhoneTtsRoute.PHONE_SPEAKER

    fun classifyScannedDevices(
        devices: List<PhoneTtsRouteDevice>,
        glassesAddress: String?,
    ): PhoneTtsRoute {
        val external = devices.filter { device ->
            device.type == PhoneTtsRouteDeviceType.BLUETOOTH_EXTERNAL ||
                device.type == PhoneTtsRouteDeviceType.OTHER_EXTERNAL
        }
        if (external.isEmpty()) return PhoneTtsRoute.PHONE_SPEAKER
        if (external.any { it.isGlasses(glassesAddress) }) return PhoneTtsRoute.GLASSES_LINK
        if (external.any { it.type == PhoneTtsRouteDeviceType.OTHER_EXTERNAL }) {
            return PhoneTtsRoute.EXTERNAL_SINK
        }
        return if (
            glassesAddress.isNullOrBlank() ||
            external.any { it.address.isNullOrBlank() }
        ) {
            PhoneTtsRoute.PHONE_SPEAKER
        } else {
            PhoneTtsRoute.EXTERNAL_SINK
        }
    }

    private fun PhoneTtsRouteDevice.classify(glassesAddress: String?): PhoneTtsRoute = when (type) {
        PhoneTtsRouteDeviceType.OTHER_EXTERNAL -> PhoneTtsRoute.EXTERNAL_SINK
        PhoneTtsRouteDeviceType.BLUETOOTH_EXTERNAL -> when {
            glassesAddress.isNullOrBlank() || address.isNullOrBlank() ->
                PhoneTtsRoute.PHONE_SPEAKER
            isGlasses(glassesAddress) -> PhoneTtsRoute.GLASSES_LINK
            else -> PhoneTtsRoute.EXTERNAL_SINK
        }
        PhoneTtsRouteDeviceType.PHONE,
        PhoneTtsRouteDeviceType.UNKNOWN,
        -> PhoneTtsRoute.PHONE_SPEAKER
    }

    private fun PhoneTtsRouteDevice.isGlasses(glassesAddress: String?): Boolean =
        type == PhoneTtsRouteDeviceType.BLUETOOTH_EXTERNAL &&
            !address.isNullOrBlank() &&
            !glassesAddress.isNullOrBlank() &&
            address.equals(glassesAddress, ignoreCase = true)
}

internal class PhoneSpeakerRouteProbe(
    context: Context,
    private val glassesAddress: () -> String?,
) {
    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun classifyRoute(): PhoneTtsRoute = classifyRoute(phoneTtsAudioAttributes())

    @SuppressLint("MissingPermission")
    fun classifyRoute(attributes: AudioAttributes): PhoneTtsRoute = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val devices = audioManager.getAudioDevicesForAttributes(attributes)
            PhoneTtsRouteClassifier.classifyWinningDevice(
                devices.map { device -> routeDevice(device.type, device.address) },
                glassesAddress(),
            )
        } else {
            PhoneTtsRouteClassifier.classifyScannedDevices(
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
                    routeDevice(device.type, device.address)
                },
                glassesAddress(),
            )
        }
    }.getOrDefault(PhoneTtsRoute.PHONE_SPEAKER)

    private fun routeDevice(type: Int, address: String?): PhoneTtsRouteDevice =
        PhoneTtsRouteDevice(
            type = when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                -> PhoneTtsRouteDeviceType.BLUETOOTH_EXTERNAL
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                -> PhoneTtsRouteDeviceType.OTHER_EXTERNAL
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
                -> PhoneTtsRouteDeviceType.PHONE
                else -> PhoneTtsRouteDeviceType.UNKNOWN
            },
            address = address,
        )
}
