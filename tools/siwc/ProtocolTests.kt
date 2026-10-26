import com.macedonga.visor.core.assistant.siwc.*
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/** Standalone protocol checks, runnable with kotlinc. No live account or network calls. */
fun main() {
    val host = ChatGptAuthorization.newHostId()
    val fresh = ChatGptAuthorization.start(host, "http://127.0.0.1:1455/auth/callback", "Visor")
    val query = URI(fresh.url).rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }
    check(query["client_id"] == "dynamic_agent_client")
    check(query["agent_name_hint"] == "Visor")
    check(query["ext_agent_host_id"] == host)
    check(query["scope"]!!.contains("chatgpt.tokens.use.direct"))
    check(query["redirect_uri"] == "http://127.0.0.1:1455/auth/callback")
    val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(fresh.verifier.toByteArray()),
    )
    check(query["code_challenge"] == expectedChallenge)
    check(fresh.clientIdFromCallback("oaiapp_test123") == "oaiapp_test123")
    val callback = fresh.validateCallback("http://127.0.0.1:1455/auth/callback?state=${fresh.state}&code=abc&client_id=oaiapp_test123")
    check(callback.code == "abc" && callback.clientId == "oaiapp_test123")
    check(runCatching { fresh.validateCallback("http://127.0.0.1:1455/auth/callback?state=wrong&code=abc&client_id=oaiapp_test123") }.isFailure)
    check(runCatching { fresh.validateCallback("http://localhost:1455/auth/callback?state=${fresh.state}&code=abc&client_id=oaiapp_test123") }.isFailure)
    check(runCatching { fresh.validateCallback("http://127.0.0.1:1455/auth/callback?state=${fresh.state}&state=${fresh.state}&code=abc&client_id=oaiapp_test123") }.isFailure)
    check(runCatching { fresh.clientIdFromCallback(null) }.isFailure)
    val returning = ChatGptAuthorization.start(host, "http://127.0.0.1:54321/auth/callback", "Visor", "oaiapp_existing")
    check(!returning.url.contains("agent_name_hint"))
    check(returning.clientIdFromCallback(null) == "oaiapp_existing")
    check(runCatching { returning.clientIdFromCallback("oaiapp_attacker") }.isFailure)
    check(runCatching { ChatGptAuthorization.start(host, "https://example.com/callback", "Visor") }.isFailure)
    check(allowsChatGptPlan(listOf("openid", "chatgpt.tokens.use.direct")))
    check(!allowsChatGptPlan(listOf("openid", "email")))

    val sse = ResponsesSse()
    check(sse.line(": ping") == null)
    check(sse.line("event: response.output_text.delta") == null)
    check(sse.line("data: {\"delta\":\"Hei!\"}") == null)
    check(sse.line("") == SseEvent("response.output_text.delta", "{\"delta\":\"Hei!\"}"))
    check(sse.line("event: response.completed") == null)
    check(sse.line("data: {\"type\":\"response.completed\"}") == null)
    check(sse.line("\r")?.type == "response.completed")
    check(sse.flush() == null)
    println("OK: OAuth/PKCE/callback/SSE protocol smoke checks passed")
}
