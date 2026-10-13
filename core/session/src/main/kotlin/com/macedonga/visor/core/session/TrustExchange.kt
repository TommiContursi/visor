package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.HandshakeBinding

/** Which direction of the svc36 trust exchange succeeded. From `identity/trust.py`. */
class TrustResult(val txOk: Boolean, val rxOk: Boolean) {

    val complete: Boolean get() = txOk && rxOk

    override fun toString(): String = "TrustResult(txOk=$txOk, rxOk=$rxOk)"
}

/** The svc36 exchange the session runs between the handshake and EndLinkSetup. */
fun interface TrustExchange {

    /**
     * Run the exchange on [connection], using [binding] for the channel binding material, and
     * report which directions completed.
     */
    suspend fun run(connection: DataXConnection, binding: HandshakeBinding): TrustResult
}
