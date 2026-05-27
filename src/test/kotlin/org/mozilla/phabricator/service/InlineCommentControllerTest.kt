package org.mozilla.phabricator.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.ConduitTransport
import org.mozilla.phabricator.conduit.FakeHttpTransport
import org.mozilla.phabricator.conduit.decodeBody
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.RevisionFields

/**
 * Behavioural parity with `revisionCommentController.ts`:
 * - Single-comment thread, flat thread of replies, nested replies all collapse correctly.
 * - postReply targets the leaf-most comment as the replyToCommentPHID.
 * - markDone fires inline.done with every comment's transaction PHID (matches the JS that fans the
 *   action across all comments in a thread).
 * - publishDrafts fires the empty-string `comment` transaction.
 */
class InlineCommentControllerTest {

    private val transactionsBodyWithThreeRoots =
        """
        {
          "result": {
            "data": [
              ${inlineTx(phid = "PHID-XACT-1", date = 100, body = "root one", parent = null)},
              ${inlineTx(phid = "PHID-XACT-2", date = 200, body = "reply to one", parent = "PHID-XACT-1")},
              ${inlineTx(phid = "PHID-XACT-3", date = 300, body = "nested reply", parent = "PHID-XACT-2")},
              ${inlineTx(phid = "PHID-XACT-4", date = 150, body = "different file root", parent = null, path = "src/b.kt")},
              ${inlineTx(phid = "PHID-XACT-5", date = 400, body = "third file root", parent = null, path = "src/c.kt")}
            ],
            "cursor": { "after": null }
          },
          "error_code": null,
          "error_info": null
        }
        """
            .trimIndent()

    // remarkup.process replies are ordered to match the bodies that threadsFor() sends, which is
    // the order of inline transactions returned by transaction.search above.
    private val renderedBodyHtml =
        """{"result":[{"content":"<p>root one</p>"},{"content":"<p>reply to one</p>"},{"content":"<p>nested reply</p>"},{"content":"<p>different file root</p>"},{"content":"<p>third file root</p>"}],"error_code":null,"error_info":null}"""

    private fun setup(): Triple<FakeHttpTransport, RevisionModel, InlineCommentController> {
        val transport = FakeHttpTransport()
        val client = ConduitClient(ConduitTransport(token = "t", transport = transport))
        val rev =
            Revision(
                id = 1,
                phid = "PHID-DREV-1",
                fields = RevisionFields(diffPHID = "PHID-DIFF-1"),
            )
        val model = RevisionModel(rev, client)
        val controller = InlineCommentController(model, client)
        return Triple(transport, model, controller)
    }

    @Test
    fun `threadsFor groups replies under their root and walks nested reply chains`() = runBlocking {
        val (transport, _, controller) = setup()
        transport.enqueueJson(body = transactionsBodyWithThreeRoots)
        transport.enqueueJson(body = renderedBodyHtml)

        val threads = controller.threadsFor("src/a.kt", "PHID-DIFF-1")

        assertEquals(1, threads.size, "only one root on (src/a.kt line 1)")
        val t = threads[0]
        assertEquals("PHID-XACT-1", t.rootPHID)
        assertEquals(
            listOf("PHID-XACT-1", "PHID-XACT-2", "PHID-XACT-3"),
            t.comments.map { it.transactionPHID },
        )
        assertEquals("PHID-XACT-3", t.replyTargetPHID, "reply target = leaf-most comment")
        assertEquals("<p>root one</p>", t.comments[0].renderedHtml)
        assertEquals("<p>reply to one</p>", t.comments[1].renderedHtml)
        assertEquals("<p>nested reply</p>", t.comments[2].renderedHtml)
    }

