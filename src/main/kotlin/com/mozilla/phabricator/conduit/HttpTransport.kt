package com.mozilla.phabricator.conduit

interface HttpTransport {
    suspend fun post(url: String, body: String, headers: Map<String, String>): Response

    data class Response(val status: Int, val statusText: String, val body: String) {
        val ok: Boolean
            get() = status in 200..299
    }
}
