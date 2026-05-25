package org.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports the wire-shape assertions for `edge.search` from the VSCode client. The JS plugin does not
 * test this endpoint explicitly; we add it here because Phase 3's stack-info UI rides on it.
 */
class EdgeSearchTest {

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `searchEdges posts sourcePHIDs and types and decodes the response`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                        {
                          "result": {
                            "data": [
                              {
                                "sourcePHID": "PHID-DREV-1",
                                "destinationPHID": "PHID-DREV-parent",
                                "edgeType": "revision.parent"
                              },
                              {
                                "sourcePHID": "PHID-DREV-1",
                                "destinationPHID": "PHID-DREV-child",
                                "edgeType": "revision.child"
                              }
                            ]
                          },
                          "error_code": null,
                          "error_info": null
                        }
                        """
                            .trimIndent()
                )
            }
        val client = newClient(transport)

        val edges =
            client.searchEdges(
                sourcePHIDs = listOf("PHID-DREV-1"),
                types = listOf("revision.parent", "revision.child"),
            )

        assertTrue(transport.calls[0].url.endsWith("edge.search"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals(
            listOf("PHID-DREV-1"),
            params["sourcePHIDs"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("revision.parent", "revision.child"),
            params["types"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        assertEquals(2, edges.size)
        assertEquals("PHID-DREV-1", edges[0].source)
        assertEquals("PHID-DREV-parent", edges[0].target)
        assertEquals("revision.parent", edges[0].type)
        assertEquals("PHID-DREV-child", edges[1].target)
        assertEquals("revision.child", edges[1].type)
    }

    @Test
    fun `searchEdges returns empty for empty inputs (no Conduit call made)`() = runBlocking {
        val transport = FakeHttpTransport()
        val client = newClient(transport)

        assertEquals(
            emptyList<org.mozilla.phabricator.conduit.model.Edge>(),
            client.searchEdges(sourcePHIDs = emptyList(), types = listOf("x")),
        )
        assertEquals(
            emptyList<org.mozilla.phabricator.conduit.model.Edge>(),
            client.searchEdges(sourcePHIDs = listOf("x"), types = emptyList()),
        )
        assertEquals(0, transport.calls.size, "no edges == no Conduit call")
    }
}
