package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesRepairContractTest {
    @Test
    fun `config round-trips and noise leaves the stored setting standing`() {
        assertEquals(true, GlassesRepairContract.autoRepairFromConfig(GlassesRepairContract.configToJson(true)))
        assertEquals(false, GlassesRepairContract.autoRepairFromConfig(GlassesRepairContract.configToJson(false)))

        // Anything unrecognisable reads as null so the glasses keep what they already have.
        assertNull(GlassesRepairContract.autoRepairFromConfig(null))
        assertNull(GlassesRepairContract.autoRepairFromConfig(JSONObject()))
        assertNull(GlassesRepairContract.autoRepairFromConfig(JSONObject().put("autoRepair", true)))
        assertNull(
            GlassesRepairContract.autoRepairFromConfig(
                JSONObject().put("version", 1).put("autoRepair", "yes"),
            ),
        )
    }

    @Test
    fun `a config from a newer phone still parses`() {
        val futuristic = GlassesRepairContract.configToJson(false)
            .put("version", GlassesRepairContract.VERSION + 3)
            .put("futureField", 7)
        assertEquals(false, GlassesRepairContract.autoRepairFromConfig(futuristic))
    }

    @Test
    fun `replies round-trip and unknown results read as no answer`() {
        GlassesRepairContract.RESULTS.forEach { result ->
            assertEquals(
                result,
                GlassesRepairContract.resultFromReply(GlassesRepairContract.replyToJson(result)),
            )
        }
        assertNull(GlassesRepairContract.resultFromReply(null))
        assertNull(GlassesRepairContract.resultFromReply(JSONObject()))
        assertNull(
            GlassesRepairContract.resultFromReply(
                JSONObject().put("version", 1).put("result", "quantum_healed"),
            ),
        )
    }

    @Test
    fun `a version 1 request is a plain repair and only hears legacy results`() {
        val legacy = JSONObject().put("version", 1)
        assertFalse(GlassesRepairContract.requestIsPairingAware(legacy))
        assertFalse(GlassesRepairContract.requestIsPairingAware(null))
        // A version 1 phone never sent a mode, and one forged onto it is not honoured.
        assertEquals(
            GlassesRepairContract.MODE_REPAIR,
            GlassesRepairContract.modeFromRequest(legacy.put("mode", GlassesRepairContract.MODE_PAIR)),
        )
        listOf(
            GlassesRepairContract.RESULT_PARTIAL,
            GlassesRepairContract.RESULT_PAIRING_PENDING,
            GlassesRepairContract.RESULT_PAIRING_REQUIRED,
        ).forEach { result ->
            assertEquals(
                GlassesRepairContract.RESULT_ARM_FAILED,
                GlassesRepairContract.resultForRequester(result, requesterPairingAware = false),
            )
            assertEquals(result, GlassesRepairContract.resultForRequester(result, requesterPairingAware = true))
        }
        GlassesRepairContract.LEGACY_RESULTS.forEach { result ->
            assertEquals(result, GlassesRepairContract.resultForRequester(result, requesterPairingAware = false))
        }
    }

    @Test
    fun `a pairing-aware request carries its mode and defaults to repair`() {
        val pair = GlassesRepairContract.requestToJson(GlassesRepairContract.MODE_PAIR)
        assertTrue(GlassesRepairContract.requestIsPairingAware(pair))
        assertEquals(GlassesRepairContract.MODE_PAIR, GlassesRepairContract.modeFromRequest(pair))
        assertEquals(
            GlassesRepairContract.MODE_REPAIR,
            GlassesRepairContract.modeFromRequest(GlassesRepairContract.requestToJson()),
        )
        assertEquals(
            GlassesRepairContract.MODE_REPAIR,
            GlassesRepairContract.modeFromRequest(
                GlassesRepairContract.requestToJson().put("mode", "reformat"),
            ),
        )
    }

    @Test
    fun `only a reply echoing the pair mode says the glasses re-paired`() {
        assertTrue(
            GlassesRepairContract.replyRanPairing(
                GlassesRepairContract.replyToJson(
                    GlassesRepairContract.RESULT_PAIRING_PENDING,
                    GlassesRepairContract.MODE_PAIR,
                ),
            ),
        )
        // Glasses that ignored the mode answer like this, even when the result looks fine.
        val legacy = GlassesRepairContract.replyToJson(GlassesRepairContract.RESULT_ALREADY_HEALTHY)
        assertFalse(GlassesRepairContract.replyRanPairing(legacy))
        assertEquals(
            GlassesRepairContract.RESULT_ALREADY_HEALTHY,
            GlassesRepairContract.resultFromReply(legacy),
        )
        assertFalse(
            GlassesRepairContract.replyRanPairing(
                GlassesRepairContract.replyToJson(
                    GlassesRepairContract.RESULT_REPAIRED,
                    GlassesRepairContract.MODE_REPAIR,
                ),
            ),
        )
        assertFalse(GlassesRepairContract.replyRanPairing(null))
    }

    @Test
    fun `only a pending re-pair names the session the phone may end`() {
        assertEquals(
            "session-1",
            GlassesRepairContract.pairingSessionFromReply(
                GlassesRepairContract.replyToJson(
                    GlassesRepairContract.RESULT_PAIRING_PENDING,
                    GlassesRepairContract.MODE_PAIR,
                    pairingSessionId = "session-1",
                ),
            ),
        )
        // Glasses that do not name it leave nothing to end.
        assertNull(
            GlassesRepairContract.pairingSessionFromReply(
                GlassesRepairContract.replyToJson(
                    GlassesRepairContract.RESULT_PAIRING_PENDING,
                    GlassesRepairContract.MODE_PAIR,
                ),
            ),
        )
        // No pairing is running behind any other verdict, whatever the glasses put in it.
        val busy = GlassesRepairContract.replyToJson(
            GlassesRepairContract.RESULT_BUSY,
            GlassesRepairContract.MODE_PAIR,
            pairingSessionId = "session-1",
        )
        assertFalse(busy.has("pairingSession"))
        assertNull(
            GlassesRepairContract.pairingSessionFromReply(
                JSONObject(busy.toString()).put("pairingSession", "session-1"),
            ),
        )
        // A plain repair is not this screen's run to end.
        assertNull(
            GlassesRepairContract.pairingSessionFromReply(
                GlassesRepairContract.replyToJson(
                    GlassesRepairContract.RESULT_PAIRING_PENDING,
                    GlassesRepairContract.MODE_REPAIR,
                    pairingSessionId = "session-1",
                ),
            ),
        )
        assertNull(GlassesRepairContract.pairingSessionFromReply(null))
    }

    @Test
    fun `an owner who never touched the switch gets the repair`() {
        assertTrue(GlassesRepairContract.DEFAULT_AUTO_REPAIR)
        assertFalse(GlassesRepairContract.RESULTS.isEmpty())
    }
}
