package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PatchErrorsTest {
    @Test fun upstreamMessagesAndWrappedCausesStayPrivate() {
        val logs = mutableListOf<String>()
        val errors = listOf(IOException("/private/account/key"),
            IllegalArgumentException("/upstream/path").apply {
                stackTrace = arrayOf(StackTraceElement("app.morphe.Parser", "read", "Parser.kt", 1))
            }, IllegalStateException("/wrapped/path", IOException("secret")))
        errors.forEach { assertEquals("Safe reason", PatchErrors.reason(it, "Safe reason", logs::add)) }
        assertTrue(logs.all { it.startsWith("failure_class=") && !it.contains("/") && !it.contains("secret") })
    }

    @Test fun ownedValidationKeepsItsActionableReason() {
        val error = IllegalArgumentException("Choose a supported stock APK.")
        assertEquals(error.message, PatchErrors.reason(error, "Fallback") {})
        val empty = IllegalStateException("")
        assertEquals("Fallback", PatchErrors.reason(empty, "Fallback") {})
    }
    @Test fun preSanitizationFailureSnapshotsCannotResurfaceRawMessages() {
        val encoded = PatchJobStore.encode(PatchJobState(status = PatchJobStatus.FAILURE, message = "/private/upstream/path"))
        val legacy = encoded.replace("\"safe_failures\":true,", "")
        assertEquals("Patching failed. Retry with a supported stock APK.", PatchJobStore.decode(legacy).message)
    }

}
