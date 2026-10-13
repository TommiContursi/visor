package com.macedonga.visor.core.transport

/** How long the message starting at these bytes is. */
object Sdu {

    /** The DataX fixed header: two bytes of flags and length, two of channel id. */
    const val DATAX_HEADER: Int = 4

    /** The AirShield DATA header: the frame byte, an 8 byte tag, and the block count. */
    const val AIRSHIELD_HEADER: Int = 10

    const val AES_BLOCK: Int = 16

    /** Below this there is nothing to read the framing out of. */
    const val MIN_HEAD: Int = 2

    /** Set on the first byte of a DataX message, because it always carries a tag. */
    const val DATAX_BIT: Int = 0x80

    /** The AirShield DATA type bits, before the stream id is or-ed in. */
    const val AIRSHIELD_DATA_BITS: Int = 0x40

    const val TYPE_MASK: Int = 0xC0

    private const val LENGTH_HIGH_MASK: Int = 0x3F

    /**
     * The total length of the message starting at [head], or null if [head] is too short to say
     * yet.
     */
    fun length(head: ByteArray): Int? {
        if (head.size < MIN_HEAD) return null
        val first = head[0].toInt() and 0xFF
        if (first and DATAX_BIT != 0) {
            return DATAX_HEADER + (((first and LENGTH_HIGH_MASK) shl 8) or (head[1].toInt() and 0xFF))
        }
        if (first and TYPE_MASK == AIRSHIELD_DATA_BITS) {
            if (head.size < AIRSHIELD_HEADER) return null
            return AIRSHIELD_HEADER + AES_BLOCK * ((head[9].toInt() and 0xFF) + 1)
        }
        throw TransportUnframeableException(first)
    }
}
