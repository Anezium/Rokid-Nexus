package com.anezium.rokidbus.plugin.meta

import org.junit.Assert.*
import org.junit.Test

class PendingWhatsAppSendsTest {
    @Test fun repeatedRefusalsDoNotConsumePendingCapacity() {
        var stored = emptySet<String>()
        val journal = PendingWhatsAppSends({ stored }, { stored = it.toSet() })
        repeat(128) { index ->
            assertEquals("not_sent", journal.send("synthetic-$index", { true }, { "not_found" }, { "not_sent" }))
        }
        assertTrue(stored.isEmpty())
        assertEquals("acked", journal.send("synthetic-final", { true }, { "not_found" }, { "acked" }))
    }

    @Test fun ambiguousOutcomesSurviveAndCapacityFailsClosed() {
        var stored = emptySet<String>()
        var submissions = 0
        val journal = PendingWhatsAppSends({ stored }, { stored = it.toSet() })
        repeat(64) { journal.send("synthetic-$it", { true }, { "unknown" }, { submissions++; "unknown" }) }
        assertEquals("not_sent", journal.send("synthetic-overflow", { true }, { "not_found" }, { submissions++; "acked" }))
        assertEquals(64, submissions)
        journal.reconcile { if (it == "synthetic-0") "not_found" else "unknown" }
        assertEquals(63, stored.size)
    }

    @Test fun transportExceptionUsesAdmissionEvidenceAndNeverResubmits() {
        var stored = emptySet<String>()
        val journal = PendingWhatsAppSends({ stored }, { stored = it.toSet() })
        assertEquals("not_found", journal.send("synthetic-a", { true }, { "not_found" }, { error("synthetic") }))
        assertTrue(stored.isEmpty())
        assertEquals("unknown", journal.send("synthetic-b", { true }, { "unknown" }, { error("synthetic") }))
        assertEquals(setOf("synthetic-b"), stored)
        assertTrue(whatsAppSendMessage("not_sent").startsWith("Not sent"))
        assertTrue(whatsAppSendMessage("unknown").startsWith("Unconfirmed"))
    }

    @Test fun expiryOrJournalFailureNeverCallsSubmit() {
        var submits = 0
        val journal = PendingWhatsAppSends({ emptySet() }, { error("synthetic persistence refusal") })
        assertEquals("not_sent", journal.send("synthetic-a", { false }, { "unknown" }, { submits++; "acked" }))
        assertEquals("not_sent", journal.send("synthetic-b", { true }, { "unknown" }, { submits++; "acked" }))
        assertEquals(0, submits)
    }
}
