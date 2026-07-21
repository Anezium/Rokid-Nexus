package com.anezium.rokidbus.glasses

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelfArmArmSequenceTest {
    @Test
    fun safePostureArmsWithoutRestartingAdbd() {
        val events = mutableListOf<String>()
        val session = FakeSession(37201, SelfArmShellTransport.PAIRED_TLS, events)

        val result = SelfArmArmSequence.run(
            initialSession = session,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = false,
            operations = operations(
                posture = SAFE,
                events = events,
                reconnect = { _, _ -> error("reconnect should not run") },
            ),
        )

        assertFalse(result.restartedAdbd)
        assertEquals(37201, result.port)
        assertEquals(listOf("prepare", "arm:start", "decision", "safe", "close:37201"), events)
    }

    @Test
    fun lingeringListenerRestartsReconnectsThenArms() {
        val events = mutableListOf<String>()
        val initial = FakeSession(37201, SelfArmShellTransport.PAIRED_TLS, events)
        val reconnected = FakeSession(37201, SelfArmShellTransport.PAIRED_TLS, events)

        val result = SelfArmArmSequence.run(
            initialSession = initial,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = true,
            operations = operations(
                posture = RESTART,
                events = events,
                reconnect = { oldPid, preferredPort ->
                    events += "reconnect:$oldPid:$preferredPort"
                    reconnected
                },
            ),
        )

        assertTrue(result.restartedAdbd)
        assertEquals(37201, result.port)
        assertEquals(
            listOf(
                "prepare",
                "arm:restart",
                "decision",
                "restart",
                "close:37201",
                "reconnect:101:37201",
                "arm:restart",
                "safe",
                "close:37201",
            ),
            events,
        )
    }

    @Test
    fun classicSessionAlwaysFinishesOnTls() {
        val events = mutableListOf<String>()
        val classic = FakeSession(5555, SelfArmShellTransport.CLASSIC_LOOPBACK, events)
        val tls = FakeSession(37201, SelfArmShellTransport.PAIRED_TLS, events)

        val result = SelfArmArmSequence.run(
            initialSession = classic,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = false,
            operations = operations(
                posture = SAFE,
                events = events,
                reconnect = { oldPid, preferredPort ->
                    events += "reconnect:${oldPid ?: "none"}:${preferredPort ?: "none"}"
                    tls
                },
            ),
        )

        assertEquals(37201, result.port)
        assertEquals(
            listOf(
                "prepare",
                "arm:start",
                "decision",
                "close:5555",
                "reconnect:none:none",
                "safe",
                "close:37201",
            ),
            events,
        )
    }

    @Test
    fun prepareStreamDeathReconnectsAndRetriesOnce() {
        val events = mutableListOf<String>()
        val commands = mutableListOf<String>()
        var prepareFailed = false
        val initial = FakeSession(
            port = 37201,
            transport = SelfArmShellTransport.PAIRED_TLS,
            events = events,
            shellFailure = { command ->
                if (!prepareFailed && command.contains("ROKID_NEXUS_PREPARE_RESULT")) {
                    prepareFailed = true
                    IOException("ADB stream is closed for local id 1")
                } else {
                    null
                }
            },
            commands = commands,
        )
        val reconnected = FakeSession(
            37201,
            SelfArmShellTransport.PAIRED_TLS,
            events,
            commands = commands,
        )
        var reconnectCount = 0

        val result = SelfArmArmSequence.run(
            initialSession = initial,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = false,
            operations = operations(
                posture = SAFE,
                events = events,
                reconnect = { oldPid, preferredPort ->
                    reconnectCount += 1
                    assertEquals(null, oldPid)
                    assertEquals(37201, preferredPort)
                    reconnected
                },
            ),
        )

        assertFalse(result.restartedAdbd)
        assertEquals(1, reconnectCount)
        assertEquals(
            2,
            commands.count { it == SelfArmSessionCommand.buildPrepare("script", "bridge") },
        )
        assertTrue(events.contains("arm:start"))
    }

    @Test
    fun restartStreamDeathAssumesDispatchReconnectsAndFinishesArm() {
        val events = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val initial = FakeSession(
            port = 37201,
            transport = SelfArmShellTransport.PAIRED_TLS,
            events = events,
            shellFailure = { command ->
                if (command.contains("ROKID_NEXUS_ADBD_RESTART_RESULT")) {
                    IOException("ADB stream is closed for local id 2")
                } else {
                    null
                }
            },
            commands = commands,
        )
        val reconnected = FakeSession(
            37201,
            SelfArmShellTransport.PAIRED_TLS,
            events,
            commands = commands,
        )
        val reconnects = mutableListOf<Pair<String?, Int?>>()

        val result = SelfArmArmSequence.run(
            initialSession = initial,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = true,
            operations = operations(
                posture = RESTART,
                events = events,
                reconnect = { oldPid, preferredPort ->
                    reconnects += oldPid to preferredPort
                    reconnected
                },
                log = logs::add,
            ),
        )

        assertTrue(result.restartedAdbd)
        assertEquals(listOf(null to 37201), reconnects)
        assertEquals(2, commands.count { it == SelfArmSessionCommand.buildArm(true) })
        assertTrue(events.contains("safe"))
        assertTrue(logs.contains("adbd restart stream died; assuming restart and reconnecting"))
    }

    @Test
    fun prepareTransportFailuresBeyondRetryCapUseStepTaggedError() {
        val events = mutableListOf<String>()
        val commands = mutableListOf<String>()
        fun failingSession() = FakeSession(
            port = 37201,
            transport = SelfArmShellTransport.PAIRED_TLS,
            events = events,
            shellFailure = { command ->
                if (command.contains("ROKID_NEXUS_PREPARE_RESULT")) {
                    IOException("ADB stream is closed for local id 3")
                } else {
                    null
                }
            },
            commands = commands,
        )
        val replacements = listOf(failingSession(), failingSession())
        var reconnectCount = 0

        try {
            SelfArmArmSequence.run(
                initialSession = failingSession(),
                watchdogScript = "script",
                bridgeScript = "bridge",
                restartWatchdog = false,
                operations = operations(
                    posture = SAFE,
                    events = events,
                    reconnect = { oldPid, preferredPort ->
                        assertEquals(null, oldPid)
                        assertEquals(37201, preferredPort)
                        replacements[reconnectCount++]
                    },
                ),
            )
            fail("Expected prepare transport failure")
        } catch (exception: SelfArmSequenceException) {
            assertTrue(exception.message.orEmpty().startsWith("prepare: "))
            assertTrue(exception.message.orEmpty().contains("after 2 retries"))
        }

        assertEquals(2, reconnectCount)
        assertEquals(3, commands.size)
    }

    @Test
    fun noExceptionRestartPathPreservesExactCommandsAndReconnectArguments() {
        val events = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val reconnects = mutableListOf<Pair<String?, Int?>>()
        val initial = FakeSession(
            37201,
            SelfArmShellTransport.PAIRED_TLS,
            events,
            commands = commands,
        )
        val reconnected = FakeSession(
            37201,
            SelfArmShellTransport.PAIRED_TLS,
            events,
            commands = commands,
        )

        SelfArmArmSequence.run(
            initialSession = initial,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = true,
            operations = operations(
                posture = RESTART,
                events = events,
                reconnect = { oldPid, preferredPort ->
                    reconnects += oldPid to preferredPort
                    reconnected
                },
            ),
        )

        assertEquals(
            listOf(
                SelfArmSessionCommand.buildPrepare("script", "bridge"),
                SelfArmSessionCommand.buildArm(restartWatchdog = true),
                SelfArmSessionCommand.buildRestartAdbd(),
                SelfArmSessionCommand.buildArm(restartWatchdog = true),
            ),
            commands,
        )
        assertEquals(listOf("101" to 37201), reconnects)
    }

    private fun operations(
        posture: SelfArmNetworkPosture,
        events: MutableList<String>,
        reconnect: (String?, Int?) -> SelfArmShellSession,
        log: (String) -> Unit = {},
    ) = SelfArmSequenceOperations(
        awaitRestartDecision = {
            events += "decision"
            posture
        },
        reconnectTls = reconnect,
        awaitSafe = {
            events += "safe"
            SAFE
        },
        log = log,
    )

    private class FakeSession(
        override val port: Int,
        override val transport: SelfArmShellTransport,
        private val events: MutableList<String>,
        private val shellFailure: (String) -> IOException? = { null },
        private val commands: MutableList<String>? = null,
    ) : SelfArmShellSession {
        override fun shell(command: String): SelfArmShellResult {
            commands?.add(command)
            shellFailure(command)?.let { throw it }
            return when {
                command.contains("ROKID_NEXUS_PREPARE_RESULT") -> {
                    events += "prepare"
                    if (command.contains("sh \"\$WATCHDOG\" start")) {
                        events += "arm:start"
                        SelfArmShellResult(0, PREPARE_OK + ARM_OK)
                    } else if (command.contains("sh \"\$WATCHDOG\" restart")) {
                        events += "arm:restart"
                        SelfArmShellResult(0, PREPARE_OK + ARM_OK)
                    } else {
                        SelfArmShellResult(0, PREPARE_OK)
                    }
                }
                command.contains("ROKID_NEXUS_ADBD_RESTART_RESULT") -> {
                    events += "restart"
                    SelfArmShellResult(0, RESTART_OK)
                }
                command.contains("sh \"\$WATCHDOG\" restart") -> {
                    events += "arm:restart"
                    SelfArmShellResult(0, ARM_OK)
                }
                command.contains("sh \"\$WATCHDOG\" start") -> {
                    events += "arm:start"
                    SelfArmShellResult(0, ARM_OK)
                }
                else -> error("Unexpected command: $command")
            }
        }

        override fun close() {
            events += "close:$port"
        }
    }

    private companion object {
        val SAFE = SelfArmNetworkPosture(true, "-1", "-1", false)
        val RESTART = SelfArmNetworkPosture(true, "-1", "-1", true)
        const val PREPARE_OK =
            "ROKID_NEXUS_PREPARE_RESULT grant=1 a11y=1 service=1 script=1 bridge_script=1 " +
                "persist=-1 service_port=-1 legacy_tcp_disabled=1\n"
        const val RESTART_OK =
            "ROKID_NEXUS_ADBD_RESTART_RESULT scheduled=1 old_adbd_pid=101\n"
        const val ARM_OK =
            "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=1 bridge=1 " +
                "persist=-1 service_port=-1 legacy_tcp_disabled=1\n"
    }
}
