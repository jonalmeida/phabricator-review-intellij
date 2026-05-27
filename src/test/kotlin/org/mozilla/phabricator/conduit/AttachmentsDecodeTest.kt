package org.mozilla.phabricator.conduit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that a full `differential.revision.search` response with all three attachments populated
 * decodes into the Phase-3 [com.mozilla.phabricator.conduit.model.Revision.attachments] block.
 * Phase 1's tests covered the basic Revision DTO only; Phase 3 lights up reviewers / subscribers /
 * projects so the overview panel has data to display.
 */
class AttachmentsDecodeTest {

    @Test
    fun `Revision attachments block decodes reviewers subscribers projects when requested`() =
        runBlocking {
            val transport =
                FakeHttpTransport().apply {
                    enqueueJson(
                        body =
                            """
                            {
                              "result": {
                                "data": [
                                  {
                                    "id": 1,
                                    "type": "DREV",
                                    "phid": "PHID-DREV-1",
                                    "fields": {
                                      "title": "Example",
                                      "authorPHID": "PHID-USER-author",
                                      "status": { "value": "needs-review", "name": "Needs Review", "closed": false },
                                      "diffPHID": "PHID-DIFF-1",
                                      "dateCreated": 100,
                                      "dateModified": 200,
                                      "bugzilla.bug-id": "1234567"
                                    },
                                    "attachments": {
                                      "reviewers": {
                                        "reviewers": [
                                          { "reviewerPHID": "PHID-USER-r1", "status": "accepted", "isBlocking": false, "actorPHID": "PHID-USER-r1" },
                                          { "reviewerPHID": "PHID-PROJ-team", "status": "added", "isBlocking": true, "actorPHID": null },
                                          { "reviewerPHID": "PHID-USER-r2", "status": "accepted-prior", "isBlocking": false, "actorPHID": "PHID-USER-r2" }
                                        ]
                                      },
                                      "subscribers": {
                                        "subscriberPHIDs": ["PHID-USER-sub1", "PHID-USER-sub2"],
                                        "subscriberCount": 2,
                                        "viewerIsSubscribed": true
                                      },
                                      "projects": {
                                        "projectPHIDs": ["PHID-PROJ-a", "PHID-PROJ-b"]
                                      }
                                    }
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
            val client = ConduitClient(ConduitTransport(token = "t", transport = transport))

            val rev =
                client
                    .searchRevisions(
                        attachments =
                            RevisionAttachments(
                                reviewers = true,
                                subscribers = true,
                                projects = true,
                            )
                    )
                    .toList()
                    .first()

            // Header data still in place from Phase 1.
            assertEquals(1, rev.id)
            assertEquals("D1", rev.monogram)
            assertEquals("PHID-USER-author", rev.fields.authorPHID)
            assertEquals("Example", rev.fields.title)
            assertEquals("needs-review", rev.fields.status.value)
            assertEquals(
                "1234567",
                rev.fields.bugzillaBugId,
                "bugzilla.bug-id flat dotted key decoded",
            )

            // Phase 3 attachments block decoded.
            val a = rev.attachments
            assertNotNull(a, "attachments block must decode when the request opted in")

            val reviewers = a!!.reviewers!!.reviewers
            assertEquals(3, reviewers.size)
            assertEquals("PHID-USER-r1", reviewers[0].reviewerPHID)
            assertEquals("accepted", reviewers[0].status)
            assertEquals(false, reviewers[0].isBlocking)
            assertEquals("PHID-PROJ-team", reviewers[1].reviewerPHID)
            assertEquals("added", reviewers[1].status)
            assertTrue(reviewers[1].isBlocking, "project reviewers can be blocking")
            assertEquals("accepted-prior", reviewers[2].status, "stale acceptance variant decoded")

            val subs = a.subscribers!!
            assertEquals(listOf("PHID-USER-sub1", "PHID-USER-sub2"), subs.subscriberPHIDs)
            assertEquals(2, subs.subscriberCount)
            assertTrue(subs.viewerIsSubscribed)

            assertEquals(listOf("PHID-PROJ-a", "PHID-PROJ-b"), a.projects!!.projectPHIDs)
        }

    @Test
    fun `Revision decodes without attachments when the request did not opt in`() = runBlocking {
        val transport =
            FakeHttpTransport().apply {
                enqueueJson(
                    body =
                        """
                        {
                          "result": {
                            "data": [
                              {
                                "id": 2,
                                "type": "DREV",
                                "phid": "PHID-DREV-2",
                                "fields": { "title": "No attachments", "diffPHID": "PHID-DIFF-2" }
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
        val client = ConduitClient(ConduitTransport(token = "t", transport = transport))

        val rev = client.searchRevisions().toList().first()
        assertEquals(null, rev.attachments)
        assertEquals(null, rev.fields.bugzillaBugId)
    }
}
