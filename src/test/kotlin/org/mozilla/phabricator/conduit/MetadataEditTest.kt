package org.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the wire shape of the metadata-edit transactions (`title` / `summary` / `testPlan`) and the
 * collection-edit transactions (`reviewers.add/remove` / `projects.add/remove`).
 *
 * Verified against `phabricator-review-vscode/src/client/client.js:487-511` (createRevision) which
 * uses the same transaction types; the value shapes are inferred from Phabricator's documented edit
 * transactions.
 */
class MetadataEditTest {

    private val editOkBody =
        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `title edit posts a single title transaction with string value`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "title")
                        put("value", "Fix the thing")
                    }
                ),
        )

        assertTrue(transport.calls[0].url.endsWith("differential.revision.edit"))
        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("title", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Fix the thing", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `summary edit posts a single summary transaction with string value`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "summary")
                        put("value", "New summary body.")
                    }
                ),
        )

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("summary", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("New summary body.", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `testPlan edit posts a single testPlan transaction (note camelCase)`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "testPlan")
                        put("value", "Ran the full suite.")
                    }
                ),
        )

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals(
            "testPlan",
            tx[0].jsonObject["type"]!!.jsonPrimitive.content,
            "testPlan is camelCase on the wire (the only one of the three; title and summary are " +
                "lowercase). Confirmed against vscode/src/client/client.js:487-511.",
        )
    }

    @Test
    fun `reviewers add transaction takes a PHID array`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "reviewers.add")
                        putJsonArray("value") {
                            add("PHID-USER-1")
                            add("PHID-USER-2")
                        }
                    }
                ),
        )

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("reviewers.add", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        val values = tx[0].jsonObject["value"]!!.jsonArray
        assertEquals(2, values.size)
        assertEquals("PHID-USER-1", values[0].jsonPrimitive.content)
        assertEquals("PHID-USER-2", values[1].jsonPrimitive.content)
    }

    @Test
    fun `reviewers remove transaction takes a PHID array`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "reviewers.remove")
                        putJsonArray("value") { add("PHID-USER-3") }
                    }
                ),
        )

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("reviewers.remove", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("PHID-USER-3", tx[0].jsonObject["value"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `projects add transaction takes a PHID array`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "projects.add")
                        putJsonArray("value") { add("PHID-PROJ-1") }
                    }
                ),
        )

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("projects.add", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("PHID-PROJ-1", tx[0].jsonObject["value"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `projects remove transaction takes a PHID array`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.editRevision(
            objectIdentifier = "PHID-DREV-1",
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "projects.remove")
                        putJsonArray("value") { add("PHID-PROJ-2") }
                    }
                ),
        )

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("projects.remove", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("PHID-PROJ-2", tx[0].jsonObject["value"]!!.jsonArray[0].jsonPrimitive.content)
    }
}
