package org.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports the top-level revision-action cases from
 * `phabricator-review-vscode/test/client/revisions.test.js` lines 72-104 (comment / accept /
 * requestChanges) plus a new abandon case for parity.
 *
 * Every assertion targets the form-encoded wire body so the Kotlin client emits exactly the same
 * `transactions` payload the JS client emits.
 */
class TopLevelActionsTest {

    private val editOkBody =
        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `comment posts a single comment transaction`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.comment("PHID-DREV-1", "looks good")

        assertTrue(transport.calls[0].url.endsWith("differential.revision.edit"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals("PHID-DREV-1", params["objectIdentifier"]!!.jsonPrimitive.content)
        val tx = params["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("comment", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("looks good", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `accept without body posts only the accept transaction`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.accept("PHID-DREV-1")

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("accept", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `accept with body posts accept plus comment transactions`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.accept("PHID-DREV-1", body = "r=me")

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(2, tx.size)
        assertEquals("accept", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("comment", tx[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("r=me", tx[1].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requestChanges fires a reject transaction (optionally with comment)`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(body = editOkBody)
                enqueueJson(body = editOkBody)
            }
        val client = newClient(transport)

        client.requestChanges("PHID-DREV-1")
        client.requestChanges("PHID-DREV-1", body = "nit: typos")

        val first =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(1, first.size)
        assertEquals("reject", first[0].jsonObject["type"]!!.jsonPrimitive.content)

        val second =
            (decodeBody(transport.calls[1].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(2, second.size)
        assertEquals("reject", second[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("comment", second[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("nit: typos", second[1].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `abandon fires an abandon transaction with optional comment`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(body = editOkBody)
                enqueueJson(body = editOkBody)
            }
        val client = newClient(transport)

        client.abandon("PHID-DREV-1")
        client.abandon("PHID-DREV-1", body = "Replaced by D2.")

        val first =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(1, first.size)
        assertEquals("abandon", first[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", first[0].jsonObject["value"]!!.jsonPrimitive.content)

        val second =
            (decodeBody(transport.calls[1].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(2, second.size)
        assertEquals("abandon", second[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("comment", second[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Replaced by D2.", second[1].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `accept with empty-string body still omits the comment transaction`() = runBlocking {
        // Phase-2 publishDrafts uses an empty-string comment intentionally; the accept-with-body
        // path must NOT fall into that shape. Empty body means "no comment", not "empty comment".
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.accept("PHID-DREV-1", body = "")

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("accept", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
    }
}
