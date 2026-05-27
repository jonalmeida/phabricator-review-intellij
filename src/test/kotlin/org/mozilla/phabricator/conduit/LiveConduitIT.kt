package org.mozilla.phabricator.conduit

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mozilla.phabricator.conduit.model.Revision

/**
 * Live integration tests that exercise real Mozilla Phabricator. Gated on a `.phabricator_token`
 * file at the repo root; if absent, every test is skipped via [assumeTrue]. The `live` JUnit tag
 * keeps these out of the default `./gradlew test` run — they fire under `./gradlew liveTest`.
 *
 * This is the strongest "behaviour matches the VSCode plugin" signal we have at the client layer:
 * we cannot mock the Mozilla Conduit endpoint shape, so if the Kotlin port deviates from the wire
 * contract these tests will fail.
 */
@Tag("live")
class LiveConduitIT {

    private val token: String? by lazy { readTokenFile() }
    private val phidPattern = Regex("""^PHID-USER-.+""")
    private val monogramPattern = Regex("""^D\d+$""")

    @Test
    fun `whoami returns a valid USER PHID`() = runBlocking {
        val token = requireToken()
        val client = ConduitClient(token = token)
        val me = client.whoami()

        assertNotNull(me.phid)
        assertTrue(phidPattern.matches(me.phid), "expected USER PHID, got ${me.phid}")
        assertTrue(me.userName.isNotEmpty(), "userName should not be empty")
    }

    @Test
    fun `searchRevisions for the authenticated author returns valid revisions`() = runBlocking {
        val token = requireToken()
        val client = ConduitClient(token = token)
        val me = client.whoami()

        val first =
            client
                .searchRevisions(
                    constraints = RevisionConstraints(authorPHIDs = listOf(me.phid)),
                    limit = 5,
                )
                .firstOrNull()

        // Skip the assertion (not fail) if the authenticated user has never
        // authored a revision: that is a property of the account, not the
        // client.
        assumeTrue(first != null, "authenticated user has no revisions to test against")
        assertTrue(
            monogramPattern.matches(first!!.monogram),
            "expected D<num> monogram, got ${first.monogram}",
        )
        assertEquals(me.phid, first.fields.authorPHID)
    }

    @Test
    fun `bugzilla bug id decodes from Mozilla's flat dotted key`() = runBlocking {
        val token = requireToken()
        val client = ConduitClient(token = token)
        val me = client.whoami()
        val revisions: List<Revision> =
            client
                .searchRevisions(
                    constraints = RevisionConstraints(authorPHIDs = listOf(me.phid)),
                    limit = 20,
                )
                .toList()
        assumeTrue(
            revisions.isNotEmpty(),
            "authenticated user has no revisions to inspect bug-id decode",
        )
        // Mozilla's flow tags virtually every revision with a Bugzilla bug. If at least one of
        // the user's recent revisions decodes a bug id, the @SerialName("bugzilla.bug-id") wiring
        // is correct. If zero of the user's twenty most recent revisions had a bug linked the
        // assumption fires instead of a failure.
        val withBug = revisions.filter { !it.fields.bugzillaBugId.isNullOrEmpty() }
        assumeTrue(
            withBug.isNotEmpty(),
            "none of the recent revisions has a bugzilla.bug-id; cannot assert decode",
        )
        val first = withBug.first()
        assertTrue(
            first.fields.bugzillaBugId!!.all { it.isDigit() },
            "expected numeric bug id, got '${first.fields.bugzillaBugId}'",
        )
    }

    @Test
    fun `processRemarkup renders bold markup to html`() = runBlocking {
        val token = requireToken()
        val client = ConduitClient(token = token)

        val rendered = client.processRemarkup(listOf("**bold**"))
        assertEquals(1, rendered.size)
        val html = rendered[0]
        assertTrue(
            html.contains("<strong>", ignoreCase = true) || html.contains("<b>", ignoreCase = true),
            "expected bold HTML, got: $html",
        )
    }

    private fun requireToken(): String {
        val t = token
        assumeTrue(
            t != null && t.isNotBlank(),
            "Set .phabricator_token at the repo root to run live integration tests",
        )
        return t!!
    }

    private fun readTokenFile(): String? {
        val path = Path.of(System.getProperty("user.dir"), ".phabricator_token")
        return if (Files.isReadable(path)) {
            Files.readString(path).trim().ifEmpty { null }
        } else {
            null
        }
    }
}
