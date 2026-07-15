package com.anezium.rokidbus.phone

import java.security.MessageDigest

internal fun signingCertificateSha256(certificateBytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(certificateBytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
