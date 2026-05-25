package org.mozilla.phabricator.conduit

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Port of `src/client/conduit.js` from phabricator-review-vscode.
 *
 * Wire contract (must match the VSCode client exactly): POST {baseUrl}{method} Content-Type:
 * application/x-www-form-urlencoded body: params=<json>&output=json&__conduit__=<json-token> where
 * the JSON inside `params` also carries `__conduit__: {token}` so both legacy (form-level) and
 * modern (params-level) endpoints stay happy.
 */
class ConduitTransport(
    private val token: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val transport: HttpTransport,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val logger: Logger = NoopLogger,
) {

    val baseUrl: String

    init {
        require(token.isNotEmpty()) { "PhabricatorClient: token is required" }
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        require(normalized.endsWith("/api/")) {
            "PhabricatorClient: baseUrl must end with /api/ (got $normalized)"
        }
        this.baseUrl = normalized
    }

    /**
     * Invoke a Conduit method. Returns the `result` member of the response, as a [JsonElement] so
     * callers can decode to a typed model on their own.
     */
    suspend fun call(method: String, args: JsonObject = EMPTY_ARGS): JsonElement {
        val url = baseUrl + method
        val body = encodeBody(token, args)
        logger.log(LogLevel.DEBUG, "POST $method", mapOf("method" to method))

        val response =
            transport.post(
                url = url,
                body = body,
                headers =
                    mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "User-Agent" to userAgent,
                        "Accept" to "application/json",
                    ),
            )

        if (!response.ok) {
            throw ConduitError(
                code = "HTTP_${response.status}",
                info = response.statusText.ifEmpty { null },
                method = method,
                httpStatus = response.status,
            )
        }

        val payload =
            try {
                Json.parseToJsonElement(response.body)
            } catch (_: Exception) {
                throw ConduitError(
                    code = "INVALID_JSON",
                    info = "Conduit returned non-JSON body: ${response.body.take(200)}",
                    method = method,
                    httpStatus = response.status,
                )
            }
        val obj =
            payload as? JsonObject
                ?: throw ConduitError(
                    code = "INVALID_JSON",
                    info = "Conduit response was not a JSON object",
                    method = method,
                    httpStatus = response.status,
                )

        val errorCode = obj["error_code"]?.asNonEmptyString()
        if (errorCode != null) {
            val errorInfo = obj["error_info"]?.asNonEmptyString()
            throw ConduitError(
                code = errorCode,
                info = errorInfo,
                method = method,
                httpStatus = response.status,
            )
        }

        return obj["result"] ?: JsonNull
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://phabricator.services.mozilla.com/api/"
        const val DEFAULT_USER_AGENT = "phabricator-client (kotlin-jvm)"

        private val EMPTY_ARGS = JsonObject(emptyMap())
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Form-encode a Conduit request body. Public so tests can assert the exact bytes match what
         * `conduit.js` produces.
         */
        fun encodeBody(token: String, args: JsonObject): String {
            val auth = buildJsonObject { put("token", token) }
            val paramsJson = JsonObject(args.toMutableMap().apply { put("__conduit__", auth) })
            val parts =
                listOf(
                    "params" to JSON.encodeToString(JsonElement.serializer(), paramsJson),
                    "output" to "json",
                    "__conduit__" to JSON.encodeToString(JsonElement.serializer(), auth),
                )
            return parts.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
            }
        }
    }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

fun interface Logger {
    fun log(level: LogLevel, message: String, meta: Map<String, Any?>)
}

private object NoopLogger : Logger {
    override fun log(level: LogLevel, message: String, meta: Map<String, Any?>) {}
}

/**
 * Mirrors JavaScript truthy-string semantics used by the VSCode client's `if (payload.error_code)`
 * check: JSON null, missing, and empty string all become null; otherwise returns the string
 * content.
 */
private fun JsonElement.asNonEmptyString(): String? {
    if (this is JsonNull) return null
    val prim = this as? JsonPrimitive ?: return null
    return prim.content.takeIf { it.isNotEmpty() }
}
