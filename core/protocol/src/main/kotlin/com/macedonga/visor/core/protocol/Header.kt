package com.macedonga.visor.core.protocol

/** DataX 4-byte fixed header plus extension-tag codec (notes/66). */
object Header {

    private const val TAG_SERVICE_ID = 1
    private const val TAG_MESSAGE_TYPE = 2
    private const val TAG_ERROR_ID = 3
    private const val TAG_MESSAGE_TYPE_CLOSED = 4
    private const val TAG_QOS = 5

    /** Parse a DataX wire header from [buf]. */
    fun parse(buf: ByteArray): MessageInfo {
        if (buf.size < 4) {
            throw ProtocolFormatException("Buffer too short for fixed header: ${buf.size} < 4")
        }

        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xFF
        val b2 = buf[2].toInt() and 0xFF
        val b3 = buf[3].toInt() and 0xFF

        val hasExtension = (b0 shr 7) and 1
        val payloadLen = ((b0 and 0x3F) shl 8) or b1
        val channelId = (b2 shl 8) or b3

        var serviceId = 0
        var messageType = 0
        var errorId = 0
        var qosPriority = 0
        var qosExperienceTag = 0

        // Flag bits, mirroring the native struct param_1[0].
        var valid = 1
        val complete = if ((buf.size - 4) >= payloadLen) 1 else 0
        var setService = 0
        var setType = 0
        var hasError = 0
        var isClosed = 0
        var hasQos = 0

        var headerLen = 4

        if (hasExtension == 1) {
            var offset = 4
            val available = minOf(buf.size, 4 + payloadLen)
            // Python uses `while ...
            var sawLastTag = false
            while (offset + 4 <= available) {
                val tagByte = buf[offset].toInt() and 0xFF
                val d1 = buf[offset + 1].toInt() and 0xFF
                val d2 = buf[offset + 2].toInt() and 0xFF
                val d3 = buf[offset + 3].toInt() and 0xFF

                val continuation = (tagByte shr 7) and 1
                val tagId = tagByte and 0x7F

                // 16-bit big-endian value from bytes 2 and 3 of the tag.
                val value16 = (d2 shl 8) or d3

                when (tagId) {
                    TAG_SERVICE_ID -> {
                        serviceId = value16
                        if (value16 == 0) {
                            setService = 1
                            setType = 1
                            isClosed = 1
                        } else {
                            setService = 1
                            setType = 1
                        }
                    }

                    TAG_MESSAGE_TYPE -> {
                        messageType = value16
                        setType = 1
                    }

                    TAG_ERROR_ID -> {
                        errorId = value16
                        hasError = 1
                    }

                    TAG_MESSAGE_TYPE_CLOSED -> {
                        messageType = value16
                        isClosed = 1
                    }

                    TAG_QOS -> {
                        qosPriority = d1
                        qosExperienceTag = value16
                        hasQos = 1
                    }
                    // else: unknown tag, skip
                }

                headerLen += 4
                offset += 4

                if (continuation == 0) {
                    sawLastTag = true
                    break
                }
            }
            if (!sawLastTag) {
                // Ran out of data while parsing extensions.
                valid = 0
            }
        }

        return MessageInfo(
            flags = HeaderFlags(
                valid = valid == 1,
                complete = complete == 1,
                hasExtension = hasExtension == 1,
                setService = setService == 1,
                setType = setType == 1,
                hasError = hasError == 1,
                isClosed = isClosed == 1,
                hasQos = hasQos == 1,
            ),
            length = payloadLen,
            channelId = channelId,
            serviceId = serviceId,
            messageType = messageType,
            errorId = errorId,
            qosPriority = qosPriority,
            qosExperienceTag = qosExperienceTag,
            headerLen = headerLen,
        )
    }

    /** Serialise a DataX header: the fixed 4 bytes plus extension tags. */
    fun build(
        channelId: Int,
        dataLen: Int,
        serviceId: Int? = null,
        messageType: Int? = null,
        errorId: Int? = null,
        qosPriority: Int? = null,
        qosExperienceTag: Int? = null,
    ): ByteArray {
        // (tagId, d1, value16), in order.
        val tags = ArrayList<Triple<Int, Int, Int>>(4)

        if (serviceId != null) tags.add(Triple(TAG_SERVICE_ID, 0, serviceId and 0xFFFF))
        if (messageType != null) tags.add(Triple(TAG_MESSAGE_TYPE, 0, messageType and 0xFFFF))
        if (errorId != null) tags.add(Triple(TAG_ERROR_ID, 0, errorId and 0xFFFF))
        if (qosPriority != null || qosExperienceTag != null) {
            tags.add(
                Triple(
                    TAG_QOS,
                    (qosPriority ?: 0) and 0xFF,
                    (qosExperienceTag ?: 0) and 0xFFFF,
                )
            )
        }

        val extLen = tags.size * 4
        val payloadLen = extLen + dataLen

        require(payloadLen <= DATAX_MAX_MESSAGE) {
            "payload_len $payloadLen exceeds 14-bit max ($DATAX_MAX_MESSAGE)"
        }

        val hasExtension = if (tags.isNotEmpty()) 1 else 0

        val out = ArrayList<Byte>(4 + extLen)
        out.add(((hasExtension shl 7) or ((payloadLen shr 8) and 0x3F)).toByte())
        out.add((payloadLen and 0xFF).toByte())
        out.add(((channelId shr 8) and 0xFF).toByte())
        out.add((channelId and 0xFF).toByte())

        // Continuation bit on all but the last.
        tags.forEachIndexed { i, (tagId, d1, value16) ->
            val continuation = if (i < tags.size - 1) 1 else 0
            out.add(((continuation shl 7) or (tagId and 0x7F)).toByte())
            out.add((d1 and 0xFF).toByte())
            out.add(((value16 shr 8) and 0xFF).toByte())
            out.add((value16 and 0xFF).toByte())
        }

        return out.toByteArray()
    }
}

/** Per-message flag set mirroring the native MessageInfo bitfield. */
data class HeaderFlags(
    val valid: Boolean = true,
    val complete: Boolean = true,
    val hasExtension: Boolean = false,
    val setService: Boolean = false,
    val setType: Boolean = false,
    val hasError: Boolean = false,
    val isClosed: Boolean = false,
    val hasQos: Boolean = false,
)

/** A parsed DataX message header (notes/66). */
data class MessageInfo(
    val flags: HeaderFlags,
    /** The 14-bit payload length. It includes the extension tags. */
    val length: Int,
    /** The 16-bit channel id. Its high bit says who opened the channel. */
    val channelId: Int,
    val serviceId: Int = 0,
    val messageType: Int = 0,
    val errorId: Int = 0,
    val qosPriority: Int = 0,
    val qosExperienceTag: Int = 0,
    /** 4 plus four per extension tag. */
    val headerLen: Int = 4,
) {
    /** Bytes this message occupies on the wire. It is `4 + length`, never `headerLen + length`. */
    val wireTotal: Int get() = 4 + length

    /** Application payload bytes, with the extension tags taken out of [length]. */
    val dataLength: Int get() = length - (headerLen - 4)
}
