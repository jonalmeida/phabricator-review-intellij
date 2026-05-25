package org.mozilla.phabricator.service

import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.Revision

/**
 * Wraps a [Revision] DTO with cached, lazily-loaded changesets. Phase-1 surface only --
 * transactions, inline comments, edits, etc. land in Phase 2/3 along with the endpoints that supply
 * them.
 */
class RevisionModel(initial: Revision, private val client: ConduitClient) {
    @Volatile private var current: Revision = initial

    val revision: Revision
        get() = current

    val id: Int
        get() = current.id

    val phid: String
        get() = current.phid

    val monogram: String
        get() = current.monogram

    val title: String
        get() = current.fields.title

    val statusValue: String
        get() = current.fields.status.value

    val authorPHID: String
        get() = current.fields.authorPHID

    val diffPHID: String
        get() = current.fields.diffPHID

    @Volatile private var cachedChangesets: List<Changeset>? = null

    fun update(latest: Revision) {
        current = latest
        // Invalidate downstream caches when the underlying diff changes.
        if (latest.fields.diffPHID != current.fields.diffPHID) {
            cachedChangesets = null
        }
    }

    /**
     * Fetch the changesets for the revision's active diff. Cached per-instance; call
     * [invalidateChangesets] to force a refetch (e.g. after `update()` brings in a new diffPHID).
     */
    suspend fun getChangesets(): List<Changeset> {
        cachedChangesets?.let {
            return it
        }
        val diff = client.searchDiffs(phids = listOf(diffPHID))
        val diffIds = mutableListOf<Int>()
        diff.collect { diffIds += it.id }
        if (diffIds.isEmpty()) return emptyList()
        val queried = client.queryDiffs(diffIds)
        val result = queried[diffIds.first()]?.changes.orEmpty()
        cachedChangesets = result
        return result
    }

    fun invalidateChangesets() {
        cachedChangesets = null
    }
}
