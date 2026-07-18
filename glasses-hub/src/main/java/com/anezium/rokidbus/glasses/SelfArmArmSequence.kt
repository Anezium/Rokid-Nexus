package com.anezium.rokidbus.glasses

import java.io.Closeable
import java.io.IOException

internal enum class SelfArmShellTransport {
    PAIRED_TLS,
    CLASSIC_LOOPBACK,
}

internal data class SelfArmShellResult(
    val exitCode: Int,
    val output: String,
    val errorOutput: String = "",
)

internal interface SelfArmShellSession : Closeable {
    val port: Int
    val transport: SelfArmShellTransport

    fun shell(command: String): SelfArmShellResult
}

internal data class SelfArmSequenceResult(
    val port: Int,
    val restartedAdbd: Boolean,
    val output: String,
    val posture: SelfArmNetworkPosture,
)

internal class SelfArmSequenceException(
    message: String,
    cause: Throwable? = null,
    val commandDispatched: Boolean,
) : IOException(message, cause)

internal data class SelfArmSequenceOperations(
    val awaitRestartDecision: () -> SelfArmNetworkPosture,
    val reconnectTls: (oldAdbdPid: String?, preferredPort: Int?) -> SelfArmShellSession,
    val awaitSafe: () -> SelfArmNetworkPosture,
    val log: (String) -> Unit = {},
)

internal object SelfArmArmSequence {
    fun run(
        initialSession: SelfArmShellSession,
        watchdogScript: String,
        restartWatchdog: Boolean,
        operations: SelfArmSequenceOperations,
    ): SelfArmSequenceResult {
        var session = initialSession
        var commandDispatched = false
        var restartedAdbd = false
        val outputs = mutableListOf<String>()
        try {
            commandDispatched = true
            val prepareCommand = SelfArmSessionCommand.buildPrepare(watchdogScript)
            val classicArmCommand = if (session.transport == SelfArmShellTransport.CLASSIC_LOOPBACK) {
                SelfArmSessionCommand.buildArm(restartWatchdog)
            } else {
                ""
            }
            val prepare = session.shell(prepareCommand + classicArmCommand)
            outputs += prepare.output
            if (prepare.exitCode != 0 || !SelfArmSessionCommand.prepareSucceeded(prepare.output)) {
                throw SelfArmSequenceException(
                    "self-arm preparation failed: ${allOutput(prepare)}",
                    commandDispatched = true,
                )
            }

            var arm = prepare
            if (classicArmCommand.isNotEmpty()) {
                if (!SelfArmSessionCommand.armSucceeded(prepare.output)) {
                    throw SelfArmSequenceException(
                        "classic watchdog arm failed before network teardown: ${allOutput(prepare)}",
                        commandDispatched = true,
                    )
                }
            } else {
                arm = session.shell(SelfArmSessionCommand.buildArm(restartWatchdog))
                outputs += arm.output
                if (arm.exitCode != 0 || !SelfArmSessionCommand.armSucceeded(arm.output)) {
                    throw SelfArmSequenceException(
                        "watchdog arm failed before network teardown: ${allOutput(arm)}",
                        commandDispatched = true,
                    )
                }
            }
            operations.log("watchdog running=yes before network teardown")

            var posture = operations.awaitRestartDecision()
            operations.log("self-arm network decision=${posture.teardownDecision()}")
            if (posture.teardownDecision() == SelfArmNetworkPosture.TeardownDecision.RESTART_ADBD) {
                val restart = session.shell(SelfArmSessionCommand.buildRestartAdbd())
                outputs += restart.output
                val request = if (restart.exitCode == 0) {
                    SelfArmSessionCommand.restartRequest(restart.output)
                } else {
                    null
                }
                if (request == null) {
                    throw SelfArmSequenceException(
                        "adbd restart was not scheduled: ${allOutput(restart)}",
                        commandDispatched = true,
                    )
                }
                restartedAdbd = true
                val previousPort = session.port
                operations.log("self-arm adbd restart scheduled oldPid=${request.oldAdbdPid}")
                session.close()
                session = operations.reconnectTls(request.oldAdbdPid, previousPort)
                operations.log("self-arm adbd reconnected port=${session.port}")
            } else if (session.transport == SelfArmShellTransport.CLASSIC_LOOPBACK) {
                session.close()
                session = operations.reconnectTls(null, null)
                operations.log("self-arm switched from classic ADB to TLS port=${session.port}")
            }

            if (restartedAdbd) {
                arm = session.shell(SelfArmSessionCommand.buildArm(restartWatchdog))
                outputs += arm.output
                if (arm.exitCode != 0 || !SelfArmSessionCommand.armSucceeded(arm.output)) {
                    throw SelfArmSequenceException(
                        "post-restart watchdog arm failed: ${allOutput(arm)}",
                        commandDispatched = true,
                    )
                }
                operations.log("post-restart watchdog running=yes")
            }
            posture = operations.awaitSafe()
            return SelfArmSequenceResult(
                port = session.port,
                restartedAdbd = restartedAdbd,
                output = outputs.joinToString("\n"),
                posture = posture,
            )
        } catch (exception: SelfArmSequenceException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SelfArmSequenceException(
                "self-arm sequence was interrupted",
                exception,
                commandDispatched,
            )
        } catch (exception: Exception) {
            throw SelfArmSequenceException(
                exception.message.orEmpty().ifBlank { "self-arm sequence failed" },
                exception,
                commandDispatched,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun allOutput(result: SelfArmShellResult): String =
        (result.errorOutput + result.output).trim().take(500)
}
