package com.anezium.rokidbus.plugin.meta

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppConnectionStatusTest {
    @Test fun networkFailureOffersRecoveryWithoutClaimingAccountRemoval() {
        assertEquals(
            "Connection unavailable · check network, then Refresh status",
            whatsappConnectionStatus("linked", connected = false, loggedIn = false, blocker = "connect_failed"),
        )
    }

    @Test fun accountFailuresRemainDistinctFromNetworkRecovery() {
        mapOf(
            "connect_rejected" to "Connection rejected · reopen settings or check linked devices",
            "temporary_ban" to "Account temporarily restricted · check WhatsApp",
            "stream_replaced" to "Session replaced · check linked devices",
            "client_outdated" to "WhatsApp client outdated · update plugin",
        ).forEach { (blocker, expected) ->
            assertEquals(expected, whatsappConnectionStatus("linked", true, true, blocker))
        }
    }

    @Test fun savedDeviceAndOpenSocketDoNotConfirmAuthentication() {
        assertEquals(
            "Stored device · not authenticated",
            whatsappConnectionStatus("linked", connected = true, loggedIn = false, blocker = ""),
        )
    }

    @Test fun storedDeviceWithoutConnectionIsNotAuthenticated() {
        assertEquals(
            "Stored device · not authenticated",
            whatsappConnectionStatus("linked", connected = false, loggedIn = true, blocker = ""),
        )
    }

    @Test fun blockerOverridesPreviouslyAuthenticatedState() {
        assertEquals(
            "Not authenticated · check linked devices",
            whatsappConnectionStatus("linked", connected = true, loggedIn = true, blocker = "logged_out"),
        )
    }

    @Test fun authenticatedStatusRequiresLinkedConnectedLoggedInAndNoBlocker() {
        assertEquals(
            "linked · authenticated",
            whatsappConnectionStatus("linked", connected = true, loggedIn = true, blocker = ""),
        )
    }

    @Test fun pairingSocketDoesNotReportACompletedAssociation() {
        assertEquals(
            "pairing · not authenticated",
            whatsappConnectionStatus("pairing", connected = true, loggedIn = false, blocker = ""),
        )
    }

    @Test fun freshUnlinkedDeviceStaysOffline() {
        assertEquals(
            "unlinked · offline",
            whatsappConnectionStatus("unlinked", connected = false, loggedIn = false, blocker = ""),
        )
    }
}
