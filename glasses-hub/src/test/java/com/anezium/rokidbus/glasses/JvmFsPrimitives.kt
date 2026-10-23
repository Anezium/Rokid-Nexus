package com.anezium.rokidbus.glasses

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Host stand-in for [AndroidFsPrimitives] with the same exclusive-create semantics, plus hooks
 * that run immediately before each primitive so tests can swap entries at the final boundary.
 */
internal class JvmFsPrimitives : GlassesFsPrimitives {
    var beforeRename: (from: File, to: File) -> Unit = { _, _ -> }
    var beforeCreate: (File) -> Unit = {}
    var failWritesAfterBytes: Long = Long.MAX_VALUE

    /** Overrides inode identity; null compares the bytes written through the handle. */
    var reportSameFile: Boolean? = null

    override fun rename(from: File, to: File): Boolean {
        beforeRename(from, to)
        return from.renameTo(to)
    }

    override fun createExclusive(file: File): GlassesExclusiveFile? {
        beforeCreate(file)
        val stream = try {
            Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            return null
        }
        val limit = failWritesAfterBytes
        return object : GlassesExclusiveFile {
            private val written = ByteArrayOutputStream()

            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written.size() + len > limit) throw IOException("ENOSPC")
                    stream.write(b, off, len)
                    written.write(b, off, len)
                }

                override fun flush() = stream.flush()

                override fun close() = stream.close()
            }

            override fun sync() = Unit

            override fun isSameFileAs(file: File): Boolean =
                reportSameFile ?: (file.isFile && file.readBytes().contentEquals(written.toByteArray()))

            override fun close() {
                runCatching { output.close() }
            }
        }
    }
}
