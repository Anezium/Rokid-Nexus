package com.anezium.rokidbus.shared

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSurfaceContractTest {
    @Test
    fun `valid request round trips with all correlation and params`() {
        val payload = request().put("params", JSONObject().put("view", "route"))
        val parsed = valid(PageSurfaceContract.validateRequest(payload))
        assertEquals(PageCorrelation("req-1", 1L, 1, "maps:route"), parsed.correlation)
        assertEquals("open", parsed.reason)
        assertEquals(payload.toString(), parsed.toPayload().toString())
    }

    @Test
    fun `requests accept all reasons and root frame zero`() {
        listOf("open", "refresh", "retry").forEach {
            assertEquals(it, valid(PageSurfaceContract.validateRequest(request().put("reason", it))).reason)
        }
        assertEquals(0, valid(PageSurfaceContract.validateRequest(request().put("frameIndex", 0))).correlation.frameIndex)
    }

    @Test
    fun `request rejects missing wrong typed and unknown fields of the contract`() {
        listOf(
            request().apply { remove("requestId") }, request().put("reason", "again"),
            request().put("params", "text"), request().put("params", JSONObject.NULL),
            request().put("frameIndex", 6), request().put("sessionGeneration", "1"),
        ).forEach { invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateRequest(it)) }
    }

    @Test
    fun `all ids require printable ASCII without whitespace and fit 128 characters`() {
        assertTrue(PageSurfaceContract.isValidId("x".repeat(128)))
        assertTrue(PageSurfaceContract.isValidId("provider:page/action-1"))
        listOf("", "x".repeat(129), "a b", "a\t", "a\n", "a\u007f", "a\u0000", "é").forEach { id ->
            assertFalse(id, PageSurfaceContract.isValidId(id))
            listOf("requestId", "pageId").forEach { key ->
                invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateRequest(request().put(key, id)))
            }
            listOf("invocationId", "actionId", "pageId").forEach { key ->
                invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateAction(invocation().put(key, id)))
            }
        }
    }

    @Test
    fun `integer validation preserves long precision and rejects fractions and overflow`() {
        assertEquals(Long.MAX_VALUE, valid(PageSurfaceContract.validateRequest(request().put("sessionGeneration", Long.MAX_VALUE))).correlation.sessionGeneration)
        listOf(-1, 1.5, "1", java.math.BigInteger("9223372036854775808")).forEach {
            invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateRequest(request().put("sessionGeneration", it)))
        }
    }

    @Test
    fun `params boundary includes UTF8 and object syntax`() {
        val boundary = sizedObject(2_048)
        valid(PageSurfaceContract.validateRequest(request().put("params", boundary)))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateRequest(request().put("params", sizedObject(2_049))))
        val unicode = JSONObject().put("text", "界".repeat(700))
        assertTrue(unicode.toString().length < 2_048)
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateRequest(request().put("params", unicode)))
    }

    @Test
    fun `successful response retains an immutable serialized copy and exact byte count`() {
        val payload = response().put("title", "Étape")
        val parsed = valid(PageSurfaceContract.validateResponse(payload)) as PageSurfaceResponse.Page
        val before = parsed.payloadJson
        payload.put("title", "Changed")
        assertEquals("Étape", parsed.title)
        assertEquals(before.toByteArray(Charsets.UTF_8).size, parsed.byteSize)
        assertEquals("Étape", JSONObject(before).getString("title"))
    }

    @Test
    fun `every reserved template is accepted and unknown templates fail closed`() {
        assertEquals(setOf("summary", "selectableList", "document", "commands", "conversation", "media", "route", "ink"), PageSurfaceContract.templates)
        PageSurfaceContract.templates.forEach { valid(PageSurfaceContract.validateResponse(response().put("template", it))) }
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("template", "grid")))
    }

    @Test
    fun `all eight error codes can replace a page`() {
        assertEquals(setOf("INVALID_PAGE_REQUEST", "INVALID_PAGE", "PAGE_TOO_LARGE", "PAGE_TIMEOUT", "PAGE_UNAVAILABLE", "FRAME_LIMIT", "STALE_GENERATION", "UNCONFIRMED_ACTION"), PageSurfaceContract.errors)
        PageSurfaceContract.errors.forEach { code ->
            val result = valid(PageSurfaceContract.validateResponse(correlation().put("error", code))) as PageSurfaceResponse.Error
            assertEquals(code, result.code)
        }
    }

    @Test
    fun `error cannot contain a page or an unknown code`() {
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("error", "PAGE_TIMEOUT")))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(correlation().put("error", "UNKNOWN")))
    }

    @Test
    fun `pending request rejects stale request generation page and frame correlation`() {
        val pending = valid(PageSurfaceContract.validateRequest(request()))
        valid(PageSurfaceContract.validateResponse(response(), pending))
        mapOf("requestId" to "old", "sessionGeneration" to 0L, "frameIndex" to 2, "pageId" to "other").forEach { (key, value) ->
            invalid(PageSurfaceContract.ERROR_STALE_GENERATION, PageSurfaceContract.validateResponse(response().put(key, value), pending))
        }
    }

    @Test
    fun `page boundary is the entire serialized response and measured in UTF8`() {
        val payload = response()
        val body = payload.getJSONObject("body").put("text", "")
        val remaining = PageSurfaceContract.MAX_PAGE_BYTES - PageSurfaceContract.serializedBytes(payload)
        body.put("text", "x".repeat(remaining))
        assertEquals(65_536, PageSurfaceContract.serializedBytes(payload))
        valid(PageSurfaceContract.validateResponse(payload))
        body.put("text", "x".repeat(remaining) + "é")
        invalid(PageSurfaceContract.ERROR_PAGE_TOO_LARGE, PageSurfaceContract.validateResponse(payload))
    }

    @Test
    fun `dataset boundary applies to top level and opaque body data`() {
        valid(PageSurfaceContract.validateResponse(response().put("data", sizedObject(2_048))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("data", sizedObject(2_049))))
        val payload = response().put("body", JSONObject().put("data", sizedObject(2_048)))
        valid(PageSurfaceContract.validateResponse(payload))
        payload.getJSONObject("body").put("data", JSONObject().put("text", "界".repeat(700)))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(payload))
    }

    @Test
    fun `review h deeply nested huge and unserializable payloads are invalid without throwing`() {
        fun nested(levels: Int): JSONObject {
            var value = JSONObject()
            repeat(levels - 1) { value = JSONObject().put("n", value) }
            return value
        }
        // The response is level 1 and its body level 2, so seven body levels reach the limit of eight.
        valid(PageSurfaceContract.validateResponse(response().put("body", nested(7))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("body", nested(8))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("body", nested(100_000))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateRequest(request().put("params", nested(100_000))))

        val huge = JSONObject().put("text", "x".repeat(1_000_000))
        invalid(PageSurfaceContract.ERROR_PAGE_TOO_LARGE, PageSurfaceContract.validateResponse(response().put("body", huge)))
        val many = JSONObject().put("items", JSONArray().apply { repeat(100_000) { put("x") } })
        invalid(PageSurfaceContract.ERROR_PAGE_TOO_LARGE, PageSurfaceContract.validateResponse(response().put("body", many)))

        val unserializable = object : Any() {
            override fun toString(): String = throw IllegalStateException("cannot render")
        }
        val broken = response().put("body", JSONObject().put("value", unserializable))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(broken))
    }

    @Test
    fun `review round 2 oversized numbers and unsupported scalars are invalid before serialization`() {
        fun withValue(value: Any) = response().put("body", JSONObject().put("value", value))
        valid(PageSurfaceContract.validateResponse(withValue(BigDecimal("9".repeat(32)))))
        valid(PageSurfaceContract.validateResponse(withValue(-1.25e-300)))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(withValue(BigDecimal("9".repeat(33)))))

        // Rejected as INVALID_PAGE by the walk, before serialization could measure them as too large.
        val hugeInteger = BigInteger.ONE.shiftLeft(3_000_000)
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(withValue(BigDecimal(hugeInteger))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(withValue(hugeInteger)))
        val chatty = object : Any() {
            override fun toString(): String = "x".repeat(1_000_000)
        }
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(withValue(chatty)))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(withValue(AtomicLong(5))))
        invalid(
            PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST,
            PageSurfaceContract.validateRequest(request().put("params", JSONObject().put("n", BigDecimal("9".repeat(33))))),
        )
    }

    @Test
    fun `action count accepts 64 and rejects 65 without truncation`() {
        fun actions(count: Int) = JSONArray().apply { repeat(count) { put(action("action-$it")) } }
        assertEquals(64, (valid(PageSurfaceContract.validateResponse(response().put("actions", actions(64)))) as PageSurfaceResponse.Page).actions.size)
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("actions", actions(65))))
    }

    @Test
    fun `action label accepts 24 characters and rejects 25`() {
        valid(PageSurfaceContract.validateResponse(response().put("actions", JSONArray().put(action().put("label", "é".repeat(24))))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("actions", JSONArray().put(action().put("label", "x".repeat(25))))))
    }

    @Test
    fun `actions validate id kind confirm type uniqueness and array shape`() {
        listOf(action().put("id", "bad id"), action().put("kind", "system"), action().put("confirm", "false")).forEach {
            invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("actions", JSONArray().put(it))))
        }
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("actions", JSONArray().put(action()).put(action()))))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(response().put("actions", JSONObject())))
    }

    @Test
    fun `page title body live and revision must have their exact shapes`() {
        valid(PageSurfaceContract.validateResponse(response().put("title", "x".repeat(48))))
        listOf(response().put("title", "x".repeat(49)), response().put("body", "text"), response().put("live", "true"), response().put("revision", 1.5)).forEach {
            invalid(PageSurfaceContract.ERROR_INVALID_PAGE, PageSurfaceContract.validateResponse(it))
        }
    }

    @Test
    fun `action payload round trips the invocation and shown revision`() {
        val parsed = valid(PageSurfaceContract.validateAction(invocation()))
        assertEquals("invoke-1", parsed.invocationId)
        assertEquals(7L, parsed.revision)
        assertEquals(invocation().toString(), parsed.toPayload().toString())
    }

    @Test
    fun `results accept each status message and validated replacement`() {
        listOf("done", "rejected", "stale").forEach {
            assertEquals(it, valid(PageSurfaceContract.validateResult(result().put("status", it))).status)
        }
        assertEquals(64, valid(PageSurfaceContract.validateResult(result().put("message", "x".repeat(64)))).message!!.length)
        assertEquals(1L, valid(PageSurfaceContract.validateResult(result().put("replacement", response()))).replacement!!.revision)
    }

    @Test
    fun `results reject invalid status oversized message and error replacement`() {
        listOf(result().put("status", "ok"), result().put("message", "x".repeat(65)), result().put("replacement", correlation().put("error", "PAGE_TIMEOUT"))).forEach {
            invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateResult(it))
        }
    }

    @Test
    fun `visible lease is required and covered lease must be absent`() {
        val payload = JSONObject().put("pageId", "maps:route").put("visible", true).put("leaseUntilMs", 120_000L)
        assertEquals(120_000L, valid(PageSurfaceContract.validateVisibility(payload)).leaseUntilMs)
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateVisibility(payload.put("visible", false)))
        payload.remove("leaseUntilMs")
        valid(PageSurfaceContract.validateVisibility(payload))
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateVisibility(payload.put("visible", true)))
    }

    @Test
    fun `closed validates all reasons and rejects unknown reasons`() {
        listOf("back", "session_closed", "timeout", "link_lost", "replaced", "frame_limit").forEach {
            valid(PageSurfaceContract.validateClosed(JSONObject().put("pageId", "maps:route").put("reason", it)))
        }
        invalid(PageSurfaceContract.ERROR_INVALID_PAGE_REQUEST, PageSurfaceContract.validateClosed(JSONObject().put("pageId", "maps:route").put("reason", "disconnect")))
    }

    @Test
    fun `reserved constants and only six page paths remain wire stable`() {
        assertEquals(listOf("/page/request", "/page/response", "/page/action", "/page/result", "/page/visibility", "/page/closed"), listOf(BusPaths.PAGE_REQUEST, BusPaths.PAGE_RESPONSE, BusPaths.PAGE_ACTION, BusPaths.PAGE_RESULT, BusPaths.PAGE_VISIBILITY, BusPaths.PAGE_CLOSED))
        assertEquals(1, PageSurfaceContract.VERSION)
        assertEquals(6, PageSurfaceContract.MAX_FRAMES)
        assertEquals(524_288, PageSurfaceContract.MAX_SNAPSHOT_TOTAL_BYTES)
        assertEquals("rokidbus.plugin.pages", PageSurfaceContract.META_PLUGIN_PAGES)
        assertEquals("pageSessionVersion", PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION)
    }

    private fun correlation() = PageSurfaceContract.correlationPayload(PageCorrelation("req-1", 1L, 1, "maps:route"))
    private fun request() = correlation().put("reason", "open")
    private fun response() = correlation().put("revision", 1L).put("template", "summary").put("title", "Route")
        .put("body", JSONObject()).put("actions", JSONArray()).put("live", true)
    private fun action(id: String = "mute") = JSONObject().put("id", id).put("label", "Mute").put("kind", "plugin").put("confirm", false)
    private fun invocation() = PageSurfaceInvocation("invoke-1", 1L, 1, "maps:route", 7L, "mute").toPayload()
    private fun result() = JSONObject().put("invocationId", "invoke-1").put("status", "done")
    private fun sizedObject(bytes: Int): JSONObject {
        val payload = JSONObject().put("text", "")
        return payload.put("text", "x".repeat(bytes - PageSurfaceContract.serializedBytes(payload)))
    }
    private fun <T> valid(result: PageSurfaceValidationResult<T>): T {
        assertTrue(result.toString(), result is PageSurfaceValidationResult.Valid)
        return (result as PageSurfaceValidationResult.Valid).value
    }
    private fun invalid(error: String, result: PageSurfaceValidationResult<*>) {
        assertTrue(result.toString(), result is PageSurfaceValidationResult.Invalid)
        assertEquals(error, (result as PageSurfaceValidationResult.Invalid).error)
    }
}
