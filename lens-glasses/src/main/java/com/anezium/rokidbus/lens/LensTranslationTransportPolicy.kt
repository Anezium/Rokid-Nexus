package com.anezium.rokidbus.lens

import com.anezium.rokidbus.shared.LinkStateBits

internal fun isLensTranslationDataLinkUp(linkState: Int): Boolean =
    linkState and LinkStateBits.SPP_DATA_UP != 0

internal fun canStartLensTranslationRequest(
    dataLinkUp: Boolean,
    pendingRequestCount: Int,
    nowMs: Long,
    retryNotBeforeMs: Long,
): Boolean =
    dataLinkUp && pendingRequestCount == 0 && nowMs >= retryNotBeforeMs
