package org.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the wire shape of the six Phase-4 review actions (`commandeer` / `resign` / `reclaim` /
 * `reopen` / `plan-changes` / `request-review`).
 *
 * `commandeer` and `resign` mirror existing VSCode client methods
 * (`phabricator-review-vscode/src/client/client.js:359-389`). The other four (`reclaim` / `reopen`
 * / `plan-changes` / `request-review`) are NOT in the VSCode source -- they appear only as
 * transaction-type labels in `phabricator-review-vscode/src/phabricator/txLabels.ts:21-35`. We're
 * inferring the wire shape from Phabricator's transaction-naming convention. If Mozilla's Phorge
 * ever rejects one of these shapes, this test surfaces the regression before runtime.
 */
class Phase4ActionsTest {

    private val editOkBody =
        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `commandeer fires a commandeer transaction with optional comment`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(body = editOkBody)
                enqueueJson(body = editOkBody)
            }
        val client = newClient(transport)

        client.commandeer("PHID-DREV-1")
        client.commandeer("PHID-DREV-1", body = "Taking this over; original author moved teams.")

        assertEquals(
            "commandeer",
            firstTxType(transport, 0),
            "single commandeer transaction without body",
        )
        assertEquals(1, txCount(transport, 0))
        assertEquals(
            "commandeer",
            firstTxType(transport, 1),
            "commandeer transaction first when body is supplied",
        )
        assertEquals(2, txCount(transport, 1))
        assertEquals("comment", secondTxType(transport, 1))
        assertEquals("Taking this over; original author moved teams.", secondTxValue(transport, 1))
    }

    @Test
    fun `resign fires a resign transaction with optional comment`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(body = editOkBody)
                enqueueJson(body = editOkBody)
            }
        val client = newClient(transport)

        client.resign("PHID-DREV-1")
        client.resign("PHID-DREV-1", body = "Not the right reviewer; reassigning.")

        assertEquals("resign", firstTxType(transport, 0))
        assertEquals(1, txCount(transport, 0))
        assertEquals("resign", firstTxType(transport, 1))
        assertEquals(2, txCount(transport, 1))
        assertEquals("Not the right reviewer; reassigning.", secondTxValue(transport, 1))
    }

    @Test
    fun `reclaim fires a reclaim transaction (wire shape inferred)`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.reclaim("PHID-DREV-1", body = "Picking this back up.")

        assertEquals("reclaim", firstTxType(transport, 0))
        assertEquals("true", firstTxValue(transport, 0))
        assertEquals(2, txCount(transport, 0))
        assertEquals("Picking this back up.", secondTxValue(transport, 0))
    }

    @Test
    fun `reopen fires a reopen transaction (wire shape inferred)`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
        val client = newClient(transport)

        client.reopen("PHID-DREV-1")

        assertEquals("reopen", firstTxType(transport, 0))
        assertEquals("true", firstTxValue(transport, 0))
        assertEquals(1, txCount(transport, 0))
    }

    @Test
    fun `planChanges fires a plan-changes transaction (note the hyphenated wire name)`() =
        runBlocking {
            val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
            val client = newClient(transport)

            client.planChanges("PHID-DREV-1")

            assertEquals(
                "plan-changes",
                firstTxType(transport, 0),
                "transaction type is dotted-hyphen (`plan-changes`), not camelCase",
            )
        }

    @Test
    fun `requestReview fires a request-review transaction (note the hyphenated wire name)`() =
        runBlocking {
            val transport = FakeHttpTransport().apply { enqueueJson(body = editOkBody) }
            val client = newClient(transport)

            client.requestReview("PHID-DREV-1", body = "Ready for another look.")

            assertEquals(
                "request-review",
                firstTxType(transport, 0),
                "transaction type is dotted-hyphen (`request-review`), not camelCase",
            )
            assertEquals(2, txCount(transport, 0))
            assertEquals("Ready for another look.", secondTxValue(transport, 0))
        }

    @Test
    fun `all six actions target the differential revision edit endpoint`() = runBlocking {
        val transport = FakeHttpTransport().apply { repeat(6) { enqueueJson(body = editOkBody) } }
        val client = newClient(transport)

        client.commandeer("PHID-DREV-1")
        client.resign("PHID-DREV-1")
        client.reclaim("PHID-DREV-1")
        client.reopen("PHID-DREV-1")
        client.planChanges("PHID-DREV-1")
        client.requestReview("PHID-DREV-1")

        for (i in 0..5) {
            assertEquals(
                true,
                transport.calls[i].url.endsWith("differential.revision.edit"),
                "call $i should target differential.revision.edit",
            )
            assertEquals(
                "PHID-DREV-1",
                (decodeBody(transport.calls[i].body).params as JsonObject)["objectIdentifier"]!!
                    .jsonPrimitive
                    .content,
            )
        }
    }

    // ----- helpers shared with TopLevelActionsTest-style assertions -----

    private fun txArrayOfCall(transport: FakeHttpTransport, callIndex: Int) =
        (decodeBody(transport.calls[callIndex].body).params as JsonObject)["transactions"]!!
            .jsonArray

    private fun txCount(transport: FakeHttpTransport, callIndex: Int): Int =
        txArrayOfCall(transport, callIndex).size

    private fun firstTxType(transport: FakeHttpTransport, callIndex: Int): String =
        txArrayOfCall(transport, callIndex)[0].jsonObject["type"]!!.jsonPrimitive.content

    private fun firstTxValue(transport: FakeHttpTransport, callIndex: Int): String =
        txArrayOfCall(transport, callIndex)[0].jsonObject["value"]!!.jsonPrimitive.content

    private fun secondTxType(transport: FakeHttpTransport, callIndex: Int): String =
        txArrayOfCall(transport, callIndex)[1].jsonObject["type"]!!.jsonPrimitive.content

    private fun secondTxValue(transport: FakeHttpTransport, callIndex: Int): String =
        txArrayOfCall(transport, callIndex)[1].jsonObject["value"]!!.jsonPrimitive.content
}
