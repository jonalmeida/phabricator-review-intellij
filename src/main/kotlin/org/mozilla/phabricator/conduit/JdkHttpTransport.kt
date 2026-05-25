package org.mozilla.phabricator.conduit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JdkHttpTransport(
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
) : HttpTransport {

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpTransport.Response =
        withContext(Dispatchers.IO) {
            val builder =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                    .timeout(Duration.ofSeconds(60))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val response = client.send(builder.build(), BodyHandlers.ofString(Charsets.UTF_8))
            HttpTransport.Response(
                status = response.statusCode(),
                statusText = if (response.statusCode() == 200) "OK" else "ERROR",
                body = response.body(),
            )
        }
}
