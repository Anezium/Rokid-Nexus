package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
    fun prepareStreamDeathReconnectsAndRetriesTheSameStep() {
        val events = mutableListOf<String>()
        val reconnects = mutableListOf<Pair<String?, Int?>>()
        val initial = FakeSession(
            port = 37201,
            transport = SelfArmShellTransport.PAIRED_TLS,
            events = events,
            transportFailures = mutableMapOf("prepare" to 1),
        )
        val reconnected = FakeSession(37201, SelfArmShellTransport.PAIRED_TLS, events)

        val result = SelfArmArmSequence.run(
            initialSession = initial,
            watchdogScript = "script",
            bridgeScript = "bridge",
            restartWatchdog = false,
            operations = operations(
                posture = SAFE,
                events = events,
                reconnect = { oldPid, preferredPort ->
                    reconnects += oldPid to preferredPort
                    events += "reconnect:${oldPid ?: "none"}:$preferredPort"
                    reconnected
                },
            ),
        )

        assertFalse(result.restartedAdbd)
        assertEquals(listOf<Pair<String?, Int?>>(null to 37201), reconnects)
        assertEquals(
            listOf(
                "prepare",
                "close:37201",
                "reconnect:none:37201",
                "prepare",
                "arm:start",
                "decision",
                "safe",
                "close:37201",
            ),
            events,
        )
    }

    @Test
    fun restartStreamDeathAssumesDispatchReconnectsAndRearms() {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val reconnects = mutableListOf<Pair<String?, Int?>>()
        val initial = FakeSession(
            port = 37201,
            transport = SelfArmShellTransport.PAIRED_TLS,
            events = events,
            transportFailures = mutableMapOf("restart" to 1),
        )
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
                    reconnects += oldPid to preferredPort
                    events += "reconnect:${oldPid ?: "none"}:$preferredPort"
                    reconnected
                },
                logs = logs,
            ),
        )

        assertTrue(result.restartedAdbd)
        assertEquals(listOf<Pair<String?, Int?>>(null to 37201), reconnects)
        assertTrue(logs.contains("adbd restart stream died; assuming restart and reconnecting"))
        assertEquals(
            listOf(
                "prepare",
                "arm:restart",
                "decision",
                "restart",
                "close:37201",
                "reconnect:none:37201",
                "arm:restart",
                "safe",
                "close:37201",
            ),
            events,
        )
    }

    @Test
    fun prepareTransportFailuresBeyondRetryCapAreStepTagged() {
        val events = mutableListOf<String>()
        val reconnects = mutableListOf<Pair<String?, Int?>>()
        val sessions = ArrayDeque(
            listOf(
                FakeSession(
                    37201,
                    SelfArmShellTransport.PAIRED_TLS,
                    events,
                    mutableMapOf("prepare" to 1),
                ),
                FakeSession(
                    37201,
                    SelfArmShellTransport.PAIRED_TLS,
                    events,
                    mutableMapOf("prepare" to 1),
                ),
            ),
        )
        val initial = FakeSession(
            37201,
            SelfArmShellTransport.PAIRED_TLS,
            events,
            mutableMapOf("prepare" to 1),
        )

        val exception = expectSequenceException {
            SelfArmArmSequence.run(
                initialSession = initial,
                watchdogScript = "script",
                bridgeScript = "bridge",
                restartWatchdog = false,
                operations = operations(
                    posture = SAFE,
                    events = events,
                    reconnect = { oldPid, preferredPort ->
                        reconnects += oldPid to preferredPort
                        sessions.removeFirst()
                    },
                ),
            )
        }

        assertTrue(exception.message.orEmpty().startsWith("prepare: "))
        assertTrue(exception.message.orEmpty().contains("ADB stream is closed"))
        assertEquals(
            listOf<Pair<String?, Int?>>(null to 37201, null to 37201),
            reconnects,
        )
    }

    @Test
    fun reconnectFailureIsStepTagged() {
        val events = mutableListOf<String>()
        val initial = FakeSession(
            port = 37201,
            transport = SelfArmShellTransport.PAIRED_TLS,
            events = events,
            transportFailures = mutableMapOf("prepare" to 1),
        )

        val exception = expectSequenceException {
            SelfArmArmSequence.run(
                initialSession = initial,
                watchdogScript = "script",
                bridgeScript = "bridge",
                restartWatchdog = false,
                operations = operations(
                    posture = SAFE,
                    events = events,
                    reconnect = { _, _ -> throw IOException("TLS unavailable") },
                ),
            )
        }

        assertTrue(exception.message.orEmpty().startsWith("reconnect: "))
    }

    @Test
    fun noExceptionPathPreservesExactCommandsAndReconnectArguments() {
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
        assertEquals(listOf<Pair<String?, Int?>>("101" to 37201), reconnects)
    }

    private fun operations(
        posture: SelfArmNetworkPosture,
        events: MutableList<String>,
        reconnect: (String?, Int?) -> SelfArmShellSession,
        logs: MutableList<String>? = null,
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
        log = { message -> logs?.add(message) },
    )

    private fun expectSequenceException(block: () -> Unit): SelfArmSequenceException = try {
        block()
        throw AssertionError("Expected SelfArmSequenceException")
    } catch (exception: SelfArmSequenceException) {
        exception
    }

    private class FakeSession(
        override val port: Int,
        override val transport: SelfArmShellTransport,
        private val events: MutableList<String>,
        private val transportFailures: MutableMap<String, Int> = mutableMapOf(),
        private val commands: MutableList<String>? = null,
    ) : SelfArmShellSession {
        override fun shell(command: String): SelfArmShellResult {
            commands?.add(command)
            return when {
                command.contains("ROKID_NEXUS_PREPARE_RESULT") -> {
                    events += "prepare"
                    failTransportIfNeeded("prepare")
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
                    failTransportIfNeeded("restart")
                    SelfArmShellResult(0, RESTART_OK)
                }
                command.contains("sh \"\$WATCHDOG\" restart") -> {
                    events += "arm:restart"
                    failTransportIfNeeded("arm:restart")
                    SelfArmShellResult(0, ARM_OK)
                }
                command.contains("sh \"\$WATCHDOG\" start") -> {
                    events += "arm:start"
                    failTransportIfNeeded("arm:start")
                    SelfArmShellResult(0, ARM_OK)
                }
                else -> error("Unexpected command: $command")
            }
        }

        private fun failTransportIfNeeded(step: String) {
            val remaining = transportFailures[step] ?: 0
            if (remaining <= 0) return
            transportFailures[step] = remaining - 1
            throw IOException("ADB stream is closed for local id 7")
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
