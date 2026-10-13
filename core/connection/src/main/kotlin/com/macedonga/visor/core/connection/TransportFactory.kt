package com.macedonga.visor.core.connection

import com.macedonga.visor.core.transport.Transport

/** Builds the byte pipe for a link. */
fun interface TransportFactory {

    /** @throws IllegalArgumentException if this build will not open that kind of link. */
    fun create(link: GlassesLink): Transport
}
