package com.macedonga.visor.core.assistant.siwc

import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Pure PKCE/OIDC request construction for OpenAI's documented OSS dynamic-client flow.
 *
 * The caller MUST start a loopback listener before opening [url], persist hostId across
 * app restarts, verify callback state, exchange with the callback-issued client ID,
 * verify the ID token signature/issuer/audience/nonce, and securely store tokens.
 * This class intentionally does none of those security-critical operations.
 */
data class ChatGptAuthorization(
    val url: String,
    val state: String,
    val nonce: String,
    val verifier: String,
    val redirectUri: String,
    val requestedClientId: String,
) {
    companion object {
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
        private const val NEW_CLIENT = "dynamic_agent_client"
        private val secureRandom = SecureRandom()

        /** Persist this once for each Android installation, not once per login. */
        fun newHostId(): String = "urn:uuid:${UUID.randomUUID()}"

        fun start(
            hostId: String,
            redirectUri: String,
            agentName: String,
            issuedClientId: String? = null,
        ): ChatGptAuthorization {
            require(hostId.startsWith("urn:uuid:") || hostId.startsWith("urn:ietf:params:oauth:jwk-thumbprint:") || hostId.startsWith("did:key:")) {
                "unsupported host ID format"
            }
            val redirect = java.net.URI(redirectUri)
            require(redirect.scheme == "http" && redirect.host == "127.0.0.1" &&
                redirect.port in 1..65535 && redirect.path == "/auth/callback" &&
                redirect.rawQuery == null && redirect.rawFragment == null
            ) { "Use an HTTP loopback callback at 127.0.0.1:<port>/auth/callback" }
            require(agentName.isNotBlank()) { "agentName is required" }
            require(issuedClientId == null || issuedClientId.isNotBlank()) { "invalid issued client ID" }

            val verifier = secret(32)
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            )
            val state = secret(24)
            val nonce = secret(24)
            val client = issuedClientId ?: NEW_CLIENT
            val params = linkedMapOf(
                "client_id" to client,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to "openid profile email offline_access resource.invoke $PLAN_SCOPE",
                "resource" to RESOURCE,
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge,
                "ext_agent_host_id" to hostId,
            )
            if (issuedClientId == null) params["agent_name_hint"] = agentName
            val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            return ChatGptAuthorization("$AUTHORIZE?$query", state, nonce, verifier, redirectUri, client)
        }

        private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        private fun secret(bytes: Int): String = ByteArray(bytes).also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}

/** Call ONLY after verifying state. The callback for a new client must issue a real client ID. */
fun ChatGptAuthorization.clientIdFromCallback(callbackClientId: String?): String {
    return if (requestedClientId == "dynamic_agent_client") {
        require(!callbackClientId.isNullOrBlank() && callbackClientId != "dynamic_agent_client") {
            "ChatGPT registration did not issue a client ID"
        }
        callbackClientId
    } else {
        require(callbackClientId == null || callbackClientId == requestedClientId) {
            "ChatGPT callback unexpectedly changed client ID"
        }
        requestedClientId
    }
}

fun allowsChatGptPlan(scopes: Collection<String>): Boolean =
    ChatGptAuthorization.PLAN_SCOPE in scopes

/** Checked browser callback. Exchange [code] with [clientId] and the attempt's PKCE verifier. */
data class VerifiedChatGptCallback(val code: String, val clientId: String)

fun ChatGptAuthorization.validateCallback(callbackUrl: String): VerifiedChatGptCallback {
    val expected = java.net.URI(redirectUri)
    val received = java.net.URI(callbackUrl)
    require(received.scheme == expected.scheme && received.host == expected.host &&
        received.port == expected.port && received.path == expected.path &&
        received.rawFragment == null) { "Unexpected ChatGPT callback URI" }
    val values = linkedMapOf<String, String>()
    for (part in received.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
        val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
        val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        require(values.put(key, value) == null) { "Duplicate OAuth callback parameter" }
    }
    val incomingState = values["state"].orEmpty().toByteArray(Charsets.UTF_8)
    require(MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), incomingState)) {
        "ChatGPT callback state mismatch"
    }
    check(values["error"] == null) { "ChatGPT authorization was declined or failed" }
    val code = values["code"].orEmpty()
    require(code.isNotBlank()) { "Missing ChatGPT authorization code" }
    return VerifiedChatGptCallback(code, clientIdFromCallback(values["client_id"]))
}
