package com.macedonga.visor.core.protocol

import java.security.MessageDigest

/** Reconnect channel-binding, the "TX/RX challenge", cracked 2026-06-13 (notes/76). */
class HandshakeBinding(
    /** 16 bytes. */
    val myChallenge: ByteArray,
    /** 16 bytes. */
    val glChallenge: ByteArray,
    /** 32 bytes. */
    val mySeed: ByteArray,
    /** 32 bytes. */
    val glSeed: ByteArray,
    /** 64 raw bytes of `x ‖ y`, our ephemeral. */
    val myPub: ByteArray = ByteArray(0),
    /** 64 raw bytes of `x ‖ y`, the glasses' EnableEncryption pubkey. */
    val glPub: ByteArray = ByteArray(0),
    /** 64 raw bytes of `x ‖ y`, the glasses' RequestEncryption pubkey. */
    val glReqPub: ByteArray = ByteArray(0),
    val ecdhSecret: ByteArray = ByteArray(0),
) {
    /**
     * The TX-cipher builder's `getTxChallenge` (C31892HfO / IYP): the peer's challenge with the
     * peer's RequestEncryption pubkey, and our seed with our pubkey, which is the KDF cross-map
     * from notes/72 and notes/76:
     */
    fun primary(): ByteArray = twoLevel(glChallenge, mySeed, glReqPub, myPub)

    /** tx form: `SHA256(SHA256(c ‖ glPub) ‖ SHA256(s ‖ myPub))`. */
    private fun tx(c: ByteArray, s: ByteArray): ByteArray = twoLevel(c, s, glPub, myPub)

    /** rx form: `SHA256(SHA256(c ‖ myPub) ‖ SHA256(s ‖ glPub))`. */
    private fun rx(c: ByteArray, s: ByteArray): ByteArray = twoLevel(c, s, myPub, glPub)

    /**
     * Exhaustive over the oracle-proven input set: tx or rx form, times our or the glasses'
     * challenge, times our or the glasses' seed.
     */
    fun candidates(): List<Candidate> {
        val myc = myChallenge
        val glc = glChallenge
        val mys = mySeed
        val gls = glSeed
        return listOf(
            // the glasses' own challenge and seed, which is their getRxChallenge
            Candidate("tx(glc,gls)", tx(glc, gls)),
            Candidate("tx(myc,gls)", tx(myc, gls)),
            Candidate("rx(glc,mys)", rx(glc, mys)),
            Candidate("rx(myc,mys)", rx(myc, mys)),
            Candidate("rx(glc,gls)", rx(glc, gls)),
            Candidate("rx(myc,gls)", rx(myc, gls)),
            Candidate("tx(myc,mys)", tx(myc, mys)),   // known 0x1043
            Candidate("tx(glc,mys)", tx(glc, mys)),   // known 0x1043
        )
    }

    fun copy(
        myChallenge: ByteArray = this.myChallenge,
        glChallenge: ByteArray = this.glChallenge,
        mySeed: ByteArray = this.mySeed,
        glSeed: ByteArray = this.glSeed,
        myPub: ByteArray = this.myPub,
        glPub: ByteArray = this.glPub,
        glReqPub: ByteArray = this.glReqPub,
        ecdhSecret: ByteArray = this.ecdhSecret,
    ): HandshakeBinding = HandshakeBinding(
        myChallenge, glChallenge, mySeed, glSeed, myPub, glPub, glReqPub, ecdhSecret,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeBinding) return false
        return myChallenge.contentEquals(other.myChallenge) &&
            glChallenge.contentEquals(other.glChallenge) &&
            mySeed.contentEquals(other.mySeed) &&
            glSeed.contentEquals(other.glSeed) &&
            myPub.contentEquals(other.myPub) &&
            glPub.contentEquals(other.glPub) &&
            glReqPub.contentEquals(other.glReqPub) &&
            ecdhSecret.contentEquals(other.ecdhSecret)
    }

    override fun hashCode(): Int {
        var result = myChallenge.contentHashCode()
        result = 31 * result + glChallenge.contentHashCode()
        result = 31 * result + mySeed.contentHashCode()
        result = 31 * result + glSeed.contentHashCode()
        result = 31 * result + myPub.contentHashCode()
        result = 31 * result + glPub.contentHashCode()
        result = 31 * result + glReqPub.contentHashCode()
        result = 31 * result + ecdhSecret.contentHashCode()
        return result
    }

    /** Deliberately says nothing about the shared secret. */
    override fun toString(): String = "HandshakeBinding(myPub=${myPub.size}B, glPub=${glPub.size}B)"

    companion object {
        /** `getTxChallenge`: `SHA256( SHA256(challenge ‖ remPub) ‖ SHA256(seed ‖ myPub) )`. */
        private fun twoLevel(
            challenge: ByteArray,
            seed: ByteArray,
            remPub: ByteArray,
            myPub: ByteArray,
        ): ByteArray = sha(sha(challenge, remPub), sha(seed, myPub))

        private fun sha(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach(digest::update)
            return digest.digest()
        }
    }

    // `binding.py` also has `from_channel`, which builds a binding by reaching into a session
    // object's private attributes with getattr.
}

/** One candidate channel binding, with the name the reference gives it. */
class Candidate(val name: String, val value: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is Candidate && name == other.name && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * name.hashCode() + value.contentHashCode()

    override fun toString(): String = "Candidate($name)"
}
