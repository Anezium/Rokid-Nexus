package com.anezium.rokidbus.plugin.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppCopyTest {
    private fun view(text: String, code: String = "") = WhatsAppSettingsView(text, code)

    @Test
    fun `session labels become consumer headlines`() {
        assertEquals("Connected", WhatsAppCopy.headline(view(whatsappConnectionStatus("linked", true, true, ""))))
        assertEquals("Not linked", WhatsAppCopy.headline(view(whatsappConnectionStatus("unlinked", false, false, ""))))
        assertEquals("Getting your code…", WhatsAppCopy.headline(view("Pairing TEST account…")))
        assertEquals("Waiting for the code in WhatsApp", WhatsAppCopy.headline(view("Pairing TEST account…", "ABCD-1234")))
        assertEquals("Code expired", WhatsAppCopy.headline(view("Pairing timed out")))
        assertEquals("Signed out by WhatsApp", WhatsAppCopy.headline(view(whatsappConnectionStatus("linked", true, false, "stream_replaced"))))
    }

    @Test
    fun `no displayed text mentions testing or raw blocker labels`() {
        val raw = listOf(
            "Checking linked device…", "Preparing TEST pairing…", "Pairing TEST account…", "Finishing link…",
            "Pairing timed out", "Pairing unavailable", "Pairing failed · check WhatsApp",
            "Passkey required · this build cannot complete pairing", "Link not authenticated · check WhatsApp",
            "WhatsApp unavailable", "Unlinked",
        ) + listOf("", "connect_failed", "connect_rejected", "temporary_ban", "stream_replaced", "client_outdated", "other")
            .flatMap { blocker -> listOf("linked", "unlinked", "pairing").map { whatsappConnectionStatus(it, false, false, blocker) } }
        for (text in raw) {
            val shown = WhatsAppCopy.headline(view(text)) + " " + WhatsAppCopy.explain(view(text)) + " " + WhatsAppCopy.presence(view(text))
            assertFalse(text, shown.contains("TEST"))
            assertFalse(text, shown.contains("test", ignoreCase = true))
            assertFalse(text, shown.contains(" · not authenticated"))
        }
    }

    @Test
    fun `every settled state carries an explanation`() {
        for (text in listOf("linked · authenticated", "Pairing timed out", "Unlinked", "WhatsApp unavailable",
            whatsappConnectionStatus("linked", false, false, "temporary_ban"))) {
            assertTrue(text, WhatsAppCopy.explain(view(text)).isNotEmpty())
        }
    }

    @Test
    fun `hub presence reads as a linked device`() {
        assertEquals("Linked · connected", WhatsAppCopy.presence(view("linked · authenticated")))
        assertEquals("Linked · no connection", WhatsAppCopy.presence(view(whatsappConnectionStatus("linked", false, false, "connect_failed"))))
    }
}
