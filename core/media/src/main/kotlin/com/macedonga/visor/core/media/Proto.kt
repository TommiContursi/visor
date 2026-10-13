package com.macedonga.visor.core.media

import java.io.ByteArrayOutputStream

/** Just enough protobuf for airship and svc10. A zero varint is not written, as the firmware does. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
        if (value != 0L) {
            raw((field shl 3).toLong())
            raw(value)
        }
    }

    fun bytes(field: Int, value: ByteArray) = apply {
        raw(((field shl 3) or 2).toLong())
        raw(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray())

    fun build(): ByteArray = out.toByteArray()

    private fun raw(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }
}

/** A protobuf read flat: field number to its values, each a Long or a ByteArray. */
internal fun fields(buf: ByteArray): Map<Int, List<Any>> {
    val out = HashMap<Int, MutableList<Any>>()
    var i = 0
    fun varint(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val b = buf[i++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) return v
        }
    }
    while (i < buf.size) {
        val tag = varint()
        val value: Any = when ((tag and 7).toInt()) {
            0 -> varint()
            2 -> {
                val n = varint().toInt()
                buf.copyOfRange(i, i + n).also { i += n }
            }
            1 -> { i += 8; continue }
            5 -> { i += 4; continue }
            else -> break
        }
        out.getOrPut((tag ushr 3).toInt()) { mutableListOf() }.add(value)
    }
    return out
}

internal fun Map<Int, List<Any>>.long(field: Int): Long = this[field]?.firstOrNull() as? Long ?: 0L

internal fun Map<Int, List<Any>>.bytes(field: Int): ByteArray = this[field]?.firstOrNull() as? ByteArray ?: ByteArray(0)
