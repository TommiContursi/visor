package com.macedonga.visor.core.protocol

/** AirShield substream framing: the one-byte outer header on each CoC frame. */
object Framing {

    const val STREAM_MASK: Int = 0x3F
    const val TYPE_MASK: Int = 0xC0

    fun pack(frameType: FrameType, streamId: Int, body: ByteArray): ByteArray {
        require(streamId in 0..STREAM_MASK) { "stream_id $streamId out of range 0..$STREAM_MASK" }
        return byteArrayOf(((frameType.value and TYPE_MASK) or (streamId and STREAM_MASK)).toByte()) + body
    }

    fun unpack(data: ByteArray): Frame {
        if (data.isEmpty()) throw ProtocolFormatException("empty frame")
        val header = data[0].toInt() and 0xFF
        return Frame(
            type = header and TYPE_MASK,
            streamId = header and STREAM_MASK,
            body = data.copyOfRange(1, data.size),
        )
    }
}

/** One unpacked substream frame. */
class Frame(val type: Int, val streamId: Int, val body: ByteArray) {

    /** The enum for DATA and COMMAND, null for the two type-bit patterns nothing has pinned. */
    val frameType: FrameType? get() = FrameType.from(type)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && streamId == other.streamId && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + streamId
        result = 31 * result + body.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Frame(type=0x${type.toString(16)}, streamId=$streamId, body=${body.size} bytes)"
}
