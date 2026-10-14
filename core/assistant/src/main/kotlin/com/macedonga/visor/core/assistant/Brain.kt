package com.macedonga.visor.core.assistant

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where an answer comes from. */
enum class Provider { CLAUDE, OPENAI_COMPATIBLE }

/** One question and its answer, the conversation behind the next question. */
data class Exchange(val question: String, val answer: String)

/** An answer, and what the model chose to remember while giving it. */
data class Reply(val text: String, val remembered: List<String> = emptyList())

/** What the assistant keeps about the wearer across conversations; the model adds and drops entries with its tools. */
interface MemoryBook {
    val memories: StateFlow<List<Memory>>
    fun remember(text: String): Memory
    fun forget(id: String): Memory?
}

/** Who the answer is for: spoken by the glasses, or read on the phone. */
enum class Audience { GLASSES, PHONE }

/** The model behind the assistant. The conversation comes with each question, from the diary. */
class Brain(private val config: () -> AssistantConfig, private val book: MemoryBook? = null) {

    /** The answer to [question] after [past], with [image] (a JPEG) when the glasses sent one. Throws on any failure. */
    suspend fun answer(
        question: String,
        locale: Locale,
        past: List<Exchange> = emptyList(),
        image: ByteArray? = null,
        note: String = "",
        audience: Audience = Audience.GLASSES,
    ): Reply = withContext(Dispatchers.IO) {
        val c = config()
        check(c.apiKey.isNotBlank() || c.provider == Provider.OPENAI_COMPATIBLE) { "no API key is set" }
        val system = (if (audience == Audience.PHONE) PHONE_PROMPT else SYSTEM_PROMPT) +
            " It is ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}." +
            (if (audience == Audience.GLASSES) " The glasses are set to ${locale.getDisplayLanguage(Locale.ENGLISH)}." else "") +
            memoryPrompt() +
            (if (note.isNotBlank()) " $note" else "") +
            if (c.instructions.isNotBlank()) " " + c.instructions else ""
        val recent = past.takeLast(MAX_EXCHANGES)
        val tokens = if (audience == Audience.PHONE) PHONE_TOKENS else GLASSES_TOKENS
        val remembered = ArrayList<String>()
        val text = when (c.provider) {
            Provider.CLAUDE -> claude(c, c.model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, recent, question, image, tokens, c.webSearch, remembered)
            Provider.OPENAI_COMPATIBLE -> openAi(c, c.model, system, recent, question, image, tokens, remembered)
        }.trim()
        check(text.isNotEmpty()) { "the model gave an empty answer" }
        Reply(text, remembered)
    }

    /** One sentence for the wearer, in [locale]'s language, written from [what]; outside the conversation. */
    suspend fun phrase(what: String, locale: Locale): String = withContext(Dispatchers.IO) {
        val c = config()
        check(c.apiKey.isNotBlank() || c.provider == Provider.OPENAI_COMPATIBLE) { "no API key is set" }
        val system = "You write one short sentence that smart glasses speak to their wearer, in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}. Answer with that sentence only."
        write(c, c.model, system, what, 200)
    }

    /** A day's lines for the diary, in [locale]'s language, from [exchanges] in order; with the lines' own model if one is set. */
    suspend fun dayLines(exchanges: String, locale: Locale): String = withContext(Dispatchers.IO) {
        val c = config()
        check(c.apiKey.isNotBlank() || c.provider == Provider.OPENAI_COMPATIBLE) { "no API key is set" }
        val system = "You keep the diary of a person who wears smart glasses with a voice assistant. From the day's " +
            "exchanges with the assistant, write the day's entry: one to three short sentences in " +
            "${locale.getDisplayLanguage(Locale.ENGLISH)}, in the second person (\"You asked about...\"), dry and precise, " +
            "naming the actual subjects, places and things looked at, in the order they came. No times, no greeting, no " +
            "judgement, no markdown. Answer with the entry only."
        write(c, c.linesModel.ifBlank { c.model }, system, exchanges, 300)
    }

    private fun write(c: AssistantConfig, model: String, system: String, text: String, tokens: Int): String = when (c.provider) {
        Provider.CLAUDE -> claude(c, model.ifBlank { DEFAULT_CLAUDE_MODEL }, system, emptyList(), text, null, tokens, false, null)
        Provider.OPENAI_COMPATIBLE -> openAi(c, model, system, emptyList(), text, null, tokens, null)
    }.trim().also { check(it.isNotEmpty()) { "the model gave an empty answer" } }

