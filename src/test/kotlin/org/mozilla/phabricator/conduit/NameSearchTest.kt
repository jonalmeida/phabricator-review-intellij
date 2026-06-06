package org.mozilla.phabricator.conduit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the wire shape of `searchUsersByName` / `searchProjectsByName`, the Phase-4 name-search
 * helpers used by the reviewer / project pickers.
 *
 * Verified against `phabricator-review-vscode/src/client/client.js:652-680`:
 * - User search: `user.search` with constraints `{nameLike: query, isDisabled: false}`.
 * - Project search: `project.search` with constraints `{name: query}`.
 *
 * Empty-query short-circuit is asserted so the picker can debounce-fire the call without filtering
 * upstream.
 */
class NameSearchTest {

    private fun newClient(transport: FakeHttpTransport): ConduitClient =
        ConduitClient(ConduitTransport(token = "t", transport = transport))

    @Test
    fun `searchUsersByName posts user-search with nameLike and isDisabled false`() = runBlocking {
        val body =
            """
            {
              "result": {
                "data": [
                  {"phid":"PHID-USER-1","fields":{"username":"alice","realName":"Alice"}},
                  {"phid":"PHID-USER-2","fields":{"username":"alex","realName":"Alex"}}
                ],
                "cursor": {"after": null}
              },
              "error_code": null,
              "error_info": null
            }
            """
                .trimIndent()
        val transport = FakeHttpTransport().apply { enqueueJson(body = body) }
        val client = newClient(transport)

        val users = client.searchUsersByName("al")

        assertTrue(transport.calls[0].url.endsWith("user.search"))
        val params = decodeBody(transport.calls[0].body).params as JsonObject
        val constraints = params["constraints"]!!.jsonObject
        assertEquals("al", constraints["nameLike"]!!.jsonPrimitive.content)
        assertEquals("false", constraints["isDisabled"]!!.jsonPrimitive.content)
        assertEquals(2, users.size)
        assertEquals("PHID-USER-1", users[0].phid)
    }

    @Test
    fun `searchProjectsByName posts project-search with the name constraint`() = runBlocking {
        val body =
            """
            {
              "result": {
                "data": [
                  {"phid":"PHID-PROJ-1","fields":{"name":"Mobile","slug":"mobile"}}
                ],
                "cursor": {"after": null}
              },
              "error_code": null,
              "error_info": null
            }
            """
                .trimIndent()
        val transport = FakeHttpTransport().apply { enqueueJson(body = body) }
        val client = newClient(transport)

        val projects = client.searchProjectsByName("mob")

        assertTrue(transport.calls[0].url.endsWith("project.search"))
        val constraints =
            (decodeBody(transport.calls[0].body).params as JsonObject)["constraints"]!!.jsonObject
        assertEquals("mob", constraints["name"]!!.jsonPrimitive.content)
        assertEquals(1, projects.size)
        assertEquals("PHID-PROJ-1", projects[0].phid)
    }

    @Test
    fun `empty query short-circuits without firing a network call`() = runBlocking {
        val transport = FakeHttpTransport()
        val client = newClient(transport)

        val users = client.searchUsersByName("")
        val projects = client.searchProjectsByName("   ")

        assertTrue(users.isEmpty(), "empty user search returns empty list")
        assertTrue(projects.isEmpty(), "blank project search returns empty list")
        assertEquals(0, transport.calls.size, "no transport calls fired")
    }

    @Test
    fun `searchUsersByName caps results at the supplied limit`() = runBlocking {
        // Five users returned but we ask for two -- the take operator must truncate before the
        // paginator fetches the next page.
        val body =
            """
            {
              "result": {
                "data": [
                  {"phid":"PHID-USER-1","fields":{"username":"a"}},
                  {"phid":"PHID-USER-2","fields":{"username":"b"}},
                  {"phid":"PHID-USER-3","fields":{"username":"c"}},
                  {"phid":"PHID-USER-4","fields":{"username":"d"}},
                  {"phid":"PHID-USER-5","fields":{"username":"e"}}
                ],
                "cursor": {"after": null}
              },
              "error_code": null,
              "error_info": null
            }
            """
                .trimIndent()
        val transport = FakeHttpTransport().apply { enqueueJson(body = body) }
        val client = newClient(transport)

        val users = client.searchUsersByName("a", limit = 2)

        assertEquals(2, users.size)
    }
}
