package com.anezium.rokidbus.plugin.youtubepatcher

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/** Android 14 dynamic code: open the descriptor, remove write bits BEFORE writing,
 * then write through that descriptor. Never chmod an already-populated writable file. */
internal object ReadOnlyBundleFile {
    // The activity's screen lock serializes bundle access. Process death may bypass finally.
    fun cleanUnused(directory: File, activeName: String?) {
        directory.listFiles()?.filter {
            it.name.endsWith(".partial") || it.name == "active.tmp" ||
                (it.name.endsWith(".mpp") && it.name != activeName)
        }?.forEach { require(it.delete()) { "Cannot remove abandoned bundle file." } }
    }

    fun requireReadOnly(file: File) {
        val permissions = Files.getPosixFilePermissions(file.toPath())
        require(permissions.none { it in setOf(PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE) }) { "Executable bundle is writable." }
    }

    fun install(directory: File, checkCancelled: () -> Unit = {},
                write: (FileOutputStream) -> Unit, validate: (File) -> Unit): File {
        val temporary = File.createTempFile("bundle-", ".partial", directory)
        val target = File(directory, "bundle-${UUID.randomUUID()}.mpp")
        var accepted = false
        try {
            FileOutputStream(temporary).use { output ->
                require(temporary.setReadOnly()) { "Cannot protect executable bundle." }
                requireReadOnly(temporary)
                checkCancelled()
                write(output)
                output.fd.sync()
            }
            checkCancelled()
            requireReadOnly(temporary)
            validate(temporary)
            checkCancelled()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            requireReadOnly(target)
            checkCancelled()
            accepted = true
            return target
        } finally {
            temporary.delete()
            if (!accepted) target.delete()
        }
    }
}
