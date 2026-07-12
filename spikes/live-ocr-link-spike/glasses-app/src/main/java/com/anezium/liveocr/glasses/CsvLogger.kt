package com.anezium.liveocr.glasses

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

internal class CsvLogger(context: Context, filename: String, header: String) : AutoCloseable {
    val file: File = File(requireNotNull(context.getExternalFilesDir(null)), filename)
    private val writer = BufferedWriter(FileWriter(file, false))
    init { writer.write(header); writer.newLine(); writer.flush() }
    @Synchronized fun append(line: String) { writer.write(line); writer.newLine(); writer.flush() }
    @Synchronized override fun close() = writer.close()
}
