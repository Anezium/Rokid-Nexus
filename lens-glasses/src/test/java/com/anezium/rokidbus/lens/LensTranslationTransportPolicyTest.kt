package com.anezium.rokidbus.lens

import com.anezium.rokidbus.shared.LinkStateBits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTranslationTransportPolicyTest {
    @Test
    fun cxrControlWithoutSppIsOfflineForTranslation() {
        assertFalse(isLensTranslationDataLinkUp(LinkStateBits.CXR_CONTROL_UP))
    }

    @Test
    fun sppDataIsSufficientWithOrWithoutCxrControl() {
        assertTrue(isLensTranslationDataLinkUp(LinkStateBits.SPP_DATA_UP))
        assertTrue(
            isLensTranslationDataLinkUp(
                LinkStateBits.SPP_DATA_UP or LinkStateBits.CXR_CONTROL_UP,
            ),
        )
    }

    @Test
    fun pendingRequestBlocksEveryAdditionalRequest() {
        assertFalse(
            canStartLensTranslationRequest(
                dataLinkUp = true,
                pendingRequestCount = 1,
                nowMs = 1_000L,
                retryNotBeforeMs = 0L,
            ),
        )
    }

    @Test
    fun retryBackoffMustExpireBeforeRequestCanResume() {
        assertFalse(
            canStartLensTranslationRequest(
                dataLinkUp = true,
                pendingRequestCount = 0,
                nowMs = 1_499L,
                retryNotBeforeMs = 1_500L,
            ),
        )
        assertTrue(
            canStartLensTranslationRequest(
                dataLinkUp = true,
                pendingRequestCount = 0,
                nowMs = 1_500L,
                retryNotBeforeMs = 1_500L,
            ),
        )
    }
}
