package com.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Ports test/client/conduit.test.js. Every behavioural assertion in the VSCode client's test suite
 * should have a mirror here so we have mechanical evidence that the Kotlin port matches the
 * JavaScript wire contract.
 */
class ConduitTransportTest {

    @Test
    fun `whoami posts form-encoded body with __conduit__ token`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":{"phid":"PHID-USER-abc","userName":"me"},"error_code":null,"error_info":null}"""
                )
            }
        val client =
            ConduitTransport(
                token = "cli-secret",
                baseUrl = "https://example.test/api/",
                transport = transport,
            )

        val result = client.call("user.whoami")

        assertEquals("PHID-USER-abc", result.jsonObject["phid"]!!.jsonPrimitive.content)
        assertEquals(1, transport.calls.size)
        assertEquals("https://example.test/api/user.whoami", transport.calls[0].url)
        assertEquals(
            "application/x-www-form-urlencoded",
            transport.calls[0].headers["Content-Type"],
        )

        val decoded = decodeBody(transport.calls[0].body)
        assertEquals("json", decoded.raw["output"])
        assertEquals("cli-secret", (decoded.conduit as JsonObject)["token"]!!.jsonPrimitive.content)
        assertEquals(
            "cli-secret",
            (decoded.params as JsonObject)["__conduit__"]!!
                .jsonObject["token"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `non-null error_code becomes ConduitError`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":null,"error_code":"ERR-INVALID-SESSION","error_info":"token expired"}"""
                )
            }
        val client = ConduitTransport(token = "bad", transport = transport)

        val err =
            assertThrows(ConduitError::class.java) { runBlocking { client.call("user.whoami") } }
        assertEquals("ERR-INVALID-SESSION", err.code)
        assertEquals("token expired", err.info)
        assertEquals("user.whoami", err.method)
    }

    @Test
    fun `HTTP error becomes ConduitError with HTTP_ code`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueue(
                    FakeHttpTransport.CannedResponse(status = 503, body = "service unavailable")
                )
            }
        val client = ConduitTransport(token = "t", transport = transport)

        val err =
            assertThrows(ConduitError::class.java) { runBlocking { client.call("user.whoami") } }
        assertEquals("HTTP_503", err.code)
        assertEquals(503, err.httpStatus)
    }

    @Test
    fun `non-JSON body becomes INVALID_JSON error`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueue(FakeHttpTransport.CannedResponse(status = 200, body = "<html>nope</html>"))
            }
        val client = ConduitTransport(token = "t", transport = transport)

        val err =
            assertThrows(ConduitError::class.java) { runBlocking { client.call("user.whoami") } }
        assertEquals("INVALID_JSON", err.code)
    }

    @Test
    fun `rejects baseUrl that does not end with api`() {
        val err =
            assertThrows(IllegalArgumentException::class.java) {
                ConduitTransport(
                    token = "t",
                    baseUrl = "https://example.test/",
                    transport = FakeHttpTransport(),
                )
            }
        assertNotNull(err.message?.contains("/api/"))
    }

    @Test
    fun `rejects empty token`() {
        val err =
            assertThrows(IllegalArgumentException::class.java) {
                ConduitTransport(token = "", transport = FakeHttpTransport())
            }
        assertNotNull(err.message?.contains("token is required"))
    }

    @Test
    fun `encodeBody serializes args plus output and __conduit__`() {
        val args = buildJsonObject {
            put("foo", "bar")
            put("limit", JsonPrimitive(10))
        }
        val body = ConduitTransport.encodeBody("tok", args)
        val decoded = decodeBody(body)

        assertEquals("json", decoded.raw["output"])
        val params = decoded.params as JsonObject
        assertEquals("bar", params["foo"]!!.jsonPrimitive.content)
        assertEquals(10, params["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("tok", params["__conduit__"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals("tok", (decoded.conduit as JsonObject)["token"]!!.jsonPrimitive.content)
    }
}
