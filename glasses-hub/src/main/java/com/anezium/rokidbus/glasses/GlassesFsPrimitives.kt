package com.anezium.rokidbus.glasses

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** A file this process created exclusively, held open so every write lands in that very inode. */
internal interface GlassesExclusiveFile : Closeable {
    val output: OutputStream

    fun sync()

    /** True while [file] names the inode this handle created. */
    fun isSameFileAs(file: File): Boolean
}

/**
 * The filesystem calls the explorer's safety guarantees rest on.
 *
 * Shared storage on the glasses is FUSE: it implements neither `link()` nor a rename that refuses
 * an existing destination, and Android's public API has no type-specific `unlink`/`rmdir`. What
 * remains is an exclusive create, which is atomic, and rename, which the store only ever aims at
 * fresh private names.
 */
internal interface GlassesFsPrimitives {
    /** Creates [file] exclusively, or returns null when the name is already taken. */
    fun createExclusive(file: File): GlassesExclusiveFile?

    fun rename(from: File, to: File): Boolean
}

internal object AndroidFsPrimitives : GlassesFsPrimitives {
    override fun createExclusive(file: File): GlassesExclusiveFile? {
        val fd = try {
            Os.open(
                file.path,
                OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY or OsConstants.O_CLOEXEC,
                FILE_MODE,
            )
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EEXIST) return null
            throw IOException(error)
        }
        val created = try {
            Os.fstat(fd)
        } catch (error: ErrnoException) {
            runCatching { Os.close(fd) }
            throw IOException(error)
        }
        return object : GlassesExclusiveFile {
            override val output = FileOutputStream(fd)

            override fun sync() {
                try {
                    Os.fsync(fd)
                } catch (error: ErrnoException) {
                    throw IOException(error)
                }
            }

            override fun isSameFileAs(file: File): Boolean {
                val current = try {
                    Os.lstat(file.path)
                } catch (_: ErrnoException) {
                    return false
                }
                return current.st_ino == created.st_ino && current.st_dev == created.st_dev
            }

            override fun close() {
                // The stream owns the descriptor; closing it releases the fd exactly once.
                runCatching { output.close() }
            }
        }
    }

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)

    private const val FILE_MODE = 0b110_110_000 // 0660, as shared storage expects
}
