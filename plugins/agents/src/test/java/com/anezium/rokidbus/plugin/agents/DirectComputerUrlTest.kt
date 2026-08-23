package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectComputerUrlTest {
    @Test
    fun parsesWsAndWssAndRejectsOtherSchemes() {
        val ws = DirectComputerUrl.parse("ws://desk.local:4000")
        assertTrue(ws is DirectComputerParseResult.Valid)
        val valid = (ws as DirectComputerParseResult.Valid).computer
        assertEquals("ws://desk.local:4000", valid.url)
        assertTrue(valid.computerId.startsWith(DirectComputer.ID_PREFIX))
        assertEquals("desk.local:4000", valid.name)

        val wss = DirectComputerUrl.parse("wss://desk.example/codex")
        assertTrue(wss is DirectComputerParseResult.Valid)
        assertEquals(
            "wss://desk.example/codex",
            (wss as DirectComputerParseResult.Valid).computer.url,
        )

        val http = DirectComputerUrl.parse("http://desk.local:4000")
        assertTrue(http is DirectComputerParseResult.Invalid)
    }

    @Test
    fun identityIgnoresUserinfoAndRedactionNeverShowsIt() {
        val withSecret = DirectComputerUrl.parse("ws://owner:s3cret@desk.local:4000/app")
        val without = DirectComputerUrl.parse("ws://desk.local:4000/app")
        assertTrue(withSecret is DirectComputerParseResult.Valid)
        assertTrue(without is DirectComputerParseResult.Valid)
        val secretComputer = (withSecret as DirectComputerParseResult.Valid).computer
        val plainComputer = (without as DirectComputerParseResult.Valid).computer
        assertEquals(plainComputer.computerId, secretComputer.computerId)
        assertTrue(secretComputer.url.contains("owner:s3cret"))
        val redacted = DirectComputerUrl.redactUserinfo(secretComputer.url)
        assertEquals("ws://desk.local:4000/app", redacted)
        assertFalse(redacted.contains("s3cret"))
        assertFalse(redacted.contains("owner"))
    }
}
