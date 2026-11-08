package com.anezium.rokidbus.plugin.meta

import org.junit.Assert.assertEquals
import org.junit.Test

/** A sign-in or pairing that settles on the phone reaches the open glasses pages through presence. */
class MetaPresenceTest {
    @Test fun observersHearAServiceGainingOrLosingItsSessionOnceAndNotALabelChange() {
        val heard = mutableListOf<Pair<String, String>>()
        val stop = MetaPresence.observe { id, state -> heard += id to state::class.simpleName!! }
        try {
            MetaPresence.set("instagram", MetaPresence.State.Absent)
            MetaPresence.set("instagram", MetaPresence.State.Present("Signed in as @a"))
            // The handle alone changing is no sign-in.
            MetaPresence.set("instagram", MetaPresence.State.Present("Signed in as @b"))
            MetaPresence.set("whatsapp", MetaPresence.State.Present("Linked"))
            MetaPresence.set("whatsapp", MetaPresence.State.Absent)
            assertEquals(
                listOf("instagram" to "Absent", "instagram" to "Present", "whatsapp" to "Present", "whatsapp" to "Absent"),
                heard,
            )
            assertEquals(MetaPresence.State.Present("Signed in as @b"), MetaPresence.get("instagram"))
        } finally {
            stop()
        }
        MetaPresence.set("instagram", MetaPresence.State.Absent)
        assertEquals(4, heard.size)
    }

    @Test fun theServiceIdsOfPresenceAreTheServicesOfThePages() {
        assertEquals(MessagingService.WHATSAPP, MessagingService.byId(MetaMessagesActivity.WHATSAPP_ID))
        MetaAccount.entries.forEach { account -> assertEquals(account.title, MessagingService.byId(account.id)?.title) }
    }
}
