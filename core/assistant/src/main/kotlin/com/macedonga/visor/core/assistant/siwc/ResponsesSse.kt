package com.macedonga.visor.core.assistant.siwc

/** Small, dependency-free SSE framing layer. Parses complete events, not JSON payloads. */
data class SseEvent(val type: String, val data: String)

class ResponsesSse {
    private var type: String? = null
    private val data = StringBuilder()

    /** Feed one line, without its line ending. A blank line ends the event. */
    fun line(raw: String): SseEvent? {
        val value = raw.removeSuffix("\r")
        if (value.isEmpty()) return flush()
        if (value.startsWith(":")) return null // heartbeat
        val field = value.substringBefore(':')
        val text = value.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> type = text
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(text) }
        }
        return null
    }

    fun flush(): SseEvent? {
        val result = if (data.isEmpty()) null else SseEvent(type ?: "message", data.toString())
        type = null
        data.clear()
        return result
    }
}
