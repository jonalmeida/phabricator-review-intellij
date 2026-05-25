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
 * Ports the inline-comment cases from `phabricator-review-vscode/test/client/revisions.test.js`
 * (lines 106–146 for createInline; new cases for markInlineDone/publishDrafts/deleteInline) plus
 * structural assertions on the wire bodies that the VSCode tests cover indirectly.
 */
class InlineCommentsTest {

    private fun newClient(transport: FakeHttpTransport, token: String = "t"): ConduitClient =
        ConduitClient(ConduitTransport(token = token, transport = transport))

    @Test
    fun `createInline translates length=1 to lineLength=0 (single-line range)`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":{"phid":"PHID-XACT-inline-new"},"error_code":null,"error_info":null}"""
                )
            }
        val client = newClient(transport)

        val phid =
            client.createInline(
                diffId = 17,
                path = "js/util.js",
                line = 42,
                length = 1,
                isNewFile = true,
                content = "nit: typo",
            )

        assertEquals("PHID-XACT-inline-new", phid)
        assertTrue(transport.calls[0].url.endsWith("differential.createinline"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals(17, params["diffID"]!!.jsonPrimitive.content.toInt())
        assertEquals("js/util.js", params["filePath"]!!.jsonPrimitive.content)
        assertEquals(42, params["lineNumber"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, params["lineLength"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", params["isNewFile"]!!.jsonPrimitive.content)
        assertEquals("nit: typo", params["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `createInline lineLength is length-1 for multi-line ranges`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body = """{"result":{"phid":"p"},"error_code":null,"error_info":null}"""
                )
            }
        val client = newClient(transport)

        client.createInline(
            diffId = 1,
            path = "a",
            line = 1,
            length = 4,
            isNewFile = false,
            content = "x",
        )

        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals(3, params["lineLength"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `createInline accepts a replyToCommentPHID and passes it through`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":{"phid":"PHID-XACT-reply"},"error_code":null,"error_info":null}"""
                )
            }
        val client = newClient(transport)

        client.createInline(
            diffId = 9,
            path = "src/x.kt",
            line = 12,
            isNewFile = true,
            content = "agreed",
            replyToCommentPHID = "PHID-XACT-parent",
        )

        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals("PHID-XACT-parent", params["replyToCommentPHID"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deleteInline posts the draft phid`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(body = """{"result":null,"error_code":null,"error_info":null}""")
            }
        val client = newClient(transport)

        client.deleteInline("PHID-XACT-draft")

        assertTrue(transport.calls[0].url.endsWith("differential.deleteinline"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals("PHID-XACT-draft", params["phid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `markInlineDone fires inline_done transaction with the comment phids`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""
                )
            }
        val client = newClient(transport)

        client.markInlineDone("PHID-DREV-1", listOf("PHID-XACT-a", "PHID-XACT-b"), done = true)

        assertTrue(transport.calls[0].url.endsWith("differential.revision.edit"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals("PHID-DREV-1", params["objectIdentifier"]!!.jsonPrimitive.content)
        val tx = params["transactions"]!!.jsonArray[0].jsonObject
        assertEquals("inline.done", tx["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("PHID-XACT-a", "PHID-XACT-b"),
            tx["value"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `markInlineDone done=false fires inline_undone`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""
                )
            }
        val client = newClient(transport)

        client.markInlineDone("PHID-DREV-1", listOf("PHID-XACT-a"), done = false)

        val params = decodeBody(transport.calls[0].body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray[0].jsonObject
        assertEquals("inline.undone", tx["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `publishDrafts fires an empty-string comment transaction`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""
                )
            }
        val client = newClient(transport)

        client.publishDrafts("PHID-DREV-1")

        assertTrue(transport.calls[0].url.endsWith("differential.revision.edit"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray[0].jsonObject
        assertEquals("comment", tx["type"]!!.jsonPrimitive.content)
        assertEquals("", tx["value"]!!.jsonPrimitive.content)
    }
}
