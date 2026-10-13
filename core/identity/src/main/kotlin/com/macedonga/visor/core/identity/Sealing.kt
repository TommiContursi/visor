package com.macedonga.visor.core.identity

/** The blob is not one of ours, or it is damaged. */
class SealingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sealing the store at rest. */
interface Sealer {

    fun seal(data: ByteArray): ByteArray

    /** @throws SealingException if the blob is not ours, is damaged, or will not open. */
    fun unseal(blob: ByteArray): ByteArray
}

/** How a sealer decides a blob is its own. */
internal fun ByteArray.startsWithMagic(magic: ByteArray): Boolean =
    size >= magic.size && magic.indices.all { this[it] == magic[it] }
