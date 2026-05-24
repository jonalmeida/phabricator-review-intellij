package com.mozilla.phabricator.conduit

import com.mozilla.phabricator.conduit.model.ConduitCursor
import com.mozilla.phabricator.conduit.model.ConduitSearchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ports test/client/pagination.test.js. */
class PaginationTest {

    @Test
    fun `paginate walks two pages and exhausts`() = runBlocking {
        val pages =
            listOf(
                ConduitSearchResult(
                    data = listOf(Item(1), Item(2)),
                    cursor = ConduitCursor(after = "page-2", limit = 100),
                ),
                ConduitSearchResult(
                    data = listOf(Item(3)),
                    cursor = ConduitCursor(after = null, limit = 100),
                ),
            )
        var i = 0
        val seen = mutableListOf<String?>()
        val flow =
            paginate<Item> { cursor ->
                seen += cursor
                pages[i++]
            }

        val out = flow.toList().map { it.id }
        assertEquals(listOf(1, 2, 3), out)
        assertEquals(listOf<String?>(null, "page-2"), seen)
    }

    @Test
    fun `paginate handles a single empty page`() = runBlocking {
        val flow =
            paginate<Item> {
                ConduitSearchResult(data = emptyList(), cursor = ConduitCursor(after = null))
            }
        assertEquals(emptyList<Item>(), flow.collectList())
    }

    @Test
    fun `collectList respects limit`() = runBlocking {
        val flow =
            paginate<Item> { cursor ->
                if (cursor == null) {
                    ConduitSearchResult(
                        data = listOf(Item(1), Item(2), Item(3)),
                        cursor = ConduitCursor(after = "next"),
                    )
                } else {
                    ConduitSearchResult(
                        data = listOf(Item(4)),
                        cursor = ConduitCursor(after = null),
                    )
                }
            }
        val out = flow.collectList(limit = 2).map { it.id }
        assertEquals(listOf(1, 2), out)
    }

    @kotlinx.serialization.Serializable private data class Item(val id: Int)
}
