package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PatchErrorsTest {
    @Test fun upstreamMessagesAndPathBearingWrappersStayPrivate() {
        val logs = mutableListOf<String>()
        val errors = listOf(IOException("/private/account/key"),
            IllegalArgumentException("/upstream/path").apply {
                stackTrace = arrayOf(StackTraceElement("app.morphe.Parser", "read", "Parser.kt", 1))
            }, IllegalStateException("Upstream internals", IOException("secret")).apply {
                stackTrace = arrayOf(StackTraceElement("app.morphe.Patcher", "apply", "Patcher.kt", 1))
            }, IllegalStateException("/wrapped/path", IOException("secret")),
            IllegalStateException("Cannot read C:\\private\\account\\key", IOException("secret")))
        errors.forEach { assertEquals("Safe reason", PatchErrors.reason(it, "Safe reason", logs::add)) }
        assertTrue(logs.all { it.startsWith("failure_class=") && !it.contains("/") && !it.contains("secret") })
    }

    @Test fun ownedPatchFailureKeepsItsActionableMessageEvenWithAnUpstreamCause() {
        val logs = mutableListOf<String>()
        val message = "One of the selected patches failed. Review your selection and try again."
        val error = IllegalStateException(message, IOException("Upstream failure at /private/account/key")).apply {
            stackTrace = arrayOf(StackTraceElement(PatchRuntime::class.java.name, "patch", "PatchRuntime.kt", 37))
        }
        assertEquals(message, PatchErrors.reason(error, "Fallback", logs::add))
        assertEquals(listOf("failure_class=java.lang.IllegalStateException"), logs)
        val empty = IllegalStateException(" ", error)
        assertEquals("Fallback", PatchErrors.reason(empty, "Fallback") {})
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
        assertEquals("Patching failed. Try again with the stock APK.", PatchJobStore.decode(legacy).message)
    }

}
