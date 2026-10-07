package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test

class PatchExecutionDiagnosticsTest {
    @Test fun schedulerValuesAreNumericOrAllowlistedAndMissingFilesStayUnknown() {
        assertEquals("/moderate", PatchExecutionDiagnostics.cpuset("/moderate\n"))
        assertEquals("unknown", PatchExecutionDiagnostics.cpuset("/user/private/account"))
        assertEquals("0-2", PatchExecutionDiagnostics.cpus("Name: private\nCpus_allowed_list:\t0-2\n"))
        assertEquals("unknown", PatchExecutionDiagnostics.cpus("Cpus_allowed_list: private/path"))
        assertEquals("unknown", PatchExecutionDiagnostics.cpus(null))
        assertEquals("unknown", PatchExecutionDiagnostics.cpuset(null))
    }
}
