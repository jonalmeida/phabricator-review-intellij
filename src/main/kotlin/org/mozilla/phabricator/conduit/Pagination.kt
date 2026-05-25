package org.mozilla.phabricator.conduit

import org.mozilla.phabricator.conduit.model.ConduitSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.take

/**
 * Port of `src/client/pagination.js#paginate`. Walks a cursor-paged Conduit search method as a
 * [Flow].
 */
fun <T> paginate(fetchPage: suspend (cursor: String?) -> ConduitSearchResult<T>): Flow<T> = flow {
    var cursor: String? = null
    while (true) {
        val page = fetchPage(cursor)
        for (item in page.data) emit(item)
        val nextCursor = page.cursor.after
        if (nextCursor.isNullOrEmpty()) break
        cursor = nextCursor
    }
}

/**
 * Port of `src/client/pagination.js#collect`. Drains a [Flow] into a list, honouring `limit` if
 * provided.
 */
suspend fun <T> Flow<T>.collectList(limit: Int = Int.MAX_VALUE): List<T> {
    val source = if (limit == Int.MAX_VALUE) this else take(limit)
    return source.fold(mutableListOf<T>()) { acc, t -> acc.apply { add(t) } }
}
