package com.macedonga.visor.core.identity

import java.net.HttpURLConnection
import java.net.URL

/**
 * The one HTTP call the Meta login and the pairing backend make: a POST, and whatever came back.
 */
class HttpReply(val status: Int, val body: ByteArray)

fun interface HttpPost {
    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpReply
}

/** The real one. 30 seconds each way, the `timeout=30` every reference call passes. */
object UrlConnectionPost : HttpPost {

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpReply(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MILLIS = 30_000
}

/** Python's `urllib.parse.quote(value, safe='')`, byte for byte. */
fun pyQuote(value: String): String = percentEncode(value, plusForSpace = false)

/** Python's `urllib.parse.quote_plus`, which `urlencode` applies to every key and value. */
fun pyQuotePlus(value: String): String = percentEncode(value, plusForSpace = true)

/** Python's `urllib.parse.urlencode(fields)` for a dict of strings, in insertion order. */
fun pyUrlencode(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) -> pyQuotePlus(key) + "=" + pyQuotePlus(value) }

private fun percentEncode(value: String, plusForSpace: Boolean): String {
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_.-~" -> out.append(c)
            plusForSpace && c == ' ' -> out.append('+')
            else -> out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
