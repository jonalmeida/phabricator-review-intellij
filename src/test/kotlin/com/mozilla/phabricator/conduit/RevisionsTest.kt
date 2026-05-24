package com.mozilla.phabricator.conduit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports the read-only subset of test/client/revisions.test.js. Write/edit endpoint tests (comment,
 * accept, createInline, createRevision, …) move with those endpoints in Phase 2/3.
 */
class RevisionsTest {

    private fun newClient(transport: FakeHttpTransport, token: String = "t"): ConduitClient =
        ConduitClient(ConduitTransport(token = token, transport = transport))

    @Test
    fun `searchRevisions flattens constraints into params`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                    {
                      "result": {
                        "data": [
                          { "id": 1, "type": "DREV", "phid": "PHID-DREV-1",
                            "fields": { "title": "one" }, "attachments": {} }
                        ],
                        "cursor": { "after": null }
                      },
                      "error_code": null,
                      "error_info": null
                    }
                """
                            .trimIndent()
                )
            }
        val client = newClient(transport)

        val out =
            client
                .searchRevisions(
                    constraints =
                        RevisionConstraints(
                            authorPHIDs = listOf("PHID-USER-me"),
                            statuses = listOf("needs-review"),
                        ),
                    attachments = RevisionAttachments(reviewers = true),
                )
                .toList()

        assertEquals(1, out.size)
        assertEquals("PHID-DREV-1", out[0].phid)
        assertEquals("D1", out[0].monogram)
        assertEquals("one", out[0].fields.title)

        val decoded = decodeBody(transport.calls[0].body)
        assertTrue(transport.calls[0].url.endsWith("differential.revision.search"))
        val params = decoded.params as JsonObject
        val constraints = params["constraints"]!!.jsonObject
        assertEquals(
            listOf("PHID-USER-me"),
            constraints["authorPHIDs"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("needs-review"),
            constraints["statuses"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "true",
            params["attachments"]!!.jsonObject["reviewers"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `querySubscribedRevisionPHIDs uses differential_query subscribers`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                    {
                      "result": [
                        { "phid": "PHID-DREV-1" },
                        { "phid": "PHID-DREV-2" },
                        { "phid": "PHID-DREV-1" },
                        { "phid": null }
                      ],
                      "error_code": null,
                      "error_info": null
                    }
                """
                            .trimIndent()
                )
            }
        val client = newClient(transport)

        val out = client.querySubscribedRevisionPHIDs("PHID-USER-me", limit = 25)
        assertEquals(listOf("PHID-DREV-1", "PHID-DREV-2"), out)

        assertTrue(transport.calls[0].url.endsWith("differential.query"))
        val decoded = decodeBody(transport.calls[0].body)
        val params = decoded.params as JsonObject
        assertEquals(
            listOf("PHID-USER-me"),
            params["subscribers"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("status-open", params["status"]!!.jsonPrimitive.content)
        assertEquals("order-modified", params["order"]!!.jsonPrimitive.content)
        assertEquals(25, params["limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `processRemarkup returns empty list without calling Conduit when contents are empty`() =
        runBlocking {
            val transport = FakeHttpTransport()
            val client = newClient(transport)

            val out = client.processRemarkup(emptyList())
            assertEquals(emptyList<String>(), out)
            assertEquals(0, transport.calls.size)
        }

    @Test
    fun `processRemarkup maps array of content entries to html strings`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                    {
                      "result": [
                        { "content": "<p>hi</p>" },
                        { "content": "<p>there</p>" }
                      ],
                      "error_code": null,
                      "error_info": null
                    }
                """
                            .trimIndent()
                )
            }
        val client = newClient(transport)

        val out = client.processRemarkup(listOf("hi", "there"))
        assertEquals(listOf("<p>hi</p>", "<p>there</p>"), out)

        val decoded = decodeBody(transport.calls[0].body)
        val params = decoded.params as JsonObject
        assertEquals("differential", params["context"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("hi", "there"),
            params["contents"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
