package com.anezium.rokidbus.glasses

internal const val MAX_SUPPORT_DIAGNOSTIC_LENGTH = 96

private val STANDALONE_PAIRING_CODE = Regex("""\b\d{6}\b""")
private val IPV4_LITERAL = Regex("""\d+\.\d+\.\d+\.\d+""")
private val DIAGNOSTIC_WHITESPACE = Regex("""\s+""")

internal fun sanitizeSupportDiagnostic(diagnostic: String): String =
    diagnostic
        .replace(STANDALONE_PAIRING_CODE, "······")
        .replace(IPV4_LITERAL, "")
        .replace(DIAGNOSTIC_WHITESPACE, " ")
        .trim()
        .take(MAX_SUPPORT_DIAGNOSTIC_LENGTH)

internal fun pairingFailureDiagnostic(causeText: String): String =
    sanitizeSupportDiagnostic("PAIR-FAIL: $causeText")
