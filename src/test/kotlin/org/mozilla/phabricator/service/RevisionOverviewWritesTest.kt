package org.mozilla.phabricator.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.ConduitTransport
import org.mozilla.phabricator.conduit.FakeHttpTransport
import org.mozilla.phabricator.conduit.decodeBody
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.RevisionFields

/**
 * Drives [RevisionModel.comment] / [RevisionModel.accept] / [RevisionModel.requestChanges] /
 * [RevisionModel.abandon] over a [FakeHttpTransport] so the wire-level body shape is asserted at
 * the same level a real run would produce, and confirms that each wrapper invalidates the local
 * transaction cache so a subsequent getTransactions() re-fetches.
 */
class RevisionOverviewWritesTest {

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
                fields = RevisionFields(title = "T", diffPHID = "PHID-DIFF-1"),
            )
        return Triple(transport, client, RevisionModel(revision, client))
    }

    @Test
    fun `comment fires a single comment transaction and invalidates the cache`() = runBlocking {
        val (transport, _, model) = newSetup()
        // Prime the transaction cache so we can observe invalidation.
        transport.enqueueJson(body = emptyTransactionsPage)
        model.getTransactions()
        assertEquals(1, transport.calls.count { it.url.endsWith("transaction.search") })

        transport.enqueueJson(body = editOkBody)
        model.comment("looks good")

        val editCall = transport.calls.last { it.url.endsWith("differential.revision.edit") }
        val params = decodeBody(editCall.body).params as JsonObject
        assertEquals("PHID-DREV-1", params["objectIdentifier"]!!.jsonPrimitive.content)
        val tx = params["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("comment", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("looks good", tx[0].jsonObject["value"]!!.jsonPrimitive.content)

        // Invalidation: next getTransactions() must hit the server again.
        transport.enqueueJson(body = emptyTransactionsPage)
        model.getTransactions()
        assertEquals(2, transport.calls.count { it.url.endsWith("transaction.search") })
    }

    @Test
    fun `accept with body fires accept plus comment transactions`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.accept(body = "r=me")

        val params = decodeBody(transport.calls.last().body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray
        assertEquals(2, tx.size)
        assertEquals("accept", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("comment", tx[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("r=me", tx[1].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `accept without body fires only the accept transaction`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.accept()

        val params = decodeBody(transport.calls.last().body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("accept", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requestChanges fires a reject transaction with the body comment`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.requestChanges(body = "nit: typos")

        val params = decodeBody(transport.calls.last().body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray
        assertEquals(2, tx.size)
        assertEquals("reject", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("comment", tx[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("nit: typos", tx[1].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `abandon without body fires only the abandon transaction`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = editOkBody)

        model.abandon()

        val params = decodeBody(transport.calls.last().body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray
        assertEquals(1, tx.size)
        assertEquals("abandon", tx[0].jsonObject["type"]!!.jsonPrimitive.content)
        // Sanity: confirm no stray comment-with-empty-body slips in (Phase-2 bug-3 caveat).
        assertNull(tx.getOrNull(1))
    }

    @Test
    fun `every write path invalidates the transaction cache`() = runBlocking {
        val (transport, _, model) = newSetup()
        transport.enqueueJson(body = emptyTransactionsPage)
        model.getTransactions()
        assertEquals(1, transport.calls.count { it.url.endsWith("transaction.search") })

        listOf<suspend () -> Unit>(
                { model.comment("a") },
                { model.accept(body = null) },
                { model.requestChanges(body = "b") },
                { model.abandon(body = null) },
            )
            .forEachIndexed { i, action ->
                transport.enqueueJson(body = editOkBody)
                transport.enqueueJson(body = emptyTransactionsPage)
                action()
                model.getTransactions()
                assertEquals(
                    2 + i,
                    transport.calls.count { it.url.endsWith("transaction.search") },
                    "write $i must invalidate the transaction cache so getTransactions() re-fetches",
                )
            }
    }
}
