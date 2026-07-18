package com.anezium.rokidbus.glasses

internal class ClassicAdbShellSession(
    private val key: AdbKeyMaterial,
) : SelfArmShellSession {
    override val port: Int = 5555
    override val transport: SelfArmShellTransport = SelfArmShellTransport.CLASSIC_LOOPBACK

    override fun shell(command: String): SelfArmShellResult {
        val result = AdbLoopbackClient(port = port).runShell(command, key)
        return SelfArmShellResult(
            exitCode = if (result.authenticated && result.commandSent) 0 else 1,
            output = result.output,
        )
    }

    override fun close() = Unit
}
