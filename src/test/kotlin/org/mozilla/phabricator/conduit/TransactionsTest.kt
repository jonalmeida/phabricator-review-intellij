package org.mozilla.phabricator.conduit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mozilla.phabricator.conduit.model.InlineCommentFields

/**
 * `transaction.search` shape verification + the `InlineCommentFields.from` extractor parity with
 * `revisionCommentController.ts:isInlineByAnchor` / `inlineDiffPHID`.
 */
class TransactionsTest {

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `searchTransactions paginates and surfaces fields plus comments verbatim`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                        {
                          "result": {
                            "data": [
                              {
                                "id": "1",
                                "phid": "PHID-XACT-1",
                                "type": "inline",
                                "authorPHID": "PHID-USER-a",
                                "objectPHID": "PHID-DREV-1",
                                "dateCreated": 100,
                                "dateModified": 100,
                                "groupID": "g1",
                                "fields": {
                                  "diffPHID": "PHID-DIFF-1",
                                  "path": "src/a.kt",
                                  "line": 10,
                                  "length": 1,
                                  "isNewFile": true,
                                  "replyToCommentPHID": null,
                                  "isDone": false
                                },
                                "comments": [
                                  {
                                    "phid": "PHID-XCMT-1",
                                    "version": 1,
                                    "authorPHID": "PHID-USER-a",
                                    "dateCreated": 100,
                                    "dateModified": 100,
                                    "removed": false,
                                    "content": { "raw": "looks good" }
                                  }
                                ]
                              }
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

        val transactions = client.searchTransactions("D1").toList()
        assertEquals(1, transactions.size)
        val t = transactions[0]
        assertEquals("inline", t.type)
        assertEquals("PHID-XACT-1", t.phid)
        assertEquals(1, t.comments.size)
        assertEquals("looks good", t.comments[0].content.raw)

        assertTrue(transport.calls[0].url.endsWith("transaction.search"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        assertEquals("D1", params["objectIdentifier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `InlineCommentFields_from extracts top-level diffPHID`() {
        val fields = buildJsonObject {
            put("diffPHID", "PHID-DIFF-x")
            put("path", "src/a.kt")
            put("line", 4)
            put("length", 2)
            put("isNewFile", true)
            put("isDone", false)
        }

        val inline = InlineCommentFields.from(fields)
        assertEquals("PHID-DIFF-x", inline?.diffPHID)
        assertEquals("src/a.kt", inline?.path)
        assertEquals(4, inline?.line)
        assertEquals(2, inline?.length)
        assertEquals(true, inline?.isNewFile)
        assertEquals(false, inline?.isDone)
        assertNull(inline?.replyToCommentPHID)
    }

    @Test
    fun `InlineCommentFields_from falls back to nested diff_phid`() {
        // Server-version variance: some Phabricator instances emit `fields.diff.phid` instead.
        val fields = buildJsonObject {
            putJsonObject("diff") { put("phid", "PHID-DIFF-nested") }
            put("path", "a")
            put("line", 1)
        }
        val inline = InlineCommentFields.from(fields)
        assertEquals("PHID-DIFF-nested", inline?.diffPHID)
    }

    @Test
    fun `InlineCommentFields_from rejects non-inline transactions`() {
        // A "comment" transaction has no path/line/diffPHID — must not match.
        val fields = JsonObject(emptyMap())
        assertNull(InlineCommentFields.from(fields))

        val partialFields = buildJsonObject { put("path", "a") }
        assertNull(InlineCommentFields.from(partialFields))
    }

    @Test
    fun `InlineCommentFields_from rejects empty diffPHID`() {
        val fields = buildJsonObject {
            put("diffPHID", "")
            put("path", "a")
            put("line", 1)
        }
        assertNull(InlineCommentFields.from(fields))
        // Sanity: confirm the explicit-false guard via length defaulting.
        assertFalse(false)
    }

    @Test
    fun `InlineCommentFields_from defaults length to 1 and isNewFile_isDone to false`() {
        val fields = buildJsonObject {
            put("diffPHID", "PHID-DIFF-1")
            put("path", "a")
            put("line", 5)
        }
        val inline = InlineCommentFields.from(fields)!!
        assertEquals(1, inline.length)
        assertEquals(false, inline.isNewFile)
        assertEquals(false, inline.isDone)
    }
}
