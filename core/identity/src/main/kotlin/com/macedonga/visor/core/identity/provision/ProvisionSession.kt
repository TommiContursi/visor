package com.macedonga.visor.core.identity.provision

import com.macedonga.visor.core.identity.token.TokenSource
import com.macedonga.visor.core.session.ChannelRegistry
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.SecureLink
import com.macedonga.visor.core.transport.Transport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drive one provisioning connection: link up, change owner, hand back the key. */
class ProvisioningResult(
    val serial: String,
    val userId: String,
    /** DIVERGENCE: the reference returns the path it wrote. The key itself comes back here. */
    val keyPem: ByteArray,
    val reusedKey: Boolean,
)

/** Run change owner over [transport] and return the key it installed. */
suspend fun provision(
    transport: Transport,
    source: TokenSource,
    existingKeyPem: ByteArray? = null,
    timeoutMillis: Long = 20_000,
    onStep: (ProvisionStep) -> Unit = {},
): ProvisioningResult {
    val key = if (existingKeyPem != null) ownerKeyFromPem(existingKeyPem) else generateOwnerKey()
    onStep(ProvisionStep.TOKEN)
    val token = withContext(Dispatchers.IO) { source.get() }
    val backend = PairingBackend(token.value, token.universe)

    onStep(ProvisionStep.CONNECT)
    transport.connect()
    val final = try {
        coroutineScope {
            onStep(ProvisionStep.SECURE_LINK)
            val link = SecureLink(transport)
            handshakeWithDeadline(transport) { link.open() }
            val connection = DataXConnection(link, ChannelRegistry())
            val reader = connection.startReader(this)
            try {
                ProvisioningFlow(connection, backend, key.publicKey64, timeoutMillis, onStep).run()
            } finally {
                // Close first: a read blocked on the socket does not answer cancellation, and
                // the reader would hold this scope open until it did.
                transport.close()
                reader.cancel()
            }
        }
    } finally {
        transport.close()
    }

    val info = final.info
    return ProvisioningResult(
        serial = info.textOf("serial"),
        userId = info.textOf("user_id"),
        keyPem = key.pem,
        reusedKey = existingKeyPem != null,
    )
}

private fun kotlinx.serialization.json.JsonObject.textOf(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

private suspend fun handshakeWithDeadline(transport: Transport, body: suspend () -> Unit) = coroutineScope {
    // The same race GlassesSession.withLinkDeadline closes: one atomic decides whether the
    // body or the watchdog finished first, so a link that just came up is never closed.
    val settled = AtomicBoolean(false)
    val watchdog = launch {
        delay(GlassesSession.LINK_SETUP_READ_TIMEOUT_MILLIS)
        if (settled.compareAndSet(false, true)) transport.close()
    }
    try {
        body()
        settled.compareAndSet(false, true)
    } catch (error: Throwable) {
        if (error !is kotlinx.coroutines.CancellationException && !settled.compareAndSet(false, true)) {
            throw ProvisioningError("the glasses stopped answering during link setup")
        }
        throw error
    } finally {
        watchdog.cancel()
    }
}
