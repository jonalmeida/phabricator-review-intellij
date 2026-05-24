package com.mozilla.phabricator.conduit

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Equivalent of the VSCode client's test/_helpers.js `mockFetch`. Records every call and returns
 * canned responses in order. Use [enqueueJson] for the common "200 with JSON body" path, or
 * [enqueue] for full control.
 */
class FakeHttpTransport : HttpTransport {

    data class Call(val url: String, val body: String, val headers: Map<String, String>)

    data class CannedResponse(val status: Int = 200, val body: String)

    val calls = mutableListOf<Call>()
    private val responses = ArrayDeque<CannedResponse>()

    fun enqueue(response: CannedResponse) {
        responses.addLast(response)
    }

    /** Convenience: 200 + JSON-serialized body. */
    fun enqueueJson(status: Int = 200, body: String) {
        responses.addLast(CannedResponse(status, body))
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpTransport.Response {
        calls += Call(url, body, headers)
        val resp =
            responses.removeFirstOrNull()
                ?: error("FakeHttpTransport: no canned response at index ${calls.size - 1}")
        return HttpTransport.Response(
            status = resp.status,
            statusText = if (resp.status == 200) "OK" else "ERROR",
            body = resp.body,
        )
    }
}

/**
 * Port of test/_helpers.js `decodeBody`: parse a Conduit form-encoded body back into its three
 * URL-encoded parts plus the JSON-decoded `params` and `__conduit__` payloads.
 */
data class DecodedBody(
    val raw: Map<String, String>,
    val params: JsonElement?,
    val conduit: JsonElement?,
)

fun decodeBody(body: String): DecodedBody {
    val raw =
        body
            .split("&")
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { pair ->
                val idx = pair.indexOf('=')
                val key = if (idx < 0) pair else pair.substring(0, idx)
                val value = if (idx < 0) "" else pair.substring(idx + 1)
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
            .toMap()
    val json = Json
    return DecodedBody(
        raw = raw,
        params = raw["params"]?.let { json.parseToJsonElement(it) },
        conduit = raw["__conduit__"]?.let { json.parseToJsonElement(it) },
    )
}
