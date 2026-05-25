package org.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.flow.toList
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.EditResult
import org.mozilla.phabricator.conduit.model.Reviewer
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

    /** Human-friendly status (e.g. "Needs Review"). Falls back to the raw value when missing. */
    val statusName: String
        get() = current.fields.status.name.ifEmpty { current.fields.status.value }

    /** Mozilla-Phabricator's linked Bugzilla bug id, e.g. "1234567"; null when unset. */
    val bugzillaBugId: String?
        get() = current.fields.bugzilla?.bugId?.takeIf { it.isNotEmpty() }

    /** Reviewer rows from the attachments block, or empty list if the request never asked. */
    val reviewers: List<Reviewer>
        get() = current.attachments?.reviewers?.reviewers.orEmpty()

    /** Subscriber PHIDs from the attachments block, or empty list if unfetched. */
    val subscriberPHIDs: List<String>
        get() = current.attachments?.subscribers?.subscriberPHIDs.orEmpty()

    /** Project tag PHIDs from the attachments block, or empty list if unfetched. */
    val projectPHIDs: List<String>
        get() = current.attachments?.projects?.projectPHIDs.orEmpty()

    fun isAuthor(viewerPHID: String): Boolean = viewerPHID.isNotEmpty() && viewerPHID == authorPHID

    fun isReviewer(viewerPHID: String): Boolean =
        viewerPHID.isNotEmpty() && reviewers.any { it.reviewerPHID == viewerPHID }

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

    // ---------------------------------------------------- top-level write paths

    /**
     * Post a top-level comment. After the server confirms, invalidates the local transaction cache
     * + fires [RevisionsManager.COMMENTS_TOPIC] so every open overview tab and diff viewer for this
     *   revision refreshes. Body is sent verbatim -- the panel rejects empty bodies before we get
     *   here.
     */
    suspend fun comment(body: String): EditResult {
        val result = client.comment(revisionPHID = phid, body = body)
        signalCommentsChanged()
        return result
    }

    /**
     * Accept the revision. Optional body posts as a follow-up comment in the same edit. Caller is
     * responsible for the role check (isReviewer && !isAuthor) -- this method does not gate on it
     * so it can be called from tests + future programmatic paths.
     */
    suspend fun accept(body: String? = null): EditResult {
        val result = client.accept(revisionPHID = phid, body = body)
        signalCommentsChanged()
        return result
    }

    /**
     * Request changes on the revision. Body is required by Phabricator convention but the wire call
     * accepts null and the server returns the same edit shape; the panel's button enforces
     * non-empty before calling.
     */
    suspend fun requestChanges(body: String? = null): EditResult {
        val result = client.requestChanges(revisionPHID = phid, body = body)
        signalCommentsChanged()
        return result
    }

    /** Abandon the revision. Optional body posts as a follow-up comment in the same edit. */
    suspend fun abandon(body: String? = null): EditResult {
        val result = client.abandon(revisionPHID = phid, body = body)
        signalCommentsChanged()
        return result
    }

    private fun signalCommentsChanged() {
        // Local cache must invalidate before the bus fires so subscribers reload from a fresh
        // server fetch rather than re-read the stale snapshot we just made out of date.
        invalidateTransactions()
        val app = ApplicationManager.getApplication() ?: return
        app.messageBus.syncPublisher(RevisionsManager.COMMENTS_TOPIC).commentsChanged(phid)
    }
}
