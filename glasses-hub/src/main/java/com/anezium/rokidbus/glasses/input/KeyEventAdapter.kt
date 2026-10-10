// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.input

/**
 * Turns the fields of an Android `KeyEvent` into a [RawKeyEvent]. The caller passes the fields
 * so this package stays free of `android.*`; the one-line extraction sits with the service.
 */
internal object KeyEventAdapter {
    fun from(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTime: Long,
        downTime: Long,
        deviceId: Int,
        deviceName: String?,
    ): RawKeyEvent = RawKeyEvent(
        keyCode = keyCode,
        action = action,
        repeatCount = repeatCount,
        eventTime = eventTime,
        downTime = downTime,
        deviceId = deviceId,
        deviceClass = classify(deviceName),
    )

    /**
     * A name containing `R08`, any case, is the ring. Every other device is treated as the
     * touchpad, which is what the service has always done: the temple pad reports as
     * `ROKID,PSOC-TP-R` through `Generic.kl`, a keyboard-class device, so telling it from a
     * bonded keyboard or dpad needs a device trace first. Until then the editable exception
     * covers the bonded keyboard, and [DeviceClass.KEYBOARD_DPAD] and [DeviceClass.OTHER]
     * stay unassigned.
     */
    fun classify(deviceName: String?): DeviceClass =
        if (deviceName?.uppercase()?.contains("R08") == true) DeviceClass.R08 else DeviceClass.TOUCHPAD
}
