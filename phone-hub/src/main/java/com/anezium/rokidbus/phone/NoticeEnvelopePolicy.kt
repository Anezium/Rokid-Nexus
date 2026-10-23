package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult

/**
 * The local notice gate, kept pure so the binary exception cannot accidentally
 * spread from show to update or hide as their implementations evolve.
 */
internal fun isValidLocalNoticeEnvelope(envelope: BusEnvelope): Boolean {
    if (envelope.payload.optString("surfaceId") != NoticeSurfaceContract.LOCAL_SURFACE_ID) {
        return false
    }
    return when (envelope.path) {
        BusPaths.NOTICE_SHOW ->
            NoticeSurfaceContract.validateShow(envelope.payload, envelope.binary) is
                NoticeSurfaceValidationResult.Valid
        BusPaths.NOTICE_UPDATE ->
            envelope.binary == null &&
                NoticeSurfaceContract.validateUpdate(envelope.payload) is
                NoticeSurfacePatchResult.Valid
        BusPaths.NOTICE_HIDE -> envelope.binary == null
        BusPaths.NOTICE_WITHDRAW ->
            envelope.binary == null && NoticeSurfaceContract.parseWithdraw(envelope.payload) != null
        BusPaths.NOTICE_FORGET -> envelope.binary == null
        else -> false
    }
}
