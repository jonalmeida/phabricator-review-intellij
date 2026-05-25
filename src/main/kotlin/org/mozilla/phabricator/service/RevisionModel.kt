package org.mozilla.phabricator.service

import kotlinx.coroutines.flow.toList
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.Transaction

/**
 * Wraps a [Revision] DTO with cached, lazily-loaded changesets and transactions. Phase 1 carried
 * just the changeset cache; Phase 2 adds the transaction cache (the inline-comment UI builds
 * threads on top of it).
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
    @Volatile private var cachedTransactions: List<Transaction>? = null

    /**
     * Replace the wrapped [Revision]. Invalidates the changeset cache iff the underlying diff PHID
     * actually changed (a new diff was uploaded); invalidates the transaction cache iff
     * `dateModified` advanced (any new activity, including a comment posted elsewhere). Mirrors the
     * conditional-invalidation in
     * `phabricator-review-vscode/src/phabricator/revisionModel.ts:update`.
     */
    fun update(latest: Revision) {
        val previousDiffPHID = current.fields.diffPHID
        val previousDateModified = current.fields.dateModified
        current = latest
        if (latest.fields.diffPHID != previousDiffPHID) {
            cachedChangesets = null
        }
        if (latest.fields.dateModified != previousDateModified) {
            cachedTransactions = null
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

    /**
     * Fetch the revision's transaction timeline (activity feed). Cached per-instance until either
     * `update()` brings in a newer `dateModified` or a comment-mutating call invokes
     * [invalidateTransactions]. The list is sorted by `dateCreated` ascending.
     */
    suspend fun getTransactions(): List<Transaction> {
        cachedTransactions?.let {
            return it
        }
        val result = client.searchTransactions(phid).toList().sortedBy { it.dateCreated }
        cachedTransactions = result
        return result
    }

    fun invalidateTransactions() {
        cachedTransactions = null
    }
}
