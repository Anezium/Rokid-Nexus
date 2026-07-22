package com.anezium.rokidbus.phone

/**
 * Manual recovery must not depend on a failure diagnostic reaching the phone: the same transport
 * failure that breaks self-arm can also prevent that diagnostic from being delivered.
 */
internal fun shouldOfferManualSetup(
    cxrReady: Boolean,
    glassesAppInstalled: Boolean,
    glassesSetupComplete: Boolean,
): Boolean = cxrReady && glassesAppInstalled && !glassesSetupComplete
