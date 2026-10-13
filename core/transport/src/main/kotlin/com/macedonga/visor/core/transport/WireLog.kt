package com.macedonga.visor.core.transport

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * Every SDU in both directions, so a session that misbehaves can be diffed against one that did
 * not.
 */
fun interface WireLog {

    /** [outbound] is true for a frame the phone sent. */
    fun trace(outbound: Boolean, frame: ByteArray)
}

/** A [WireLog] that appends `direction timestamp length hex` to a file, one line per SDU. */
class FileWireLog(file: File) : WireLog, Closeable {

    // Starts over past 64 MB: one imported video is about 35 MB of hex.
    private val writer = PrintWriter(BufferedWriter(FileWriter(file, file.length() < 64L shl 20), 1 shl 16))
    private var flushedAt = 0L

    // It runs on the link's read path, so it formats by hand and flushes at most four times a second.
    override fun trace(outbound: Boolean, frame: ByteArray) {
        val now = System.currentTimeMillis()
        writer.append(if (outbound) "tx " else "rx ")
            .append((now / 1000).toString()).append('.').append((now % 1000).toString().padStart(3, '0'))
            .append(' ').append(frame.size.toString()).append(' ').append(frame.toHex()).append('\n')
        if (now - flushedAt >= 250) {
            writer.flush()
            flushedAt = now
        }
    }

    override fun close() {
        writer.close()
    }
}

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        out[i * 2] = HEX[b ushr 4]
        out[i * 2 + 1] = HEX[b and 0x0F]
    }
    return String(out)
}