    @Test
    fun `threadsFor groups replies when replyToCommentPHID points at the XCMT (Mozilla form)`() =
        runBlocking {
            val (transport, _, controller) = setup()
            // Hand-built response: tx-2's replyToCommentPHID points at tx-1's COMMENT PHID
            // (PHID-XCMT-1) rather than its transaction PHID. Mirrors what Mozilla's Phabricator
            // emits. Pre-fix this lookup missed and tx-2 became its own one-comment thread.
            transport.enqueueJson(
                body =
                    """
                    {
                      "result": {
                        "data": [
                          ${
                              inlineTxWithComment(
                                  txPhid = "PHID-XACT-1",
                                  commentPhid = "PHID-XCMT-1",
                                  date = 100,
                                  body = "root",
                                  parent = null,
                              )
                          },
                          ${
                              inlineTxWithComment(
                                  txPhid = "PHID-XACT-2",
                                  commentPhid = "PHID-XCMT-2",
                                  date = 200,
                                  body = "reply via XCMT",
                                  parent = "PHID-XCMT-1",
                              )
                          },
                          ${
                              inlineTxWithComment(
                                  txPhid = "PHID-XACT-3",
                                  commentPhid = "PHID-XCMT-3",
                                  date = 300,
                                  body = "second reply via XCMT",
                                  parent = "PHID-XCMT-2",
                              )
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
            transport.enqueueJson(
                body =
                    """{"result":[{"content":"<p>root</p>"},{"content":"<p>reply via XCMT</p>"},{"content":"<p>second reply via XCMT</p>"}],"error_code":null,"error_info":null}"""
            )

            val threads = controller.threadsFor("src/a.kt", "PHID-DIFF-1")

            assertEquals(1, threads.size, "three inlines must collapse into one thread")
            val t = threads[0]
            assertEquals("PHID-XACT-1", t.rootPHID)
            assertEquals(
                listOf("PHID-XACT-1", "PHID-XACT-2", "PHID-XACT-3"),
                t.comments.map { it.transactionPHID },
            )
        }

    @Test
    fun `threadsFor filters by changesetPath and diffPHID`() = runBlocking {
        val (transport, _, controller) = setup()
        transport.enqueueJson(body = transactionsBodyWithThreeRoots)
        transport.enqueueJson(body = renderedBodyHtml)

        val threadsOnB = controller.threadsFor("src/b.kt", "PHID-DIFF-1")
        assertEquals(1, threadsOnB.size)
        assertEquals("PHID-XACT-4", threadsOnB[0].rootPHID)

        val threadsOnWrongDiff = controller.threadsFor("src/a.kt", "PHID-DIFF-OTHER")
        assertEquals(emptyList<InlineThread>(), threadsOnWrongDiff, "anchor.diffPHID must match")
    }

    @Test
    fun `threadsFor falls back to raw bodies if remarkup_process throws`() = runBlocking {
        val (transport, _, controller) = setup()
        transport.enqueueJson(body = transactionsBodyWithThreeRoots)
        // Force remarkup to return an HTTP error.
        transport.enqueue(FakeHttpTransport.CannedResponse(status = 503, body = "service down"))

        val threads = controller.threadsFor("src/a.kt", "PHID-DIFF-1")
        assertEquals(1, threads.size)
        // renderedHtml should fall back to rawBody when processRemarkup fails.
        assertEquals("root one", threads[0].comments[0].renderedHtml)
        assertEquals("reply to one", threads[0].comments[1].renderedHtml)
    }

    @Test
    fun `postReply targets the leaf comment as replyTarget (server-side threading TBD)`() =
        runBlocking {
            val (transport, _, controller) = setup()
            // Build a 3-comment thread by hand to avoid coupling to threadsFor in this test.
            val thread =
                InlineThread(
                    rootPHID = "PHID-XACT-1",
                    path = "src/a.kt",
                    line = 5,
                    length = 1,
                    isNewFile = true,
                    isDone = false,
                    comments =
                        listOf(
                            comment("PHID-XACT-1", body = "a"),
                            comment("PHID-XACT-2", body = "b"),
                            comment("PHID-XACT-3", body = "c"),
                        ),
                )
            transport.enqueueJson(
                body = """{"result":{"phid":"PHID-XACT-new"},"error_code":null,"error_info":null}"""
            )

            assertEquals(
                "PHID-XACT-3",
                thread.replyTargetPHID,
                "controller must still target the leaf comment as the conceptual reply parent",
            )
            val newPhid = controller.postReply(thread, body = "lgtm", diffId = 7)
            assertEquals("PHID-XACT-new", newPhid)

            assertTrue(transport.calls[0].url.endsWith("differential.createinline"))
            val params = decodeBody(transport.calls[0].body).params as JsonObject
            assertEquals(7, params["diffID"]!!.jsonPrimitive.content.toInt())
            assertEquals("src/a.kt", params["filePath"]!!.jsonPrimitive.content)
            assertEquals(5, params["lineNumber"]!!.jsonPrimitive.content.toInt())
            assertEquals("true", params["isNewFile"]!!.jsonPrimitive.content)
            assertEquals("lgtm", params["content"]!!.jsonPrimitive.content)
            // Mozilla Phabricator rejects replyToCommentPHID; the Kotlin client must not send it.
            assertEquals(
                null,
                params["replyToCommentPHID"],
                "replyToCommentPHID must not be on the wire until the server supports it",
            )
        }

    @Test
    fun `markDone fires inline_done with every comment's transactionPHID`() = runBlocking {
        val (transport, _, controller) = setup()
        val thread =
            InlineThread(
                rootPHID = "PHID-XACT-1",
                path = "src/a.kt",
                line = 5,
                length = 1,
                isNewFile = false,
                isDone = false,
                comments = listOf(comment("PHID-XACT-1"), comment("PHID-XACT-2")),
            )
        transport.enqueueJson(
            body =
                """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""
        )

        controller.markDone(thread, done = true)

        val params = decodeBody(transport.calls[0].body).params as JsonObject
        val tx = params["transactions"]!!.jsonArray[0].jsonObject
        assertEquals("inline.done", tx["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("PHID-XACT-1", "PHID-XACT-2"),
            tx["value"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `publishDrafts goes through ConduitClient publishDrafts on the revision PHID`() =
        runBlocking {
            val (transport, _, controller) = setup()
            transport.enqueueJson(
                body =
                    """{"result":{"object":"PHID-DREV-1","transactions":[]},"error_code":null,"error_info":null}"""
            )

            controller.publishDrafts()

            val params = decodeBody(transport.calls[0].body).params as JsonObject
            assertEquals("PHID-DREV-1", params["objectIdentifier"]!!.jsonPrimitive.content)
            val tx = params["transactions"]!!.jsonArray[0].jsonObject
            assertEquals("comment", tx["type"]!!.jsonPrimitive.content)
            assertEquals("", tx["value"]!!.jsonPrimitive.content)
        }

    // -------- helpers --------

    private fun comment(transactionPHID: String, body: String = "x"): InlineComment =
        InlineComment(
            transactionPHID = transactionPHID,
            authorPHID = "PHID-USER-a",
            dateCreated = 0,
            rawBody = body,
            renderedHtml = body,
        )

    /**
     * Variant of [inlineTx] that lets the caller pick the embedded comment's PHID independently of
     * the transaction PHID -- needed for the Mozilla-style "replyTo points at the XCMT" regression
     * test.
     */
    private fun inlineTxWithComment(
        txPhid: String,
        commentPhid: String,
        date: Long,
        body: String,
        parent: String?,
        path: String = "src/a.kt",
        line: Int = 1,
    ): String =
        buildJsonObject {
                put("id", txPhid.substringAfterLast("-"))
                put("phid", txPhid)
                put("type", "inline")
                put("authorPHID", "PHID-USER-a")
                put("objectPHID", "PHID-DREV-1")
                put("dateCreated", date)
                put("dateModified", date)
                put(
                    "fields",
                    buildJsonObject {
                        put("diffPHID", "PHID-DIFF-1")
                        put("path", path)
                        put("line", line)
                        put("length", 1)
                        put("isNewFile", true)
                        if (parent != null) put("replyToCommentPHID", parent)
                        put("isDone", false)
                    },
                )
                put(
                    "comments",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("phid", commentPhid)
                                put("version", 1)
                                put("authorPHID", "PHID-USER-a")
                                put("dateCreated", date)
                                put("dateModified", date)
                                put("removed", false)
                                put("content", buildJsonObject { put("raw", body) })
                            }
                        )
                    },
                )
            }
            .toString()

    private fun inlineTx(
        phid: String,
        date: Long,
        body: String,
        parent: String?,
        path: String = "src/a.kt",
        line: Int = 1,
    ): String =
        buildJsonObject {
                put("id", phid.substringAfterLast("-"))
                put("phid", phid)
                put("type", "inline")
                put("authorPHID", "PHID-USER-a")
                put("objectPHID", "PHID-DREV-1")
                put("dateCreated", date)
                put("dateModified", date)
                put(
                    "fields",
                    buildJsonObject {
                        put("diffPHID", "PHID-DIFF-1")
                        put("path", path)
                        put("line", line)
                        put("length", 1)
                        put("isNewFile", true)
                        if (parent != null) put("replyToCommentPHID", parent)
                        put("isDone", false)
                    },
                )
                put(
                    "comments",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("phid", "PHID-XCMT-$phid")
                                put("version", 1)
                                put("authorPHID", "PHID-USER-a")
                                put("dateCreated", date)
                                put("dateModified", date)
                                put("removed", false)
                                put("content", buildJsonObject { put("raw", body) })
                            }
                        )
                    },
                )
            }
            .toString()
}
