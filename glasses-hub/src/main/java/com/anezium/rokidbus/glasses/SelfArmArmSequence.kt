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
        bridgeScript: String,
        restartWatchdog: Boolean,
        operations: SelfArmSequenceOperations,
    ): SelfArmSequenceResult {
        var session = initialSession
        var commandDispatched = false
        var restartedAdbd = false
        val outputs = mutableListOf<String>()
        try {
            commandDispatched = true
            val prepareCommand = SelfArmSessionCommand.buildPrepare(watchdogScript, bridgeScript)
            val classicArmCommand = if (session.transport == SelfArmShellTransport.CLASSIC_LOOPBACK) {
                SelfArmSessionCommand.buildArm(restartWatchdog)
            } else {
                ""
            }
            val prepareStep = executeStepWithTransportRetry(
                step = "prepare",
                initialSession = session,
                command = prepareCommand + classicArmCommand,
                operations = operations,
            )
            session = prepareStep.session
            val prepare = prepareStep.result
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
                val armStep = executeStepWithTransportRetry(
                    step = "arm",
                    initialSession = session,
                    command = SelfArmSessionCommand.buildArm(restartWatchdog),
                    operations = operations,
                )
                session = armStep.session
                arm = armStep.result
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
                val previousPort = session.port
                val restart = try {
                    session.shell(SelfArmSessionCommand.buildRestartAdbd())
                } catch (exception: IOException) {
                    restartedAdbd = true
                    operations.log("adbd restart stream died; assuming restart and reconnecting")
                    runCatching { session.close() }
                    session = reconnectTls(
                        operations = operations,
                        oldAdbdPid = null,
                        preferredPort = previousPort,
                        context = "after adbd restart stream failure",
                    )
                    null
                } catch (exception: Exception) {
                    throw stepException("adbd-restart", exception)
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
                    runCatching { session.close() }
                    session = reconnectTls(
                        operations = operations,
                        oldAdbdPid = request.oldAdbdPid,
                        preferredPort = previousPort,
                        context = "after scheduled adbd restart",
                    )
                }
                operations.log("self-arm adbd reconnected port=${session.port}")
            } else if (session.transport == SelfArmShellTransport.CLASSIC_LOOPBACK) {
                runCatching { session.close() }
                session = reconnectTls(
                    operations = operations,
                    oldAdbdPid = null,
                    preferredPort = null,
                    context = "while switching from classic ADB",
                )
                operations.log("self-arm switched from classic ADB to TLS port=${session.port}")
            }

            if (restartedAdbd) {
                val postRestartArmStep = executeStepWithTransportRetry(
                    step = "post-restart arm",
                    initialSession = session,
                    command = SelfArmSessionCommand.buildArm(restartWatchdog),
                    operations = operations,
                )
                session = postRestartArmStep.session
                arm = postRestartArmStep.result
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

    private fun executeStepWithTransportRetry(
        step: String,
        initialSession: SelfArmShellSession,
        command: String,
        operations: SelfArmSequenceOperations,
    ): StepExecution {
        var session = initialSession
        var transportRetries = 0
        while (true) {
            try {
                return StepExecution(session, session.shell(command))
            } catch (exception: IOException) {
                runCatching { session.close() }
                if (transportRetries >= MAX_TRANSPORT_RETRIES_PER_STEP) {
                    throw SelfArmSequenceException(
                        "$step: shell transport failed after $transportRetries retries: " +
                            failureMessage(exception),
                        exception,
                        commandDispatched = true,
                    )
                }
                transportRetries += 1
                val previousPort = session.port
                operations.log(
                    "$step stream died; reconnecting " +
                        "retry=$transportRetries/$MAX_TRANSPORT_RETRIES_PER_STEP",
                )
                session = reconnectTls(
                    operations = operations,
                    oldAdbdPid = null,
                    preferredPort = previousPort,
                    context = "while retrying $step",
                )
            } catch (exception: Exception) {
                throw stepException(step, exception)
            }
        }
    }

    private fun reconnectTls(
        operations: SelfArmSequenceOperations,
        oldAdbdPid: String?,
        preferredPort: Int?,
        context: String,
    ): SelfArmShellSession = try {
        operations.reconnectTls(oldAdbdPid, preferredPort)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw SelfArmSequenceException(
            "reconnect: $context was interrupted",
            exception,
            commandDispatched = true,
        )
    } catch (exception: Exception) {
        throw SelfArmSequenceException(
            "reconnect: $context failed: ${failureMessage(exception)}",
            exception,
            commandDispatched = true,
        )
    }

    private fun stepException(step: String, exception: Exception): SelfArmSequenceException {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        return SelfArmSequenceException(
            "$step: ${failureMessage(exception)}",
            exception,
            commandDispatched = true,
        )
    }

    private fun failureMessage(exception: Throwable): String =
        exception.message.orEmpty().ifBlank { exception::class.java.simpleName }

    private fun allOutput(result: SelfArmShellResult): String =
        (result.errorOutput + result.output).trim().take(500)

    private data class StepExecution(
        val session: SelfArmShellSession,
        val result: SelfArmShellResult,
    )

    private const val MAX_TRANSPORT_RETRIES_PER_STEP = 2
}
