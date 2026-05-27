package org.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhidQueryTest {

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `queryPHIDs posts the phid list and decodes the response`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                        {
                          "result": {
                            "PHID-APPS-PhabricatorHarbormasterApplication": {
                              "phid": "PHID-APPS-PhabricatorHarbormasterApplication",
                              "uri": "https://phab.example/harbormaster/",
                              "typeName": "Application",
                              "typeIcon": "fa-ship",
                              "name": "Harbormaster",
                              "fullName": "Harbormaster",
                              "status": "open"
                            }
                          },
                          "error_code": null,
                          "error_info": null
                        }
                        """
                            .trimIndent()
                )
            }
        val client = newClient(transport)

        val result = client.queryPHIDs(listOf("PHID-APPS-PhabricatorHarbormasterApplication"))

        assertTrue(transport.calls[0].url.endsWith("phid.query"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals(
            listOf("PHID-APPS-PhabricatorHarbormasterApplication"),
            params["phids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val info = result["PHID-APPS-PhabricatorHarbormasterApplication"]!!
        assertEquals("Harbormaster", info.name)
        assertEquals("Harbormaster", info.fullName)
        assertEquals("Application", info.typeName)
        assertEquals("https://phab.example/harbormaster/", info.uri)
    }

    @Test
    fun `queryPHIDs returns empty for empty input without a Conduit call`() = runBlocking {
        val transport = FakeHttpTransport()
        val client = newClient(transport)

        val result = client.queryPHIDs(emptyList())

        assertEquals(emptyMap<String, Any>(), result)
        assertEquals(0, transport.calls.size, "empty input must not fire a Conduit call")
    }
}
