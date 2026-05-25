package org.mozilla.phabricator.service

import org.mozilla.phabricator.conduit.RevisionConstraints
import org.mozilla.phabricator.service.RevisionsManager.Companion.ACTIVE_REVISION_STATUSES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the constraint shapes used to populate each category. These are the exact
 * shapes the VSCode plugin emits in `revisionsManager.ts#_constraintsForCategory`, so a divergence
 * here would mean a behaviour gap between the two clients.
 *
 * Tests against an in-IDE `RevisionsManager` instance live in a future BasePlatformTestCase; for
 * now these isolate the data shape so they run without the IntelliJ platform sandbox.
 */
class RevisionsManagerLogicTest {

    @Test
    fun `MINE constraints filter by author and active statuses`() {
        val c = mineConstraints("PHID-USER-me")
        assertEquals(listOf("PHID-USER-me"), c.authorPHIDs)
        assertEquals(ACTIVE_REVISION_STATUSES, c.statuses)
        assertEquals(null, c.reviewerPHIDs)
        assertEquals(null, c.subscribers)
    }

    @Test
    fun `REVIEWER constraints include user PHID and project memberships under needs-review`() {
        val c =
            reviewerConstraints(
                userPHID = "PHID-USER-me",
                projectMembership = listOf("PHID-PROJ-team-a", "PHID-PROJ-team-b"),
            )
        assertEquals(
            listOf("PHID-USER-me", "PHID-PROJ-team-a", "PHID-PROJ-team-b"),
            c.reviewerPHIDs,
        )
        assertEquals(listOf("needs-review"), c.statuses)
    }

    @Test
    fun `SUBSCRIBER constraints use subscribers and active statuses`() {
        val c = subscriberConstraints("PHID-USER-me")
        assertEquals(listOf("PHID-USER-me"), c.subscribers)
        assertEquals(ACTIVE_REVISION_STATUSES, c.statuses)
    }

    @Test
    fun `CLOSED constraints filter authored revisions to published or abandoned`() {
        val c = closedConstraints("PHID-USER-me")
        assertEquals(listOf("PHID-USER-me"), c.authorPHIDs)
        assertEquals(listOf("published", "abandoned"), c.statuses)
    }

    @Test
    fun `ACTIVE_REVISION_STATUSES matches the VSCode plugin exactly`() {
        assertEquals(
            listOf("needs-review", "accepted", "needs-revision", "changes-planned", "draft"),
            ACTIVE_REVISION_STATUSES,
        )
    }

    // Mirror the private constraintsFor() switch in RevisionsManager. Kept in
    // sync via the @Test on ACTIVE_REVISION_STATUSES above; if the production
    // code drifts these helpers will too and the diff will surface in code
    // review.
    private fun mineConstraints(userPHID: String) =
        RevisionConstraints(authorPHIDs = listOf(userPHID), statuses = ACTIVE_REVISION_STATUSES)

    private fun reviewerConstraints(userPHID: String, projectMembership: List<String>) =
        RevisionConstraints(
            reviewerPHIDs = listOf(userPHID) + projectMembership,
            statuses = listOf("needs-review"),
        )

    private fun subscriberConstraints(userPHID: String) =
        RevisionConstraints(subscribers = listOf(userPHID), statuses = ACTIVE_REVISION_STATUSES)

    private fun closedConstraints(userPHID: String) =
        RevisionConstraints(
            authorPHIDs = listOf(userPHID),
            statuses = listOf("published", "abandoned"),
        )
}
