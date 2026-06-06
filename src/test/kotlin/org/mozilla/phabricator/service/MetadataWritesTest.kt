package org.mozilla.phabricator.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.ConduitTransport
import org.mozilla.phabricator.conduit.FakeHttpTransport
import org.mozilla.phabricator.conduit.decodeBody
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.RevisionFields

/**
 * Drives [RevisionModel.editTitle] / [RevisionModel.editSummary] / [RevisionModel.editTestPlan]
 * over a [FakeHttpTransport] so the wire-level body shape is asserted end-to-end + each wrapper
 * invalidates the local transaction cache. Mirrors the Phase-3 RevisionOverviewWritesTest pattern.
 */
class MetadataWritesTest {

    private val editOkBody =
        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""
    private val emptyTransactionsPage =
        """{"result":{"data":[],"cursor":{"after":null}},"error_code":null,"error_info":null}"""

    private fun newSetup(): Triple<FakeHttpTransport, ConduitClient, RevisionModel> {
        val transport = FakeHttpTransport()
        val client = ConduitClient(ConduitTransport(token = "t", transport = transport))
        val revision =
            Revision(
                id = 1,
                phid = "PHID-DREV-1",
                fields = RevisionFields(title = "Original", diffPHID = "PHID-DIFF-1"),
            )
        return Triple(transport, client, RevisionModel(revision, client))
    }

    @Test
    fun `editTitle fires a title transaction and invalidates the transaction cache`() =
        runBlocking {
            val (transport, _, model) = newSetup()
            transport.enqueueJson(body = emptyTransactionsPage)
            model.getTransactions()
            assertEquals(1, transport.calls.count { it.url.endsWith("transaction.search") })

            transport.enqueueJson(body = editOkBody)
            model.editTitle("New title")

            val editCall = transport.calls.last { it.url.endsWith("differential.revision.edit") }
            val params = decodeBody(editCall.body).params as JsonObject
            assertEquals("PHID-DREV-1", params["objectIdentifier"]!!.jsonPrimitive.content)
            val tx = params["transactions"]!!.jsonArray
            assertEquals(1, tx.size)
            assertEquals("title", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("New title", tx[0].jsonObject["value"]!!.jsonPrimitive.content)

            // Cache invalidation: the next getTransactions should refetch.
            transport.enqueueJson(body = emptyTransactionsPage)
            model.getTransactions()
            assertEquals(
                2,
                transport.calls.count { it.url.endsWith("transaction.search") },
                "editTitle should invalidate the transaction cache",
            )
        }

    @Test
    fun `editSummary fires a summary transaction`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.editSummary("A new summary block.")

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("summary", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("A new summary block.", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `editTestPlan fires a testPlan transaction`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.editTestPlan("Manual smoke test.")

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("testPlan", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Manual smoke test.", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `editSummary with empty string is allowed (clears the field)`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.editSummary("")

        val tx =
            (decodeBody(transport.calls[0].body).params as JsonObject)["transactions"]!!.jsonArray
        assertEquals("summary", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("", tx[0].jsonObject["value"]!!.jsonPrimitive.content)
    }
}
