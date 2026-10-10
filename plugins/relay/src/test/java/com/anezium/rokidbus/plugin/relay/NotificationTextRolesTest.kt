package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.Person
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Roles read from a real MessagingStyle notification, as a messaging app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NotificationTextRolesTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `the user's own messages and the remote input history read as ours`() {
        val me = Person.Builder().setName("Sam").setKey("me").build()
        val mika = Person.Builder().setName("Mika").setKey("mika").build()
        val style = Notification.MessagingStyle(me)
            .addMessage("on est devant", 100L, mika)
            .addMessage("j'arrive", 200L, null as Person?)
            .addMessage("deux minutes", 300L, me)
            // Someone else who happens to share the user's name is told apart by key.
            .addMessage("salut", 400L, Person.Builder().setName("Sam").setKey("other-sam").build())
        val notification = Notification.Builder(app, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .setRemoteInputHistory(arrayOf<CharSequence>("vu"))
            .build()

        val thread = NotificationTextExtractor.extractThread(
            NotificationTextExtractor.fromExtras(notification.extras),
            messageLimit = 20,
        )

        assertEquals(
            listOf(
                RelayMessage(RelayMessageRole.RECEIVED, "on est devant", "Mika"),
                RelayMessage(RelayMessageRole.OURS, "j'arrive"),
                RelayMessage(RelayMessageRole.OURS, "deux minutes"),
                RelayMessage(RelayMessageRole.RECEIVED, "salut", "Sam"),
                RelayMessage(RelayMessageRole.OURS, "vu"),
            ),
            thread.messages,
        )
    }
}
