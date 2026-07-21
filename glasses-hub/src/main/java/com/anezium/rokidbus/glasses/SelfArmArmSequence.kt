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
    private const val MAX_TRANSPORT_RETRIES_PER_STEP = 2

    fun run(
        initialSession: SelfArmShellSession,
        watchdogScript: String,
        bridgeScript: String,
        restartWatchdog: Boolean,
        operations: SelfArmSequenceOperations,
    ): SelfArmSequenceResult {
        val activeSession = ActiveSession(initialSession)
        var commandDispatched = false
        var restartedAdbd = false
        val outputs = mutableListOf<String>()
        try {
            commandDispatched = true
            val prepareCommand = SelfArmSessionCommand.buildPrepare(watchdogScript, bridgeScript)
            val classicArmCommand = if (
                activeSession.value.transport == SelfArmShellTransport.CLASSIC_LOOPBACK
            ) {
                SelfArmSessionCommand.buildArm(restartWatchdog)
            } else {
                ""
            }
            val prepare = executeStep(
                step = "prepare",
                activeSession = activeSession,
                command = prepareCommand + classicArmCommand,
                operations = operations,
            )
            outputs += prepare.output
            if (prepare.exitCode != 0 || !SelfArmSessionCommand.prepareSucceeded(prepare.output)) {
                throw SelfArmSequenceException(
                    "prepare: self-arm preparation failed: ${allOutput(prepare)}",
                    commandDispatched = true,
                )
            }

            var arm = prepare
            if (classicArmCommand.isNotEmpty()) {
                if (!SelfArmSessionCommand.armSucceeded(prepare.output)) {
                    throw SelfArmSequenceException(
                        "arm: classic watchdog arm failed before network teardown: ${allOutput(prepare)}",
                        commandDispatched = true,
                    )
                }
            } else {
                arm = executeStep(
                    step = "arm",
                    activeSession = activeSession,
                    command = SelfArmSessionCommand.buildArm(restartWatchdog),
                    operations = operations,
                )
                outputs += arm.output
                if (arm.exitCode != 0 || !SelfArmSessionCommand.armSucceeded(arm.output)) {
                    throw SelfArmSequenceException(
                        "arm: watchdog arm failed before network teardown: ${allOutput(arm)}",
                        commandDispatched = true,
                    )
                }
            }
            operations.log("watchdog running=yes before network teardown")

            var posture = operations.awaitRestartDecision()
            operations.log("self-arm network decision=${posture.teardownDecision()}")
            if (posture.teardownDecision() == SelfArmNetworkPosture.TeardownDecision.RESTART_ADBD) {
                val previousPort = activeSession.value.port
                val restart = try {
                    activeSession.value.shell(SelfArmSessionCommand.buildRestartAdbd())
                } catch (exception: IOException) {
                    restartedAdbd = true
                    operations.log("adbd restart stream died; assuming restart and reconnecting")
                    runCatching { activeSession.value.close() }
                    activeSession.value = reconnect(
                        operations = operations,
                        oldAdbdPid = null,
                        preferredPort = previousPort,
                    )
                    operations.log("self-arm adbd reconnected port=${activeSession.value.port}")
                    null
                }
                if (restart != null) {
                    outputs += restart.output
                    val request = if (restart.exitCode == 0) {
                        SelfArmSessionCommand.restartRequest(restart.output)
                    } else {
                        null
                    }
                    if (request == null) {
                        throw SelfArmSequenceException(
                            "adbd-restart: adbd restart was not scheduled: ${allOutput(restart)}",
                            commandDispatched = true,
                        )
                    }
                    restartedAdbd = true
                    operations.log("self-arm adbd restart scheduled oldPid=${request.oldAdbdPid}")
                    activeSession.value.close()
                    activeSession.value = reconnect(
                        operations = operations,
                        oldAdbdPid = request.oldAdbdPid,
                        preferredPort = previousPort,
                    )
                    operations.log("self-arm adbd reconnected port=${activeSession.value.port}")
                }
            } else if (activeSession.value.transport == SelfArmShellTransport.CLASSIC_LOOPBACK) {
                activeSession.value.close()
                activeSession.value = reconnect(
                    operations = operations,
                    oldAdbdPid = null,
                    preferredPort = null,
                )
                operations.log(
                    "self-arm switched from classic ADB to TLS port=${activeSession.value.port}",
                )
            }

            if (restartedAdbd) {
                arm = executeStep(
                    step = "post-restart arm",
                    activeSession = activeSession,
                    command = SelfArmSessionCommand.buildArm(restartWatchdog),
                    operations = operations,
                )
                outputs += arm.output
                if (arm.exitCode != 0 || !SelfArmSessionCommand.armSucceeded(arm.output)) {
                    throw SelfArmSequenceException(
                        "post-restart arm: post-restart watchdog arm failed: ${allOutput(arm)}",
                        commandDispatched = true,
                    )
                }
                operations.log("post-restart watchdog running=yes")
            }
            posture = operations.awaitSafe()
            return SelfArmSequenceResult(
                port = activeSession.value.port,
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
            runCatching { activeSession.value.close() }
        }
    }

    private fun executeStep(
        step: String,
        activeSession: ActiveSession,
        command: String,
        operations: SelfArmSequenceOperations,
    ): SelfArmShellResult {
        var transportRetries = 0
        while (true) {
            try {
                return activeSession.value.shell(command)
            } catch (exception: IOException) {
                if (transportRetries >= MAX_TRANSPORT_RETRIES_PER_STEP) {
                    throw SelfArmSequenceException(
                        "$step: ${failureMessage(exception, "ADB shell transport failed")}",
                        exception,
                        commandDispatched = true,
                    )
                }
                val previousPort = activeSession.value.port
                runCatching { activeSession.value.close() }
                transportRetries += 1
                operations.log(
                    "$step stream died; reconnecting " +
                        "($transportRetries/$MAX_TRANSPORT_RETRIES_PER_STEP)",
                )
                activeSession.value = reconnect(
                    operations = operations,
                    oldAdbdPid = null,
                    preferredPort = previousPort,
                )
            }
        }
    }

    private fun reconnect(
        operations: SelfArmSequenceOperations,
        oldAdbdPid: String?,
        preferredPort: Int?,
    ): SelfArmShellSession = try {
        operations.reconnectTls(oldAdbdPid, preferredPort)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw SelfArmSequenceException(
            "reconnect: ${failureMessage(exception, "TLS reconnect was interrupted")}",
            exception,
            commandDispatched = true,
        )
    } catch (exception: Exception) {
        throw SelfArmSequenceException(
            "reconnect: ${failureMessage(exception, "TLS reconnect failed")}",
            exception,
            commandDispatched = true,
        )
    }

    private fun failureMessage(exception: Exception, fallback: String): String =
        exception.message.orEmpty().trim().ifBlank { fallback }

    private fun allOutput(result: SelfArmShellResult): String =
        (result.errorOutput + result.output).trim().take(500)

    private class ActiveSession(
        var value: SelfArmShellSession,
    )
}
