package com.macedonga.visor.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Raised when a frame's MAC does not verify, or when its padding is invalid. */
class AuthenticationException(message: String) : Exception(message)

/** Which way round the 32-bit sequence number goes into the MAC counter. */
enum class SeqEndian { LITTLE, BIG }

/** AirShield per-frame cipher: AES-256-CBC plus HMAC-SHA256, encrypt-then-MAC (notes/67). */
object AirShieldCipher {

    /** Truncated HMAC-SHA256. */
    const val TAG_SIZE: Int = 8

    /** AES block and CBC block. */
    const val BLOCK: Int = 16

    /** Modified PKCS#7: the pad byte is `pad_len + 0xC0`. */
    const val PAD_OFFSET: Int = 0xC0

    /** The constant 4-byte little-endian word ahead of the seq in the 8-byte MAC counter. */
    const val COUNTER_PREFIX: Int = 0x00000202

    /** Modified PKCS#7. An aligned input gets a whole block, so there is always a pad. */
    fun pad(data: ByteArray): ByteArray {
        val padLen = BLOCK - (data.size % BLOCK)   // always 1..16, a full block when aligned
        val marker = ((padLen + PAD_OFFSET) and 0xFF).toByte()
        return data + ByteArray(padLen) { marker }
    }

    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % BLOCK != 0) {
            throw AuthenticationException("ciphertext not block-aligned")
        }
        val last = data[data.size - 1].toInt() and 0xFF
        val padLen = (last + 0x40) and 0xFF        // inverse of +0xC0
        if (padLen !in 1..BLOCK || padLen > data.size) {
            throw AuthenticationException("invalid padding length")
        }
        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xFF) != last) {
                throw AuthenticationException("inconsistent padding bytes")
            }
        }
        return data.copyOfRange(0, data.size - padLen)
    }

    internal fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(data)
    }

    internal fun seqBytes(seq: Int, endian: SeqEndian): ByteArray {
        val order = if (endian == SeqEndian.LITTLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        return ByteBuffer.allocate(4).order(order).putInt(seq).array()
    }
}

/**
 * Stateful per-channel crypto: [seal] for tx and [unseal] for rx on AirShield DATA frame bodies.
 */
class FrameCrypto(
    keys: SessionKeys,
    private val seqEndian: SeqEndian = SeqEndian.LITTLE,
    private val chainIv: Boolean = true,
    private val counterPrefix: Int = AirShieldCipher.COUNTER_PREFIX,
    initTxSeq: Int = 0,
    initRxSeq: Int = 0,
) {
    private val aesKey: ByteArray
    private val hmacKey: ByteArray
    private var txIv: ByteArray
    private var rxIv: ByteArray
    private var txSeq: Int = initTxSeq
    private var rxSeq: Int = initRxSeq

    init {
        require(keys.aesKey.size == 32 && keys.hmacKey.size == 32 && keys.iv.size == AirShieldCipher.BLOCK) {
            "bad key/iv sizes for AES-256-CBC + HMAC-SHA256"
        }
        aesKey = keys.aesKey
        hmacKey = keys.hmacKey
        txIv = keys.iv
        rxIv = keys.iv
    }

    /**
     * A relayed stream's cipher: the SAME AES-256 and HMAC-SHA256 keys, the seq RESET to 0, and the
     * IV taken from this stream's current chained value.
     */
    fun forkStream(): FrameCrypto {
        val forked = FrameCrypto(
            SessionKeys(aesKey, hmacKey, txIv),
            seqEndian = seqEndian,
            chainIv = chainIv,
            counterPrefix = counterPrefix,
            initTxSeq = 0,
            initRxSeq = 0,
        )
        forked.txIv = txIv
        forked.rxIv = rxIv
        return forked
    }

    private fun mac(seq: Int, blockCount: Int, ciphertext: ByteArray): ByteArray {
        // 8-byte counter: the constant prefix as LE u32, then the seq, then blockCount and
        // the ciphertext.
        val ctr8 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(counterPrefix).array() +
            AirShieldCipher.seqBytes(seq, seqEndian)
        val macInput = ctr8 + byteArrayOf(blockCount.toByte()) + ciphertext
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return hmac.doFinal(macInput).copyOf(AirShieldCipher.TAG_SIZE)
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val ciphertext = AirShieldCipher.aesCbc(aesKey, txIv, AirShieldCipher.pad(plaintext), encrypt = true)
        val blockCount = (ciphertext.size / AirShieldCipher.BLOCK) - 1
        require(blockCount in 0..0xFF) { "plaintext too large for a single AirShield frame" }
        val tag = mac(txSeq, blockCount, ciphertext)
        val frame = tag + byteArrayOf(blockCount.toByte()) + ciphertext
        if (chainIv) {
            txIv = ciphertext.copyOfRange(ciphertext.size - AirShieldCipher.BLOCK, ciphertext.size)
        }
        txSeq += 1
        return frame
    }

    /** Verify and decrypt one DATA frame body. */
    fun unseal(frame: ByteArray, verifyUnpad: Boolean = true): ByteArray {
        if (frame.size < AirShieldCipher.TAG_SIZE + 1 + AirShieldCipher.BLOCK) {
            throw AuthenticationException("frame too short")
        }
        val tag = frame.copyOfRange(0, AirShieldCipher.TAG_SIZE)
        val blockCount = frame[AirShieldCipher.TAG_SIZE].toInt() and 0xFF
        val ciphertext = frame.copyOfRange(AirShieldCipher.TAG_SIZE + 1, frame.size)
        if (ciphertext.size != (blockCount + 1) * AirShieldCipher.BLOCK) {
            throw AuthenticationException("ciphertext length != block_count")
        }
        // MessageDigest.isEqual is the JVM's constant-time comparison, the counterpart of
        // Python's hmac.compare_digest. contentEquals short-circuits and leaks timing.
        if (!MessageDigest.isEqual(tag, mac(rxSeq, blockCount, ciphertext))) {
            throw AuthenticationException("MAC verification failed")
        }
        var plaintext = AirShieldCipher.aesCbc(aesKey, rxIv, ciphertext, encrypt = false)
        if (verifyUnpad) {
            plaintext = AirShieldCipher.unpad(plaintext)
        }
        if (chainIv) {
            rxIv = ciphertext.copyOfRange(ciphertext.size - AirShieldCipher.BLOCK, ciphertext.size)
        }
        rxSeq += 1
        return plaintext
    }
}
