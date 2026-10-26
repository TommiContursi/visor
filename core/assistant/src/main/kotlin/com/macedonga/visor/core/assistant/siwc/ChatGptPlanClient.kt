package com.macedonga.visor.core.assistant.siwc

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Inference-only adapter for an ALREADY VERIFIED & AUTHORIZED OAuth connection.
 *
 * Do not pass an ID token or a Claude/ChatGPT web-session cookie. Only pass the
 * access_token issued with the chatgpt.tokens.use.direct permission. Token refresh,
 * ID-token verification, credential storage and Android browser sign-in belong to
 * the separate sign-in layer and must be finished before wiring this into Brain.
 *
 * Implements first text-only request for technical smoke testing. Tool calls,
 * pictures, model-specific capabilities, and account switching are intentionally
 * NOT implemented yet.
 */
class ChatGptPlanClient(private val accessToken: () -> String) {
    data class Model(val slug: String, val name: String)
    private val api = "https://api.openai.com/v1"

    fun models(): List<Model> {
        val connection = connect("$api/models", "GET")
        try {
            checkSuccess(connection)
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = json.optJSONArray("models") ?: error("ChatGPT model list was missing")
            return (0 until items.length()).map { items.getJSONObject(it) }
                .filter { it.optString("visibility") == "list" }
                .map { Model(it.getString("slug"), it.optString("display_name", it.getString("slug"))) }
        } finally {
            connection.disconnect()
        }
    }

    /** Synchronous network call: invoke from Dispatchers.IO, never Android's main thread. */
    fun text(model: String, instructions: String, history: List<Pair<String, String>>, question: String): String {
        require(model.isNotBlank()) { "Select a model from the signed-in account's model list" }
        require(question.isNotBlank()) { "Question must not be blank" }
        val input = JSONArray()
        history.forEach { (user, assistant) ->
            input.put(JSONObject().put("role", "user").put("content", user))
            input.put(JSONObject().put("role", "assistant").put("content", assistant))
        }
        input.put(JSONObject().put("role", "user").put("content", question))
        val payload = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("store", false)
            .put("stream", true)

        val connection = connect("$api/responses", "POST")
        try {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            checkSuccess(connection)

            val decoder = ResponsesSse()
            val output = StringBuilder()
            var completed = false
            fun handle(event: SseEvent?) {
                if (event == null) return
                val data = JSONObject(event.data)
                val type = if (event.type == "message") data.optString("type") else event.type
                when (type) {
                    "response.output_text.delta" -> output.append(data.optString("delta"))
                    "response.completed" -> completed = true
                    "response.failed", "response.incomplete", "error" -> {
                        val error = data.optJSONObject("response")?.optJSONObject("error")
                            ?: data.optJSONObject("error")
                        throw IllegalStateException("ChatGPT request $type: ${error?.optString("code") ?: data.optString("message", "unknown error")}")
                    }
                }
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> handle(decoder.line(line)) }
            }
            handle(decoder.flush())
            check(completed) { "ChatGPT stream ended without response.completed" }
            return output.toString().trim().also { check(it.isNotEmpty()) { "ChatGPT returned no text" } }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 90_000
            val token = accessToken()
            require(token.isNotBlank()) { "No verified ChatGPT OAuth access token" }
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, text/event-stream")
        }

    private fun checkSuccess(connection: HttpURLConnection) {
        if (connection.responseCode in 200..299) return
        val message = connection.errorStream?.bufferedReader()?.use { it.readText().take(1500) }.orEmpty()
        // Never log Authorization or OAuth token values.
        error("ChatGPT HTTP ${connection.responseCode}: $message")
    }
}