    /** What the model knows of the wearer, and how to keep it. Empty when there is nowhere to keep it. */
    private fun memoryPrompt(): String {
        val book = book ?: return ""
        val known = book.memories.value
        return " You can keep lasting facts about the wearer with the remember tool, and drop wrong or outdated ones with " +
            "forget. Remember only what will help in later conversations (people and their names, preferences, places, " +
            "plans, what they own), never the moment's small talk. Use the tools silently, without mentioning them, and " +
            "always give your answer too." +
            if (known.isEmpty()) " You remember nothing about the wearer yet." else
                " What you remember about the wearer, with each fact's id: " + known.joinToString(" ") { "[${it.id}] ${it.text}" }
    }

    /** remember or forget, run for the model; what it is told back. */
    private fun runTool(name: String, input: JSONObject, remembered: MutableList<String>): String {
        val book = book ?: return "unavailable"
        return when (name) {
            "remember" -> {
                val fact = input.optString("fact").trim()
                if (fact.isEmpty()) return "nothing to remember"
                book.remember(fact)
                remembered += fact
                "kept"
            }
            "forget" -> if (book.forget(input.optString("id")) != null) "forgotten" else "no such fact"
            else -> "unknown tool"
        }
    }

    private fun claude(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, webSearch: Boolean, remembered: MutableList<String>?,
    ): String {
        val messages = JSONArray()
        for (e in past) {
            messages.put(JSONObject().put("role", "user").put("content", e.question))
            messages.put(JSONObject().put("role", "assistant").put("content", e.answer))
        }
        val content = JSONArray()
        if (image != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(image, Base64.NO_WRAP)),
                ),
            )
        }
        content.put(JSONObject().put("type", "text").put("text", question))
        messages.put(JSONObject().put("role", "user").put("content", content))
        val tools = JSONArray()
        if (webSearch) tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2))
        if (remembered != null && book != null) {
            tools.put(JSONObject().put("name", "remember").put("description", REMEMBER_HELP).put("input_schema", schema("fact", FACT_HELP)))
            tools.put(JSONObject().put("name", "forget").put("description", FORGET_HELP).put("input_schema", schema("id", "The fact's id.")))
        }
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().put("model", model).put("max_tokens", tokens).put("system", system).put("messages", messages)
            if (tools.length() > 0) body.put("tools", tools)
            val answer = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to c.apiKey, "anthropic-version" to "2023-06-01"), body)
            val blocks = answer.getJSONArray("content")
            val list = (0 until blocks.length()).map { blocks.getJSONObject(it) }
            // With web search the answer is split into several text blocks around the citations.
            val text = list.filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") }
            if (text.isNotBlank()) said = text
            val uses = list.filter { it.optString("type") == "tool_use" }
            if (answer.optString("stop_reason") != "tool_use" || uses.isEmpty() || remembered == null) return said
            messages.put(JSONObject().put("role", "assistant").put("content", blocks))
            val results = JSONArray()
            for (use in uses) {
                val out = runTool(use.getString("name"), use.optJSONObject("input") ?: JSONObject(), remembered)
                results.put(JSONObject().put("type", "tool_result").put("tool_use_id", use.getString("id")).put("content", out))
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        return said
    }

    private fun openAi(
        c: AssistantConfig, model: String, system: String, past: List<Exchange>, question: String, image: ByteArray?,
        tokens: Int, remembered: MutableList<String>?,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (e in past) {
            messages.put(JSONObject().put("role", "user").put("content", e.question))
            messages.put(JSONObject().put("role", "assistant").put("content", e.answer))
        }
        val user: Any = if (image == null) question else JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)),
                ),
            )
        messages.put(JSONObject().put("role", "user").put("content", user))
        val headers = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")
        val url = c.baseUrl.trimEnd('/') + "/chat/completions"
        var tools = if (remembered != null && book != null) JSONArray()
            .put(function("remember", REMEMBER_HELP, schema("fact", FACT_HELP)))
            .put(function("forget", FORGET_HELP, schema("id", "The fact's id."))) else null
        var said = ""
        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().put("model", model).put("messages", messages).put("max_tokens", tokens)
            tools?.let { body.put("tools", it) }
            val answer = try {
                send(url, headers, body)
            } catch (error: IllegalStateException) {
                // A server without tools refuses the field; then the conversation goes on without memory.
                if (tools == null || !TOOLS_REFUSED.containsMatchIn(error.message.orEmpty())) throw error
                tools = null
                send(url, headers, body.apply { remove("tools") })
            }
            val choice = answer.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty().replace(THINKING, "")
            check(text.isNotBlank() || choice.optString("finish_reason") != "length") { "the model used its whole answer thinking" }
            if (text.isNotBlank()) said = text
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0 || remembered == null) return said
            messages.put(message)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val input = runCatching { JSONObject(fn.optString("arguments")) }.getOrDefault(JSONObject())
                val out = runTool(fn.getString("name"), input, remembered)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", out))
            }
        }
        return said
    }

    /** A local reasoning model (LM Studio, llama.cpp) otherwise spends the whole answer thinking; OpenAI's own and some others refuse the field. */
    private fun send(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        // OpenAI's newer models refuse max_tokens, and every one of its models takes max_completion_tokens.
        if ("api.openai.com" in url) return post(url, headers, completionTokens(body))
        return try {
            runCatching { post(url, headers, JSONObject(body.toString()).put("reasoning_effort", "none")) }
                .getOrElse { if ("reasoning" in it.message.orEmpty()) post(url, headers, body) else throw it }
        } catch (error: IllegalStateException) {
            // A server that relays those models (Azure, a proxy) says which field it wants.
            if ("max_completion_tokens" !in error.message.orEmpty()) throw error
            post(url, headers, completionTokens(body))
        }
    }

    private fun completionTokens(body: JSONObject) = JSONObject(body.toString()).apply { put("max_completion_tokens", remove("max_tokens")) }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val http = URL(url).openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 5_000
            // A local model reading a picture can take a while; each turn still has its own budget above.
            http.readTimeout = 45_000
            http.doOutput = true
            http.setRequestProperty("content-type", "application/json")
            headers.forEach(http::setRequestProperty)
            http.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = http.responseCode
            val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                error("the model answered $code: $message")
            }
            return JSONObject(text)
        } finally {
            http.disconnect()
        }
    }

    private companion object {
        /** Thinking a server left inline in the answer. */
        val THINKING = Regex("(?s)<think>.*?</think>")
        val TOOLS_REFUSED = Regex("tool|function", RegexOption.IGNORE_CASE)
        const val MAX_EXCHANGES = 12
        const val MAX_TOOL_ROUNDS = 3
        const val GLASSES_TOKENS = 400
        const val PHONE_TOKENS = 900
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"
        const val SYSTEM_PROMPT =
            "You are the voice assistant in a pair of Ray-Ban Meta smart glasses, run by the wearer's own app, Visor. " +
                "Everything you write is spoken aloud through the glasses' speakers, so answer in one to three short, " +
                "natural sentences unless asked for more. No lists, markdown, emoji, URLs or citations. " +
                "Reply in the language the wearer speaks. When an image is attached, it is what the wearer is looking at " +
                "through the glasses' camera right now."
        const val PHONE_PROMPT =
            "You are the assistant of a person who wears Ray-Ban Meta smart glasses, run by their own app, Visor. Right now " +
                "they are writing to you in the app on their phone, often to continue what they asked the glasses; your " +
                "answer is shown there as text, not spoken. Be direct and brief: a few short paragraphs at most, plain " +
                "text without markdown or emoji. Reply in the language they write in."
        const val REMEMBER_HELP = "Keep a lasting fact about the wearer for later conversations."
        const val FORGET_HELP = "Drop a fact you remember that is wrong or outdated, or that the wearer asks you to forget."
        const val FACT_HELP = "One short sentence addressed to the wearer, in their language, e.g. \"Your sister is called Giulia.\""

        fun schema(field: String, help: String): JSONObject = JSONObject().put("type", "object")
            .put("properties", JSONObject().put(field, JSONObject().put("type", "string").put("description", help)))
            .put("required", JSONArray().put(field))

        fun function(name: String, help: String, parameters: JSONObject): JSONObject = JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", help).put("parameters", parameters))
    }
}
